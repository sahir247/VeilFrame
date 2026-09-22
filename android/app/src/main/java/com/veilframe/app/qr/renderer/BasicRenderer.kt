package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrVisualGeometry
import com.veilframe.app.qr.QrStyleParams
import kotlin.random.Random

/**
 * Style 1 — BASIC (Visual Grammar: Geometric)
 *
 * Implements clean geometric module rendering:
 * - Square, Rounded, Circle, Dot, Squircle, Diamond, Hex
 * - High-contrast canonical finder patterns via [FinderRenderer]
 * - Allocation-free execution via [RenderContext]
 */
class BasicRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val shape = design.moduleStyle.shape

        // 1. Draw finders first with protected canonical geometry
        val eyeOuter = design.eyeStyle.outerColor ?: fgColor
        val eyeInner = design.eyeStyle.innerColor ?: fgColor
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = eyeOuter,
            innerColor = eyeInner,
            backgroundColor = bgColor,
            context = context
        )

        val fgPaint = context.obtainFill(fgColor)
        val rng = Random(design.effects.seed)

        // 2. Draw remaining modules (Timing, Alignment, Data)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue // Already handled by FinderRenderer
                }

                val rect = geometry.moduleRect(col, row, scale)
                val type = matrix.functionMask[col, row]

                when {
                    type == FunctionPatternType.TIMING -> {
                        // Timing pattern: preserve crisp contrast
                        drawModuleShape(canvas, rect, ModuleShape.ROUNDED, 0.2f, fgPaint, context)
                    }
                    type == FunctionPatternType.ALIGNMENT_CENTER || type == FunctionPatternType.ALIGNMENT_OTHER -> {
                        // Alignment pattern
                        drawModuleShape(canvas, rect, ModuleShape.ROUNDED, 0.25f, fgPaint, context)
                    }
                    else -> {
                        // Regular Data module
                        drawModuleShape(canvas, rect, shape, design.moduleStyle.cornerRadiusFraction, fgPaint, context, rng)
                    }
                }
            }
        }

        // 3. Composite center logo if configured
        drawLogo(canvas, design, geometry, context)
    }

    private fun drawModuleShape(
        canvas: Canvas,
        rect: RectF,
        shape: ModuleShape,
        cornerFraction: Float,
        paint: Paint,
        context: RenderContext,
        rng: Random? = null
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width()

        when (shape) {
            ModuleShape.SQUARE -> {
                canvas.drawRect(rect, paint)
            }
            ModuleShape.ROUNDED -> {
                val rx = w * cornerFraction
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
            ModuleShape.CIRCLE -> {
                canvas.drawCircle(cx, cy, w / 2f, paint)
            }
            ModuleShape.DOT -> {
                val r = (w / 2f) * 0.75f
                canvas.drawCircle(cx, cy, r, paint)
            }
            ModuleShape.SQUIRCLE -> {
                val path = QrVisualGeometry.createSquirclePath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.DIAMOND -> {
                val path = QrVisualGeometry.createDiamondPath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.HEX -> {
                val path = QrVisualGeometry.createHexagonPath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.ORGANIC -> {
                // Deterministic variable radius circle
                val factor = rng?.let { it.nextDouble(0.6, 1.0).toFloat() } ?: 0.85f
                canvas.drawCircle(cx, cy, (w / 2f) * factor, paint)
            }
            else -> {
                canvas.drawRect(rect, paint)
            }
        }
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0 // Legacy caller specified exact matrix canvas
        )
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
