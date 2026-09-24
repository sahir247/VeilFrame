package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 11 — STYLE_FUNCTION (Style-level function override)
 *
 * A "meta-style" that applies a gradient shader over the entire QR canvas,
 * then draws modules as rounded rectangles masked by the gradient — giving
 * the appearance that the color of each module is determined by its position
 * in the canvas (style-level function, not per-module).
 *
 * Uses design-level gradient configuration. If no gradient is configured,
 * falls back to a diagonal gradient from foreground to its complementary hue.
 *
 * Mirrors VeilFrame Style Engine's style-level function concept where the
 * module color is defined by a function of (x, y) rather than a flat color.
 */
class StyleFunctionRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val cs = geometry.moduleSize
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)

        // 1. Draw protected finders first
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )

        // 2. Build diagonal gradient paint for data modules (single allocation)
        val dataBounds = geometry.dataRegionBounds()
        val gradStart = design.palette.gradientStart ?: fgColor
        val gradEnd = design.palette.gradientEnd ?: complementaryColor(fgColor)
        val gradientPaint = context.tempPaint
        gradientPaint.reset()
        gradientPaint.isAntiAlias = true
        gradientPaint.shader = LinearGradient(
            dataBounds.left, dataBounds.top,
            dataBounds.right, dataBounds.bottom,
            gradStart, gradEnd,
            Shader.TileMode.CLAMP
        )

        val solidPaint = context.fillPaint

        // 3. Draw remaining modules (Timing, Alignment, Data)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue // Already handled by FinderRenderer
                }

                val type = matrix.functionMask[col, row]
                val rect = geometry.moduleRect(col, row, scale)

                when {
                    type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER -> {
                        // Timing & Alignment: solid color for contrast
                        solidPaint.reset()
                        solidPaint.isAntiAlias = true
                        solidPaint.style = Paint.Style.FILL
                        solidPaint.color = fgColor
                        val rx = rect.width() * 0.25f
                        canvas.drawRoundRect(rect, rx, rx, solidPaint)
                    }
                    else -> {
                        val half = cs * scale / 2f
                        canvas.drawRoundRect(
                            rect,
                            half * 0.45f, half * 0.45f,
                            gradientPaint
                        )
                    }
                }
            }
        }

        // 4. Draw center logo
        drawLogo(canvas, design, geometry, context)
    }

    /** Produces a rough complementary color by rotating hue by 180°. */
    private fun complementaryColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        return android.graphics.Color.HSVToColor(hsv)
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
