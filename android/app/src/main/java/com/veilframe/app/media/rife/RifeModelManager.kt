package com.veilframe.app.media.rife

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages physical storage, downloads, active selection, and lifecycle of modern RIFE v4 AI models (v4.0 - v4.26).
 * Legacy RIFE v2.4 is completely excluded.
 */
class RifeModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VeilFrame.RifeModel"
        private const val PREFS_NAME = "veilframe_rife_prefs"
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
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "cdn.jsdelivr.net"
        )

        private fun isAllowedHost(host: String): Boolean {
            val normalized = host.trimEnd('.').lowercase(java.util.Locale.ROOT)
            return normalized in allowedExactHosts
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

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resolves the physical directory for a specific model spec across internal files or external storage.
     */
    fun getModelDir(spec: RifeModelSpec): File {
        val internalDir = File(context.filesDir, "models/${spec.id}")
        if (File(internalDir, "flownet.param").exists()) return internalDir

        val externalDir = context.getExternalFilesDir("models")?.let { File(it, spec.id) }
        if (externalDir != null && File(externalDir, "flownet.param").exists()) return externalDir

        // Legacy baseline path compatibility (context.filesDir/models/rife-v4)
        if (spec.id == RifeModelRegistry.RIFE_V4_0.id) {
            val legacyDir = File(context.filesDir, "models/rife-v4")
            if (File(legacyDir, "flownet.param").exists()) return legacyDir
        }

        return internalDir.also { it.mkdirs() }
    }

    fun isModelDownloaded(spec: RifeModelSpec): Boolean {
        val internalDir = File(context.filesDir, "models/${spec.id}")
        val externalDir = context.getExternalFilesDir("models")?.let { File(it, spec.id) }
        val legacyDir = if (spec.id == RifeModelRegistry.RIFE_V4_0.id) File(context.filesDir, "models/rife-v4") else null

        fun isValidDir(d: File?): Boolean {
            if (d == null || !d.exists()) return false
            val p = File(d, "flownet.param")
            val b = File(d, "flownet.bin")
            return p.exists() && p.length() > 500 && p.length() <= spec.maxParamBytes &&
                b.exists() && b.length() >= 4_000_000L && b.length() <= spec.maxBinBytes
        }

        return isValidDir(internalDir) || isValidDir(externalDir) || isValidDir(legacyDir)
    }

    fun computeFileSha256(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            file.inputStream().use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cryptographically validates that model artifacts match the pinned SHA-256 digests.
     */
    fun verifyModelChecksums(spec: RifeModelSpec): Boolean {
        val dir = getModelDir(spec)
        val p = File(dir, "flownet.param")
        val b = File(dir, "flownet.bin")
        if (!p.exists() || !b.exists()) return false

        val pHash = computeFileSha256(p) ?: return false
        val bHash = computeFileSha256(b) ?: return false
        return pHash.equals(spec.paramSha256, ignoreCase = true) &&
            bHash.equals(spec.binSha256, ignoreCase = true)
    }

    fun getSelectedModel(): RifeModelSpec {
        val savedId = prefs.getString(RifeModelRegistry.PREF_SELECTED_MODEL_ID, null)
        if (savedId != null) {
            return RifeModelRegistry.findById(savedId)
        }
        // Auto-select first available downloaded model, or default to RIFE_V4_25
        for (m in RifeModelRegistry.ALL_MODELS) {
            if (isModelDownloaded(m)) return m
        }
        return RifeModelRegistry.RIFE_V4_25
    }

    fun setSelectedModel(spec: RifeModelSpec) {
        prefs.edit().putString(RifeModelRegistry.PREF_SELECTED_MODEL_ID, spec.id).apply()
    }

    /**
     * Physical directory of currently active model.
     */
    val modelDir: File
        get() = getModelDir(getSelectedModel())

    val paramFile: File
        get() = File(modelDir, "flownet.param")

    val binFile: File
        get() = File(modelDir, "flownet.bin")

    val requiredPadding: Int
        get() = getSelectedModel().requiredPadding

    /**
     * Checks whether the currently active model is ready for inference.
     * If not ready, checks if any valid model is downloaded.
     */
    fun isModelReady(): Boolean {
        if (isModelDownloaded(getSelectedModel())) return true
        return RifeModelRegistry.ALL_MODELS.any { isModelDownloaded(it) }
    }

    /**
     * Returns total disk space used by all downloaded RIFE models in bytes.
     */
    fun getTotalStorageUsedBytes(): Long {
        var total = 0L
        for (m in RifeModelRegistry.ALL_MODELS) {
            val internalDir = File(context.filesDir, "models/${m.id}")
            val externalDir = context.getExternalFilesDir("models")?.let { File(it, m.id) }
            val legacyDir = if (m.id == RifeModelRegistry.RIFE_V4_0.id) File(context.filesDir, "models/rife-v4") else null

            val dirs = listOfNotNull(internalDir, externalDir, legacyDir).filter { it.exists() }
            for (d in dirs) {
                val p = File(d, "flownet.param")
                val b = File(d, "flownet.bin")
                if (p.exists()) total += p.length()
                if (b.exists()) total += b.length()
            }
        }
        return total
    }

    /**
     * Delete model files to reclaim storage.
     * Cleans up both internal app storage, external storage, and legacy directories.
     */
    fun deleteModel(spec: RifeModelSpec): Boolean {
        var anyDeleted = false

        // 1. Internal models directory
        val internalDir = File(context.filesDir, "models/${spec.id}")
        if (internalDir.exists()) {
            if (internalDir.deleteRecursively()) anyDeleted = true
        }

        // 2. External models directory
        context.getExternalFilesDir("models")?.let { parent ->
            val externalDir = File(parent, spec.id)
            if (externalDir.exists()) {
                if (externalDir.deleteRecursively()) anyDeleted = true
            }
        }

        // 3. Legacy baseline directory if applicable
        if (spec.id == RifeModelRegistry.RIFE_V4_0.id) {
            val legacyDir = File(context.filesDir, "models/rife-v4")
            if (legacyDir.exists()) {
                if (legacyDir.deleteRecursively()) anyDeleted = true
            }
        }

        // 4. Also clean up any lingering tmp models if writable
        val tmpDir = File("/data/local/tmp/rife_models/${spec.id}")
        if (tmpDir.exists()) {
            runCatching { tmpDir.deleteRecursively() }
        }

        // 5. If this was the active model, fall back to another downloaded model or clear preference
        val currentSelected = prefs.getString(RifeModelRegistry.PREF_SELECTED_MODEL_ID, null)
        if (currentSelected == spec.id) {
            val nextAvailable = RifeModelRegistry.ALL_MODELS.firstOrNull { it.id != spec.id && isModelDownloaded(it) }
            if (nextAvailable != null) {
                setSelectedModel(nextAvailable)
            } else {
                prefs.edit().remove(RifeModelRegistry.PREF_SELECTED_MODEL_ID).apply()
            }
        }

        return anyDeleted || !isModelDownloaded(spec)
    }

    /**
     * Downloads the specified model with resilient fallback mirrors (Primary -> CDN -> Raw GitHub)
     * and real-time progress callbacks.
     */
    suspend fun downloadModel(
        spec: RifeModelSpec,
        forceRedownload: Boolean = false,
        onProgress: (progressPct: Int, message: String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "models/${spec.id}").also { it.mkdirs() }
        val paramF = File(targetDir, "flownet.param")
        val binF = File(targetDir, "flownet.bin")

        if (!forceRedownload && isModelDownloaded(spec)) {
            return@withContext Result.success(getModelDir(spec))
        }

        // Clean up partial or forced redownload files
        if (forceRedownload) {
            paramF.delete()
            binF.delete()
        }
        File(targetDir, "flownet.param.tmp").delete()
        File(targetDir, "flownet.bin.tmp").delete()

        try {
            onProgress(5, "Downloading ${spec.displayName} configuration…")
            val primaryParamUrl = spec.paramCdnUrl ?: spec.paramUrl
            val paramSuccess = runCatching {
                downloadSingle(primaryParamUrl, paramF, expectedSha256 = spec.paramSha256, maxBytes = spec.maxParamBytes) { _, _ -> }
            }.recoverCatching { e ->
                Log.w(TAG, "Primary param download failed, trying raw GitHub: ${e.message}")
                downloadSingle(spec.paramUrl, paramF, expectedSha256 = spec.paramSha256, maxBytes = spec.maxParamBytes) { _, _ -> }
            }.recoverCatching { e ->
                if (spec.paramFallbackUrl != null) {
                    Log.w(TAG, "Raw param download failed, trying fallback: ${e.message}")
                    downloadSingle(spec.paramFallbackUrl, paramF, expectedSha256 = spec.paramSha256, maxBytes = spec.maxParamBytes) { _, _ -> }
                } else throw e
            }.isSuccess

            if (!paramSuccess || !paramF.exists() || paramF.length() < 500) {
                throw IOException("Failed to download ${spec.displayName} parameter file")
            }

            onProgress(15, "Downloading ${spec.displayName} neural weights (${spec.sizeFormatted})…")
            val estimatedTotalBytes = spec.estimatedBytes
            var lastUpdateMs = 0L
            var lastReportedPct = -1
            val progressCallback: (Long, Long) -> Unit = { downloaded, total ->
                val now = System.currentTimeMillis()
                val effectiveTotal = if (total > 0) total else estimatedTotalBytes
                val pct = (15 + ((downloaded * 80) / effectiveTotal).toInt()).coerceIn(15, 95)
                if (now - lastUpdateMs >= 150L || pct - lastReportedPct >= 2 || pct >= 95) {
                    lastUpdateMs = now
                    lastReportedPct = pct
                    val dlMb = downloaded / (1024 * 1024)
                    val totalMb = (effectiveTotal / (1024 * 1024)).coerceAtLeast(1)
                    onProgress(pct, "Downloading ${spec.shortBadge} weights: ${dlMb}MB / ${totalMb}MB…")
                }
            }

            val primaryBinUrl = spec.binCdnUrl ?: spec.binUrl
            val binSuccess = runCatching {
                downloadSingle(primaryBinUrl, binF, expectedSha256 = spec.binSha256, maxBytes = spec.maxBinBytes, onFileProgress = progressCallback)
            }.recoverCatching { e ->
                Log.w(TAG, "Primary bin download failed, trying raw GitHub: ${e.message}")
                downloadSingle(spec.binUrl, binF, expectedSha256 = spec.binSha256, maxBytes = spec.maxBinBytes, onFileProgress = progressCallback)
            }.recoverCatching { e ->
                if (spec.binFallbackUrl != null) {
                    Log.w(TAG, "Raw bin download failed, trying fallback: ${e.message}")
                    downloadSingle(spec.binFallbackUrl, binF, expectedSha256 = spec.binSha256, maxBytes = spec.maxBinBytes, onFileProgress = progressCallback)
                } else throw e
            }.isSuccess

            if (!binSuccess || !isModelDownloaded(spec)) {
                paramF.delete()
                binF.delete()
                throw IllegalStateException("Model files for ${spec.displayName} are incomplete after download")
            }

            onProgress(100, "${spec.displayName} ready!")
            setSelectedModel(spec)
            Result.success(targetDir)
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed for ${spec.displayName}: ${t.message}", t)
            paramF.delete()
            binF.delete()
            Result.failure(t)
        }
    }

    suspend fun ensureModelDownloaded(
        onProgress: (progressPct: Int, message: String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val selected = getSelectedModel()
        if (isModelDownloaded(selected)) {
            return@withContext Result.success(getModelDir(selected))
        }
        // If an alternative model is downloaded, automatically switch to it if needed
        val available = RifeModelRegistry.ALL_MODELS.firstOrNull { isModelDownloaded(it) }
        if (available != null) {
            setSelectedModel(available)
            return@withContext Result.success(getModelDir(available))
        }
        // Otherwise download the selected model
        return@withContext downloadModel(selected, forceRedownload = false, onProgress = onProgress)
    }

    private suspend fun downloadSingle(
        urlStr: String,
        destFile: File,
        expectedSha256: String? = null,
        maxBytes: Long = 25_000_000L,
        onFileProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isAllowedUrl(urlStr)) {
            throw SecurityException("URL not allowed by origin policy: $urlStr")
        }

        var currentUrl = urlStr
        var redirectCount = 0
        var connection: HttpURLConnection? = null

        val parentDir = destFile.parentFile ?: throw IOException("Invalid destination file parent")
        if (!parentDir.exists()) parentDir.mkdirs()

        val tempFile = File(parentDir, "${destFile.name}.tmp")
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
            if (totalBytes > maxBytes) {
                throw SecurityException("Reported file size ($totalBytes bytes) exceeds maximum limit ($maxBytes bytes)")
            }

            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var totalDownloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        md.update(buffer, 0, bytesRead)
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        if (totalDownloaded > maxBytes) {
                            throw SecurityException("Downloaded bytes ($totalDownloaded) exceeded maximum allowed limit ($maxBytes)")
                        }
                        onFileProgress(totalDownloaded, totalBytes)
                    }
                    output.flush()
                    try { output.fd.sync() } catch (_: Exception) {}
                }
            }

            val computedHash = md.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !computedHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                tempFile.delete()
                throw SecurityException("Cryptographic SHA-256 mismatch for ${destFile.name}! Expected $expectedSha256, computed $computedHash")
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
