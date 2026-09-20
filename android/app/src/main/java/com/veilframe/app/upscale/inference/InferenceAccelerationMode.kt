package com.veilframe.app.upscale.inference

/**
 * Execution backends supported or planned in VeilFrame.
 * Truthful reporting:
 * - NNAPI: Android Neural Networks API accelerator
 * - CPU: Standard optimized ONNX Runtime CPU engine
 * - XNNPACK: Highly optimized mobile floating-point CPU engine
 * - QNN: Qualcomm Neural Network execution provider (requires QNN-enabled custom ORT build)
 */
enum class Backend {
    NNAPI,
    CPU,
    XNNPACK,
    QNN
}

/**
 * Strategy for concurrent session execution across parallel workers.
 */
enum class SessionStrategy {
    SHARED_SESSION,
    SESSION_POOL
}

/**
 * User-selectable or automatic acceleration mode preference.
 */
enum class InferenceAccelerationMode {
    AUTO,
    NNAPI,
    CPU,
    XNNPACK,
    QNN
}

/**
 * Precision mode for neural network execution.
 * DEFAULT: Strict FP32 precision
 * FP16_RELAXED: Enables reduced memory bandwidth and faster execution where supported (e.g. NNAPIFlags.USE_FP16)
 */
enum class InferencePrecisionMode {
    DEFAULT,
    FP16_RELAXED
}

/**
 * Explicit capability declaration for a specific backend.
 * Prevents invalid Cartesian products (e.g. XNNPACK + FP16 when unsupported).
 */
data class BackendCapability(
    val backend: Backend,
    val available: Boolean,
    val supportedPrecisions: Set<InferencePrecisionMode>,
    val supportedSessionStrategies: Set<SessionStrategy>,
    val configurationId: String = backend.name
)

/**
 * Structured diagnostic metadata describing the active inference backend.
 * Uses truthful provider reporting without claiming generic "GPU" or unobserved fallback.
 */
data class InferenceBackendInfo(
    val backend: Backend,
    val accelerationMode: InferenceAccelerationMode,
    val configuredExecutionProvider: String,
    val cpuFallbackEnabled: Boolean,
    val observedFallback: String? = null,
    val fp16Enabled: Boolean,
    val intraOpThreads: Int? = null,
    val interOpThreads: Int? = null,
    val providerConfiguration: Map<String, String> = emptyMap(),
    val displayLabel: String = when {
        backend == Backend.NNAPI && cpuFallbackEnabled -> "NNAPI • CPU fallback enabled"
        backend == Backend.NNAPI -> "NNAPI"
        backend == Backend.QNN && providerConfiguration["backend_type"] == "HTP" -> "QNN / HTP"
        backend == Backend.QNN && providerConfiguration["backend_type"] == "GPU" -> "QNN / GPU"
        backend == Backend.QNN -> "QNN"
        backend == Backend.XNNPACK -> "XNNPACK"
        else -> "ORT CPU"
    }
) {
    val providerDescription: String get() = displayLabel
}

