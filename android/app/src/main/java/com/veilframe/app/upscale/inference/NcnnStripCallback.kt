package com.veilframe.app.upscale.inference

import java.nio.ByteBuffer

/**
 * Functional callback invoked by native RealSR engine to fetch a horizontal input strip
 * on demand. Enables bounded-memory streaming without holding full source bitmaps in memory.
 */
fun interface NcnnStripCallback {
    /**
     * Called by native engine to populate [buffer] with [height] image scanlines
     * starting at absolute source scanline [y0].
     *
     * @param buffer Direct [ByteBuffer] preallocated to at least `width * height * channels` bytes.
     * @param y0 Starting Y scanline index in the source image.
     * @param height Number of scanlines in this strip.
     * @return 0 to continue, non-zero to cancel/abort processing.
     */
    fun onFetchStrip(buffer: ByteBuffer, y0: Int, height: Int): Int
}
