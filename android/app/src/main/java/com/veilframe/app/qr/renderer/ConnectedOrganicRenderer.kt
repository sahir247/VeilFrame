package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams
import kotlin.random.Random

/**
 * Visual Grammar: ORGANIC (Connected Graph Engine)
 *
 * Constructs a 4-way neighbor graph (north, south, east, west) across data modules,
 * creating cohesive organic paths, smooth corner junctions, and droplets anchored
 * to module centers while strictly preserving QR decodability and finder geometry.
 */
class ConnectedOrganicRenderer : QrRenderer {

    companion object {
        /**
         * Converts [CompositePrimitiveStyle.lineThickness] into a stroke fraction relative to module size
         * using a continuous, non-decreasing mapping with an upper saturation at 1.0.
         * Clamps lower bound to 0.05f to maintain a continuous, non-decreasing contract without zero-point discontinuity.
         * Canonical default lineThickness (0.25f) yields exactly 0.75f.
         */
        fun calculateStrokeFraction(lineThickness: Float): Float {
            val thickness = lineThickness.coerceAtLeast(0.05f)
            return (thickness * 3.0f).coerceAtMost(1.0f)
        }
    }

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
        val cs = geometry.moduleSize

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

        val fillPaint = context.obtainFill(fgColor)
        val strokeFraction = calculateStrokeFraction(design.compositeStyle.lineThickness)
        val strokePaint = context.obtainStroke(fgColor, cs * strokeFraction, Paint.Cap.ROUND)
        val rng = Random(design.effects.seed)

