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
 * Implements VeilFrameStyleLine with full directional grammar parity:
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
 * - 5 position finder styles (.rectangle, .round, .roundedRectangle, .planets, .dsj) via [VeilPositionPatternGeometry].
 */
class LineRenderer : QrRenderer {

    companion object {
        fun pseudoRandom(x: Int, y: Int, tag: Int, min: Float, max: Float): Float {
            val hash = (x * 374761393 + y * 668265263 + tag * 982451653) and 0x7FFFFFFF
            val norm = (hash % 10000) / 10000f
            return min + norm * (max - min)
        }
    }

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): com.veilframe.app.qr.geometry.QrGeometryIr {
        val nCount = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        val posColor = design.lineStyle.positionColor ?: design.palette.foreground
        val posStyle = design.lineStyle.positionStyle
        val posSize = design.lineStyle.positionSize

        val nodes = mutableListOf<com.veilframe.app.qr.geometry.QrGeometryNode>()
        nodes.add(
            com.veilframe.app.qr.geometry.RectNode(
                x = 0f,
                y = 0f,
                width = geometry.outputWidth.toFloat(),
                height = geometry.outputHeight.toFloat(),
                fill = design.palette.background
            )
        )

        // 1. Draw finders via canonical VeilFrame position geometry
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(nCount - 4, 3),
            Pair(3, nCount - 4)
        )
        for ((fx, fy) in finderCenters) {
            nodes.addAll(
                VeilPositionPatternGeometry.toIrNodes(
                    x = fx,
                    y = fy,
                    moduleSize = cs,
                    offsetX = ox,
                    offsetY = oy,
                    style = posStyle,
                    size = posSize,
                    color = posColor
                )
            )
        }

        val thickness = max(0.05f, design.lineStyle.thicknessFraction)
        val lineColor = design.lineStyle.color ?: design.palette.foreground

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
                if (VeilPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

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
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                    )
                                )
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            nodes.add(
                                com.veilframe.app.qr.geometry.CircleNode(
                                    cx = cx, cy = cy, radius = (thickness / 2f) * cs, fill = lineColor
                                )
                            )
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
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                    )
                                )
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            nodes.add(
                                com.veilframe.app.qr.geometry.CircleNode(
                                    cx = cx, cy = cy, radius = (thickness / 2f) * cs, fill = lineColor
                                )
                            )
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
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                    )
                                )
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
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                    )
                                )
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            nodes.add(
                                com.veilframe.app.qr.geometry.CircleNode(
                                    cx = cx, cy = cy, radius = (thickness / 2f) * cs, fill = lineColor
                                )
                            )
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
                                    nodes.add(
                                        com.veilframe.app.qr.geometry.LineNode(
                                            x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                            strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                        )
                                    )
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
                                    nodes.add(
                                        com.veilframe.app.qr.geometry.LineNode(
                                            x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                            strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                        )
                                    )
                                }
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            nodes.add(
                                com.veilframe.app.qr.geometry.CircleNode(
                                    cx = cx, cy = cy, radius = (thickness / 2f) * cs, fill = lineColor
                                )
                            )
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
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                    )
                                )
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            nodes.add(
                                com.veilframe.app.qr.geometry.CircleNode(
                                    cx = cx, cy = cy, radius = (thickness / 2f) * cs, fill = lineColor
                                )
                            )
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
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = thickness * cs, isRoundCap = true
                                    )
                                )
                            }
                        }
                        if (available[x][y]) {
                            val cx = ox + (x + 0.5f) * cs
                            val cy = oy + (y + 0.5f) * cs
                            nodes.add(
                                com.veilframe.app.qr.geometry.CircleNode(
                                    cx = cx, cy = cy, radius = (thickness / 2f) * cs, fill = lineColor
                                )
                            )
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
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y - end + 1.5f) * cs
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = sw, isRoundCap = true
                                    )
                                )
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
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                nodes.add(
                                    com.veilframe.app.qr.geometry.LineNode(
                                        x1 = lx1, y1 = ly1, x2 = lx2, y2 = ly2,
                                        strokeColor = lineColor, strokeWidth = sw, isRoundCap = true
                                    )
                                )
                            }
                        }
                        // Center dot
                        val r = 0.5f * pseudoRandom(x, y, 3, 0.33f, 0.9f) * cs
                        val cx = ox + (x + 0.5f) * cs
                        val cy = oy + (y + 0.5f) * cs
                        nodes.add(
                            com.veilframe.app.qr.geometry.CircleNode(
                                cx = cx, cy = cy, radius = r, fill = lineColor
                            )
                        )
                    }
                    else -> {}
                }
            }
        }

        return com.veilframe.app.qr.geometry.QrGeometryIr(
            width = geometry.outputWidth.toFloat(),
            height = geometry.outputHeight.toFloat(),
            rootNodes = nodes
        )
    }

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val ir = generateGeometry(matrix, design, geometry)
        com.veilframe.app.qr.geometry.IrCanvasRenderer.render(ir, canvas)
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
