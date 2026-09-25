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

        nodes.addAll(
            LineTopologyBuilder.buildTopology(
                matrix = matrix,
                ox = ox,
                oy = oy,
                cs = cs,
                thicknessFraction = thickness,
                lineColor = lineColor,
                direction = direction,
                addAccentRings = true
            )
        )

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
