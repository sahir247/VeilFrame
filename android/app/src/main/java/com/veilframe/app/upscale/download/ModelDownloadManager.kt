package com.veilframe.app.upscale.download

import android.content.Context
import android.util.Log
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

        if (partFile.exists()) {
            partFile.delete()
        }

        try {
            ensureActive()
            var currentUrl = model.downloadUrl
            var connection: HttpURLConnection
            var redirectCount = 0

            // Follow HTTP redirects safely (HuggingFace uses 302 to CDN)
            while (true) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "VeilFrame-Android/1.0")

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308
                ) {
                    currentUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    redirectCount++
                    if (redirectCount > 5) {
                        throw IllegalStateException("Too many redirects downloading model")
                    }
                    continue
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Server returned HTTP $status: ${connection.responseMessage}")
                }
                break
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else model.sizeBytes

            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var totalDownloaded = 0L
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
                            listener.onProgress(totalDownloaded, totalBytes, speed)
                            lastTime = now
                            bytesSinceLastTime = 0L
                        }
                    }
                    output.flush()
                }
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

            // Atomic rename to production model location
            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            listener.onSuccess(model, finalFile)
            Result.success(finalFile)
        } catch (e: Exception) {
            if (partFile.exists()) {
                partFile.delete()
            }
            Log.e(TAG, "Download failed for ${model.name}: ${e.message}", e)
            listener.onError(e.message ?: "Unknown download error")
            Result.failure(e)
        }
    }
}
