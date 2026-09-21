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
    val inputColorOrder: ColorOrder = ColorOrder.RGB,
    val inputNormalization: NormalizationSpec = NormalizationSpec.ZERO_TO_ONE,
    val outputColorOrder: ColorOrder = ColorOrder.RGB,
    val outputRange: OutputRangeSpec = OutputRangeSpec.ZERO_TO_ONE,
    val opset: Int = 17,
    val requiredProvider: String? = null
) {
    // Backward-compatibility accessors
    val colorOrder: ColorOrder get() = inputColorOrder
    val normalization: NormalizationSpec get() = inputNormalization

    constructor(
        sha256: String,
        inputWidthMultiple: Int = 1,
        inputHeightMultiple: Int = 1,
        scaleFactor: Int,
        inputChannels: Int = 3,
        outputChannels: Int = 3,
        colorOrder: ColorOrder = ColorOrder.RGB,
        normalization: NormalizationSpec = NormalizationSpec.ZERO_TO_ONE,
        opset: Int = 17,
        requiredProvider: String? = null
    ) : this(
        sha256 = sha256,
        inputWidthMultiple = inputWidthMultiple,
        inputHeightMultiple = inputHeightMultiple,
        scaleFactor = scaleFactor,
        inputChannels = inputChannels,
        outputChannels = outputChannels,
        inputColorOrder = colorOrder,
        inputNormalization = normalization,
        outputColorOrder = colorOrder,
        outputRange = when (normalization) {
            NormalizationSpec.ZERO_TO_ONE -> OutputRangeSpec.ZERO_TO_ONE
            NormalizationSpec.NEG_ONE_TO_ONE -> OutputRangeSpec.NEG_ONE_TO_ONE
            NormalizationSpec.ZERO_TO_255 -> OutputRangeSpec.ZERO_TO_255
        },
        opset = opset,
        requiredProvider = requiredProvider
    )
}

enum class ColorOrder {
    RGB,
    BGR
}

enum class NormalizationSpec {
    ZERO_TO_ONE,     // [0.0, 1.0]
    NEG_ONE_TO_ONE,  // [-1.0, 1.0]
    ZERO_TO_255      // [0, 255]
}

enum class OutputRangeSpec {
    ZERO_TO_ONE,     // [0.0, 1.0]
    NEG_ONE_TO_ONE,  // [-1.0, 1.0]
    ZERO_TO_255      // [0, 255]
}
