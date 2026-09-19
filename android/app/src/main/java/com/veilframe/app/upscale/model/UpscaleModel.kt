package com.veilframe.app.upscale.model

/**
 * Metadata definition for image upscaling and restoration models.
 * Decouples native model scale from supported output scales (e.g., 4× neural + 2× Lanczos -> 8× output).
 * Stores comprehensive ONNX tensor and runtime compatibility metadata.
 */
data class UpscaleModel(
    val id: String,
    val name: String,
    val description: String,
    val type: ModelType,
    val nativeScale: Int,
    val sizeBytes: Long,
    val downloadUrl: String = "",
    val sha256: String = "",
    val supportedOutputScales: List<Int> = listOf(nativeScale),
    val recommendedPresetIds: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val supportsAlpha: Boolean = true,
    val genre: ModelGenre = ModelGenre.GENERAL_PHOTO,
    val capability: ModelCapability = ModelCapability.SUPER_RESOLUTION,
    val tier: ModelTier = ModelTier.TIER_A_NATIVE,
    val license: String = "Apache-2.0 / Open Source",
    val qualityFocus: String = "Balanced",
    // ONNX Tensor & Runtime Compatibility Metadata
    val inputTensorLayout: String = "NCHW",
    val outputTensorLayout: String = "NCHW",
    val inputDataType: String = "FLOAT32",
    val outputDataType: String = "FLOAT32",
    val channelOrder: String = "RGB",
    val normalizationRange: String = "[0.0, 1.0]",
    val tileCompatible: Boolean = true,
    val minInputDimension: Int = 16
) {
    /**
     * Backward-compatibility accessor for existing tests and call sites.
     */
    val supportedScales: List<Int>
        get() = supportedOutputScales
}

enum class ModelType {
    ALGORITHMIC,
    AI_ONNX
}

enum class ModelTier {
    TIER_A_NATIVE,
    TIER_B_CONVERTED,
    TIER_C_DESKTOP
}

enum class ModelGenre {
    GENERAL_PHOTO,
    PHOTO_FIDELITY,
    JPEG_WEB,
    ANIME_MANGA,
    PORTRAIT_FACE,
    LIGHTWEIGHT_MOBILE,
    ALGORITHMIC_FAST
}

enum class ModelCapability {
    SUPER_RESOLUTION,
    FACE_RESTORATION,
    DENOISING,
    JPEG_ARTIFACT_REDUCTION
}
