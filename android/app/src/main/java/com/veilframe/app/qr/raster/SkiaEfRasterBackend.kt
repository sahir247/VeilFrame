package com.veilframe.app.qr.raster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Production Android/Skia implementation of [EfRasterBackend].
 *
 * Enforces the [EfRasterProfile.EF_7_0_3] contract:
 * - ARGB_8888 premultiplied raster output
 * - Bilinear filtering via [Paint.FILTER_BITMAP_FLAG]
 * - Dithering strictly disabled ([Paint.isDither] = false)
 * - Transparent canvas cleared to 0x00000000
 * - Single floating-to-subpixel boundary at Skia RectF
 */
object SkiaEfRasterBackend : EfRasterBackend {

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = false
    }

    override fun resize(source: Bitmap, width: Int, height: Int): Bitmap {
        if (width == source.width && height == source.height) return source
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        canvas.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), filterPaint)
        return dst
    }

    override fun drawInto(
        source: Bitmap,
        destinationWidth: Int,
        destinationHeight: Int,
        dstRect: RectF
    ): Bitmap {
        val w = destinationWidth.coerceAtLeast(1)
        val h = destinationHeight.coerceAtLeast(1)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // createBitmap zeroes memory to 0x00000000, matching CoreGraphics transparent context clear
        val canvas = Canvas(dst)
        canvas.drawBitmap(source, null, dstRect, filterPaint)
        return dst
    }

    override fun crop(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val safeX = x.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val safeW = width.coerceIn(1, (source.width - safeX).coerceAtLeast(1))
        val safeH = height.coerceIn(1, (source.height - safeY).coerceAtLeast(1))
        return Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
    }
}
