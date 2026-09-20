package com.veilframe.app.upscale.inference

/**
 * Complete, unified execution configuration selected by the AdaptiveExecutionPlanner.
 * Encapsulates backend, precision, concurrency, threading, tile size, overlap, and session strategy.
 *
 * intraOpThreads and interOpThreads are populated for CPU/XNNPACK execution and null for pure accelerator paths.
 */
data class ExecutionProfile(
    val backend: Backend,
    val precision: InferencePrecisionMode,
    val workers: Int,
    val intraOpThreads: Int?,
    val interOpThreads: Int?,
    val tileSize: Int,
    val overlap: Int,
    val sessionStrategy: SessionStrategy = SessionStrategy.SHARED_SESSION,
    val providerConfiguration: Map<String, String> = emptyMap()
) {
    init {
        require(workers >= 1) { "Workers must be >= 1, but was $workers" }
    }
}
