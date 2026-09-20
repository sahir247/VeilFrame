package com.veilframe.app.upscale.inference

/**
 * Execution backend actually in use for model inference.
 * Truthful reporting: NNAPI or ORT CPU.
 */
enum class Backend {
    NNAPI,
    CPU
}

/**
 * User-selectable or automatic acceleration mode preference.
 */
enum class InferenceAccelerationMode {
    AUTO,
    NNAPI,
    CPU
}

/**
 * Precision mode for neural network execution.
 * DEFAULT: Strict FP32 precision
 * FP16_RELAXED: Enables NNAPIFlags.USE_FP16 for reduced memory bandwidth and faster execution
 */
enum class InferencePrecisionMode {
    DEFAULT,
    FP16_RELAXED
}

/**
 * Structured diagnostic metadata describing the active inference backend.
 */
data class InferenceBackendInfo(
    val backend: Backend,
    val accelerationMode: InferenceAccelerationMode,
    val cpuFallbackEnabled: Boolean,
    val fp16Enabled: Boolean,
    val intraOpThreads: Int,
    val interOpThreads: Int,
    val providerDescription: String
)
