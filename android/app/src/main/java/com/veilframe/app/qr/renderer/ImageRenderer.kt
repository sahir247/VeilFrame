package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 6 — IMAGE (full background overlay)
 *
 * A classic QR is rendered first (dark modules as rounded squares),
 * then the background image is composited over it at [params.backgroundImageAlpha].
 * The result is a QR where the image is subtly visible through the module pattern —
 * identical in effect to EFQRCodeStyleImage.swift's watermark approach.
 *
 * The image is drawn at full canvas size with SRC_ATOP blending so it
 * only appears where the QR modules are, preserving the module outlines.
 */
class ImageRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val totalSize = (n * cs).toInt()
        val scale = params.dataScale.coerceIn(0.5f, 1.0f)

        // Pass 1: draw solid QR modules
        val fgPaint = solidPaint(params.foreground)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val x = col * cs; val y = row * cs
                val cx2 = x + cs * 0.5f; val cy2 = y + cs * 0.5f

                when (type) {
                    ModuleType.POS_CENTER -> {
                        val p = solidPaint(params.positionColor ?: params.foreground)
                        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = cs * 0.9f
                            color = params.positionColor ?: params.foreground
                        }
                        canvas.drawRect(RectF(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs), ring)
                        canvas.drawRect(x - cs, y - cs, x + 2f * cs, y + 2f * cs, p)
                    }
                    ModuleType.POS_OTHER -> {}
                    else -> {
                        val half = cs * scale / 2f
                        canvas.drawRoundRect(
                            RectF(cx2 - half, cy2 - half, cx2 + half, cy2 + half),
                            half * 0.25f, half * 0.25f, fgPaint
                        )
                    }
                }
            }
        }

        // Pass 2: blend background image on top using SRC_OVER at partial alpha
        val bgImage = params.backgroundImage ?: run {
            drawLogo(canvas, params, totalSize)
            return
        }
        val scaled = Bitmap.createScaledBitmap(bgImage, totalSize, totalSize, true)
        val imgPaint = Paint().apply {
            alpha = (params.backgroundImageAlpha * 255).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
        canvas.drawBitmap(scaled, 0f, 0f, imgPaint)

        drawLogo(canvas, params, totalSize)
    }
}
