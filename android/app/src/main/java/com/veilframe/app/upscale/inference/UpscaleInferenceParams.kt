package com.veilframe.app.upscale.inference

/**
 * Execution parameters for AI image processing pipeline.
 *
 * @property chunkSize Maximum tile dimension (width/height) for tiled super-resolution.
 * @property overlap Border overlap in pixels used for cubic Hermite seam blending.
 * @property strength Conditioning factor [0..100] for strength-aware models (e.g. FBCNN deblocking).
 * @property enableChunking Whether to partition large images into streaming disk chunks.
 * @property parallelWorkers Number of concurrent tile worker coroutines (clamped to available CPU cores).
 * @property detailEnhancement Whether to apply adaptive edge-preserving high-frequency texture restoration.
 * @property detailStrength Intensity factor [0.0..1.0] for micro-texture restoration (default 0.35f).
 */
data class UpscaleInferenceParams(
    val chunkSize: Int = 512,
    val overlap: Int = 32,
    val strength: Float = 65f,
    val enableChunking: Boolean = true,
    val parallelWorkers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    val detailEnhancement: Boolean = true,
    val detailStrength: Float = 0.35f
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
        require(overlap >= 0) { "overlap cannot be negative: $overlap" }
        if (overlap > 0) {
            require(overlap < chunkSize / 2) {
                "overlap ($overlap) must be less than half of chunkSize ($chunkSize)"
            }
        }
        require(strength in 0f..100f) { "strength must be between 0 and 100: $strength" }
        require(parallelWorkers in 0..8) { "parallelWorkers must be in 0..8: $parallelWorkers" }
        require(detailStrength in 0f..1f) { "detailStrength must be between 0 and 1: $detailStrength" }
    }

    companion object {
        /**
         * Scale-aware peak working memory per tile estimate:
         * Input float planes + intermediate activations + output planes + output bitmap
         * perTilePeakBytes ~= (chunk * targetScale)^2 * 16 bytes
         */
        fun estimatePeakTileMemory(chunkSize: Int, targetScale: Int): Long {
            val outTileDim = chunkSize.toLong() * targetScale.coerceAtLeast(1)
            return (outTileDim * outTileDim * 16L).coerceAtLeast(1L)
        }

        /**
         * F3 (device-class tile sizing): replaces the fixed 512px/4-worker
         * default that spiked ~100 MB per worker on 4x models and thrashed
         * 8-core SoCs into thermal throttling.
         *
         * Invariant enforced by the tier table:
         *   workers x perTilePeak(chunk x scale) <= ~40% of the app heap,
         *   where perTilePeak ~= (chunk*scale)^2 x 20 bytes (float planes +
         *   int pixels + output bitmap + blend buffers).
         */
        fun forDevice(context: android.content.Context, targetScale: Int): UpscaleInferenceParams {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            val heapMb = (am?.largeMemoryClass ?: am?.memoryClass ?: 256)
            val lowRam = am?.isLowRamDevice ?: false
            val bigScale = targetScale >= 4

            var chunk: Int
            var workers: Int
            when {
                lowRam || heapMb < 256 -> {
                    chunk = if (bigScale) 128 else 160
                    workers = 1
                }
                heapMb < 512 -> {
                    chunk = if (bigScale) 160 else 256
                    workers = 1
                }
                else -> {
                    // For >=4x scales, 160 produces a 640px output tile per pass, ensuring
                    // single-dispatch GPU executions stay under 3-4s and never trip system watchdogs.
                    chunk = if (bigScale) 160 else 256
                    workers = 2
                }
            }

            // DYNAMIC adjustment (target fleet: 6-16 GB TOTAL, with fluctuating
            // AVAILABLE memory): the static heap tier above is only the ceiling.
            // Live MemAvailable decides the actual profile — neither hardcoded
            // conservative (would waste 16 GB flagships) nor hardcoded aggressive
            // (would OOM a 6 GB device busy with other apps).
            val availBytes = try {
                com.veilframe.app.cv.core.MemInfoMemoryProbe().availableMemoryBytes()
            } catch (_: Throwable) {
                0L
            }
            val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

            if (availBytes <= 0L || availBytes < 1_500_000_000L) {
                // Unknown (probe failed) or under ~1.5 GB available: step chunk down ~25% (snapped to 32px), single worker.
                chunk = ((chunk * 3 / 4) / 32 * 32).coerceAtLeast(128)
                workers = 1
            } else if (availBytes >= 3_500_000_000L && heapMb >= 512) {
                val perTilePeakBytes = estimatePeakTileMemory(chunk, targetScale)
                val heapBytes = heapMb.toLong() * 1024L * 1024L

                // Budget cap: concurrency must not exceed 25% of available system memory
                // or 40% of the app heap limit.
                val maxWorkersByAvailRam = (availBytes / 4 / perTilePeakBytes).toInt()
                val maxWorkersByHeap = ((heapBytes * 0.40) / perTilePeakBytes).toInt()
                val memorySafeWorkers = minOf(maxWorkersByAvailRam, maxWorkersByHeap)

                val budget = computeBudget(availableCores)
                workers = minOf(budget.parallelWorkers, memorySafeWorkers).coerceAtLeast(1)
            }

            val thermal = try {
                com.veilframe.app.runtime.ThermalGovernor.response
            } catch (_: Throwable) {
                com.veilframe.app.runtime.ThermalGovernor.ThermalResponse.NONE
            }

            when (thermal) {
                com.veilframe.app.runtime.ThermalGovernor.ThermalResponse.FULL_THROTTLE -> {
                    chunk = ((chunk * 3 / 4) / 32 * 32).coerceAtLeast(128)
                    workers = 1
                }
                com.veilframe.app.runtime.ThermalGovernor.ThermalResponse.MILD_THROTTLE -> {
                    workers = maxOf(1, workers - 1)
                }
                else -> { /* retain computed budget */ }
            }

            return UpscaleInferenceParams(
                chunkSize = chunk,
                overlap = minOf(32, chunk / 4),
                parallelWorkers = workers,
            )
        }

        data class ComputeBudget(
            val parallelWorkers: Int,
            val intraOpThreads: Int
        )

        fun computeBudget(availableCores: Int): ComputeBudget {
            val usableCores = (availableCores - 1).coerceAtLeast(2)
            val workers = usableCores.coerceIn(2, 3)
            val intraOpThreads = usableCores.coerceIn(2, 4)
            return ComputeBudget(workers, intraOpThreads)
        }
    }
}
