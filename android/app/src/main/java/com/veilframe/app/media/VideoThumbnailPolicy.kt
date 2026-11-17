package com.veilframe.app.media

import kotlin.math.roundToInt

/**
 * Request bounded decoder output for UI-only video thumbnails. Source
 * dimensions remain unchanged for export and frame-accurate processing.
 */
internal object VideoThumbnailPolicy {
    const val MAX_EDGE = 1280

    fun fitWithin(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
        require(maxEdge > 0) { "Maximum thumbnail edge must be positive" }
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height).toDouble())
        return maxOf(1, (width * scale).roundToInt()) to
            maxOf(1, (height * scale).roundToInt())
    }
}
