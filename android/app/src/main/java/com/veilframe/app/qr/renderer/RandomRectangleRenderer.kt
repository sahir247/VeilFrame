package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Style 9 — RANDOM_RECTANGLE
 *
 * Implements EFQRCode's exact EFQRCodeStyleRandomRectangle algorithm:
 * - Color parameter: [design.randomRectColor] (default EF green 0x14AA3C / RGB 20, 170, 60)
 * - Shuffles all matrix coordinates (row, col)
 * - For each dark cell:
 *   tempRand in 0.8..1.3
 *   randNum in 50..230
 *   rValue = clamp(red + randNum)
 *   gValue = clamp(green - randNum / 2)
 *   bValue = clamp(blue + randNum * 2)
 *   r2Value = clamp(rValue - 40)
 *   g2Value = clamp(gValue - 40)
 *   b2Value = clamp(bValue - 40)
 * - Emits dual sharp rectangles:
 *   1. Outer shadow rect: width = (tempRand + 0.15), opacity = 0.9 * alpha, color = rgb(r2Value, g2Value, b2Value)
 *   2. Inner main rect: width = tempRand, opacity = alpha, color = rgb(rValue, gValue, bValue)
 *   Both centered at the cell: x = col - (tempRand - 1) / 2.0, y = row - (tempRand - 1) / 2.0
 */
class RandomRectangleRenderer : QrRenderer {

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

        val color = design.randomRectColor
        val redValue = ((color ushr 16) and 0xFF).toDouble()
        val greenValue = ((color ushr 8) and 0xFF).toDouble()
        val blueValue = (color and 0xFF).toDouble()
        val alphaValue = ((color ushr 24) and 0xFF) / 255.0

        val randArr = ArrayList<Pair<Int, Int>>(nCount * nCount)
        for (row in 0 until nCount) {
            for (col in 0 until nCount) {
                randArr.add(Pair(row, col))
            }
        }
        val rng = Random(design.effects.seed)
        randArr.shuffle(rng)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (item in randArr) {
            val row = item.first
            val col = item.second

            if (matrix.isDark(col, row)) {
                val tempRand = rng.nextDouble(0.8, 1.3)
                val randNum = rng.nextDouble(50.0, 230.0)

                val rValue = clampRGBValue((redValue + randNum).toInt())
                val gValue = clampRGBValue((greenValue - randNum / 2.0).toInt())
                val bValue = clampRGBValue((blueValue + randNum * 2.0).toInt())

                val r2Value = clampRGBValue(rValue - 40)
                val g2Value = clampRGBValue(gValue - 40)
                val b2Value = clampRGBValue(bValue - 40)

                val cellX = ox + col * cs
                val cellY = oy + row * cs
                val offset = (tempRand - 1.0) / 2.0

                val x = (cellX - offset * cs).toFloat()
                val y = (cellY - offset * cs).toFloat()

                // Layer 1: Outer shadow rect (width = tempRand + 0.15)
                val w1 = ((tempRand + 0.15) * cs).toFloat()
                paint.color = Color.argb(((0.9 * alphaValue) * 255).toInt(), r2Value, g2Value, b2Value)
                canvas.drawRect(x, y, x + w1, y + w1, paint)

                // Layer 2: Inner main rect (width = tempRand)
                val w2 = (tempRand * cs).toFloat()
                paint.color = Color.argb((alphaValue * 255).toInt(), rValue, gValue, bValue)
                canvas.drawRect(x, y, x + w2, y + w2, paint)
            }
        }

        drawLogo(canvas, design, geometry, context)
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val qz = design.quietZoneModules
        val totalPx = ((matrix.size + 2 * qz) * cellSize).toInt()
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = totalPx,
            outputHeight = totalPx,
            quietZoneModules = qz
        )
        render(matrix, design, canvas, geometry, RenderContext())
    }

    private fun clampRGBValue(value: Int): Int {
        return max(0, min(255, value))
    }
}
