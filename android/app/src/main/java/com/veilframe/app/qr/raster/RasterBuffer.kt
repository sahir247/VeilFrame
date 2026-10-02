package com.veilframe.app.qr.raster

import android.graphics.Bitmap

/**
 * Platform-independent abstraction for raw 32-bit RGBA / ARGB pixel buffers.
 * Facilitates differential testing, hash computation, and channel-by-channel comparison.
 */
class RasterBuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "Dimensions must be positive: ${width}x${height}" }
        require(pixels.size == width * height) { "Pixel array size (${pixels.size}) does not match ${width}x${height}" }
    }

    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun getAlpha(x: Int, y: Int): Int = (pixels[y * width + x] ushr 24) and 0xFF
    fun getRed(x: Int, y: Int): Int = (pixels[y * width + x] ushr 16) and 0xFF
    fun getGreen(x: Int, y: Int): Int = (pixels[y * width + x] ushr 8) and 0xFF
    fun getBlue(x: Int, y: Int): Int = pixels[y * width + x] and 0xFF

    companion object {
        fun fromBitmap(bitmap: Bitmap): RasterBuffer {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return RasterBuffer(w, h, pixels)
        }
    }
}
