package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import kotlin.math.max

/**
 * Style 4 — DSJ (DJ-cross)
 *
 * Implements EFQRCodeStyleDSJ with exact parity:
 * - 5 position finder styles (.dsj, .rectangle, .round, .roundedRectangle, .planets) via [EfPositionPatternGeometry].
 * - 5-stage data grouping algorithm:
 *   1. 3x3 X-pattern diagonal line crosses (xColor)
 *   2. 2x2 X-pattern diagonal line crosses (xColor)
 *   3. Vertical lines (>2 cells) with end squares (verticalLineColor)
 *   4. Horizontal lines (>1 cells) (horizontalLineColor)
 *   5. Residual single modules (horizontalLineColor)
 */
class DsjRenderer : QrRenderer {

    private data class LineCmd(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private data class RectCmd(val x: Float, val y: Float, val w: Float, val h: Float, val isVertical: Boolean)

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val nCount = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        val posColor = design.eyeStyle.outerColor ?: design.palette.foreground
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize

        // 1. Draw finders via canonical EF position geometry
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(nCount - 4, 3),
            Pair(3, nCount - 4)
        )
        for ((fx, fy) in finderCenters) {
            EfPositionPatternGeometry.drawCanvas(
                canvas = canvas,
                x = fx,
                y = fy,
                moduleSize = cs,
                offsetX = ox,
                offsetY = oy,
                style = posStyle,
                size = posSize,
                color = posColor
            )
        }

        val width2 = max(0f, design.efDsjStyle.lineSize)
        val width1 = max(0f, design.efDsjStyle.xSize)
        val sqrt8 = 2.82842712474619f

        val hColor = design.efDsjStyle.horizontalLineColor
        val vColor = design.efDsjStyle.verticalLineColor
        val xColor = design.efDsjStyle.xColor

        val hPaint = context.obtainFill(hColor)
        val vPaint = context.obtainFill(vColor)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = xColor
            style = Paint.Style.STROKE
            strokeWidth = width1 * cs
        }

        val available = Array(nCount) { BooleanArray(nCount) { true } }
        val ava2 = Array(nCount) { BooleanArray(nCount) { true } }

        val g1Lines = mutableListOf<LineCmd>()
        val g2Rects = mutableListOf<RectCmd>()
        val residualRects = mutableListOf<RectCmd>()

        for (y in 0 until nCount) {
            for (x in 0 until nCount) {
                if (!matrix.isDark(x, y)) continue
                if (EfPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                // Stage 1: 3x3 X
                if (available[x][y] && ava2[x][y] && x < nCount - 2 && y < nCount - 2) {
                    var ctn = true
                    for (i in 0 until 3) {
                        for (j in 0 until 3) {
                            if (!ava2[x + i][y + j]) {
                                ctn = false
                            }
                        }
                    }
                    if (ctn && matrix.isDark(x + 2, y) && matrix.isDark(x + 1, y + 1) &&
                        matrix.isDark(x, y + 2) && matrix.isDark(x + 2, y + 2)
                    ) {
                        val d = width1 / sqrt8
                        g1Lines.add(LineCmd(x + d, y + d, x + 3f - d, y + 3f - d))
                        g1Lines.add(LineCmd(x + 3f - d, y + d, x + d, y + 3f - d))

                        available[x][y] = false
                        available[x + 2][y] = false
                        available[x][y + 2] = false
                        available[x + 2][y + 2] = false
                        available[x + 1][y + 1] = false

                        for (i in 0 until 3) {
                            for (j in 0 until 3) {
                                ava2[x + i][y + j] = false
                            }
                        }
                    }
                }

                // Stage 2: 2x2 X
                if (available[x][y] && ava2[x][y] && x < nCount - 1 && y < nCount - 1) {
                    var ctn = true
                    for (i in 0 until 2) {
                        for (j in 0 until 2) {
                            if (!ava2[x + i][y + j]) {
                                ctn = false
                            }
                        }
                    }
                    if (ctn && matrix.isDark(x + 1, y) && matrix.isDark(x, y + 1) && matrix.isDark(x + 1, y + 1)) {
                        val d = width1 / sqrt8
                        g1Lines.add(LineCmd(x + d, y + d, x + 2f - d, y + 2f - d))
                        g1Lines.add(LineCmd(x + 2f - d, y + d, x + d, y + 2f - d))

                        for (i in 0 until 2) {
                            for (j in 0 until 2) {
                                available[x + i][y + j] = false
                                ava2[x + i][y + j] = false
                            }
                        }
                    }
                }

                // Stage 3: Vertical runs
                if (available[x][y] && ava2[x][y]) {
                    if (y == 0 || !matrix.isDark(x, y - 1) || !ava2[x][y - 1]) {
                        val start = y
                        var end = y
                        var ctn = true
                        while (ctn && end < nCount) {
                            if (matrix.isDark(x, end) && ava2[x][end]) {
                                end++
                            } else {
                                ctn = false
                            }
                        }
                        if (end - start > 2) {
                            for (i in start until end) {
                                ava2[x][i] = false
                                available[x][i] = false
                            }
                            val rx = x + (1f - width2) / 2f
                            val ry = y + (1f - width2) / 2f
                            val rh = (end - start - 1).toFloat() - (1f - width2)
                            g2Rects.add(RectCmd(rx, ry, width2, rh, isVertical = true))
                            val endY = (end - 1).toFloat() + (1f - width2) / 2f
                            g2Rects.add(RectCmd(rx, endY, width2, width2, isVertical = true))
                        }
                    }
                }

                // Stage 4: Horizontal runs
                if (available[x][y] && ava2[x][y]) {
                    if (x == 0 || !matrix.isDark(x - 1, y) || !ava2[x - 1][y]) {
                        val start = x
                        var end = x
                        var ctn = true
                        while (ctn && end < nCount) {
                            if (matrix.isDark(end, y) && ava2[end][y]) {
                                end++
                            } else {
                                ctn = false
                            }
                        }
                        if (end - start > 1) {
                            for (i in start until end) {
                                ava2[i][y] = false
                                available[i][y] = false
                            }
                            val rx = x + (1f - width2) / 2f
                            val ry = y + (1f - width2) / 2f
                            val rw = (end - start).toFloat() - (1f - width2)
                            g2Rects.add(RectCmd(rx, ry, rw, width2, isVertical = false))
                        }
                    }
                }

                // Stage 5: Residual single cell
                if (available[x][y]) {
                    val rx = x + (1f - width2) / 2f
                    val ry = y + (1f - width2) / 2f
                    residualRects.add(RectCmd(rx, ry, width2, width2, isVertical = false))
                }
            }
        }

        // Draw in EF order: residual singles, then g1 (X lines), then g2 (runs)
        for (cmd in residualRects) {
            val left = ox + cmd.x * cs
            val top = oy + cmd.y * cs
            canvas.drawRect(left, top, left + cmd.w * cs, top + cmd.h * cs, hPaint)
        }

        for (cmd in g1Lines) {
            canvas.drawLine(
                ox + cmd.x1 * cs,
                oy + cmd.y1 * cs,
                ox + cmd.x2 * cs,
                oy + cmd.y2 * cs,
                xPaint
            )
        }

        for (cmd in g2Rects) {
            val paint = if (cmd.isVertical) vPaint else hPaint
            val left = ox + cmd.x * cs
            val top = oy + cmd.y * cs
            canvas.drawRect(left, top, left + cmd.w * cs, top + cmd.h * cs, paint)
        }

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
        render(matrix, design, canvas, geometry, RenderContext())
    }
}