        // 2. Build neighbor occupancy matrix for stylable data modules
        val isOccupied = Array(n) { BooleanArray(n) }
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix.isDark(c, r) && !matrix.functionMask.isFinder(c, r) && !matrix.functionMask.isSeparator(c, r)) {
                    isOccupied[c][r] = true
                }
            }
        }

        // 3. Draw connected organic strokes and nodes
        val visited = Array(n) { BooleanArray(n) }

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!isOccupied[col][row]) continue

                val type = matrix.functionMask[col, row]
                val (cx, cy) = geometry.moduleCenter(col, row)

                // Preserve high contrast for timing and alignment patterns
                if (type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER
                ) {
                    canvas.drawCircle(cx, cy, cs * 0.42f, fillPaint)
                    continue
                }

                // Query 4-way neighbors
                val north = row > 0 && isOccupied[col][row - 1]
                val south = row < n - 1 && isOccupied[col][row + 1]
                val west = col > 0 && isOccupied[col - 1][row]
                val east = col < n - 1 && isOccupied[col + 1][row]

                val degree = (if (north) 1 else 0) + (if (south) 1 else 0) + (if (west) 1 else 0) + (if (east) 1 else 0)

                when (degree) {
                    0 -> {
                        // Isolated node: droplet
                        val r = (cs * 0.40f) * rng.nextDouble(0.85, 1.05).toFloat()
                        canvas.drawCircle(cx, cy, r, fillPaint)
                    }
                    1 -> {
                        // Degree 1 endpoint: draw terminal node and stroke toward neighbor
                        canvas.drawCircle(cx, cy, cs * 0.40f, fillPaint)
                        val (nx, ny) = when {
                            north -> geometry.moduleCenter(col, row - 1)
                            south -> geometry.moduleCenter(col, row + 1)
                            east -> geometry.moduleCenter(col + 1, row)
                            else -> geometry.moduleCenter(col - 1, row)
                        }
                        canvas.drawLine(cx, cy, (cx + nx) / 2f, (cy + ny) / 2f, strokePaint)
                    }
                    2 -> {
                        canvas.drawCircle(cx, cy, cs * 0.38f, fillPaint)
                        // Either linear corridor or curved corner
                        if (north && south) {
                            val (_, ny) = geometry.moduleCenter(col, row - 1)
                            val (_, sy) = geometry.moduleCenter(col, row + 1)
                            canvas.drawLine(cx, (cy + ny) / 2f, cx, (cy + sy) / 2f, strokePaint)
                        } else if (east && west) {
                            val (ex, _) = geometry.moduleCenter(col + 1, row)
                            val (wx, _) = geometry.moduleCenter(col - 1, row)
                            canvas.drawLine((cx + wx) / 2f, cy, (cx + ex) / 2f, cy, strokePaint)
                        } else {
                            // Organic corner junction
                            val path = context.tempPath1
                            path.reset()
                            val (p1x, p1y) = if (north) geometry.moduleCenter(col, row - 1) else geometry.moduleCenter(col, row + 1)
                            val (p2x, p2y) = if (east) geometry.moduleCenter(col + 1, row) else geometry.moduleCenter(col - 1, row)
                            path.moveTo((cx + p1x) / 2f, (cy + p1y) / 2f)
                            path.quadTo(cx, cy, (cx + p2x) / 2f, (cy + p2y) / 2f)
                            canvas.drawPath(path, strokePaint)
                        }
                    }
                    else -> {
                        // Hub node (T-junction or Cross)
                        canvas.drawCircle(cx, cy, cs * 0.44f, fillPaint)
                        if (north) {
                            val (_, ny) = geometry.moduleCenter(col, row - 1)
                            canvas.drawLine(cx, cy, cx, (cy + ny) / 2f, strokePaint)
                        }
                        if (south) {
                            val (_, sy) = geometry.moduleCenter(col, row + 1)
                            canvas.drawLine(cx, cy, cx, (cy + sy) / 2f, strokePaint)
                        }
                        if (east) {
                            val (ex, _) = geometry.moduleCenter(col + 1, row)
                            canvas.drawLine(cx, cy, (cx + ex) / 2f, cy, strokePaint)
                        }
                        if (west) {
                            val (wx, _) = geometry.moduleCenter(col - 1, row)
                            canvas.drawLine(cx, cy, (cx + wx) / 2f, cy, strokePaint)
                        }
                    }
                }
            }
        }

        // 4. Draw logo
        drawLogo(canvas, design, geometry, context)
    }

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): com.veilframe.app.qr.geometry.QrGeometryIr {
        val n = matrix.size
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        val nodes = mutableListOf<com.veilframe.app.qr.geometry.QrGeometryNode>()
        if (geometry.outputWidthFloat > 0f && geometry.outputHeightFloat > 0f) {
            nodes.add(
                com.veilframe.app.qr.geometry.RectNode(
                    x = 0f,
                    y = 0f,
                    width = geometry.outputWidthFloat,
                    height = geometry.outputHeightFloat,
                    fill = bgColor
                )
            )
        }

        // 1. Draw finders via canonical position pattern geometry
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(n - 4, 3),
            Pair(3, n - 4)
        )
        for ((fx, fy) in finderCenters) {
            nodes.addAll(
                VeilPositionPatternGeometry.toIrNodes(
                    x = fx,
                    y = fy,
                    moduleSize = cs,
                    offsetX = ox,
                    offsetY = oy,
                    style = design.eyeStyle.style,
                    size = design.positionSize,
                    color = design.eyeStyle.outerColor ?: fgColor,
                    bgColor = bgColor
                )
            )
        }

        val strokeFraction = calculateStrokeFraction(design.compositeStyle.lineThickness)
        val strokeW = cs * strokeFraction
        val rng = Random(design.effects.seed)

        // 2. Build occupancy
        val isOccupied = Array(n) { BooleanArray(n) }
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix.isDark(c, r) && !matrix.functionMask.isFinder(c, r) && !matrix.functionMask.isSeparator(c, r)) {
                    isOccupied[c][r] = true
                }
            }
        }

        // 3. Draw connected organic strokes and nodes
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!isOccupied[col][row]) continue

                val type = matrix.functionMask[col, row]
                val (cx, cy) = geometry.moduleCenter(col, row)

                if (type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER
                ) {
                    nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, cs * 0.42f, fill = fgColor))
                    continue
                }

                val north = row > 0 && isOccupied[col][row - 1]
                val south = row < n - 1 && isOccupied[col][row + 1]
                val west = col > 0 && isOccupied[col - 1][row]
                val east = col < n - 1 && isOccupied[col + 1][row]

                val degree = (if (north) 1 else 0) + (if (south) 1 else 0) + (if (west) 1 else 0) + (if (east) 1 else 0)

                when (degree) {
                    0 -> {
                        val r = (cs * 0.40f) * rng.nextDouble(0.85, 1.05).toFloat()
                        nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, r, fill = fgColor))
                    }
                    1 -> {
                        nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, cs * 0.40f, fill = fgColor))
                        val (nx, ny) = when {
                            north -> geometry.moduleCenter(col, row - 1)
                            south -> geometry.moduleCenter(col, row + 1)
                            east -> geometry.moduleCenter(col + 1, row)
                            else -> geometry.moduleCenter(col - 1, row)
                        }
                        nodes.add(com.veilframe.app.qr.geometry.LineNode(cx, cy, (cx + nx) / 2f, (cy + ny) / 2f, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                    }
                    2 -> {
                        nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, cs * 0.38f, fill = fgColor))
                        if (north && south) {
                            val (_, ny) = geometry.moduleCenter(col, row - 1)
                            val (_, sy) = geometry.moduleCenter(col, row + 1)
                            nodes.add(com.veilframe.app.qr.geometry.LineNode(cx, (cy + ny) / 2f, cx, (cy + sy) / 2f, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                        } else if (east && west) {
                            val (ex, _) = geometry.moduleCenter(col + 1, row)
                            val (wx, _) = geometry.moduleCenter(col - 1, row)
                            nodes.add(com.veilframe.app.qr.geometry.LineNode((cx + wx) / 2f, cy, (cx + ex) / 2f, cy, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                        } else {
                            val (p1x, p1y) = if (north) geometry.moduleCenter(col, row - 1) else geometry.moduleCenter(col, row + 1)
                            val (p2x, p2y) = if (east) geometry.moduleCenter(col + 1, row) else geometry.moduleCenter(col - 1, row)
                            val startX = (cx + p1x) / 2f
                            val startY = (cy + p1y) / 2f
                            val endX = (cx + p2x) / 2f
                            val endY = (cy + p2y) / 2f
                            val pathData = String.format(java.util.Locale.US, "M %.3f %.3f Q %.3f %.3f %.3f %.3f", startX, startY, cx, cy, endX, endY)
                            nodes.add(com.veilframe.app.qr.geometry.PathNode(svgPathData = pathData, stroke = fgColor, strokeWidth = strokeW, fill = null))
                        }
                    }
                    else -> {
                        nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, cs * 0.44f, fill = fgColor))
                        if (north) {
                            val (_, ny) = geometry.moduleCenter(col, row - 1)
                            nodes.add(com.veilframe.app.qr.geometry.LineNode(cx, cy, cx, (cy + ny) / 2f, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                        }
                        if (south) {
                            val (_, sy) = geometry.moduleCenter(col, row + 1)
                            nodes.add(com.veilframe.app.qr.geometry.LineNode(cx, cy, cx, (cy + sy) / 2f, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                        }
                        if (east) {
                            val (ex, _) = geometry.moduleCenter(col + 1, row)
                            nodes.add(com.veilframe.app.qr.geometry.LineNode(cx, cy, (cx + ex) / 2f, cy, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                        }
                        if (west) {
                            val (wx, _) = geometry.moduleCenter(col - 1, row)
                            nodes.add(com.veilframe.app.qr.geometry.LineNode(cx, cy, (cx + wx) / 2f, cy, strokeColor = fgColor, strokeWidth = strokeW, isRoundCap = true))
                        }
                    }
                }
            }
        }

        return com.veilframe.app.qr.geometry.QrGeometryIr(
            width = geometry.outputWidthFloat,
            height = geometry.outputHeightFloat,
            viewBox = com.veilframe.app.qr.geometry.QrGeometryIr.defaultViewBox(geometry.outputWidthFloat, geometry.outputHeightFloat),
            rootNodes = nodes
        )
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
