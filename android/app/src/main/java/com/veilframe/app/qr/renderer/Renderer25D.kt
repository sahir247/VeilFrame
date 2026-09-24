package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.sqrt

/**
 * Style 3 — 2.5D ISOMETRIC (VeilFrameStyle25D Parity)
 *
 * Implements VeilFrame Art Engine axonometric isometric projection:
 * Matrix: `matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)`
 * ViewBox: `x = -nCount, y = -nCount/2, width = nCount*2, height = nCount*2`
 *
 * Each dark module is extruded into a 3D isometric block with:
 * - Top Face: Rhombus mapped with [topColor]
 * - Left Face: Skewed parallelogram extending down by [height] with [leftColor]
 * - Right Face: Skewed parallelogram extending down by [height] with [rightColor]
 *
 * Modules are drawn in diagonal wave order (col + row from 0 to 2*(N-1))
 * guaranteeing painter's-algorithm visibility without z-fighting.
 */
class Renderer25D : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val topColor = design.depthStyle.topColor
        val leftColor = design.depthStyle.leftColor
        val rightColor = design.depthStyle.rightColor

        val dataH = design.depthStyle.depth.coerceAtLeast(0.1f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.1f)

        // Isometric constants matching VeilFrame Art Engine:
        // matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)
        // viewBox: [-n, -n/2, 2*n, 2*n]
        val sq3h = (sqrt(3.0) / 2.0).toFloat()

        val vbX = -n.toFloat()
        val vbY = -n.toFloat() / 2.0f
        val vbW = n.toFloat() * 2.0f
        val vbH = n.toFloat() * 2.0f

        val scaleX = geometry.outputWidth.toFloat() / vbW
        val scaleY = geometry.outputHeight.toFloat() / vbH
        val scale = minOf(scaleX, scaleY)
        val transX = (geometry.outputWidth.toFloat() - vbW * scale) / 2f
        val transY = (geometry.outputHeight.toFloat() - vbH * scale) / 2f

        fun screenX(u: Float, v: Float): Float {
            val isoX = sq3h * (u - v)
            return (isoX - vbX) * scale + transX
        }

        fun screenY(u: Float, v: Float, z: Float): Float {
            val isoY = 0.5f * (u + v) + z
            return (isoY - vbY) * scale + transY
        }

        val topPaint = context.obtainFill(topColor)
        val leftPaint = context.obtainFill(leftColor)
        val rightPaint = context.obtainFill(rightColor)

        val polyPath = Path()

        // Iterate in diagonal wave order (col + row from 0 to 2*(n-1))
        // Back-to-front painter's order ensures foreground blocks properly occlude background blocks
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

                // Top Face Vertices: (c, r, 0), (c+1, r, 0), (c+1, r+1, 0), (c, r+1, 0)
                val p0x = screenX(c, r)
                val p0y = screenY(c, r, 0f)
                val p1x = screenX(c + 1f, r)
                val p1y = screenY(c + 1f, r, 0f)
                val p2x = screenX(c + 1f, r + 1f)
                val p2y = screenY(c + 1f, r + 1f, 0f)
                val p3x = screenX(c, r + 1f)
                val p3y = screenY(c, r + 1f, 0f)

                // 1. Draw Top Face
                polyPath.reset()
                polyPath.moveTo(p0x, p0y)
                polyPath.lineTo(p1x, p1y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(p3x, p3y)
                polyPath.close()
                canvas.drawPath(polyPath, topPaint)

                // Left Face Vertices: (c+1, r, 0), (c+1, r+1, 0), (c+1, r+1, h), (c+1, r, h)
                val l2x = screenX(c + 1f, r + 1f)
                val l2y = screenY(c + 1f, r + 1f, h)
                val l3x = screenX(c + 1f, r)
                val l3y = screenY(c + 1f, r, h)

                // 2. Draw Left Face
                polyPath.reset()
                polyPath.moveTo(p1x, p1y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(l2x, l2y)
                polyPath.lineTo(l3x, l3y)
                polyPath.close()
                canvas.drawPath(polyPath, leftPaint)

                // Right Face Vertices: (c, r+1, 0), (c+1, r+1, 0), (c+1, r+1, h), (c, r+1, h)
                val r3x = screenX(c, r + 1f)
                val r3y = screenY(c, r + 1f, h)

                // 3. Draw Right Face
                polyPath.reset()
                polyPath.moveTo(p3x, p3y)
                polyPath.lineTo(p2x, p2y)
                polyPath.lineTo(l2x, l2y)
                polyPath.lineTo(r3x, r3y)
                polyPath.close()
                canvas.drawPath(polyPath, rightPaint)
            }
        }

        // Draw Center Logo if present
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
