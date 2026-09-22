package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 8 — LINE (Visual Grammar: Geometric Lines)
 *
 * Implements EFQRCodeStyleLine:
 * - Horizontally contiguous data modules merge into rounded horizontal lines.
 * - Vertically contiguous data modules merge into rounded vertical lines.
 * - Isolated modules render as smooth circles.
 * - Finders rendered via [FinderRenderer].
 */
class LineRenderer : QrRenderer {

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

        // 1. Draw finders
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )

        val strokePaint = context.obtainStroke(fgColor, cs * 0.80f, Paint.Cap.ROUND)
        val fillPaint = context.obtainFill(fgColor)

        val used = Array(n) { BooleanArray(n) }

        // Reserve protected function modules
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.functionMask.isProtected(col, row)) {
                    used[col][row] = true
                    // Timing strips and alignment
                    if (matrix.isDark(col, row) && !matrix.functionMask.isFinder(col, row) && !matrix.functionMask.isSeparator(col, row)) {
                        val (cx, cy) = geometry.moduleCenter(col, row)
                        canvas.drawCircle(cx, cy, cs * 0.40f, fillPaint)
                    }
                }
            }
        }

        // 2. Horizontal runs
        for (row in 0 until n) {
            var col = 0
            while (col < n) {
                if (used[col][row] || !matrix.isDark(col, row)) {
                    col++
                    continue
                }

                var end = col + 1
                while (end < n && !used[end][row] && matrix.isDark(end, row)) {
                    end++
                }

                val runLength = end - col
                if (runLength >= 2) {
                    val (x1, y) = geometry.moduleCenter(col, row)
                    val (x2, _) = geometry.moduleCenter(end - 1, row)
                    canvas.drawLine(x1, y, x2, y, strokePaint)
                    for (c in col until end) {
                        used[c][row] = true
                    }
                }
                col = end
            }
        }

        // 3. Vertical runs
        for (col in 0 until n) {
            var row = 0
            while (row < n) {
                if (used[col][row] || !matrix.isDark(col, row)) {
                    row++
                    continue
                }

                var end = row + 1
                while (end < n && !used[col][end] && matrix.isDark(col, end)) {
                    end++
                }

                val runLength = end - row
                if (runLength >= 2) {
                    val (x, y1) = geometry.moduleCenter(col, row)
                    val (_, y2) = geometry.moduleCenter(col, end - 1)
                    canvas.drawLine(x, y1, x, y2, strokePaint)
                    for (r in row until end) {
                        used[col][r] = true
                    }
                } else {
                    // Single isolated dark cell -> circle
                    val (cx, cy) = geometry.moduleCenter(col, row)
                    canvas.drawCircle(cx, cy, cs * 0.40f, fillPaint)
                    used[col][row] = true
                }
                row = end
            }
        }

        // 4. Logo
        drawLogo(canvas, design, geometry, context)
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
