package com.veilframe.app.upscale.model

/**
 * Technical runtime and tensor specification for ONNX super-resolution models.
 * Governs pre-execution shape verification, channel ordering, and normalization invariants.
 */
data class ModelRuntimeSpec(
    val sha256: String,
    val inputWidthMultiple: Int = 1,
    val inputHeightMultiple: Int = 1,
    val scaleFactor: Int,
    val inputChannels: Int = 3,
    val outputChannels: Int = 3,
    val colorOrder: ColorOrder = ColorOrder.RGB,
    val normalization: NormalizationSpec = NormalizationSpec.ZERO_TO_ONE,
    val opset: Int = 17,
    val requiredProvider: String? = null
)

enum class ColorOrder {
    RGB,
    BGR
}

enum class NormalizationSpec {
    ZERO_TO_ONE,     // [0.0, 1.0]
    NEG_ONE_TO_ONE,  // [-1.0, 1.0]
    ZERO_TO_255      // [0, 255]
}
