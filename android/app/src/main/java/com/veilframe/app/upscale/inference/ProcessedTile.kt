package com.veilframe.app.upscale.inference

import android.graphics.Bitmap

/**
 * Explicit scale contract for tile processing.
 *
 * Guarantees that the returned [bitmap] is already at the final requested [outputScale]
 * relative to the source tile, preventing implicit scale inference.
 */
data class ProcessedTile(
    val bitmap: Bitmap,
    val outputScale: Int
)
