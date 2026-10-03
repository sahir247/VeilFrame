package com.veilframe.app.qr.raster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Production Android/Skia implementation of [EfRasterBackend].
 *
 * Enforces the [EfRasterProfile.EF_RASTER_7_0_3_SRGB8] contract:
 * - ARGB_8888 premultiplied raster output
 * - Bilinear filtering via [Paint.FILTER_BITMAP_FLAG]
 * - Dithering strictly disabled ([Paint.isDither] = false)
 * - Transparent canvas cleared to 0x00000000
 * - Faithful reproduction of CoreGraphics CGRectIntegral in [crop]
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
        val dst: Bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            throw EfRasterException("Failed to allocate destination bitmap ($w x $h) in SkiaEfRasterBackend.resize", t)
        } ?: throw EfRasterException("Bitmap.createBitmap returned null in SkiaEfRasterBackend.resize (headless JVM stub or unavailable graphics driver)")

        try {
            val canvas = Canvas(dst)
            canvas.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), filterPaint)
            return dst
        } catch (t: Throwable) {
            throw EfRasterException("Failed to draw into destination bitmap ($w x $h) in SkiaEfRasterBackend.resize", t)
        }
    }

    override fun drawInto(
        source: Bitmap,
        destinationWidth: Int,
        destinationHeight: Int,
        dstRect: RectF
    ): Bitmap {
        val w = destinationWidth.coerceAtLeast(1)
        val h = destinationHeight.coerceAtLeast(1)
        val dst: Bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            throw EfRasterException("Failed to allocate destination bitmap ($w x $h) in SkiaEfRasterBackend.drawInto", t)
        } ?: throw EfRasterException("Bitmap.createBitmap returned null in SkiaEfRasterBackend.drawInto (headless JVM stub or unavailable graphics driver)")

        // createBitmap zeroes memory to 0x00000000, matching CoreGraphics transparent context clear
        try {
            val canvas = Canvas(dst)
            canvas.drawBitmap(source, null, dstRect, filterPaint)
            return dst
        } catch (t: Throwable) {
            throw EfRasterException("Failed to draw into destination bitmap ($w x $h) in SkiaEfRasterBackend.drawInto", t)
        }
    }

    override fun crop(source: Bitmap, rect: RectF): Bitmap {
        return crop(source, rect.left.toDouble(), rect.top.toDouble(), rect.width().toDouble(), rect.height().toDouble())
    }

    override fun crop(source: Bitmap, x: Double, y: Double, width: Double, height: Double): Bitmap {
        // Faithful reproduction of Apple CoreGraphics CGRectIntegral:
        // Returns the smallest rectangle with integer coordinates that contains the source rectangle:
        // x = floor(rect.origin.x)
        // y = floor(rect.origin.y)
        // width = ceil(rect.origin.x + rect.size.width) - floor(rect.origin.x)
        // height = ceil(rect.origin.y + rect.size.height) - floor(rect.origin.y)
        val minX = floor(x).toInt()
        val minY = floor(y).toInt()
        val maxX = ceil(x + width).toInt()
        val maxY = ceil(y + height).toInt()
        val integralWidth = (maxX - minX).coerceAtLeast(1)
        val integralHeight = (maxY - minY).coerceAtLeast(1)

        val safeX = minX.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val safeY = minY.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val safeW = integralWidth.coerceIn(1, (source.width - safeX).coerceAtLeast(1))
        val safeH = integralHeight.coerceIn(1, (source.height - safeY).coerceAtLeast(1))

        return try {
            Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
        } catch (t: Throwable) {
            throw EfRasterException("Failed to crop bitmap at [$safeX, $safeY, $safeW, $safeH] in SkiaEfRasterBackend.crop", t)
        } ?: throw EfRasterException("Bitmap.createBitmap returned null in SkiaEfRasterBackend.crop (headless JVM stub or unavailable graphics driver)")
    }
}
