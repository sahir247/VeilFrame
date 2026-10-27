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
        val paramFile = getParamFile(model)
        return binFile.exists() && binFile.length() >= model.minBytes &&
            paramFile.exists() && paramFile.length() > 500
    }

    fun getCachedSizeBytes(model: RembgModel): Long {
        if (model.isBundled) return model.estimatedSizeBytes
        val bin = getModelFile(model).takeIf { it.exists() }?.length() ?: 0L
        val param = getParamFile(model).takeIf { it.exists() }?.length() ?: 0L
        return bin + param
    }

    fun deleteModel(model: RembgModel): Boolean {
        if (model.isBundled) return false // Cannot delete bundled APK asset
        val f1 = getModelFile(model).let { it.exists() && it.delete() }
        val f2 = getParamFile(model).let { it.exists() && it.delete() }
        return f1 || f2
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
