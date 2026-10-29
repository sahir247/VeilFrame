package com.veilframe.app.media.rife

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages physical storage, first-run download, and lifecycle of RIFE v4 AI models.
 * Downloaded on first run in Motion Lab (context.filesDir/models/rife-v4).
 */
class RifeModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VeilFrame.RifeModel"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000

        const val PARAM_URL = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/master/models/rife-v4/flownet.param"
        const val BIN_URL = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/master/models/rife-v4/flownet.bin"
        const val PARAM_CDN_URL = "https://cdn.jsdelivr.net/gh/nihui/rife-ncnn-vulkan@master/models/rife-v4/flownet.param"
        const val BIN_CDN_URL = "https://cdn.jsdelivr.net/gh/nihui/rife-ncnn-vulkan@master/models/rife-v4/flownet.bin"
        const val PARAM_FALLBACK_URL = "https://github.com/nihui/rife-ncnn-vulkan/raw/master/models/rife-v4/flownet.param"
        const val BIN_FALLBACK_URL = "https://github.com/nihui/rife-ncnn-vulkan/raw/master/models/rife-v4/flownet.bin"

        private val allowedExactHosts = setOf(
            "github.com",
            "githubusercontent.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "cdn.jsdelivr.net",
            "jsdelivr.net",
            "huggingface.co",
            "hf.co",
            "amazonaws.com"
        )

        private val allowedHostSuffixes = setOf(
            ".github.com",
            ".githubusercontent.com",
            ".jsdelivr.net",
            ".huggingface.co",
            ".hf.co",
            ".amazonaws.com"
        )

        private fun isAllowedHost(host: String): Boolean {
            val normalized = host.trimEnd('.').lowercase(java.util.Locale.ROOT)
            return normalized in allowedExactHosts ||
                allowedHostSuffixes.any { normalized.endsWith(it) } ||
                allowedExactHosts.any { normalized.endsWith(".$it") }
        }

        fun isAllowedUrl(urlString: String): Boolean {
            val uri = try {
                java.net.URI(urlString).parseServerAuthority()
            } catch (_: Exception) {
                return false
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host ?: return false
            return isAllowedHost(host)
        }
    }

    val modelDir: File
        get() = File(context.filesDir, "models/rife-v4").also { it.mkdirs() }

    val paramFile: File
        get() = File(modelDir, "flownet.param")

    val binFile: File
        get() = File(modelDir, "flownet.bin")

    fun isModelReady(): Boolean {
        return paramFile.exists() && paramFile.length() > 500 &&
            binFile.exists() && binFile.length() >= 10_000_000L
    }

    suspend fun ensureModelDownloaded(
        onProgress: (progressPct: Int, message: String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            return@withContext Result.success(modelDir)
        }

        try {
            onProgress(5, "Downloading RIFE AI model configuration…")
            val paramSuccess = runCatching {
                downloadSingle(PARAM_URL, paramFile) { _, _ -> }
            }.recoverCatching { e ->
                Log.w(TAG, "Primary param download failed, trying CDN: ${e.message}")
                downloadSingle(PARAM_CDN_URL, paramFile) { _, _ -> }
            }.recoverCatching { e ->
                Log.w(TAG, "CDN param download failed, trying fallback: ${e.message}")
                downloadSingle(PARAM_FALLBACK_URL, paramFile) { _, _ -> }
            }.isSuccess

            if (!paramSuccess || !paramFile.exists() || paramFile.length() < 500) {
                throw IOException("Failed to download RIFE parameter file")
            }

            onProgress(15, "Downloading RIFE AI neural weights (~10 MB)…")
            val estimatedTotalBytes = 10_340_416L
            var lastUpdateMs = 0L
            var lastReportedPct = -1
            val progressCallback: (Long, Long) -> Unit = { downloaded, total ->
                val now = System.currentTimeMillis()
                val effectiveTotal = if (total > 0) total else estimatedTotalBytes
                val pct = (15 + ((downloaded * 80) / effectiveTotal).toInt()).coerceIn(15, 95)
                if (now - lastUpdateMs >= 250L || pct - lastReportedPct >= 2 || pct >= 95) {
                    lastUpdateMs = now
                    lastReportedPct = pct
                    val dlMb = downloaded / (1024 * 1024)
                    val totalMb = (effectiveTotal / (1024 * 1024)).coerceAtLeast(1)
                    onProgress(pct, "Downloading RIFE AI weights: ${dlMb}MB / ${totalMb}MB…")
                }
            }

            val binSuccess = runCatching {
                downloadSingle(BIN_URL, binFile, progressCallback)
            }.recoverCatching { e ->
                Log.w(TAG, "Primary bin download failed, trying CDN: ${e.message}")
                downloadSingle(BIN_CDN_URL, binFile, progressCallback)
            }.recoverCatching { e ->
                Log.w(TAG, "CDN bin download failed, trying fallback: ${e.message}")
                downloadSingle(BIN_FALLBACK_URL, binFile, progressCallback)
            }.isSuccess

            if (!binSuccess || !isModelReady()) {
                paramFile.delete()
                binFile.delete()
                throw IllegalStateException("RIFE model files are incomplete after download")
            }

            onProgress(100, "RIFE v4 AI model ready!")
            Result.success(modelDir)
        } catch (t: Throwable) {
            Log.e(TAG, "RIFE model download failed: ${t.message}", t)
            paramFile.delete()
            binFile.delete()
            Result.failure(t)
        }
    }

    private suspend fun downloadSingle(
        urlStr: String,
        destFile: File,
        onFileProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isAllowedUrl(urlStr)) {
            throw SecurityException("URL not allowed by origin policy: $urlStr")
        }

        var currentUrl = urlStr
        var redirectCount = 0
        var connection: HttpURLConnection? = null

        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        try {
            while (true) {
                ensureActive()
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "VeilFrame-Android")
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location header")
                    val resolved = URL(url, location).toString()
                    connection.disconnect()
                    if (!isAllowedUrl(resolved)) {
                        throw SecurityException("Redirect blocked to unapproved host: $resolved")
                    }
                    currentUrl = resolved
                    redirectCount++
                    if (redirectCount > 8) throw IllegalStateException("Too many redirects")
                    continue
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $status ${connection.responseMessage}")
                }
                break
            }

            val conn = connection ?: throw IllegalStateException("Connection null")
            val totalBytes = conn.contentLengthLong

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var totalDownloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        onFileProgress(totalDownloaded, totalBytes)
                    }
                    output.flush()
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
