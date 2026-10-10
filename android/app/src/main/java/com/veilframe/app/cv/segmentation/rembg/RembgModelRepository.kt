package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Manages physical storage, presence checks, and lifecycle of downloaded ONNX models.
 * Models are stored in private internal files directory (context.filesDir/models/rembg),
 * completely isolated from external storage and guaranteed not to be shipped in the APK.
 */
class RembgModelRepository(
    private val context: Context,
) {
    val modelsDir: File
        get() = File(context.filesDir, "models/rembg").also { it.mkdirs() }

    fun getModelFile(model: RembgModel): File =
        File(modelsDir, model.fileName)

    fun isModelReady(model: RembgModel): Boolean {
        val file = getModelFile(model)
        return file.exists() && file.length() >= model.minBytes
    }

    fun getCachedSizeBytes(model: RembgModel): Long =
        getModelFile(model).takeIf { it.exists() }?.length() ?: 0L

    fun deleteModel(model: RembgModel): Boolean {
        val file = getModelFile(model)
        return file.exists() && file.delete()
    }

    fun clearAllModels() {
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    fun listReadyModels(): List<RembgModel> =
        RembgModel.entries.filter { isModelReady(it) }

    fun getTotalStorageUsedBytes(): Long {
        var total = 0L
        RembgModel.entries.forEach { model ->
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
