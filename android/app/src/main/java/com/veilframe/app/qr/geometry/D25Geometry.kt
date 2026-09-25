package com.veilframe.app.qr.geometry

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import kotlin.math.sqrt

/**
 * Dedicated 2.5D Isometric Geometry Engine.
 *
 * Implements axonometric isometric projection matching the 3-face geometry:
 * Matrix: `matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)`
 * ViewBox: `[-N, -N/2, 2*N, 2*N]`
 *
 * Emits exactly three faces per dark module:
 * 1. Top Face: Rhombus mapped with [topColor]
 * 2. Left Face: Skewed parallelogram extending down by [height] with [leftColor]
 * 3. Right Face: Skewed parallelogram extending down by [height] with [rightColor]
 *
 * Sorted in diagonal wave order (col + row from 0 to 2*(N-1)) ensuring back-to-front
 * occlusion without z-fighting.
 */
object D25Geometry {

    val SQ3H: Float = (sqrt(3.0) / 2.0).toFloat()
    const val MATRIX_STRING: String = "matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)"

    data class Projection(
        val scale: Float,
        val transX: Float,
        val transY: Float,
        val vbX: Float,
        val vbY: Float
    ) {
        fun screenX(u: Float, v: Float): Float {
            val isoX = SQ3H * (u - v)
            return (isoX - vbX) * scale + transX
        }

        fun screenY(u: Float, v: Float, z: Float): Float {
            val isoY = 0.5f * (u + v) + z
            return (isoY - vbY) * scale + transY
        }
    }

    fun computeProjection(n: Int, outputWidth: Float, outputHeight: Float): Projection {
        val vbX = -n.toFloat()
        val vbY = -n.toFloat() / 2.0f
        val vbW = n.toFloat() * 2.0f
        val vbH = n.toFloat() * 2.0f

        val scaleX = outputWidth / vbW
        val scaleY = outputHeight / vbH
        val scale = minOf(scaleX, scaleY)
        val transX = (outputWidth - vbW * scale) / 2f
        val transY = (outputHeight - vbH * scale) / 2f

        return Projection(scale, transX, transY, vbX, vbY)
    }

