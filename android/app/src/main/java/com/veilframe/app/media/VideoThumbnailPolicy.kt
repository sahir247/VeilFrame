package com.veilframe.app.media

import kotlin.math.roundToInt

/**
 * Request bounded decoder output for UI-only video thumbnails. Source
 * dimensions remain unchanged for export and frame-accurate processing.
 */
internal object VideoThumbnailPolicy {
    const val MAX_EDGE = 1280

    /**
     * A scaled decoder frame can be mapped back to full-resolution coordinates
     * only when its orientation/aspect ratio agrees with source metadata.
     * Allow small rounding error, but reject 90-degree rotation mismatches.
     */
    fun matchesSourceAspect(
        sourceWidth: Int,
        sourceHeight: Int,
        decodedWidth: Int,
        decodedHeight: Int
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || decodedWidth <= 0 || decodedHeight <= 0) {
            return false
        }
        val original = sourceWidth.toDouble() / sourceHeight
        val decoded = decodedWidth.toDouble() / decodedHeight
        return kotlin.math.abs(decoded / original - 1.0) <= 0.03
    }

    fun fitWithin(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
        require(maxEdge > 0) { "Maximum thumbnail edge must be positive" }
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height).toDouble())
        return maxOf(1, (width * scale).roundToInt()) to
            maxOf(1, (height * scale).roundToInt())
    }
}
