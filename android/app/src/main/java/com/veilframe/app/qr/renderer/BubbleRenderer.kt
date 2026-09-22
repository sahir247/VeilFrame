package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Style 2 — BUBBLE (Visual Grammar: Organic / Bubble Cluster)
 *
 * Implements EFQRCode's bubble clustering algorithm:
 * 1. 3x3 cross clusters -> large central bubble with stroke and core dot.
 * 2. 2x2 all-dark corners -> radius sqrt(1/2) circle inscribed at intersection.
 * 3. 1x2 vertical / 2x1 horizontal pairs -> rounded capsule bubbles.
 * 4. Remaining isolated dark cells -> single circles.
 * 5. Protected canonical finders via [FinderRenderer].
 */
class BubbleRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val cs = geometry.moduleSize
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val rng = Random(design.effects.seed)

        // 1. Draw finders first
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )

        // 2. Setup availability matrices
        val avail = Array(n) { BooleanArray(n) { true } }
        val avail2 = Array(n) { BooleanArray(n) { true } }

        // Reserve protected function patterns
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.functionMask.isProtected(col, row)) {
                    avail[col][row] = false
                    avail2[col][row] = false
                    // Draw timing and alignment patterns as clean rounded bubbles
                    if (matrix.isDark(col, row) && !matrix.functionMask.isFinder(col, row) && !matrix.functionMask.isSeparator(col, row)) {
                        val (cx, cy) = geometry.moduleCenter(col, row)
                        canvas.drawCircle(cx, cy, cs * 0.42f, context.obtainFill(fgColor))
                    }
                }
            }
        }

        val outlineColor = fgColor
        val centerColor = bgColor

        // 3. Scan for 3x3 Cross Bubbles
        for (col in 0 until n - 2) {
            for (row in 0 until n - 2) {
                if (avail[col][row] && avail2[col][row]) {
                    var allAvail2 = true
                    for (di in 0..2) {
                        for (dj in 0..2) {
                            if (!avail2[col + di][row + dj]) allAvail2 = false
                        }
                    }

                    if (allAvail2 &&
                        matrix.isDark(col + 1, row) && matrix.isDark(col + 1, row + 2) &&
                        matrix.isDark(col, row + 1) && matrix.isDark(col + 2, row + 1)
                    ) {
                        val (cx, cy) = geometry.moduleCenter(col + 1, row + 1)
                        val sw = (0.35f + rng.nextFloat() * 0.15f) * cs

                        canvas.drawCircle(cx, cy, cs * 1.0f, context.obtainFill(centerColor))
                        canvas.drawCircle(cx, cy, cs * 1.0f, context.obtainStroke(outlineColor, sw))

                        if (matrix.isDark(col + 1, row + 1)) {
                            val r2 = (0.28f + rng.nextFloat() * 0.20f) * cs
                            canvas.drawCircle(cx, cy, r2, context.obtainFill(outlineColor))
                        }

                        for (di in 0..2) {
                            for (dj in 0..2) {
                                avail[col + di][row + dj] = false
                                avail2[col + di][row + dj] = false
                            }
                        }
                    }
                }
            }
        }

        // 4. Scan for 2x2 Square Bubbles
        for (col in 0 until n - 1) {
            for (row in 0 until n - 1) {
                if (avail[col][row] &&
                    matrix.isDark(col, row) && matrix.isDark(col + 1, row) &&
                    matrix.isDark(col, row + 1) && matrix.isDark(col + 1, row + 1)
                ) {
                    val (cx1, cy1) = geometry.moduleCenter(col, row)
                    val (cx2, cy2) = geometry.moduleCenter(col + 1, row + 1)
                    val midX = (cx1 + cx2) / 2f
                    val midY = (cy1 + cy2) / 2f
                    val sw = (0.33f + rng.nextFloat() * 0.15f) * cs

                    canvas.drawCircle(midX, midY, cs * sqrt(0.5f), context.obtainFill(centerColor))
                    canvas.drawCircle(midX, midY, cs * sqrt(0.5f), context.obtainStroke(outlineColor, sw))

                    for (di in 0..1) {
                        for (dj in 0..1) {
                            avail[col + di][row + dj] = false
                            avail2[col + di][row + dj] = false
                        }
                    }
                }
            }
        }

        // 5. Scan for Pairs (Vertical & Horizontal)
        for (col in 0 until n) {
            for (row in 0 until n) {
                // Vertical pair
                if (avail[col][row] && row < n - 1 && matrix.isDark(col, row) && matrix.isDark(col, row + 1)) {
                    val (cx1, cy1) = geometry.moduleCenter(col, row)
                    val (_, cy2) = geometry.moduleCenter(col, row + 1)
                    val midY = (cy1 + cy2) / 2f
                    val sw = (0.35f + rng.nextFloat() * 0.1f) * cs

                    canvas.drawCircle(cx1, midY, cs * 0.48f, context.obtainFill(centerColor))
                    canvas.drawCircle(cx1, midY, cs * 0.48f, context.obtainStroke(outlineColor, sw))

                    avail[col][row] = false
                    avail[col][row + 1] = false
                }

                // Horizontal pair
                if (avail[col][row] && col < n - 1 && matrix.isDark(col, row) && matrix.isDark(col + 1, row)) {
                    val (cx1, cy1) = geometry.moduleCenter(col, row)
                    val (cx2, _) = geometry.moduleCenter(col + 1, row)
                    val midX = (cx1 + cx2) / 2f
                    val sw = (0.35f + rng.nextFloat() * 0.1f) * cs

                    canvas.drawCircle(midX, cy1, cs * 0.48f, context.obtainFill(centerColor))
                    canvas.drawCircle(midX, cy1, cs * 0.48f, context.obtainStroke(outlineColor, sw))

                    avail[col][row] = false
                    avail[col + 1][row] = false
                }

                // 6. Remaining Single Isolated Dark Modules
                if (avail[col][row] && matrix.isDark(col, row)) {
                    val (cx, cy) = geometry.moduleCenter(col, row)
                    val r = (0.30f + rng.nextFloat() * 0.15f) * cs
                    canvas.drawCircle(cx, cy, r, context.obtainFill(outlineColor))
                    avail[col][row] = false
                }
            }
        }

        // 7. Composite center logo
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