    /**
     * Renders the 3-face isometric modules directly to an Android [Canvas].
     */
    fun renderCanvas(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry
    ) {
        val n = matrix.size
        val topColor = design.depthStyle.topColor
        val leftColor = design.depthStyle.leftColor
        val rightColor = design.depthStyle.rightColor

        val dataH = design.depthStyle.depth.coerceAtLeast(0.1f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.1f)

        val proj = computeProjection(n, geometry.outputWidth.toFloat(), geometry.outputHeight.toFloat())

        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = topColor
        }
        val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = leftColor
        }
        val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = rightColor
        }

        val polyPath = Path()

        for (diagonal in 0 until (2 * n - 1)) {
            val minCol = maxOf(0, diagonal - (n - 1))
            val maxCol = minOf(n - 1, diagonal)

            for (col in minCol..maxCol) {
                val row = diagonal - col
                if (!matrix.isDark(col, row)) continue

                val isPosition = matrix.roleAt(col, row) == QrModuleRole.FINDER_INNER ||
                    matrix.roleAt(col, row) == QrModuleRole.FINDER_OUTER ||
                    matrix.functionMask.isFinder(col, row)

                val h = if (isPosition) posH else dataH
                val c = col.toFloat()
                val r = row.toFloat()

                val p0x = proj.screenX(c, r)
                val p0y = proj.screenY(c, r, 0f)
                val p1x = proj.screenX(c + 1f, r)
                val p1y = proj.screenY(c + 1f, r, 0f)
                val p2x = proj.screenX(c + 1f, r + 1f)
                val p2y = proj.screenY(c + 1f, r + 1f, 0f)
                val p3x = proj.screenX(c, r + 1f)
                val p3y = proj.screenY(c, r + 1f, 0f)

                // 1. Top Face
                polyPath.reset()
                polyPath.moveTo(p0x, p0y)
                polyPath.lineTo(p1x, p1y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(p3x, p3y)
                polyPath.close()
                canvas.drawPath(polyPath, topPaint)

                // 2. Left Face
                val l2x = proj.screenX(c + 1f, r + 1f)
                val l2y = proj.screenY(c + 1f, r + 1f, h)
                val l3x = proj.screenX(c + 1f, r)
                val l3y = proj.screenY(c + 1f, r, h)

                polyPath.reset()
                polyPath.moveTo(p1x, p1y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(l2x, l2y)
                polyPath.lineTo(l3x, l3y)
                polyPath.close()
                canvas.drawPath(polyPath, leftPaint)

                // 3. Right Face
                val r3x = proj.screenX(c, r + 1f)
                val r3y = proj.screenY(c, r + 1f, h)

                polyPath.reset()
                polyPath.moveTo(p3x, p3y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(l2x, l2y)
                polyPath.lineTo(r3x, r3y)
                polyPath.close()
                canvas.drawPath(polyPath, rightPaint)
            }
        }
    }

    /**
     * Builds the unified [QrGeometryIr] representation for 2.5D Isometric style.
     */
    fun buildGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val topColor = design.depthStyle.topColor
        val leftColor = design.depthStyle.leftColor
        val rightColor = design.depthStyle.rightColor

        val dataH = design.depthStyle.depth.coerceAtLeast(0.1f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.1f)

        val proj = computeProjection(n, geometry.outputWidth.toFloat(), geometry.outputHeight.toFloat())
        val nodes = mutableListOf<QrGeometryNode>()

        nodes.add(
            RectNode(
                x = 0f,
                y = 0f,
                width = geometry.outputWidth.toFloat(),
                height = geometry.outputHeight.toFloat(),
                fill = design.palette.background
            )
        )

        for (diagonal in 0 until (2 * n - 1)) {
            val minCol = maxOf(0, diagonal - (n - 1))
            val maxCol = minOf(n - 1, diagonal)

            for (col in minCol..maxCol) {
                val row = diagonal - col
                if (!matrix.isDark(col, row)) continue

                val isPosition = matrix.roleAt(col, row) == QrModuleRole.FINDER_INNER ||
                    matrix.roleAt(col, row) == QrModuleRole.FINDER_OUTER ||
                    matrix.functionMask.isFinder(col, row)

                val h = if (isPosition) posH else dataH
                val c = col.toFloat()
                val r = row.toFloat()

                val p0x = proj.screenX(c, r)
                val p0y = proj.screenY(c, r, 0f)
                val p1x = proj.screenX(c + 1f, r)
                val p1y = proj.screenY(c + 1f, r, 0f)
                val p2x = proj.screenX(c + 1f, r + 1f)
                val p2y = proj.screenY(c + 1f, r + 1f, 0f)
                val p3x = proj.screenX(c, r + 1f)
                val p3y = proj.screenY(c, r + 1f, 0f)

                val topPts = listOf(Pair(p0x, p0y), Pair(p1x, p1y), Pair(p2x, p2y), Pair(p3x, p3y))
                nodes.add(
                    PolygonNode(
                        points = "$p0x,$p0y $p1x,$p1y $p2x,$p2y $p3x,$p3y",
                        pointsList = topPts,
                        fill = topColor
                    )
                )

                val l2x = proj.screenX(c + 1f, r + 1f)
                val l2y = proj.screenY(c + 1f, r + 1f, h)
                val l3x = proj.screenX(c + 1f, r)
                val l3y = proj.screenY(c + 1f, r, h)
                val leftPts = listOf(Pair(p1x, p1y), Pair(p2x, p2y), Pair(l2x, l2y), Pair(l3x, l3y))
                nodes.add(
                    PolygonNode(
                        points = "$p1x,$p1y $p2x,$p2y $l2x,$l2y $l3x,$l3y",
                        pointsList = leftPts,
                        fill = leftColor
                    )
                )

                val r3x = proj.screenX(c, r + 1f)
                val r3y = proj.screenY(c, r + 1f, h)
                val rightPts = listOf(Pair(p3x, p3y), Pair(p2x, p2y), Pair(l2x, l2y), Pair(r3x, r3y))
                nodes.add(
                    PolygonNode(
                        points = "$p3x,$p3y $p2x,$p2y $l2x,$l2y $r3x,$r3y",
                        pointsList = rightPts,
                        fill = rightColor
                    )
                )
            }
        }

        return QrGeometryIr(
            width = geometry.outputWidth.toFloat(),
            height = geometry.outputHeight.toFloat(),
            rootNodes = nodes
        )
    }
}
