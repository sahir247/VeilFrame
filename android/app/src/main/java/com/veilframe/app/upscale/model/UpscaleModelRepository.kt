package com.veilframe.app.upscale.model

import android.content.Context
import java.io.File

/**
 * Manages local storage and installation state of upscaling models.
 * App-private storage at: context.filesDir/models/upscaler/{modelId}/
 * NCNN uses model.param + model.bin and an install marker.
 */
class UpscaleModelRepository(private val context: Context) {

    private val baseModelsDir: File
        get() = File(context.filesDir, "models/upscaler").apply { if (!exists()) mkdirs() }

    fun getModelDir(modelId: String): File {
        return File(baseModelsDir, modelId).apply { if (!exists()) mkdirs() }
    }

    fun getModelFile(modelId: String): File {
        return File(getModelDir(modelId), "model.bin")
    }

    fun getNcnnParamFile(modelId: String): File =
        File(getModelDir(modelId), "model.param")

    fun getNcnnBinFile(modelId: String): File =
        File(getModelDir(modelId), "model.bin")

    fun getNcnnInstallMarkerFile(modelId: String): File =
        File(getModelDir(modelId), "model.ncnn.complete")

    fun isModelInstalled(model: UpscaleModel): Boolean {
        if (model.isBuiltIn) return true
        return when (model.type) {
            ModelType.ALGORITHMIC -> false
            ModelType.AI_NCNN -> {
                val paramFile = getNcnnParamFile(model.id)
                val binFile = getNcnnBinFile(model.id)
                if (!paramFile.isFile || !binFile.isFile || paramFile.length() == 0L || binFile.length() == 0L) {
                    false
                } else if (model.isImported) {
                    true
                } else {
                    val marker = getNcnnInstallMarkerFile(model.id)
                    if (!marker.isFile) {
                        false
                    } else {
                        val markerLines = runCatching { marker.readLines() }.getOrDefault(emptyList())
                        markerLines.size >= 2 &&
                            markerLines[0].equals(model.paramSha256, ignoreCase = true) &&
                            markerLines[1].equals(model.binSha256, ignoreCase = true) &&
                            paramFile.length() == model.paramSizeBytes &&
                            binFile.length() == model.binSizeBytes
                    }
                }
            }
        }
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
     * Imports an external NCNN model file into app-private storage and registers it.
     */
    fun importCustomModel(
        sourceFile: File,
        name: String = sourceFile.nameWithoutExtension,
        nativeScale: Int = 4
    ): UpscaleModel {
        require(sourceFile.exists() && sourceFile.length() > 0) { "Source model file is empty or missing: ${sourceFile.absolutePath}" }
        val sanitized = sourceFile.nameWithoutExtension.lowercase().replace("[^a-z0-9_-]".toRegex(), "_")
        val modelId = "custom-ncnn-$sanitized"
        val destFile = getModelFile(modelId)
        sourceFile.copyTo(destFile, overwrite = true)
        val sha256 = com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(destFile)
        val customModel = UpscaleModel(
            id = modelId,
            name = name.replace("_", " ").replace("-", " ") + " (NCNN)",
            description = "Custom imported NCNN Vulkan model (${destFile.name})",
            type = ModelType.AI_NCNN,
            nativeScale = nativeScale,
            sizeBytes = destFile.length(),
            sha256 = sha256,
            supportedOutputScales = listOf(nativeScale),
            isBuiltIn = false,
            genre = ModelGenre.CUSTOM_IMPORTED,
            tier = ModelTier.TIER_A_NATIVE,
            isImported = true,
            engineKind = NcnnEngineKind.REALSR_ESRGAN,
            prePadding = 10
        )
        UpscaleModelRegistry.registerCustomModel(customModel)
        return customModel
    }

    /**
     * Imports an external custom model from an Android content URI.
     * Zip archives containing .param and .bin are imported as native NCNN models.
     */
    fun importCustomModelFromUri(
        uri: android.net.Uri,
        fileName: String,
        nativeScale: Int = 4
    ): UpscaleModel {
        if (fileName.endsWith(".zip", ignoreCase = true)) {
            return importCustomNcnnFromZip(uri, fileName, nativeScale)
        }
        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}_$fileName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Failed to open input stream for URI: $uri")

            val isZip = try {
                tempFile.inputStream().use { stream ->
                    val header = ByteArray(4)
                    val read = stream.read(header)
                    read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                }
            } catch (_: Exception) { false }

            return if (isZip) {
                importCustomNcnnFromZip(uri, fileName, nativeScale)
            } else {
                importCustomModel(tempFile, fileName.substringBeforeLast('.'), nativeScale)
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Imports an external NCNN .param and .bin pair into app-private storage and registers it.
     */
    fun importCustomNcnnModel(
        paramFile: File,
        binFile: File,
        name: String,
        nativeScale: Int = 4
    ): UpscaleModel {
        require(paramFile.exists() && paramFile.length() > 0) { "Missing param file: ${paramFile.absolutePath}" }
        require(binFile.exists() && binFile.length() > 0) { "Missing bin file: ${binFile.absolutePath}" }
        val sanitized = name.lowercase().replace("[^a-z0-9_-]".toRegex(), "_")
        val modelId = "custom-ncnn-$sanitized"
        val destParam = getNcnnParamFile(modelId).apply { parentFile?.mkdirs() }
        val destBin = getNcnnBinFile(modelId)
        paramFile.copyTo(destParam, overwrite = true)
        binFile.copyTo(destBin, overwrite = true)

        val paramSha = com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(destParam)
        val binSha = com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(destBin)

        val marker = getNcnnInstallMarkerFile(modelId)
        marker.writeText("$paramSha\n$binSha\n$nativeScale\n")

        val customModel = UpscaleModel(
            id = modelId,
            name = name.replace("_", " ").replace("-", " ") + " (NCNN)",
            description = "Custom NCNN Model (${destParam.name})",
            type = ModelType.AI_NCNN,
            nativeScale = nativeScale,
            sizeBytes = destParam.length() + destBin.length(),
            supportedOutputScales = listOf(nativeScale),
            isBuiltIn = false,
            genre = ModelGenre.CUSTOM_IMPORTED,
            tier = ModelTier.TIER_A_NATIVE,
            isImported = true,
            engineKind = NcnnEngineKind.REALSR_ESRGAN,
            paramSha256 = paramSha,
            paramSizeBytes = destParam.length(),
            binSha256 = binSha,
            binSizeBytes = destBin.length(),
            prePadding = 10
        )
        UpscaleModelRegistry.registerCustomModel(customModel)
        return customModel
    }

    /**
     * Imports an NCNN model from a .zip archive containing .param and .bin files.
     */
    companion object {
        const val MAX_NCNN_ZIP_ENTRIES = 25
        const val MAX_NCNN_ZIP_ENTRY_BYTES = 250 * 1024 * 1024L // 250 MB
        const val MAX_NCNN_ZIP_TOTAL_BYTES = 400 * 1024 * 1024L // 400 MB
    }

    /**
     * Imports an NCNN model from a .zip archive containing .param and .bin files.
     * Enforces entry count, single-file size, and total expansion bounds to protect
     * against zip bombs, disk exhaustion, and path traversal vulnerabilities.
     */
    fun importCustomNcnnFromZip(
        uri: android.net.Uri,
        fileName: String,
        nativeScale: Int = 4
    ): UpscaleModel {
        val tempExtractDir = File(context.cacheDir, "ncnn_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var entryCount = 0
            var totalExtractedBytes = 0L

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                java.util.zip.ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_NCNN_ZIP_ENTRIES) {
                            throw SecurityException("ZIP archive exceeds maximum entry limit ($MAX_NCNN_ZIP_ENTRIES entries)")
                        }

                        if (!entry.isDirectory) {
                            val rawName = entry.name
                            if (rawName.contains("..") || rawName.startsWith("/") || rawName.startsWith("\\")) {
                                throw SecurityException("Zip path traversal attempt detected: ")
                            }
                            val cleanName = File(rawName).name
                            if (cleanName.isBlank() || cleanName.contains("..") || cleanName.contains("/") || cleanName.contains("\\")) {
                                throw SecurityException("Malformed entry path in ZIP archive: $rawName")
                            }

                            val entryFile = File(tempExtractDir, cleanName)
                            var entryBytes = 0L

                            entryFile.outputStream().use { fos ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (zis.read(buffer).also { len = it } != -1) {
                                    entryBytes += len
                                    totalExtractedBytes += len
                                    if (entryBytes > MAX_NCNN_ZIP_ENTRY_BYTES) {
                                        throw SecurityException("ZIP entry '$cleanName' exceeds single file limit of $MAX_NCNN_ZIP_ENTRY_BYTES bytes")
                                    }
                                    if (totalExtractedBytes > MAX_NCNN_ZIP_TOTAL_BYTES) {
                                        throw SecurityException("ZIP archive exceeds total decompressed limit of $MAX_NCNN_ZIP_TOTAL_BYTES bytes")
                                    }
                                    fos.write(buffer, 0, len)
                                }
                                fos.flush()
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: error("Could not open input stream for zip: $uri")

            val files = tempExtractDir.listFiles() ?: emptyArray()
            val paramFile = files.firstOrNull { it.name.endsWith(".param", ignoreCase = true) }
                ?: error("Zip archive does not contain a .param file")
            val binFile = files.firstOrNull { it.name.endsWith(".bin", ignoreCase = true) }
                ?: error("Zip archive does not contain a .bin file")

            val modelName = fileName.removeSuffix(".zip").removeSuffix(".ZIP")
            return importCustomNcnnModel(paramFile, binFile, modelName, nativeScale)
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    /**
     * Discovers previously imported custom models on disk and registers them.
     */
    fun scanAndRegisterImportedModels() {
        val dirs = baseModelsDir.listFiles() ?: return
        for (dir in dirs) {
            if (dir.isDirectory && dir.name.startsWith("custom-")) {
                val ortFile = File(dir, "model.ort")
                val paramFile = File(dir, "model.param")
                val binFile = File(dir, "model.bin")
                val modelId = dir.name

                if (paramFile.exists() && binFile.exists() && paramFile.length() > 0 && binFile.length() > 0) {
                    if (UpscaleModelRegistry.getModelById(modelId) == null) {
                        val displayName = dir.name.removePrefix("custom-").removePrefix("ncnn-")
                            .replace("_", " ")
                            .replace("-", " ")
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        val model = UpscaleModel(
                            id = modelId,
                            name = "$displayName (NCNN)",
                            description = "Custom imported NCNN model",
                            type = ModelType.AI_NCNN,
                            nativeScale = 4,
                            sizeBytes = paramFile.length() + binFile.length(),
                            supportedOutputScales = listOf(4),
                            isBuiltIn = false,
                            genre = ModelGenre.CUSTOM_IMPORTED,
                            tier = ModelTier.TIER_A_NATIVE,
                            isImported = true,
                            engineKind = NcnnEngineKind.REALSR_ESRGAN,
                            paramSizeBytes = paramFile.length(),
                            binSizeBytes = binFile.length(),
                            prePadding = 10
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
