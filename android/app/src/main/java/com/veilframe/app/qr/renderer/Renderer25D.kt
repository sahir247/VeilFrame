package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.sqrt

/**
 * Style 3 — 2.5D ISOMETRIC
 *
 * Renders each dark module as a 3-faced isometric cube:
 *   - TOP face:   topColor  (parallelogram via skewed rect)
 *   - LEFT face:  leftColor (vertical right-skewed quad)
 *   - RIGHT face: rightColor (horizontal bottom-skewed quad)
 *
 * The isometric transformation matrix from the Swift source:
 *   matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)
 *
 * Translated to Android: we apply an android.graphics.Matrix to the Canvas
 * in a save/restore block for each module. Position patterns use
 * positionHeight and data modules use dataHeight.
 */
class Renderer25D : QrRenderer {

    // Isometric projection: x-axis goes right-down, y-axis goes left-down
    private val ISO_MATRIX = Matrix().apply {
        val sq3h = sqrt(3.0).toFloat() / 2f
        // [ sqrt3/2,  0.5,  -sqrt3/2, 0.5,  0, 0 ]
        setValues(floatArrayOf(
            sq3h,  sq3h, 0f,
            -sq3h, sq3h, 0f,
            0f,    0f,   1f
        ))
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize

        val topPaint   = solidPaint(params.d25TopColor)
        val leftPaint  = solidPaint(params.d25LeftColor)
        val rightPaint = solidPaint(params.d25RightColor)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val isPos = type == ModuleType.POS_CENTER || type == ModuleType.POS_OTHER
                val h = if (isPos) params.d25PositionHeight else params.d25DataHeight

                val xv = col.toFloat()
                val yv = row.toFloat()

                // TOP face: square at (xv, yv) in iso space
                drawIsoRect(canvas, cs, xv, yv, 1f, 1f, ISO_MATRIX, topPaint)
                // LEFT face: skewY(45) quad — right side of the top face going down h
                drawIsoSkewY(canvas, cs, xv + 1f, yv, h, 1f, ISO_MATRIX, leftPaint)
                // RIGHT face: skewX(45) quad — bottom of the top face going down h
                drawIsoSkewX(canvas, cs, xv, yv + 1f, 1f, h, ISO_MATRIX, rightPaint)
            }
        }

        drawLogo(canvas, params, (n * cs).toInt())
    }

    /**
     * Draws a rectangle at iso coordinates ([ix],[iy]) with size [iw]×[ih],
     * transformed by [isoM] and scaled by [cs].
     */
    private fun drawIsoRect(
        canvas: Canvas, cs: Float,
        ix: Float, iy: Float, iw: Float, ih: Float,
        isoM: Matrix, paint: Paint
    ) {
        val path = Path()
        val pts = floatArrayOf(
            ix * cs,        iy * cs,
            (ix + iw) * cs, iy * cs,
            (ix + iw) * cs, (iy + ih) * cs,
            ix * cs,        (iy + ih) * cs
        )
        isoM.mapPoints(pts)
        path.moveTo(pts[0], pts[1])
        for (i in 1 until 4) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * Draws the LEFT (skewY 45°) side face.
     * In Swift: translate(xv+size, yv) skewY(45) then draw h×size rect
     * → generates a parallelogram going diagonally downward
     */
    private fun drawIsoSkewY(
        canvas: Canvas, cs: Float,
        ix: Float, iy: Float, h: Float, ih: Float,
        isoM: Matrix, paint: Paint
    ) {
        val path = Path()
        // Four corners of the skewY quad in iso space
        val pts = floatArrayOf(
            ix * cs,         iy * cs,
            (ix + h) * cs,   iy * cs,
            (ix + h) * cs,   (iy + ih) * cs,
            ix * cs,         (iy + ih) * cs
        )
        // Apply skewY(45): x' = x + y*tan(45) = x + y
        for (i in 0 until 4) {
            val xi = pts[i * 2]; val yi = pts[i * 2 + 1]
            pts[i * 2] = xi + yi          // skewY
        }
        isoM.mapPoints(pts)
        path.moveTo(pts[0], pts[1])
        for (i in 1 until 4) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * Draws the RIGHT (skewX 45°) bottom face.
     * In Swift: translate(xv, yv+size) skewX(45) then draw size×h rect
     */
    private fun drawIsoSkewX(
        canvas: Canvas, cs: Float,
        ix: Float, iy: Float, iw: Float, h: Float,
        isoM: Matrix, paint: Paint
    ) {
        val path = Path()
        val pts = floatArrayOf(
            ix * cs,         iy * cs,
            (ix + iw) * cs,  iy * cs,
            (ix + iw) * cs,  (iy + h) * cs,
            ix * cs,         (iy + h) * cs
        )
        // Apply skewX(45): y' = y + x*tan(45) = y + x
        for (i in 0 until 4) {
            val xi = pts[i * 2]; val yi = pts[i * 2 + 1]
            pts[i * 2 + 1] = yi + xi      // skewX
        }
        isoM.mapPoints(pts)
        path.moveTo(pts[0], pts[1])
        for (i in 1 until 4) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        path.close()
        canvas.drawPath(path, paint)
    }
}
