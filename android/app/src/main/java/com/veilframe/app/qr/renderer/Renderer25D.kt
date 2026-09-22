package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.sqrt

/**
 * Style 3 — 2.5D ISOMETRIC (Visual Grammar: 3D Projection)
 *
 * Implements isometric bounding-box fitting:
 * 1. Maps matrix coordinates through the isometric projection matrix.
 * 2. Computes the tight axis-aligned bounding box of all projected 3D columns.
 * 3. Scales and translates the entire 3D projection to fit cleanly within
 *    the canvas while preserving the 4-module quiet zone margin on all borders.
 * 4. Eliminates clipping and maintains decoder-friendly finder alignment.
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
        val topColor = design.effects.topColor
        val leftColor = design.effects.leftColor
        val rightColor = design.effects.rightColor

        val dataH = design.effects.dataHeightRatio.coerceIn(0.2f, 1.5f)
        val posH = design.effects.positionHeightRatio.coerceIn(0.3f, 2.0f)

        // Raw isometric projection matrix:
        // [ cos(30°),  cos(30°), 0 ]
        // [ -sin(30°), sin(30°), 0 ]
        // [ 0,         0,        1 ]
        val sq3h = (sqrt(3.0) / 2.0).toFloat()
        val rawMatrix = Matrix().apply {
            setValues(floatArrayOf(
                sq3h,  sq3h, 0f,
                -0.5f, 0.5f, 0f,
                0f,    0f,   1f
            ))
        }

        // 1. Compute projection bounding box for the entire matrix grid
        // Vertices of the base grid plus maximum extrusion height
        val maxH = maxOf(dataH, posH)
        val testPoints = floatArrayOf(
            0f, 0f,
            n.toFloat(), 0f,
            n.toFloat(), n.toFloat(),
            0f, n.toFloat(),
            0f, -maxH,
            n.toFloat(), -maxH,
            n.toFloat(), n.toFloat() - maxH,
            0f, n.toFloat() - maxH
        )
        rawMatrix.mapPoints(testPoints)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (i in 0 until testPoints.size step 2) {
            val px = testPoints[i]
            val py = testPoints[i + 1]
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }

        val projW = maxX - minX
        val projH = maxY - minY

        // Fit within target area (output size minus 2 * quiet zone margin)
        val targetSize = geometry.outputWidth - (2f * geometry.quietZoneModules * geometry.moduleSize)
        val fitScale = minOf(targetSize / projW, targetSize / projH)

        // Translation to center projected QR inside output canvas
        val centerX = geometry.outputWidth / 2f
        val centerY = geometry.outputHeight / 2f
        val projCenterX = (minX + maxX) / 2f * fitScale
        val projCenterY = (minY + maxY) / 2f * fitScale
        val transX = centerX - projCenterX
        val transY = centerY - projCenterY

        val finalTransform = Matrix().apply {
            set(rawMatrix)
            postScale(fitScale, fitScale)
            postTranslate(transX, transY)
        }

        val topPaint = context.obtainFill(topColor)
        val leftPaint = context.tempPaint.apply {
            reset()
            isAntiAlias = true
            style = Paint.Style.FILL
            color = leftColor
        }
        val rightPaint = context.fillPaint.apply {
            reset()
            isAntiAlias = true
            style = Paint.Style.FILL
            color = rightColor
        }

        val pts = FloatArray(8)

        // 2. Draw 2.5D modules in topological order (back to front: row 0..n, col 0..n)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val isPos = matrix.functionMask.isFinder(col, row)
                val h = if (isPos) posH else dataH

                val x = col.toFloat()
                val y = row.toFloat()

                // Top Face (diamond)
                pts[0] = x;     pts[1] = y - h
                pts[2] = x + 1; pts[3] = y - h
                pts[4] = x + 1; pts[5] = y + 1 - h
                pts[6] = x;     pts[7] = y + 1 - h
                finalTransform.mapPoints(pts)
                drawPolygon(canvas, pts, topPaint, context.tempPath1)

                // Left Face
                pts[0] = x;     pts[1] = y - h
                pts[2] = x;     pts[3] = y + 1 - h
                pts[4] = x;     pts[5] = y + 1
                pts[6] = x;     pts[7] = y
                finalTransform.mapPoints(pts)
                drawPolygon(canvas, pts, leftPaint, context.tempPath1)

                // Right Face
                pts[0] = x;     pts[1] = y + 1 - h
                pts[2] = x + 1; pts[3] = y + 1 - h
                pts[4] = x + 1; pts[5] = y + 1
                pts[6] = x;     pts[7] = y + 1
                finalTransform.mapPoints(pts)
                drawPolygon(canvas, pts, rightPaint, context.tempPath1)
            }
        }

        // 3. Composite center logo
        drawLogo(canvas, design, geometry, context)
    }

    private fun drawPolygon(canvas: Canvas, pts: FloatArray, paint: Paint, path: Path) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        path.lineTo(pts[2], pts[3])
        path.lineTo(pts[4], pts[5])
        path.lineTo(pts[6], pts[7])
        path.close()
        canvas.drawPath(path, paint)
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
