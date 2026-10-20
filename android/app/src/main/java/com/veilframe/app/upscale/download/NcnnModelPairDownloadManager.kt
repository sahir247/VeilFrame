package com.veilframe.app.upscale.download

import android.util.Log
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext

/**
 * Downloads and activates an NCNN model as a verified .param/.bin pair.
 *
 * Pair install marker is written last, so an interrupted update cannot expose a
 * half-installed model as usable.
 */
class NcnnModelPairDownloadManager(
    private val repository: UpscaleModelRepository
) {
    companion object {
        private val modelLocks = ConcurrentHashMap<String, Mutex>()
        private const val TAG = "VeilFrame.NcnnDownload"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
    }

    suspend fun download(
        model: UpscaleModel,
        listener: ModelDownloadManager.DownloadListener,
        force: Boolean = false
    ): Result<File> {
        val modelLock = modelLocks.getOrPut(model.id) { Mutex() }
        return modelLock.withLock { downloadLocked(model, listener, force) }
    }

    private suspend fun downloadLocked(
        model: UpscaleModel,
        listener: ModelDownloadManager.DownloadListener,
        force: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        if (model.type != ModelType.AI_NCNN) {
            val error = "NCNN pair downloader received a non-NCNN model: ${model.id}"
            listener.onError(error)
            return@withContext Result.failure(IllegalArgumentException(error))
        }

        try {
            model.validateNcnnArtifactContract()
        } catch (e: Exception) {
            listener.onError(e.message ?: "Invalid NCNN model metadata.")
            return@withContext Result.failure(e)
        }

        val finalParam = repository.getNcnnParamFile(model.id)
        val finalBin = repository.getNcnnBinFile(model.id)
        val marker = repository.getNcnnInstallMarkerFile(model.id)
        val wasInstalled = repository.isModelInstalled(model)
        if (!force && wasInstalled) {
            listener.onSuccess(model, finalBin)
            return@withContext Result.success(finalBin)
        }

        val requiredStorage = (model.sizeBytes * 1.5).toLong()
        val availableStorage = repository.getAvailableStorageBytes()
        if (availableStorage < requiredStorage) {
            val error = "Insufficient storage for ${model.name}. Required: " +
                "${requiredStorage / (1024 * 1024)} MB, available: ${availableStorage / (1024 * 1024)} MB."
            listener.onError(error)
            return@withContext Result.failure(IllegalStateException(error))
        }

        val dir = repository.getModelDir(model.id)
        val paramPart = File(dir, "model.param.part")
        val binPart = File(dir, "model.bin.part")
        val paramTemp = File(dir, "model.param.tmp")
        val binTemp = File(dir, "model.bin.tmp")
        val markerTemp = File(dir, "model.ncnn.complete.tmp")
        val paramBackup = File(dir, "model.param.backup")
        val binBackup = File(dir, "model.bin.backup")
        val hadParam = finalParam.isFile
        val hadBin = finalBin.isFile

        listOf(paramPart, binPart, paramTemp, binTemp, markerTemp, paramBackup, binBackup)
            .forEach { it.delete() }

        try {
            downloadAndVerify(
                urlString = requireNotNull(model.paramDownloadUrl),
                partFile = paramPart,
                expectedSize = model.paramSizeBytes,
                expectedSha256 = requireNotNull(model.paramSha256),
                progressOffset = 0L,
                totalSize = model.sizeBytes,
                listener = listener
            )
            downloadAndVerify(
                urlString = requireNotNull(model.binDownloadUrl),
                partFile = binPart,
                expectedSize = model.binSizeBytes,
                expectedSha256 = requireNotNull(model.binSha256),
                progressOffset = model.paramSizeBytes,
                totalSize = model.sizeBytes,
                listener = listener
            )

            moveOrCopy(paramPart, paramTemp)
            moveOrCopy(binPart, binTemp)

            // Preserve the previous pair so activation can roll back if either move fails.
            if (hadParam) finalParam.copyTo(paramBackup, overwrite = true)
            if (hadBin) finalBin.copyTo(binBackup, overwrite = true)

            // The marker is the commit point for the pair. Removing it prevents
            // concurrent readers from treating a partially swapped pair as installed.
            if (marker.exists() && !marker.delete()) {
                throw IllegalStateException("Cannot invalidate NCNN install marker for ${model.id}.")
            }
            try {
                moveOrCopy(paramTemp, finalParam)
                moveOrCopy(binTemp, finalBin)
                markerTemp.writeText(
                    "${model.paramSha256}\n${model.binSha256}\n",
                    Charsets.US_ASCII
                )
                moveOrCopy(markerTemp, marker)

                if (!repository.isModelInstalled(model)) {
                    throw IllegalStateException("NCNN pair activation validation failed for ${model.name}.")
                }
            } catch (activationError: Throwable) {
                marker.delete()
                restoreFile(paramBackup, finalParam, hadParam)
                restoreFile(binBackup, finalBin, hadBin)
                if (wasInstalled && finalParam.isFile && finalBin.isFile) {
                    markerTemp.writeText(
                        "${model.paramSha256}\n${model.binSha256}\n",
                        Charsets.US_ASCII
                    )
                    runCatching { moveOrCopy(markerTemp, marker) }
                }
                throw activationError
            }

            paramBackup.delete()
            binBackup.delete()
            listener.onProgress(model.sizeBytes, model.sizeBytes, 0L)
            listener.onSuccess(model, finalBin)
            Result.success(finalBin)
        } catch (cancelled: CancellationException) {
            cleanupTemporaryFiles(paramPart, binPart, paramTemp, binTemp, markerTemp)
            throw cancelled
        } catch (e: Exception) {
            cleanupTemporaryFiles(paramPart, binPart, paramTemp, binTemp, markerTemp)
            Log.e(TAG, "NCNN model download failed for ${model.id}: ${e.message}", e)
            listener.onError(e.message ?: "Unknown NCNN model download error.")
            Result.failure(e)
        } finally {
            // A failed activation may leave backups after rollback; do not remove them
            // before rollback has completed, but clean them once no longer needed.
            if (repository.isModelInstalled(model)) {
                paramBackup.delete()
                binBackup.delete()
            }
        }
    }

    private suspend fun downloadAndVerify(
        urlString: String,
        partFile: File,
        expectedSize: Long,
        expectedSha256: String,
        progressOffset: Long,
        totalSize: Long,
        listener: ModelDownloadManager.DownloadListener
    ) {
        if (!ModelDownloadManager.isAllowedModelUrl(urlString)) {
            throw SecurityException("NCNN model URL violates the approved origin policy.")
        }
        partFile.delete()

        var currentUrl = urlString
        var redirectCount = 0
        var activeConnection: HttpURLConnection? = null
        try {
            var resolvedConnection: HttpURLConnection? = null
            while (resolvedConnection == null) {
                currentCoroutineContext().ensureActive()
                if (!ModelDownloadManager.isAllowedModelUrl(currentUrl)) {
                    throw SecurityException("NCNN model URL or redirect violates the approved origin policy.")
                }

                val url = URL(currentUrl)
                val candidate = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", com.veilframe.app.util.AppVersionProvider.getUserAgent())
                }
                activeConnection = candidate
                val status = candidate.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val location = candidate.getHeaderField("Location")
                        ?: throw IllegalStateException("NCNN model redirect had no Location header.")
                    val resolved = URL(url, location).toString()
                    candidate.disconnect()
                    activeConnection = null
                    if (!ModelDownloadManager.isAllowedModelUrl(resolved)) {
                        throw SecurityException("NCNN model redirect destination is not approved.")
                    }
                    redirectCount++
                    if (redirectCount > MAX_REDIRECTS) {
                        throw IllegalStateException("Too many redirects downloading NCNN model artifact.")
                    }
                    currentUrl = resolved
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    val msg = candidate.responseMessage
                    candidate.disconnect()
                    activeConnection = null
                    throw IllegalStateException("NCNN model server returned HTTP ${status}: $msg")
                }
                if (candidate.contentLengthLong > 0L && candidate.contentLengthLong != expectedSize) {
                    throw SecurityException(
                        "NCNN artifact size mismatch before download: expected ${expectedSize}, " +
                            "server declared ${candidate.contentLengthLong} bytes."
                    )
                }
                resolvedConnection = candidate
            }
            val connection = requireNotNull(resolvedConnection)
            val cancelHandler = currentCoroutineContext().job.invokeOnCompletion {
                runCatching { connection.disconnect() }
            }

            var downloaded = 0L
            var sinceLastUpdate = 0L
            var lastUpdate = System.currentTimeMillis()
            val startedAt = lastUpdate
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > expectedSize) {
                                throw SecurityException("NCNN artifact exceeded its pinned size of ${expectedSize} bytes.")
                            }
                            output.write(buffer, 0, count)
                            sinceLastUpdate += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 250L) {
                                val speed = (sinceLastUpdate * 1000L) / (now - lastUpdate)
                                listener.onProgress(progressOffset + downloaded, totalSize, speed)
                                sinceLastUpdate = 0L
                                lastUpdate = now
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                cancelHandler.dispose()
                connection.disconnect()
            }

            if (downloaded != expectedSize || partFile.length() != expectedSize) {
                throw SecurityException(
                    "NCNN artifact size mismatch: expected ${expectedSize} bytes, downloaded ${downloaded}."
                )
            }
            listener.onProgress(
                progressOffset + downloaded,
                totalSize,
                if (System.currentTimeMillis() > startedAt)
                    (downloaded * 1000L) / (System.currentTimeMillis() - startedAt)
                else 0L
            )
            listener.onVerifying()
            currentCoroutineContext().ensureActive()
            if (!ModelDownloadVerifier.verify(partFile, expectedSha256)) {
                throw SecurityException("NCNN artifact SHA-256 verification failed; the partial file was discarded.")
            }
        } finally {
            activeConnection?.disconnect()
        }
    }

    private fun moveOrCopy(source: File, destination: File) {
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("Cannot replace NCNN artifact file: ${destination.name}")
        }
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            if (!source.delete()) {
                Log.w(TAG, "Could not delete staged file ${source.name} after copy.")
            }
        }
    }

    private fun restoreFile(backup: File, destination: File, previouslyExisted: Boolean) {
        if (!previouslyExisted) {
            destination.delete()
        } else if (backup.isFile) {
            backup.copyTo(destination, overwrite = true)
        }
    }

    private fun cleanupTemporaryFiles(vararg files: File) {
        files.forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not clean temporary NCNN file ${file.name}.")
            }
        }
    }
}
