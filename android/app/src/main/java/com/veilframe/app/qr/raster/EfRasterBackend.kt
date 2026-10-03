package com.veilframe.app.qr.raster

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Dedicated exception thrown on fail-closed rasterization failure in [EfRasterBackend].
 */
class EfRasterException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Authoritative interface for EFQRCode-compatible rasterization primitives.
 *
 * Decouples image preprocessing geometry calculations from the underlying graphics engine,
 * enabling production execution via [SkiaEfRasterBackend] and offline verification via
 * CoreGraphics or synthetic oracles.
 */
interface EfRasterBackend {

    /**
     * Resizes [source] into a destination bitmap of dimensions [width] x [height]
     * using the reference interpolation kernel without dithering.
     * Equivalent to CGImage.resize(to:).
     */
    fun resize(
        source: Bitmap,
        width: Int,
        height: Int
    ): Bitmap

    /**
     * Draws [source] into an expanded transparent canvas of dimensions [destinationWidth] x [destinationHeight]
     * with the source placed at [dstRect] (which may have fractional or negative coordinates).
     * Equivalent to CGImage.clipAndExpandingTransparencyWith(rect:).
     */
    fun drawInto(
        source: Bitmap,
        destinationWidth: Int,
        destinationHeight: Int,
        dstRect: RectF
    ): Bitmap

    /**
     * Performs a crop with CoreGraphics CGRectIntegral semantics:
     * - minX = floor(rect.left)
     * - minY = floor(rect.top)
     * - maxX = ceil(rect.right)
     * - maxY = ceil(rect.bottom)
     * - width = maxX - minX
     * - height = maxY - minY
     *
     * Equivalent to CGImage.cropping(to:) which applies CGRectIntegral prior to slicing.
     */
    fun crop(
        source: Bitmap,
        rect: RectF
    ): Bitmap

    /**
     * Performs a crop with 64-bit Double coordinates applying CoreGraphics CGRectIntegral semantics.
     */
    fun crop(
        source: Bitmap,
        x: Double,
        y: Double,
        width: Double,
        height: Double
    ): Bitmap
}
