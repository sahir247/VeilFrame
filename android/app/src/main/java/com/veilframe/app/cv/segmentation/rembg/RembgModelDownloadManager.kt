package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.util.Log
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
 * safely atomically moved to production storage, and can be deleted/re-downloaded.
 */
class RembgModelDownloadManager(
    private val context: Context,
    private val repository: RembgModelRepository,
) {
    companion object {
        private const val TAG = "VeilFrame.RembgDownload"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000

        private fun isAllowedHost(host: String): Boolean {
            val h = host.lowercase(java.util.Locale.ROOT)
            return h == "github.com" || h.endsWith(".github.com") ||
                h == "objects.githubusercontent.com" || h.endsWith(".objects.githubusercontent.com") ||
                h == "huggingface.co" || h.endsWith(".huggingface.co") ||
                h == "hf.co" || h.endsWith(".hf.co")
        }

        fun isAllowedModelUrl(urlString: String): Boolean {
            val url = try { URL(urlString) } catch (_: Exception) { return false }
            if (!url.protocol.equals("https", ignoreCase = true)) return false
            if (url.port != -1 && url.port != 443) return false
            if (url.userInfo != null) return false
            return isAllowedHost(url.host)
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
        if (repository.isModelReady(model)) {
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

        val requiredStorage = (model.minBytes * 1.2).toLong()
        val availableStorage = repository.getAvailableStorageBytes()
        if (availableStorage < requiredStorage) {
            val err = "Insufficient storage. Required: ${requiredStorage / (1024 * 1024)} MB, Available: ${availableStorage / (1024 * 1024)} MB"
            listener?.onError(err)
            _state.value = RembgDownloadState(model = model, error = err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val destFile = repository.getModelFile(model)
        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        val tempFinal = File(destFile.parentFile, "${destFile.name}.tmp")

        try {
            _state.value = RembgDownloadState(
                model = model,
                isDownloading = true,
                totalBytes = model.estimatedSizeBytes,
            )

            var existingBytes = 0L
            if (partFile.exists()) {
                existingBytes = partFile.length()
            }

            var currentUrl = model.downloadUrl
            var redirectCount = 0

            while (true) {
                ensureActive()
                if (!isAllowedModelUrl(currentUrl)) {
                    throw SecurityException("Redirect URL violates security origin policy: $currentUrl")
                }
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "VeilFrame-Android/1.0")
                    if (existingBytes > 0) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }
                activeConnection = conn

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location header")
                    val resolved = URL(url, location).toString()
                    conn.disconnect()
                    activeConnection = null

                    if (!isAllowedModelUrl(resolved)) {
                        throw SecurityException("Redirect destination violates model origin policy: $resolved")
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
                    // If Range request failed (e.g., 416 Range Not Satisfiable), restart from scratch
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
            val contentLength = connection.contentLengthLong
            val totalBytes = when {
                isPartial -> existingBytes + contentLength
                contentLength > 0 -> contentLength
                else -> model.estimatedSizeBytes
            }

            _state.value = _state.value.copy(
                bytesDownloaded = if (isPartial) existingBytes else 0L,
                totalBytes = totalBytes,
            )

            connection.inputStream.use { input ->
                FileOutputStream(partFile, isPartial).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var totalDownloaded = if (isPartial) existingBytes else 0L
                    var lastTime = System.currentTimeMillis()
                    var bytesSinceLastTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        bytesSinceLastTime += bytesRead

                        val now = System.currentTimeMillis()
                        val delta = now - lastTime
                        if (delta >= 250) {
                            val speed = (bytesSinceLastTime * 1000L) / delta
                            _state.value = _state.value.copy(
                                bytesDownloaded = totalDownloaded,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed,
                            )
                            listener?.onProgress(totalDownloaded, totalBytes, speed)
                            lastTime = now
                            bytesSinceLastTime = 0L
                        }
                    }
                    output.flush()
                }
            }

            ensureActive()
            if (partFile.length() < model.minBytes) {
                partFile.delete()
                val err = "Downloaded file size (${partFile.length() / (1024 * 1024)} MB) is below minimum expected threshold (${model.minBytes / (1024 * 1024)} MB)"
                throw IllegalStateException(err)
            }

            // Atomic move to destination
            if (!partFile.renameTo(tempFinal)) {
                partFile.copyTo(tempFinal, overwrite = true)
                partFile.delete()
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFinal.renameTo(destFile)) {
                tempFinal.copyTo(destFile, overwrite = true)
                tempFinal.delete()
            }

            _state.value = _state.value.copy(
                isDownloading = false,
                bytesDownloaded = totalBytes,
                totalBytes = totalBytes,
                speedBytesPerSec = 0L,
            )
            listener?.onSuccess(model, destFile)
            Result.success(destFile)
        } catch (e: Exception) {
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
