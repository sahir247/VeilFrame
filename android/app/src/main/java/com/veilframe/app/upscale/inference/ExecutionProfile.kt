package com.veilframe.app.upscale.inference

import ai.onnxruntime.OrtSession

/**
 * Low-level hardware accelerator options for execution providers such as NNAPI.
 */
data class AcceleratorConfiguration(
    val nnapiUseNchw: Boolean = false,
    val useFp16: Boolean = false,
    val providerOptions: Map<String, String> = emptyMap()
)

/**
 * Complete, unified execution configuration selected by the AdaptiveExecutionPlanner.
 * Encapsulates backend, precision, concurrency, threading, tile size, overlap, session strategy,
 * and accelerator options.
 *
 * intraOpThreads and interOpThreads are populated for CPU/XNNPACK execution and null for pure accelerator paths.
 */
data class ExecutionProfile(
    val backend: Backend,
    val precision: InferencePrecisionMode,
    val workers: Int,
    val intraOpThreads: Int? = null,
    val interOpThreads: Int? = null,
    val tileSize: Int,
    val overlap: Int,
    val sessionStrategy: SessionStrategy = SessionStrategy.SHARED_SESSION,
    val providerConfiguration: Map<String, String> = emptyMap(),
    val acceleratorConfiguration: AcceleratorConfiguration = AcceleratorConfiguration(),
    val optLevel: OrtSession.SessionOptions.OptLevel? = null
) {
    init {
        require(workers >= 1) { "Workers must be >= 1, but was $workers" }
    }
}
