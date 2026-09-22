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
        UpscaleModelRegistry.unregisterCustomModel(modelId)
        val dir = File(baseModelsDir, modelId)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else false
    }

    fun getInstalledAiModels(): List<UpscaleModel> {
        scanAndRegisterImportedModels()
        return UpscaleModelRegistry.AI_MODELS.filter { isModelInstalled(it) }
    }

    /**
     * Imports an external ONNX/ORT model file into app-private storage and registers it.
     */
    fun importCustomModel(
        sourceFile: File,
        name: String = sourceFile.nameWithoutExtension,
        nativeScale: Int = 4
    ): UpscaleModel {
        require(sourceFile.exists() && sourceFile.length() > 0) { "Source model file is empty or missing: ${sourceFile.absolutePath}" }
        val sanitized = sourceFile.nameWithoutExtension.lowercase().replace("[^a-z0-9_-]".toRegex(), "_")
        val modelId = "custom-$sanitized"
        val destFile = getModelFile(modelId)
        sourceFile.copyTo(destFile, overwrite = true)
        val sha256 = com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(destFile)
        val customModel = UpscaleModel(
            id = modelId,
            name = name.replace("_", " ").replace("-", " "),
            description = "Custom imported ONNX/ORT model (${destFile.name})",
            type = ModelType.AI_ONNX,
            nativeScale = nativeScale,
            sizeBytes = destFile.length(),
            sha256 = sha256,
            supportedOutputScales = listOf(nativeScale, nativeScale * 2),
            isBuiltIn = false,
            genre = ModelGenre.CUSTOM_IMPORTED,
            tier = ModelTier.TIER_A_NATIVE,
            isImported = true
        )
        UpscaleModelRegistry.registerCustomModel(customModel)
        return customModel
    }

    /**
     * Imports an external ONNX/ORT model from an Android content URI.
     */
    fun importCustomModelFromUri(
        uri: android.net.Uri,
        fileName: String,
        nativeScale: Int = 4
    ): UpscaleModel {
        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}_$fileName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Failed to open input stream for URI: $uri")
            return importCustomModel(tempFile, fileName.substringBeforeLast('.'), nativeScale)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Discovers previously imported custom models on disk and registers them.
     */
    fun scanAndRegisterImportedModels() {
        val dirs = baseModelsDir.listFiles() ?: return
        for (dir in dirs) {
            if (dir.isDirectory && dir.name.startsWith("custom-")) {
                val file = File(dir, "model.ort")
                if (file.exists() && file.length() > 0) {
                    val modelId = dir.name
                    if (UpscaleModelRegistry.getModelById(modelId) == null) {
                        val sha256 = com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(file)
                        val displayName = dir.name.removePrefix("custom-")
                            .replace("_", " ")
                            .replace("-", " ")
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        val model = UpscaleModel(
                            id = modelId,
                            name = displayName,
                            description = "Custom imported ONNX/ORT model",
                            type = ModelType.AI_ONNX,
                            nativeScale = 4,
                            sizeBytes = file.length(),
                            sha256 = sha256,
                            supportedOutputScales = listOf(4, 8),
                            isBuiltIn = false,
                            genre = ModelGenre.CUSTOM_IMPORTED,
                            tier = ModelTier.TIER_A_NATIVE,
                            isImported = true
                        )
                        UpscaleModelRegistry.registerCustomModel(model)
                    }
                }
            }
        }
    }

    fun getTotalStorageUsedBytes(): Long {
        return baseModelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getAvailableStorageBytes(): Long {
        return context.filesDir.usableSpace
    }
}
