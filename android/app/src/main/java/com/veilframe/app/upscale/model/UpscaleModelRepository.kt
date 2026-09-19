package com.veilframe.app.upscale.model

import android.content.Context
import java.io.File

/**
 * Manages local storage and installation state of upscaling models.
 * App-private storage at: context.filesDir/models/upscaler/{modelId}/model.ort
 */
class UpscaleModelRepository(private val context: Context) {

    private val baseModelsDir: File
        get() = File(context.filesDir, "models/upscaler").apply { if (!exists()) mkdirs() }

    fun getModelDir(modelId: String): File {
        return File(baseModelsDir, modelId).apply { if (!exists()) mkdirs() }
    }

    fun getModelFile(modelId: String): File {
        return File(getModelDir(modelId), "model.ort")
    }

    fun isModelInstalled(model: UpscaleModel): Boolean {
        if (model.isBuiltIn) return true
        val file = getModelFile(model.id)
        return file.exists() && file.length() > 0
    }

    fun isModelInstalled(modelId: String): Boolean {
        val model = UpscaleModelRegistry.getModelById(modelId) ?: return false
        return isModelInstalled(model)
    }

    fun deleteModel(modelId: String): Boolean {
        val dir = File(baseModelsDir, modelId)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else false
    }

    fun getInstalledAiModels(): List<UpscaleModel> {
        return UpscaleModelRegistry.AI_MODELS.filter { isModelInstalled(it) }
    }

    fun getTotalStorageUsedBytes(): Long {
        return baseModelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getAvailableStorageBytes(): Long {
        return context.filesDir.usableSpace
    }
}
