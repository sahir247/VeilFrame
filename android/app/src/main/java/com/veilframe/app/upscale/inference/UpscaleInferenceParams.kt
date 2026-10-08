package com.veilframe.app.upscale.inference

/**
 * Execution parameters for AI image processing pipeline.
 *
 * @property chunkSize Maximum tile dimension (width/height) for tiled super-resolution.
 * @property overlap Border overlap in pixels used for cubic Hermite seam blending.
 * @property strength Conditioning factor [0..100] for strength-aware models (e.g. FBCNN deblocking).
 * @property enableChunking Whether to partition large images into streaming disk chunks.
 * @property parallelWorkers Number of concurrent tile worker coroutines (clamped to available CPU cores).
 */
data class UpscaleInferenceParams(
    val chunkSize: Int = 512,
    val overlap: Int = 32,
    val strength: Float = 65f,
    val enableChunking: Boolean = true,
    val parallelWorkers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
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
    }

    companion object {
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
                    chunk = if (bigScale) 160 else 256
                    workers = 1
                }
                heapMb < 512 -> {
                    chunk = if (bigScale) 256 else 384
                    workers = 1
                }
                else -> {
                    chunk = if (bigScale) 384 else 512
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
            if (availBytes in 1 until 1_500_000_000L) {
                // Under ~1.5 GB available: step chunk down ~25% (snapped to 32px), single worker.
                chunk = ((chunk * 3 / 4) / 32 * 32).coerceAtLeast(128)
                workers = 1
            } else if (availBytes >= 4_000_000_000L && heapMb >= 512 && !bigScale) {
                // Plenty of headroom: keep the aggressive profile (2 workers).
                workers = 2
            }

            return UpscaleInferenceParams(
                chunkSize = chunk,
                overlap = minOf(32, chunk / 4),
                parallelWorkers = workers,
            )
        }
    }
}
