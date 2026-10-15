package com.veilframe.app.upscale.inference

import java.nio.ByteBuffer

/**
 * High-performance row callback for streaming scanline delivery from native NCNN.
 */
fun interface NcnnRowCallback {
    /**
     * Invoked when a scanline has completed inference.
     *
     * @param rowBuffer Direct [ByteBuffer] wrapping the native scanline memory.
     * @param rowIndex Zero-based vertical scanline index in the upscaled image.
     * @param rowWidth Width of the scanline in pixels.
     * @param channels Channel count (3 for RGB, 4 for RGBA).
     * @return 0 to continue, non-zero to cancel/abort inference immediately.
     */
    fun onRow(rowBuffer: ByteBuffer, rowIndex: Int, rowWidth: Int, channels: Int): Int
}
