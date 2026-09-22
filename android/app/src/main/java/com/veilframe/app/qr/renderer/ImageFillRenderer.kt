package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 5 — IMAGE_FILL
 *
 * The background image is tiled as a BitmapShader over each dark module cell.
 * Each dark module samples its color directly from the underlying image,
 * creating a "data mosaic" where the full image is visible through the QR pattern.
 *
 * Light modules reveal the plain background color (or white), providing
 * the contrast needed for scanning.
 *
 * Mirrors EFQRCodeStyleImageFill.swift: the image is scaled to fill the full
 * QR canvas and sampled per-module using a CLAMP/REPEAT BitmapShader.
 */
class ImageFillRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val totalSize = (n * cs).toInt()

        // Draw modules using image shader if backgroundImage is provided,
        // otherwise fall back to solid foreground color
        val bgImage = params.backgroundImage
        val modulePaint: Paint = if (bgImage != null) {
            val scaled = Bitmap.createScaledBitmap(bgImage, totalSize, totalSize, true)
            val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        } else {
            solidPaint(params.foreground)
        }

        val scale = params.dataScale.coerceIn(0.5f, 1.0f)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val x = col * cs; val y = row * cs

                when (type) {
                    ModuleType.POS_CENTER -> {
                        // Position patterns: solid color for reliable scanning
                        val p = solidPaint(params.positionColor ?: params.foreground)
                        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = cs * 0.9f
                            color = params.positionColor ?: params.foreground
                        }
                        val cx2 = x + cs * 0.5f; val cy2 = y + cs * 0.5f
                        canvas.drawCircle(cx2, cy2, cs * 3f, ring)
                        canvas.drawCircle(cx2, cy2, cs * 1.5f, p)
                    }
                    ModuleType.POS_OTHER -> {}
                    else -> {
                        val half = cs * scale / 2f
                        val cx2 = x + cs / 2f; val cy2 = y + cs / 2f
                        canvas.drawRoundRect(
                            RectF(cx2 - half, cy2 - half, cx2 + half, cy2 + half),
                            half * 0.3f, half * 0.3f, modulePaint
                        )
                    }
                }
            }
        }

        drawLogo(canvas, params, totalSize)
    }
}
