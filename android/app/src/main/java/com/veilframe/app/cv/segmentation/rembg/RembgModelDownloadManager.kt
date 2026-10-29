package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Robust on-device model download manager for Rembg models.
 * Ensures models are fetched on-demand, validated for size/integrity,
 * safely atomically moved to production storage only after all model artifacts
 * (.bin and .param) are downloaded and cryptographically verified.
 */
class RembgModelDownloadManager(
    private val context: Context,
    private val repository: RembgModelRepository,
) {
    companion object {
        private const val TAG = "VeilFrame.RembgDownload"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000

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
        fun onSuccess(model: RembgModel, destinationFile: File)
        fun onError(error: String)
    }

    private val _state = MutableStateFlow(RembgDownloadState())
    val state: StateFlow<RembgDownloadState> = _state.asStateFlow()

    private var activeConnection: HttpURLConnection? = null

    suspend fun downloadModel(
        model: RembgModel,
        listener: DownloadListener? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (model.isBundled || repository.isModelReady(model)) {
            val file = repository.getModelFile(model)
            listener?.onSuccess(model, file)
            return@withContext Result.success(file)
        }

        if (!isAllowedModelUrl(model.downloadUrl)) {
            val err = "Model URL is not in approved origin allowlist: ${model.downloadUrl}"
            listener?.onError(err)
            _state.value = RembgDownloadState(model = model, error = err)
            return@withContext Result.failure(SecurityException(err))
        }

        if (model.paramDownloadUrl.isNotEmpty() && !isAllowedModelUrl(model.paramDownloadUrl)) {
            val err = "Model param URL is not in approved origin allowlist: ${model.paramDownloadUrl}"
            listener?.onError(err)
            _state.value = RembgDownloadState(model = model, error = err)
            return@withContext Result.failure(SecurityException(err))
        }

        val requiredStorage = (model.minBytes * 1.2).toLong() + 100_000L
        val availableStorage = repository.getAvailableStorageBytes()
        if (availableStorage < requiredStorage) {
            val err = "Insufficient storage. Required: ${requiredStorage / (1024 * 1024)} MB, Available: ${availableStorage / (1024 * 1024)} MB"
            listener?.onError(err)
            _state.value = RembgDownloadState(model = model, error = err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val destBinFile = repository.getModelFile(model)
        val destParamFile = repository.getParamFile(model)

        val partBinFile = File(destBinFile.parentFile, "${destBinFile.name}.part")
        val partParamFile = File(destParamFile.parentFile, "${destParamFile.name}.part")
        val tempFinalBin = File(destBinFile.parentFile, "${destBinFile.name}.tmp")
        val tempFinalParam = File(destParamFile.parentFile, "${destParamFile.name}.tmp")

        try {
            val totalEstimated = model.estimatedSizeBytes + if (model.paramDownloadUrl.isNotEmpty()) 25_000L else 0L
            _state.value = RembgDownloadState(
                model = model,
                isDownloading = true,
                totalBytes = totalEstimated,
            )

            // Stage 1: Download .bin artifact to temporary part file with origin & redirect validation
            val binMaxAllowed = (model.estimatedSizeBytes * 1.25).toLong().coerceAtLeast(20 * 1024 * 1024L)
            downloadArtifactInternal(
                urlStr = model.downloadUrl,
                expectedSha256 = model.sha256,
                minBytes = model.minBytes,
                maxAllowedBytes = binMaxAllowed,
                partFile = partBinFile,
                totalProgressBytes = totalEstimated,
                progressOffset = 0L,
                listener = listener
            )

            // Stage 2: Download .param artifact to temporary part file with origin & redirect validation
            if (model.paramDownloadUrl.isNotEmpty()) {
                downloadArtifactInternal(
                    urlStr = model.paramDownloadUrl,
                    expectedSha256 = model.paramSha256,
                    minBytes = 500L,
                    maxAllowedBytes = 10 * 1024 * 1024L,
                    partFile = partParamFile,
                    totalProgressBytes = totalEstimated,
                    progressOffset = partBinFile.length(),
                    listener = listener
                )
            }

            // Stage 3: Transactional atomic installation of BOTH files
            // 3a. Move .bin into place
            if (!partBinFile.renameTo(tempFinalBin)) {
                partBinFile.copyTo(tempFinalBin, overwrite = true)
                partBinFile.delete()
            }
            if (destBinFile.exists()) destBinFile.delete()
            if (!tempFinalBin.renameTo(destBinFile)) {
                tempFinalBin.copyTo(destBinFile, overwrite = true)
                tempFinalBin.delete()
            }

            // 3b. Move .param into place if required
            if (model.paramDownloadUrl.isNotEmpty()) {
                if (!partParamFile.renameTo(tempFinalParam)) {
                    partParamFile.copyTo(tempFinalParam, overwrite = true)
                    partParamFile.delete()
                }
                if (destParamFile.exists()) destParamFile.delete()
                if (!tempFinalParam.renameTo(destParamFile)) {
                    tempFinalParam.copyTo(destParamFile, overwrite = true)
                    tempFinalParam.delete()
                }
            }

            // Stage 4: Verification of physical model readiness in repository
            if (!repository.isModelReady(model)) {
                destBinFile.delete()
                destParamFile.delete()
                throw IllegalStateException("Model installation incomplete: repository isModelReady check failed after install")
            }

            _state.value = _state.value.copy(
                isDownloading = false,
                bytesDownloaded = totalEstimated,
                totalBytes = totalEstimated,
                speedBytesPerSec = 0L,
            )
            listener?.onSuccess(model, destBinFile)
            Result.success(destBinFile)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (partBinFile.exists()) partBinFile.delete()
            if (partParamFile.exists()) partParamFile.delete()
            if (tempFinalBin.exists()) tempFinalBin.delete()
            if (tempFinalParam.exists()) tempFinalParam.delete()
            _state.value = _state.value.copy(isDownloading = false)
            throw cancelled
        } catch (e: Exception) {
            if (partBinFile.exists()) partBinFile.delete()
            if (partParamFile.exists()) partParamFile.delete()
            if (tempFinalBin.exists()) tempFinalBin.delete()
            if (tempFinalParam.exists()) tempFinalParam.delete()
            _state.value = _state.value.copy(
                isDownloading = false,
                error = e.message ?: "Download failed",
            )
            listener?.onError(e.message ?: "Download failed")
            Result.failure(e)
        } finally {
            try {
                activeConnection?.disconnect()
            } catch (_: Exception) {}
            activeConnection = null
        }
    }

    private suspend fun downloadArtifactInternal(
        urlStr: String,
        expectedSha256: String,
        minBytes: Long,
        maxAllowedBytes: Long,
        partFile: File,
        totalProgressBytes: Long,
        progressOffset: Long,
        listener: DownloadListener?
    ) {
        var existingBytes = 0L
        if (partFile.exists()) {
            existingBytes = partFile.length()
        }

        var currentUrl = urlStr
        var redirectCount = 0

        while (true) {
            coroutineContext.ensureActive()
            if (!isAllowedModelUrl(currentUrl)) {
                throw SecurityException("Redirect URL violates security origin policy: $currentUrl")
            }
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", com.veilframe.app.util.AppVersionProvider.getUserAgent(context))
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
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
                    Log.e(TAG, "Blocked model redirect: fromHost=${url.host}, toHost=$rejectedHost")
                    throw SecurityException("Model redirect blocked: unapproved host $rejectedHost")
                }
                currentUrl = resolved
                redirectCount++
                if (redirectCount > 8) {
                    throw IllegalStateException("Too many redirects downloading model")
                }
                continue
            }

            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                activeConnection = null
                if (existingBytes > 0 && (status == 416 || status == 400)) {
                    partFile.delete()
                    existingBytes = 0L
                    continue
                }
                throw IllegalStateException("Server returned HTTP $status: ${conn.responseMessage}")
            }
            break
        }

        val connection = activeConnection ?: throw IllegalStateException("No active connection")
        val isPartial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!isPartial && existingBytes > 0L) {
            existingBytes = 0L
        }

        connection.inputStream.use { input ->
            FileOutputStream(partFile, isPartial).use { output ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalDownloaded = if (isPartial) existingBytes else 0L
                var lastTime = System.currentTimeMillis()
                var bytesSinceLastTime = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()
                    totalDownloaded += bytesRead
                    if (totalDownloaded > maxAllowedBytes) {
                        partFile.delete()
                        throw SecurityException("Model artifact exceeded safety limit of $maxAllowedBytes bytes")
                    }
                    output.write(buffer, 0, bytesRead)
                    bytesSinceLastTime += bytesRead

                    val now = System.currentTimeMillis()
                    val delta = now - lastTime
                    if (delta >= 250) {
                        val speed = (bytesSinceLastTime * 1000L) / delta
                        _state.value = _state.value.copy(
                            bytesDownloaded = progressOffset + totalDownloaded,
                            totalBytes = totalProgressBytes,
                            speedBytesPerSec = speed,
                        )
                        listener?.onProgress(progressOffset + totalDownloaded, totalProgressBytes, speed)
                        lastTime = now
                        bytesSinceLastTime = 0L
                    }
                }
                output.flush()
            }
        }

        coroutineContext.ensureActive()
        if (partFile.length() < minBytes) {
            partFile.delete()
            throw IllegalStateException("Downloaded file size (${partFile.length()} bytes) is below minimum expected threshold ($minBytes bytes)")
        }

        // Cryptographic checksum verification
        if (expectedSha256.isNotEmpty()) {
            val isValid = com.veilframe.app.upscale.download.ModelDownloadVerifier.verify(partFile, expectedSha256)
            if (!isValid) {
                partFile.delete()
                val err = "Artifact verification failed: SHA-256 checksum mismatch. File discarded for security."
                Log.e(TAG, err)
                throw SecurityException(err)
            }
        } else {
            // Non-bundled models require pinned checksums
            partFile.delete()
            throw SecurityException("Model integrity failure: missing required SHA-256 checksum for artifact $urlStr")
        }
    }

    fun cancelDownload() {
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {}
        activeConnection = null
        _state.value = _state.value.copy(isDownloading = false)
    }

    fun deleteModel(model: RembgModel): Boolean {
        cancelDownload()
        return repository.deleteModel(model)
    }
}
