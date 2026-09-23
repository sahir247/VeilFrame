package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.*

/**
 * Style 7 — IMAGE_RESAMPLE (Pixelated image resample into QR matrix)
 *
 * Samples pixel colors from a background image into the QR module grid,
 * applying gamma-corrected luminance compression to guarantee dark polarity and scanability.
 *
 * Inherits from [BaseQrRenderer] to guarantee that Finders, Timing Tracks, and Alignment
 * patterns maintain crisp solid contrast while data modules carry the resampled image texture.
 *
 * Inspired by EFQRCodeStyleResampleImage.swift.
 */
class ResampleImageRenderer : BaseQrRenderer() {

    override fun renderDataModules(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val fgColor = design.palette.foreground
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val bgImage = design.backgroundImage ?: (design.background as? BackgroundStyle.Image)?.bitmap

        val thumb: Bitmap? = if (bgImage != null && !bgImage.isRecycled) {
            Bitmap.createScaledBitmap(bgImage, n, n, true)
        } else null

        val paint = context.fillPaint
        val hsv = FloatArray(3)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.isProtected(col, row)) continue

                val rect = geometry.moduleRect(col, row, scale)

                val moduleColor: Int = if (thumb != null && col < thumb.width && row < thumb.height) {
                    val pixel = thumb.getPixel(col, row)
                    // EFQRCode gamma-corrected luminance computation
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val gray = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f

                    Color.colorToHSV(pixel, hsv)
                    // Compress value (brightness) to 0.05..0.50 to guarantee high contrast against light background
                    hsv[2] = 0.05f + (gray * 0.45f)
                    hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1.0f)
                    Color.HSVToColor(hsv)
                } else {
                    fgColor
                }

                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = moduleColor
                val rx = rect.width() * design.moduleStyle.cornerRadiusFraction.coerceAtLeast(0.15f)
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
        }

        thumb?.recycle()
    }
}
