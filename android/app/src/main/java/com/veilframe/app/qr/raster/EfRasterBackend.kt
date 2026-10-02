package com.veilframe.app.qr.raster

import android.graphics.Bitmap
import android.graphics.RectF

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
     * Performs a zero-interpolation pixel slice of [source] within integer bounds.
     * Equivalent to CGImage.cropping(to:).
     */
    fun crop(
        source: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Bitmap
}
