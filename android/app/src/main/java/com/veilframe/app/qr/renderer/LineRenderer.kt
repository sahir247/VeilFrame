package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import kotlin.math.max

/**
 * Style 8 — LINE (Visual Grammar: Geometric Lines)
 *
 * Implements EFQRCodeStyleLine with full directional grammar parity:
 * - 7 line directions:
 *   1. HORIZONTAL
 *   2. VERTICAL
 *   3. CROSS (vertical and horizontal runs <= 4)
 *   4. LOOPBACK (radial quadrant directional partitioning)
 *   5. TOP_LEFT_TO_BOTTOM_RIGHT (diagonal forward)
 *   6. TOP_RIGHT_TO_BOTTOM_LEFT (diagonal backward)
 *   7. X (both diagonals with varied stroke widths + center circles)
 * - Round-capped continuous stroke lines.
 * - Single isolated data modules rendered as smooth circles of radius `thickness / 2`.
 * - 5 position finder styles (.rectangle, .round, .roundedRectangle, .planets, .dsj) via [EfPositionPatternGeometry].
 */
class LineRenderer : QrRenderer {

    companion object {
        fun pseudoRandom(x: Int, y: Int, tag: Int, min: Float, max: Float): Float {
            val hash = (x * 374761393 + y * 668265263 + tag * 982451653) and 0x7FFFFFFF
            val norm = (hash % 10000) / 10000f
            return min + norm * (max - min)
        }
    }

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

        val posColor = design.lineStyle.positionColor ?: design.palette.foreground
        val posStyle = design.lineStyle.positionStyle
        val posSize = design.lineStyle.positionSize

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

