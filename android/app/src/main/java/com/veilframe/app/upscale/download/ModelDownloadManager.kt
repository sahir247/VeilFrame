package com.veilframe.app.upscale.download

import android.content.Context
import android.util.Log
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Robust, privacy-respecting model download engine.
 * Downloads to .part file, performs SHA-256 verification, and atomically moves to target location.
 */
class ModelDownloadManager(
    private val context: Context,
    private val repository: UpscaleModelRepository
) {

    companion object {
        private const val TAG = "VeilFrame.ModelDownload"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000

        private val allowedExactHosts = setOf(
            "github.com",
            "githubusercontent.com",
            "huggingface.co",
            "hf.co"
        )

        private val allowedHostSuffixes = setOf(
            ".github.com",
            ".githubusercontent.com",
            ".huggingface.co",
            ".hf.co"
        )

        private fun isAllowedHost(host: String): Boolean {
            val normalized = host
                .trimEnd('.')
                .lowercase(java.util.Locale.ROOT)

            return normalized in allowedExactHosts ||
                allowedHostSuffixes.any { normalized.endsWith(it) }
        }

        fun isAllowedModelUrl(urlString: String): Boolean {
            val uri = try {
                java.net.URI(urlString).parseServerAuthority()
            } catch (_: Exception) {
                return false
            }

            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.rawUserInfo != null) return false
            if (uri.port != -1 && uri.port != 443) return false

            val host = uri.host ?: return false

            // Reject IP literals; model downloads should use approved DNS hosts.
            if (host.contains(':') ||
                host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            ) return false

            return isAllowedHost(host)
        }
    }

    interface DownloadListener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long)
        fun onVerifying()
        fun onSuccess(model: UpscaleModel, destinationFile: File)
        fun onError(error: String)
    }

    suspend fun downloadModel(
        model: UpscaleModel,
        listener: DownloadListener
    ): Result<File> = withContext(Dispatchers.IO) {
        // Native NCNN weights are a paired artifact; never pass them through the
        // legacy single-file model.ort downloader.
        if (model.type == ModelType.AI_NCNN) {
            return@withContext NcnnModelPairDownloadManager(repository)
                .download(model, listener)
        }

        if (model.isBuiltIn) {
            val dummyFile = File(context.cacheDir, "${model.id}.builtIn")
            listener.onSuccess(model, dummyFile)
            return@withContext Result.success(dummyFile)
        }

        if (model.downloadUrl.isEmpty()) {
            val err = "Model ${model.name} does not specify a download URL."
            listener.onError(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        if (!isAllowedModelUrl(model.downloadUrl)) {
            val err = "Model download URL is not in approved origin allowlist: ${model.downloadUrl}"
            listener.onError(err)
            return@withContext Result.failure(SecurityException(err))
        }

        val requiredStorage = (model.sizeBytes * 1.5).toLong()
        val availableStorage = repository.getAvailableStorageBytes()
        if (availableStorage < requiredStorage) {
            val err = "Insufficient storage. Required: ${requiredStorage / (1024 * 1024)} MB, Available: ${availableStorage / (1024 * 1024)} MB"
            listener.onError(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val modelDir = repository.getModelDir(model.id)
        val finalFile = repository.getModelFile(model.id)
        val partFile = File(modelDir, "model.ort.part")
        val tempFinal = File(modelDir, "model.ort.tmp")

        if (partFile.exists()) {
            partFile.delete()
        }
        if (tempFinal.exists()) {
            tempFinal.delete()
        }

        var activeConnection: HttpURLConnection? = null
        try {
            ensureActive()
            var currentUrl = model.downloadUrl
            var redirectCount = 0

            // Follow HTTPS redirects safely with origin validation
            while (true) {
                if (!isAllowedModelUrl(currentUrl)) {
                    throw SecurityException("Model download URL or redirect violates security origin policy: $currentUrl")
                }
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "VeilFrame-Android/1.0")
                }
                activeConnection = conn

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location header")
                    val resolved = URL(url, location).toString()
                    conn.disconnect()
                    activeConnection = null

                    if (!isAllowedModelUrl(resolved)) {
                        val rejectedHost = try {
                            java.net.URI(resolved).host ?: "<invalid-host>"
                        } catch (_: Exception) {
                            "<invalid-url>"
                        }

                        Log.e(
                            TAG,
                            "Blocked model redirect: fromHost=${url.host}, toHost=$rejectedHost"
                        )

                        throw SecurityException(
                            "Model redirect blocked: unapproved host $rejectedHost"
                        )
                    }
                    currentUrl = resolved
                    redirectCount++
                    if (redirectCount > 5) {
                        throw IllegalStateException("Too many redirects downloading model")
                    }
                    continue
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    activeConnection = null
                    throw IllegalStateException("Server returned HTTP $status: ${conn.responseMessage}")
                }
                break
            }

            val connection = activeConnection ?: throw IllegalStateException("No active connection")
            val cancelHandler = currentCoroutineContext().job.invokeOnCompletion {
                runCatching { connection.disconnect() }
            }
            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else model.sizeBytes
            val maxAllowedBytes = if (model.sizeBytes > 0L) (model.sizeBytes * 1.15).toLong().coerceAtLeast(10 * 1024 * 1024L) else 150 * 1024 * 1024L

            try {
                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var totalDownloaded = 0L
                        var lastTime = System.currentTimeMillis()
                        var bytesSinceLastTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            totalDownloaded += bytesRead
                            if (totalDownloaded > maxAllowedBytes) {
                                throw SecurityException("Model download exceeded streaming safety limit of $maxAllowedBytes bytes")
                            }
                            output.write(buffer, 0, bytesRead)
                            bytesSinceLastTime += bytesRead

                            val now = System.currentTimeMillis()
                            val delta = now - lastTime
                            if (delta >= 250) {
                                val speed = (bytesSinceLastTime * 1000L) / delta
                                listener.onProgress(totalDownloaded, totalBytes, speed)
                                lastTime = now
                                bytesSinceLastTime = 0L
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                cancelHandler.dispose()
            }

            // Cryptographic checksum verification
            listener.onVerifying()
            ensureActive()

            val isValid = ModelDownloadVerifier.verify(partFile, model.sha256)
            if (!isValid) {
                partFile.delete()
                val err = "Model verification failed: SHA-256 checksum mismatch. Downloaded file was discarded for security."
                Log.e(TAG, err)
                listener.onError(err)
                return@withContext Result.failure(SecurityException(err))
            }

            // Safe atomic replacement using temp file with rollback capability
            if (!partFile.renameTo(tempFinal)) {
                partFile.copyTo(tempFinal, overwrite = true)
                partFile.delete()
            }

            val backupFile = File(finalFile.parentFile, "${finalFile.name}.backup_${System.currentTimeMillis()}")
            var backupCreated = false

            try {
                // Preserve existing model in backup before modifying destination
                if (finalFile.exists()) {
                    if (!finalFile.renameTo(backupFile)) {
                        finalFile.copyTo(backupFile, overwrite = true)
                        finalFile.delete()
                    }
                    backupCreated = true
                }

                var activated = tempFinal.renameTo(finalFile)
                if (!activated) {
                    try {
                        tempFinal.copyTo(finalFile, overwrite = true)
                        tempFinal.delete()
                        activated = true
                    } catch (copyEx: Exception) {
                        Log.e(TAG, "Fallback copy failed during model activation: ${copyEx.message}", copyEx)
                        if (finalFile.exists()) {
                            finalFile.delete()
                        }
                        throw copyEx
                    }
                }

                if (!finalFile.exists() || finalFile.length() == 0L) {
                    throw IllegalStateException("Activated model file is missing or empty")
                }

                // Activation succeeded; remove backup
                if (backupCreated && backupFile.exists()) {
                    backupFile.delete()
                }

                listener.onSuccess(model, finalFile)
                Result.success(finalFile)
            } catch (t: Throwable) {
                // Rollback: restore known-good model from backup if replacement failed
                if (backupCreated && backupFile.exists()) {
                    Log.w(TAG, "Restoring previous model from backup after activation failure: ${model.name}")
                    if (finalFile.exists()) {
                        finalFile.delete()
                    }
                    if (!backupFile.renameTo(finalFile)) {
                        runCatching { backupFile.copyTo(finalFile, overwrite = true) }
                        backupFile.delete()
                    }
                }
                throw t
            }
        } catch (e: Exception) {
            if (partFile.exists()) {
                partFile.delete()
            }
            if (tempFinal.exists()) {
                tempFinal.delete()
            }
            Log.e(TAG, "Download failed for ${model.name}: ${e.message}", e)
            listener.onError(e.message ?: "Unknown download error")
            Result.failure(e)
        } finally {
            try {
                activeConnection?.disconnect()
            } catch (_: Exception) {}
        }
    }
}
