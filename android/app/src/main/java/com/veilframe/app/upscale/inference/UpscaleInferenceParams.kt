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
}
