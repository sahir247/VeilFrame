package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.model.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 7 — IMAGE_RESAMPLE (Pixelated image resample into QR matrix)
 *
 * The background image is downsampled to the QR module grid size (n×n pixels).
 * Each dark module is drawn with the average color of its corresponding 1×1 pixel
 * in the downsampled image — producing a pixelated photo embedded in the QR.
 *
 * Light modules still render as plain background so scanning works.
 *
 * Mirrors EFQRCodeStyleResampleImage.swift's pixel-color-sampling approach.
 */
class ResampleImageRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val totalSize = (n * cs).toInt()

        // Downsample background image to n×n
        val thumb: Bitmap? = params.backgroundImage?.let {
            Bitmap.createScaledBitmap(it, n, n, true)
        }

        val scale = params.dataScale.coerceIn(0.5f, 1.0f)

        for (col in 0 until n) {
            for (row in 0 until n) {
                val type = matrix.typeAt(col, row)
                val dark = matrix.isDark(col, row)
                val x = col * cs; val y = row * cs
                val cx2 = x + cs * 0.5f; val cy2 = y + cs * 0.5f

                when (type) {
                    ModuleType.POS_CENTER -> {
                        if (!dark) continue
                        val p = solidPaint(params.positionColor ?: params.foreground)
                        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = cs * 0.9f
                            color = params.positionColor ?: params.foreground
                        }
                        canvas.drawRect(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs, ring)
                        canvas.drawRect(x - cs, y - cs, x + 2f * cs, y + 2f * cs, p)
                    }
                    ModuleType.POS_OTHER -> {}
                    else -> {
                        if (!dark) continue
                        // Sample color from downsampled image at (col, row)
                        val moduleColor: Int = if (thumb != null && col < thumb.width && row < thumb.height) {
                            thumb.getPixel(col, row)
                        } else {
                            params.foreground
                        }
                        val paint = solidPaint(moduleColor)
                        val half = cs * scale / 2f
                        canvas.drawRect(cx2 - half, cy2 - half, cx2 + half, cy2 + half, paint)
                    }
                }
            }
        }

        thumb?.recycle()
        drawLogo(canvas, params, totalSize)
    }
}
