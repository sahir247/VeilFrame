package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.model.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Style 10 — FUNCTION (Function-based custom module shapes)
 *
 * Each dark data module is rendered as a star/diamond shape whose
 * exact path is computed via a parametric function — mirroring
 * EFQRCodeStyleFunction.swift's function-drawing approach.
 *
 * The default shape is a 4-point star (rhombus with slightly curved sides),
 * but this renderer also supports diamond and cross variants selectable
 * via [QrStyleParams.dataShape]:
 *
 *   RECTANGLE   → 4-pointed star (default)
 *   ROUND       → soft petal/flower (8-point using sin/cos)
 *   ROUNDED_RECT → diamond (rotated square)
 *
 * Position patterns use classic round circles for legibility.
 */
class FunctionRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val fgPaint = solidPaint(params.foreground)
        val posPaint = solidPaint(params.positionColor ?: params.foreground)
        val scale = params.dataScale.coerceIn(0.4f, 1.0f)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val cx2 = (col + 0.5f) * cs; val cy2 = (row + 0.5f) * cs

                when (type) {
                    ModuleType.POS_CENTER -> {
                        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = cs * 0.9f
                            color = params.positionColor ?: params.foreground
                        }
                        canvas.drawCircle(cx2, cy2, cs * 3f, ring)
                        canvas.drawCircle(cx2, cy2, cs * 1.5f, posPaint)
                    }
                    ModuleType.POS_OTHER -> {}
                    else -> drawFunctionModule(canvas, cx2, cy2, cs, scale, params, fgPaint)
                }
            }
        }

        drawLogo(canvas, params, (n * cs).toInt())
    }

    private fun drawFunctionModule(
        canvas: Canvas, cx: Float, cy: Float,
        cs: Float, scale: Float,
        params: QrStyleParams, paint: Paint
    ) {
        val r = cs * scale * 0.5f
        val path = when (params.dataShape) {
            com.veilframe.app.qr.ModuleShape.ROUND -> flowerPath(cx, cy, r, petals = 8)
            com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE -> diamondPath(cx, cy, r)
            else -> starPath(cx, cy, r, points = 4)
        }
        canvas.drawPath(path, paint)
    }

    /** 4 or N-pointed star. */
    private fun starPath(cx: Float, cy: Float, outerR: Float, points: Int): Path {
        val innerR = outerR * 0.4f
        val path = Path()
        val angleStep = PI.toFloat() / points
        for (i in 0 until points * 2) {
            val angle = i * angleStep - PI.toFloat() / 2
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * cos(angle); val y = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** N-petal flower shape using sin/cos. */
    private fun flowerPath(cx: Float, cy: Float, r: Float, petals: Int): Path {
        val path = Path()
        val steps = 360
        for (i in 0..steps) {
            val t = i.toFloat() / steps * 2 * PI.toFloat()
            val freq = petals.toFloat()
            val pR = r * (0.5f + 0.5f * cos(freq * t))
            val x = cx + pR * cos(t); val y = cy + pR * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** Diamond = rotated square. */
    private fun diamondPath(cx: Float, cy: Float, r: Float): Path {
        val path = Path()
        path.moveTo(cx, cy - r)  // top
        path.lineTo(cx + r, cy)  // right
        path.lineTo(cx, cy + r)  // bottom
        path.lineTo(cx - r, cy)  // left
        path.close()
        return path
    }
}
