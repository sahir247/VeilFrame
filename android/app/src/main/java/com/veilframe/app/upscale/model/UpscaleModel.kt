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
    val deploymentStatus: DeploymentStatus = DeploymentStatus.ANDROID_NATIVE,
    val license: String = "Apache-2.0 / Open Source",
    val qualityFocus: String = "Balanced",
    // ONNX Tensor & Runtime Compatibility Metadata
    val inputTensorLayout: String = "NCHW",
    val outputTensorLayout: String = "NCHW",
    val inputDataType: String = "FLOAT32",
    val outputDataType: String = "FLOAT32",
    val channelOrder: String = "RGB",
    val normalizationRange: String = "[0.0, 1.0]",
    val inputChannelOrder: String = channelOrder,
    val inputNormalizationRange: String = normalizationRange,
    val outputChannelOrder: String = "RGB",
    val outputNormalizationRange: String = "[0.0, 1.0]",
    val tileCompatible: Boolean = true,
    val minInputDimension: Int = 16,
    val isImported: Boolean = false
) {
    /**
     * Backward-compatibility accessor for existing tests and call sites.
     */
    val supportedScales: List<Int>
        get() = supportedOutputScales

    /**
     * Converts to a validated ModelRuntimeSpec, strictly rejecting unknown channel orders
     * or normalization strings (fail-closed, zero silent fallbacks).
     */
    fun toRuntimeSpec(): ModelRuntimeSpec {
        val inOrder = inputChannelOrder.trim().uppercase()
        val inColor = when (inOrder) {
            "RGB" -> ColorOrder.RGB
            "BGR" -> ColorOrder.BGR
            else -> throw IllegalArgumentException("Unsupported input channel order: '$inputChannelOrder' for model $id")
        }
        val inNorm = when (inputNormalizationRange.trim()) {
            "[0.0, 1.0]", "[0, 1]", "ZERO_TO_ONE" -> NormalizationSpec.ZERO_TO_ONE
            "[-1.0, 1.0]", "[-1, 1]", "NEG_ONE_TO_ONE" -> NormalizationSpec.NEG_ONE_TO_ONE
            "[0, 255]", "ZERO_TO_255" -> NormalizationSpec.ZERO_TO_255
            else -> throw IllegalArgumentException("Unsupported input normalization: '$inputNormalizationRange' for model $id")
        }
        val outOrder = outputChannelOrder.trim().uppercase()
        val outColor = when (outOrder) {
            "RGB" -> ColorOrder.RGB
            "BGR" -> ColorOrder.BGR
            else -> throw IllegalArgumentException("Unsupported output channel order: '$outputChannelOrder' for model $id")
        }
        val outRange = when (outputNormalizationRange.trim()) {
            "[0.0, 1.0]", "[0, 1]", "ZERO_TO_ONE" -> OutputRangeSpec.ZERO_TO_ONE
            "[-1.0, 1.0]", "[-1, 1]", "NEG_ONE_TO_ONE" -> OutputRangeSpec.NEG_ONE_TO_ONE
            "[0, 255]", "ZERO_TO_255" -> OutputRangeSpec.ZERO_TO_255
            else -> throw IllegalArgumentException("Unsupported output range: '$outputNormalizationRange' for model $id")
        }
        return ModelRuntimeSpec(
            sha256 = sha256,
            scaleFactor = nativeScale,
            inputChannels = 3,
            outputChannels = 3,
            inputColorOrder = inColor,
            inputNormalization = inNorm,
            outputColorOrder = outColor,
            outputRange = outRange
        )
    }
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

enum class DeploymentStatus {
    ANDROID_NATIVE,
    ANDROID_EXPERIMENTAL,
    REFERENCE_ONLY
}

enum class ModelGenre {
    GENERAL_PHOTO,
    PHOTO_FIDELITY,
    JPEG_WEB,
    ANIME_MANGA,
    PORTRAIT_FACE,
    LIGHTWEIGHT_MOBILE,
    ALGORITHMIC_FAST,
    CUSTOM_IMPORTED
}

enum class ModelCapability {
    SUPER_RESOLUTION,
    FACE_RESTORATION,
    DENOISING,
    JPEG_ARTIFACT_REDUCTION
}
