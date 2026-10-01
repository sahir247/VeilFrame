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
 * Implements VeilFrame Art Engine's exact VeilFrameStyleRandomRectangle algorithm:
 * - Color parameter: [design.randomRectColor] (default VeilFrame art green 0x14AA3C / RGB 20, 170, 60)
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

    companion object {
        // Deterministic sub-stream salts derived from 64-bit golden ratios and primes
        // to prevent stream collisions and ensure independent RNG sequences per property.
        private const val SHUFFLE_SALT = 0x5A17F00D_12345678L
        private const val SCALE_SALT   = 0x3C6EF35F_1ABCDEF0L
        private const val COLOR_SALT   = 0x4E3779B9_7F4A7C15L
        private const val OFFSET_SALT  = 0x27D4EB2F_165667B1L
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
        val masterSeed = design.jitterStyle.seed
        val shuffleRng = Random(masterSeed xor SHUFFLE_SALT)
        val scaleRng = Random(masterSeed xor SCALE_SALT)
        val colorRng = Random(masterSeed xor COLOR_SALT)
        val offsetRng = Random(masterSeed xor OFFSET_SALT)

        randArr.shuffle(shuffleRng)

        val nodes = mutableListOf<com.veilframe.app.qr.geometry.QrGeometryNode>()
        nodes.add(
            com.veilframe.app.qr.geometry.RectNode(
                x = 0f,
                y = 0f,
                width = geometry.outputWidthFloat,
                height = geometry.outputHeightFloat,
                fill = design.backdropStyle.color ?: design.palette.background
            )
        )

        for (item in randArr) {
            val row = item.first
            val col = item.second

            if (matrix.isDark(col, row)) {
                // 1. Scale Jitter: consumes solely from scaleRng
                val scaleSample = scaleRng.nextDouble()
                val scaleJitter = design.jitterStyle.scaleJitter.toDouble()
                val tempRand = if (scaleJitter <= 0.0001) {
                    1.05
                } else {
                    val minScale = (1.05 - scaleJitter).coerceAtLeast(0.1)
                    val maxScale = (1.05 + scaleJitter).coerceAtLeast(minScale + 0.001)
                    minScale + scaleSample * (maxScale - minScale)
                }

                // 2. Color Jitter: consumes solely from colorRng
                val colorSample = colorRng.nextDouble()
                val colorScale = (design.jitterStyle.colorJitter / 0.1).coerceIn(0.0, 5.0)
                val randNum = 50.0 + 180.0 * colorScale * colorSample

                val rValue = clampRGBValue((redValue + randNum).toInt())
                val gValue = clampRGBValue((greenValue - randNum / 2.0).toInt())
                val bValue = clampRGBValue((blueValue + randNum * 2.0).toInt())

                val r2Value = clampRGBValue(rValue - 40)
                val g2Value = clampRGBValue(gValue - 40)
                val b2Value = clampRGBValue(bValue - 40)

                // 3. Offset Jitter: consumes solely from offsetRng
                val oxSample = offsetRng.nextDouble(-1.0, 1.0)
                val oySample = offsetRng.nextDouble(-1.0, 1.0)

                val oxJitter = if (design.jitterStyle.offsetJitter > 0f) {
                    oxSample * design.jitterStyle.offsetJitter * cs
                } else {
                    0.0
                }
                val oyJitter = if (design.jitterStyle.offsetJitter > 0f) {
                    oySample * design.jitterStyle.offsetJitter * cs
                } else {
                    0.0
                }

                val cellX = ox + col * cs
                val cellY = oy + row * cs
                val offset = (tempRand - 1.0) / 2.0

                val x = (cellX - offset * cs + oxJitter).toFloat()
                val y = (cellY - offset * cs + oyJitter).toFloat()

                // Layer 1: Outer shadow rect (width = tempRand + 0.15)
                val w1 = ((tempRand + 0.15) * cs).toFloat()
                val c1 = (0xFF shl 24) or (r2Value shl 16) or (g2Value shl 8) or b2Value
                nodes.add(
                    com.veilframe.app.qr.geometry.RectNode(
                        x = x,
                        y = y,
                        width = w1,
                        height = w1,
                        fill = c1,
                        fillString = "rgb($r2Value, $g2Value, $b2Value)",
                        opacity = (0.9 * alphaValue).toFloat(),
                        opacityString = String.format(java.util.Locale.US, "%.2f", 0.9 * alphaValue),
                        alwaysEmitOpacity = true
                    )
                )

                // Layer 2: Inner main rect (width = tempRand)
                val w2 = (tempRand * cs).toFloat()
                val c2 = (0xFF shl 24) or (rValue shl 16) or (gValue shl 8) or bValue
                nodes.add(
                    com.veilframe.app.qr.geometry.RectNode(
                        x = x,
                        y = y,
                        width = w2,
                        height = w2,
                        fill = c2,
                        fillString = "rgb($rValue, $gValue, $bValue)",
                        opacity = alphaValue.toFloat(),
                        opacityString = String.format(java.util.Locale.US, "%.2f", alphaValue),
                        alwaysEmitOpacity = true
                    )
                )
            }
        }

        return com.veilframe.app.qr.geometry.QrGeometryIr(
            width = geometry.outputWidthFloat,
            height = geometry.outputHeightFloat,
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
        val resolvedQz = QrGeometry.resolveQuietZone(design, matrix.size)
        val totalPxW = (matrix.size + resolvedQz.left + resolvedQz.right) * cellSize
        val totalPxH = (matrix.size + resolvedQz.top + resolvedQz.bottom) * cellSize
        val geometry = QrGeometry.fromDesign(
            matrixSize = matrix.size,
            outputWidth = totalPxW,
            outputHeight = totalPxH,
            design = design
        )
        render(matrix, design, canvas, geometry, RenderContext())
    }

    private fun clampRGBValue(value: Int): Int {
        return max(0, min(255, value))
    }
}
