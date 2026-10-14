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
    val isImported: Boolean = false,
    // ---- F15: quantization / variant metadata (additive; defaults keep every
    // existing entry and test valid). Populated by tools/model_pipeline output.
    /** Weight precision of THIS artifact. FP16/INT8 variants are published separately. */
    val precision: ModelPrecision = ModelPrecision.FP32,
    /** Base FP32 model id when this entry is a quantized variant. */
    val variantOf: String? = null,
    /** Approximate peak working-set bytes at the reference tile size (0 = unknown). */
    val memoryClassBytes: Long = 0L,
    /** Pipeline-measured backend hint (legacy ONNX only); null = benchmark auto. */
    val recommendedBackend: String? = null,
    /** NCNN runtime family. Required for [ModelType.AI_NCNN]. */
    val engineKind: NcnnEngineKind? = null,
    /** NCNN model graph URL and its pinned SHA-256 digest. */
    val paramDownloadUrl: String? = null,
    val paramSha256: String? = null,
    val paramSizeBytes: Long = 0L,
    /** NCNN weights URL and its pinned SHA-256 digest. */
    val binDownloadUrl: String? = null,
    val binSha256: String? = null,
    val binSizeBytes: Long = 0L,
    /** Input/output blob names in the NCNN graph. */
    val inputBlobName: String = "data",
    val outputBlobName: String = "output",
    /** Border replicated around each inference tile before model execution. */
    val prePadding: Int = 10
) {
    /**
     * Validates the complete NCNN artifact contract before registering/downloading
     * a neural NCNN model. Checksums are mandatory; empty placeholders are rejected.
     */
    fun validateNcnnArtifactContract() {
        require(type == ModelType.AI_NCNN) { "Model $id is not an NCNN model." }
        require(engineKind != null) { "NCNN model $id must declare an engineKind." }
        require(nativeScale > 0) { "NCNN model $id must declare a positive native scale." }
        require(prePadding >= 0) { "NCNN model $id has invalid prePadding=$prePadding." }
        require(inputBlobName.isNotBlank() && outputBlobName.isNotBlank()) {
            "NCNN model $id must declare non-empty input/output blob names."
        }
        val paramUrl = requireNotNull(paramDownloadUrl) { "NCNN model $id has no .param URL." }
        val binUrl = requireNotNull(binDownloadUrl) { "NCNN model $id has no .bin URL." }
        require(paramUrl.startsWith("https://", ignoreCase = true) &&
            paramUrl.substringBefore('?').endsWith(".param", ignoreCase = true)) {
            "NCNN model $id must use an HTTPS .param URL."
        }
        require(binUrl.startsWith("https://", ignoreCase = true) &&
            binUrl.substringBefore('?').endsWith(".bin", ignoreCase = true)) {
            "NCNN model $id must use an HTTPS .bin URL."
        }
        val sha256Regex = Regex("^[A-Fa-f0-9]{64}$")
        require(paramSha256?.matches(sha256Regex) == true) {
            "NCNN model $id must declare a valid .param SHA-256."
        }
        require(binSha256?.matches(sha256Regex) == true) {
            "NCNN model $id must declare a valid .bin SHA-256."
        }
        require(paramSizeBytes > 0L && binSizeBytes > 0L) {
            "NCNN model $id must declare positive .param/.bin byte sizes."
        }
        require(sizeBytes == paramSizeBytes + binSizeBytes) {
            "NCNN model $id total size ($sizeBytes) must equal .param + .bin sizes " +
                "($paramSizeBytes + $binSizeBytes)."
        }
    }

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
    /** Native NCNN inference. The remaining AI_ONNX entries are migration-only and will be retired in a later phase. */
    AI_NCNN,
    AI_ONNX
}

/** Native NCNN implementation family, kept separate from model identity and file format. */
enum class NcnnEngineKind {
    REALSR_ESRGAN,
    REALCUGAN,
    WAIFU2X
}

/** F15: weight precision of a published model artifact. */
enum class ModelPrecision {
    FP32,
    FP16,
    INT8
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
