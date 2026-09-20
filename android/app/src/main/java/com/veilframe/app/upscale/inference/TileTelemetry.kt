package com.veilframe.app.upscale.inference

/**
 * Isolated per-tile execution breakdown.
 * Eliminates race conditions by associating telemetry directly with the tile that generated it.
 */
data class TileTelemetry(
    val tileIndex: Int,
    val bitmapExtractionMs: Long = 0L,
    val tensorPreparationMs: Long = 0L,
    val inferenceMs: Long = 0L,
    val outputConversionMs: Long = 0L,
    val bitmapCreationMs: Long = 0L,
    val lanczosMs: Long = 0L,
    val compositionMs: Long = 0L,
    val totalMs: Long = 0L,
    val backend: Backend = Backend.CPU,
    val precision: InferencePrecisionMode = InferencePrecisionMode.DEFAULT,
    val workers: Int = 1,
    val tileSize: Int = 256,
    val inputPixels: Long = 0L,
    val outputPixels: Long = 0L
) {
    val throughputMpPerSec: Double
        get() = if (totalMs > 0) (outputPixels / 1_000_000.0) / (totalMs / 1000.0) else 0.0
}

/**
 * Immutable envelope holding tile completion index and exact measured telemetry.
 */
data class TileResult(
    val index: Int,
    val telemetry: TileTelemetry
)
