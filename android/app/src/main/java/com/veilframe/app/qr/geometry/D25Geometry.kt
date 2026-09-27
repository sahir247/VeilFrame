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
        val vbY: Float,
        val vbW: Float = 0f,
        val vbH: Float = 0f
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

    fun computeProjection(
        n: Int,
        outputWidth: Float,
        outputHeight: Float,
        quietZoneLeft: Int = 0,
        quietZoneTop: Int = 0,
        quietZoneRight: Int = 0,
        quietZoneBottom: Int = 0
    ): Projection {
        // Canonical EFQRCode formula (EFQRCodeStyle25D.swift):
        // When quiet zone is expressed as integer module counts, the fractional insets are (qz / n).
        // Substituting left = qzLeft / n into:
        //   vbX = -n * (left + 1) = -(n + qzLeft)
        //   vbY = -n * (top + 0.5) = -(n/2 + qzTop)
        //   vbW = n * (left + 2 + right) = 2n + qzLeft + qzRight
        //   vbH = n * (top + 2 + bottom) = 2n + qzTop + qzBottom
        val vbX = -(n + quietZoneLeft).toFloat()
        val vbY = -(n / 2f + quietZoneTop)
        val vbW = (2 * n + quietZoneLeft + quietZoneRight).toFloat()
        val vbH = (2 * n + quietZoneTop + quietZoneBottom).toFloat()

        val scaleX = outputWidth / vbW
        val scaleY = outputHeight / vbH
        val scale = minOf(scaleX, scaleY)
        val transX = (outputWidth - vbW * scale) / 2f
        val transY = (outputHeight - vbH * scale) / 2f

        return Projection(scale, transX, transY, vbX, vbY, vbW, vbH)
    }

    /**
     * Computes isometric projection using EFQRCode's canonical fractional quiet zone insets:
     *   vbX = -n * (left + 1)
     *   vbY = -n * (top + 0.5)
     *   vbW = n * (left + 2 + right)
     *   vbH = n * (top + 2 + bottom)
     */
    fun computeProjectionWithInsets(
        n: Int,
        outputWidth: Float,
        outputHeight: Float,
        left: Float = 0f,
        top: Float = 0f,
        right: Float = 0f,
        bottom: Float = 0f
    ): Projection {
        val vbX = -n.toFloat() * (left + 1f)
        val vbY = -n.toFloat() * (top + 0.5f)
        val vbW = n.toFloat() * (left + 2f + right)
        val vbH = n.toFloat() * (top + 2f + bottom)

        val scaleX = outputWidth / vbW
        val scaleY = outputHeight / vbH
        val scale = minOf(scaleX, scaleY)
        val transX = (outputWidth - vbW * scale) / 2f
        val transY = (outputHeight - vbH * scale) / 2f

        return Projection(scale, transX, transY, vbX, vbY, vbW, vbH)
    }

    fun computeProjection(
        n: Int,
        outputWidth: Float,
        outputHeight: Float,
        quietZoneModules: Int = 0
    ): Projection {
        return computeProjection(n, outputWidth, outputHeight, quietZoneModules, quietZoneModules, quietZoneModules, quietZoneModules)
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

        val dataH = design.depthStyle.depth.coerceAtLeast(0.0f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.0f)
        val dataScale = design.moduleStyle.scale.coerceIn(0.1f, 1.0f)

        val proj = computeProjection(
            n = n,
            outputWidth = geometry.outputWidth.toFloat(),
            outputHeight = geometry.outputHeight.toFloat(),
            quietZoneLeft = geometry.quietZoneLeft,
            quietZoneTop = geometry.quietZoneTop,
            quietZoneRight = geometry.quietZoneRight,
            quietZoneBottom = geometry.quietZoneBottom
        )

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
                val size = if (isPosition) 1.0f else dataScale
                val offset = (1.0f - size) / 2.0f
                val c0 = col + offset
                val r0 = row + offset
                val c1 = c0 + size
                val r1 = r0 + size

                val p0x = proj.screenX(c0, r0)
                val p0y = proj.screenY(c0, r0, 0f)
                val p1x = proj.screenX(c1, r0)
                val p1y = proj.screenY(c1, r0, 0f)
                val p2x = proj.screenX(c1, r1)
                val p2y = proj.screenY(c1, r1, 0f)
                val p3x = proj.screenX(c0, r1)
                val p3y = proj.screenY(c0, r1, 0f)

                // 1. Top Face
                polyPath.reset()
                polyPath.moveTo(p0x, p0y)
                polyPath.lineTo(p1x, p1y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(p3x, p3y)
                polyPath.close()
                canvas.drawPath(polyPath, topPaint)

                if (h > 0.0001f) {
                    // 2. Left Face (analytical equivalent of matrix * translate(c0 + size, r0) * skewY(45))
                    val l2x = proj.screenX(c1, r1)
                    val l2y = proj.screenY(c1, r1, h)
                    val l3x = proj.screenX(c1, r0)
                    val l3y = proj.screenY(c1, r0, h)

                    polyPath.reset()
                    polyPath.moveTo(p1x, p1y)
                    polyPath.lineTo(p2x, p2y)
                    polyPath.lineTo(l2x, l2y)
                    polyPath.lineTo(l3x, l3y)
                    polyPath.close()
                    canvas.drawPath(polyPath, leftPaint)

                    // 3. Right Face (analytical equivalent of matrix * translate(c0, r0 + size) * skewX(45))
                    val r3x = proj.screenX(c0, r1)
                    val r3y = proj.screenY(c0, r1, h)

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

        val dataH = design.depthStyle.depth.coerceAtLeast(0.0f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.0f)
        val dataScale = design.moduleStyle.scale.coerceIn(0.1f, 1.0f)

        val proj = computeProjection(
            n = n,
            outputWidth = geometry.outputWidth.toFloat(),
            outputHeight = geometry.outputHeight.toFloat(),
            quietZoneLeft = geometry.quietZoneLeft,
            quietZoneTop = geometry.quietZoneTop,
            quietZoneRight = geometry.quietZoneRight,
            quietZoneBottom = geometry.quietZoneBottom
        )
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
                val size = if (isPosition) 1.0f else dataScale
                val offset = (1.0f - size) / 2.0f
                val c0 = col + offset
                val r0 = row + offset
                val c1 = c0 + size
                val r1 = r0 + size

                val p0x = proj.screenX(c0, r0)
                val p0y = proj.screenY(c0, r0, 0f)
                val p1x = proj.screenX(c1, r0)
                val p1y = proj.screenY(c1, r0, 0f)
                val p2x = proj.screenX(c1, r1)
                val p2y = proj.screenY(c1, r1, 0f)
                val p3x = proj.screenX(c0, r1)
                val p3y = proj.screenY(c0, r1, 0f)

                val topPts = listOf(Pair(p0x, p0y), Pair(p1x, p1y), Pair(p2x, p2y), Pair(p3x, p3y))
                nodes.add(
                    PolygonNode(
                        points = "$p0x,$p0y $p1x,$p1y $p2x,$p2y $p3x,$p3y",
                        pointsList = topPts,
                        fill = topColor
                    )
                )

                if (h > 0.0001f) {
                    val l2x = proj.screenX(c1, r1)
                    val l2y = proj.screenY(c1, r1, h)
                    val l3x = proj.screenX(c1, r0)
                    val l3y = proj.screenY(c1, r0, h)
                    val leftPts = listOf(Pair(p1x, p1y), Pair(p2x, p2y), Pair(l2x, l2y), Pair(l3x, l3y))
                    nodes.add(
                        PolygonNode(
                            points = "$p1x,$p1y $p2x,$p2y $l2x,$l2y $l3x,$l3y",
                            pointsList = leftPts,
                            fill = leftColor
                        )
                    )

                    val r3x = proj.screenX(c0, r1)
                    val r3y = proj.screenY(c0, r1, h)
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
        }

        return QrGeometryIr(
            width = geometry.outputWidth.toFloat(),
            height = geometry.outputHeight.toFloat(),
            rootNodes = nodes
        )
    }
}
