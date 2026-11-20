package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Manages physical storage, presence checks, and lifecycle of background removal models.
 * Bundled models (U^2-Net-P) are stored in APK assets and are always ready.
 * Downloaded models (MODNet) are stored in context.filesDir/models/rembg.
 */
class RembgModelRepository(
    private val context: Context,
) {
    val modelsDir: File
        get() = File(context.filesDir, "models/rembg").also { it.mkdirs() }

    fun getModelFile(model: RembgModel): File =
        File(modelsDir, model.fileName)

    fun getParamFile(model: RembgModel): File =
        File(modelsDir, model.paramFileName)

    fun isModelReady(model: RembgModel): Boolean {
        if (model.isBundled) return true
        val binFile = getModelFile(model)
        val binReady = (binFile.exists() && binFile.length() >= model.minBytes) ||
            (model == RembgModel.COLOROS_LIFT_SHIFT &&
                File(context.filesDir, "models/lift_shift/image_matting_mobile.tflite").let { it.exists() && it.length() >= model.minBytes })
        if (!binReady) return false
        if (model.paramFileName.isEmpty()) return true
        val paramFile = getParamFile(model)
        return paramFile.exists() && paramFile.length() > 500
    }

    /**
     * Disk presence is only a lightweight UI hint. Revalidate pinned bytes before
     * returning a cached download or passing downloaded weights to native code.
     * Call off the UI thread; cancellation checkpoints are supplied by the caller.
     */
    internal fun verifiedModelFile(model: RembgModel, checkpoint: () -> Unit = {}): File? {
        require(!model.isBundled) { "Bundled models are not downloaded files" }
        val binaryCandidates = listOf(getModelFile(model)) +
            if (model == RembgModel.COLOROS_LIFT_SHIFT)
                listOf(File(context.filesDir, "models/lift_shift/image_matting_mobile.tflite"))
            else emptyList()
        val maxBinBytes = (model.estimatedSizeBytes * 1.25).toLong().coerceAtLeast(20L * 1024 * 1024)
        val binary = binaryCandidates.firstOrNull {
            matchesPinnedArtifact(it, model.sha256, model.minBytes, maxBinBytes, checkpoint)
        } ?: return null
        if (model.paramFileName.isNotEmpty() &&
            !matchesPinnedArtifact(getParamFile(model), model.paramSha256, 500L, 10L * 1024 * 1024, checkpoint)) return null
        return binary
    }

    internal fun matchesPinnedArtifact(file: File, expected: String, minBytes: Long, maxBytes: Long,
                                       checkpoint: () -> Unit = {}): Boolean {
        if (!expected.matches(Regex("[a-fA-F0-9]{64}")) || !file.isFile ||
            file.length() !in minBytes..maxBytes) return false
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var total = 0L
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (n == 0) return false
                    total += n
                    if (total > maxBytes) return false
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
            total in minBytes..maxBytes && total == file.length() && actual.equals(expected, ignoreCase = true)
        } catch (_: java.io.IOException) { false }
        catch (_: SecurityException) { false }
    }

    fun getCachedSizeBytes(model: RembgModel): Long {
        if (model.isBundled) return model.estimatedSizeBytes
        var bin = getModelFile(model).takeIf { it.exists() }?.length() ?: 0L
        if (bin == 0L && model == RembgModel.COLOROS_LIFT_SHIFT) {
            bin = File(context.filesDir, "models/lift_shift/image_matting_mobile.tflite").takeIf { it.exists() }?.length() ?: 0L
        }
        val param = if (model.paramFileName.isNotEmpty()) {
            getParamFile(model).takeIf { it.exists() }?.length() ?: 0L
        } else 0L
        return bin + param
    }

    fun deleteModel(model: RembgModel): Boolean {
        if (model.isBundled) return false // Cannot delete bundled APK asset
        val f1 = getModelFile(model).let { it.exists() && it.delete() }
        val f2 = if (model.paramFileName.isNotEmpty()) {
            getParamFile(model).let { it.exists() && it.delete() }
        } else false
        val f3 = if (model == RembgModel.COLOROS_LIFT_SHIFT) {
            File(context.filesDir, "models/lift_shift/image_matting_mobile.tflite").let { it.exists() && it.delete() }
        } else false
        return f1 || f2 || f3
    }

    fun clearAllModels() {
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    fun listReadyModels(): List<RembgModel> =
        RembgModel.entries.filter { isModelReady(it) }

    fun getTotalStorageUsedBytes(): Long {
        var total = 0L
        RembgModel.entries.filter { !it.isBundled }.forEach { model ->
            total += getCachedSizeBytes(model)
        }
        return total
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            1024L * 1024L * 1024L // 1 GB fallback
        }
    }
}
