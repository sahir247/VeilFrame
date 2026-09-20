package com.veilframe.app.upscale.inference

/**
 * Qualcomm QNN acceleration execution targets supported by hardware and runtime.
 */
enum class QnnTarget {
    HTP,
    GPU
}

/**
 * Computed compatibility status of a model on QNN execution provider targets.
 */
enum class QnnCompatibilityStatus {
    UNKNOWN,
    SUPPORTED,
    SUPPORTED_WITH_FALLBACK,
    UNSUPPORTED,
    REQUIRES_MODEL_VARIANT
}

/**
 * Optional model variant pointer (e.g. QDQ quantized variant for HTP, FP16 optimized graph).
 */
data class ModelVariant(
    val id: String,
    val relativePath: String? = null,
    val isQuantized: Boolean = false
)

/**
 * Model-level QNN target capability distinguishing floating-point and quantized support for HTP and GPU.
 *
 * QNN HTP:
 * - Floating-point and quantized models are both potentially eligible.
 * - Floating-point execution capability depends on SoC/QAIRT/model constraints (using FP16 math on supported SoCs).
 * - Quantized/QDQ variants may provide a different execution/performance path.
 * - Do not pre-classify RealESRGAN FP32/FP16 as HTP-ineligible.
 *
 * QNN GPU:
 * - FP32/FP16 models are directly eligible.
 * - Quantized-weight configurations may also be eligible where supported.
 *
 * Final eligibility is determined by runtime EP-device discovery, model compatibility, and the full-coverage execution probe.
 */
data class QnnModelCapability(
    val htpCandidate: Boolean = true,
    val gpuCandidate: Boolean = true,
    val htpSupported: Boolean = htpCandidate,
    val gpuSupported: Boolean = gpuCandidate,
    val htpPrecisionModes: Set<InferencePrecisionMode> = setOf(
        InferencePrecisionMode.DEFAULT,
        InferencePrecisionMode.FP16_RELAXED
    ),
    val gpuPrecisionModes: Set<InferencePrecisionMode> = setOf(
        InferencePrecisionMode.DEFAULT,
        InferencePrecisionMode.FP16_RELAXED
    ),
    val htpModelVariant: ModelVariant? = null,
    val gpuModelVariant: ModelVariant? = null
)

/**
 * Declares operational constraints of an AI super-resolution model extracted via graph introspection.
 * Used by the AdaptiveExecutionPlanner to filter out candidate configurations the model cannot support.
 */
data class ModelExecutionCapabilities(
    val nativeScale: Int,
    val supportedOutputScales: List<Int> = listOf(nativeScale, nativeScale * 2),
    val inputType: String = "FLOAT",
    val outputType: String = "FLOAT",
    val fixedWidth: Int? = null,
    val fixedHeight: Int? = null,
    val minSpatialSize: Int = 32,
    val tileCompatible: Boolean = true,
    val supportsFp16: Boolean = true,
    val qnnCompatibilityStatus: QnnCompatibilityStatus = QnnCompatibilityStatus.SUPPORTED,
    val qnnCapability: QnnModelCapability = QnnModelCapability(
        htpCandidate = true,
        gpuCandidate = true
    ),
    val qnnCandidateTargets: Set<QnnTarget> = buildSet {
        if (qnnCapability.gpuCandidate) add(QnnTarget.GPU)
        if (qnnCapability.htpCandidate) add(QnnTarget.HTP)
    },
    val qnnSupportedTargets: Set<QnnTarget> = buildSet {
        if (qnnCapability.gpuSupported) add(QnnTarget.GPU)
        if (qnnCapability.htpSupported) add(QnnTarget.HTP)
    }
) {
    val qnnCompatible: Boolean
        get() = qnnCompatibilityStatus == QnnCompatibilityStatus.SUPPORTED ||
                qnnCompatibilityStatus == QnnCompatibilityStatus.SUPPORTED_WITH_FALLBACK ||
                qnnSupportedTargets.isNotEmpty()

    val isDynamicSpatial: Boolean
        get() = fixedWidth == null && fixedHeight == null
}