        val thickness = max(0.05f, design.lineStyle.thicknessFraction)
        val lineColor = design.lineStyle.color ?: design.palette.foreground

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = thickness * cs
            strokeCap = Paint.Cap.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.FILL
        }

        val available = Array(nCount) { BooleanArray(nCount) { true } }
        val ava2 = Array(nCount) { BooleanArray(nCount) { true } }

        val direction = when (design.lineStyle.direction) {
            LineDirection.DIAGONAL_FORWARD -> LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            LineDirection.DIAGONAL_BACKWARD -> LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT
            LineDirection.LOOP -> LineDirection.LOOPBACK
            else -> design.lineStyle.direction
        }

        for (x in 0 until nCount) {
            for (y in 0 until nCount) {
                if (!matrix.isDark(x, y)) continue
                if (EfPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                when (direction) {
                    LineDirection.HORIZONTAL -> {
                        if (x == 0 || (x > 0 && (!matrix.isDark(x - 1, y) || !ava2[x - 1][y]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount) {
                                if (matrix.isDark(x + end, y) && ava2[x + end][y]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y] = false
                                    available[x + i][y] = false
                                }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + 0.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            canvas.drawCircle(cx, cy, (thickness / 2f) * cs, fillPaint)
                        }
                    }

                    LineDirection.VERTICAL -> {
                        if (y == 0 || (y > 0 && (!matrix.isDark(x, y - 1) || !ava2[x][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount) {
                                if (matrix.isDark(x, y + end) && ava2[x][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x][y + i] = false
                                    available[x][y + i] = false
                                }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            canvas.drawCircle(cx, cy, (thickness / 2f) * cs, fillPaint)
                        }
                    }

                    LineDirection.CROSS -> {
                        if (y == 0 || (y > 0 && (!matrix.isDark(x, y - 1) || !ava2[x][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount && end <= 3) {
                                if (matrix.isDark(x, y + end) && ava2[x][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x][y + i] = false
                                    available[x][y + i] = false
                                }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                            }
                        }
                        if (x == 0 || (x > 0 && (!matrix.isDark(x - 1, y) || !ava2[x - 1][y]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount && end <= 3) {
                                if (matrix.isDark(x + end, y) && ava2[x + end][y]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y] = false
                                    available[x + i][y] = false
                                }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + 0.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            canvas.drawCircle(cx, cy, (thickness / 2f) * cs, fillPaint)
                        }
                    }

                    LineDirection.LOOPBACK -> {
                        if ((x > y) != (x + y < nCount)) {
                            if (y == 0 || (y > 0 && (!matrix.isDark(x, y - 1) || !ava2[x][y - 1]))) {
                                var end = 0
                                var ctn = true
                                while (ctn && y + end < nCount && end <= 3) {
                                    if (matrix.isDark(x, y + end) && ava2[x][y + end]) {
                                        end++
                                    } else {
                                        ctn = false
                                    }
                                }
                                if (end > 1) {
                                    for (i in 0 until end) {
                                        ava2[x][y + i] = false
                                        available[x][y + i] = false
                                    }
                                    val lx1 = ox + (x + 0.5f) * cs
                                    val ly1 = oy + (y + 0.5f) * cs
                                    val lx2 = ox + (x + 0.5f) * cs
                                    val ly2 = oy + (y + end - 0.5f) * cs
                                    canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                                }
                            }
                        } else {
                            if (x == 0 || (x > 0 && (!matrix.isDark(x - 1, y) || !ava2[x - 1][y]))) {
                                var end = 0
                                var ctn = true
                                while (ctn && x + end < nCount && end <= 3) {
                                    if (matrix.isDark(x + end, y) && ava2[x + end][y]) {
                                        end++
                                    } else {
                                        ctn = false
                                    }
                                }
                                if (end > 1) {
                                    for (i in 0 until end) {
                                        ava2[x + i][y] = false
                                        available[x + i][y] = false
                                    }
                                    val lx1 = ox + (x + 0.5f) * cs
                                    val ly1 = oy + (y + 0.5f) * cs
                                    val lx2 = ox + (x + end - 0.5f) * cs
                                    val ly2 = oy + (y + 0.5f) * cs
                                    canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                                }
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            canvas.drawCircle(cx, cy, (thickness / 2f) * cs, fillPaint)
                        }
                    }

                    LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT -> {
                        if (y == 0 || x == 0 || ((y > 0 && x > 0) && (!matrix.isDark(x - 1, y - 1) || !ava2[x - 1][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount && x + end < nCount) {
                                if (matrix.isDark(x + end, y + end) && ava2[x + end][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y + i] = false
                                    available[x + i][y + i] = false
                                }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            canvas.drawCircle(cx, cy, (thickness / 2f) * cs, fillPaint)
                        }
                    }

                    LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT -> {
                        if (x == 0 || y == nCount - 1 || ((x > 0 && y < nCount - 1) && (!matrix.isDark(x - 1, y + 1) || !ava2[x - 1][y + 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount && y - end >= 0) {
                                if (matrix.isDark(x + end, y - end) && available[x + end][y - end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y - i] = false
                                    available[x + i][y - i] = false
                                }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y - end + 1.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, strokePaint)
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            canvas.drawCircle(cx, cy, (thickness / 2f) * cs, fillPaint)
                        }
                    }

                    LineDirection.X -> {
                        // Diagonal 1: (x+i, y-i)
                        if (x == 0 || y == nCount - 1 || ((x > 0 && y < nCount - 1) && (!matrix.isDark(x - 1, y + 1) || !ava2[x - 1][y + 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount && y - end >= 0) {
                                if (matrix.isDark(x + end, y - end) && ava2[x + end][y - end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y - i] = false
                                }
                                val sw = thickness / 2f * pseudoRandom(x, y, 1, 0.3f, 1.0f) * cs
                                val xStrokePaint = Paint(strokePaint).apply { strokeWidth = sw }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y - end + 1.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, xStrokePaint)
                            }
                        }
                        // Diagonal 2: (x+i, y+i)
                        if (y == 0 || x == 0 || ((y > 0 && x > 0) && (!matrix.isDark(x - 1, y - 1) || !available[x - 1][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount && x + end < nCount) {
                                if (matrix.isDark(x + end, y + end) && available[x + end][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    available[x + i][y + i] = false
                                }
                                val sw = thickness / 2f * pseudoRandom(x, y, 2, 0.3f, 1.0f) * cs
                                val xStrokePaint = Paint(strokePaint).apply { strokeWidth = sw }
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                canvas.drawLine(lx1, ly1, lx2, ly2, xStrokePaint)
                            }
                        }
                        // Center dot
                        val r = 0.5f * pseudoRandom(x, y, 3, 0.33f, 0.9f) * cs
                        val cx = ox + (x + 0.5f) * cs
                        val cy = oy + (y + 0.5f) * cs
                        canvas.drawCircle(cx, cy, r, fillPaint)
                    }
                    else -> {}
                }
            }
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
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
