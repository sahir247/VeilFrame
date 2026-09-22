package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 11 — STYLE_FUNCTION (Style-level function override)
 *
 * A "meta-style" that applies a gradient shader over the entire QR canvas,
 * then draws modules as rounded rectangles masked by the gradient — giving
 * the appearance that the color of each module is determined by its position
 * in the canvas (style-level function, not per-module).
 *
 * If [QrStyleParams.gradientStart] and [gradientEnd] are both non-null,
 * a diagonal LinearGradient is applied. Otherwise, it falls back to a
 * diagonal gradient from foreground to its complementary hue.
 *
 * Mirrors EFQRCodeStyle.swift's style-level function concept where the
 * module color is defined by a function of (x, y) rather than a flat color.
 */
class StyleFunctionRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val totalSize = n * cs
        val scale = params.dataScale.coerceIn(0.5f, 1.0f)
        val posPaint = solidPaint(params.positionColor ?: params.foreground)

        // Build diagonal gradient paint for data modules
        val gradStart = params.gradientStart ?: params.foreground
        val gradEnd   = params.gradientEnd   ?: complementaryColor(params.foreground)
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, totalSize, totalSize,
                gradStart, gradEnd,
                Shader.TileMode.CLAMP
            )
        }

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val x = col * cs; val y = row * cs
                val cx2 = x + cs * 0.5f; val cy2 = y + cs * 0.5f

                when (type) {
                    ModuleType.POS_CENTER -> {
                        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = cs * 0.9f
                            color = params.positionColor ?: gradStart
                        }
                        canvas.drawRoundRect(
                            RectF(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs),
                            cs * 1.2f, cs * 1.2f, ring
                        )
                        canvas.drawRoundRect(
                            RectF(x - cs, y - cs, x + 2f * cs, y + 2f * cs),
                            cs * 0.6f, cs * 0.6f, posPaint
                        )
                    }
                    ModuleType.POS_OTHER -> {}
                    else -> {
                        val half = cs * scale / 2f
                        canvas.drawRoundRect(
                            RectF(cx2 - half, cy2 - half, cx2 + half, cy2 + half),
                            half * 0.45f, half * 0.45f, gradientPaint
                        )
                    }
                }
            }
        }

        drawLogo(canvas, params, totalSize.toInt())
    }

    /** Produces a rough complementary color by rotating hue by 180°. */
    private fun complementaryColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        return android.graphics.Color.HSVToColor(hsv)
    }
}
