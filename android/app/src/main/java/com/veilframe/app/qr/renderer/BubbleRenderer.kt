package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Style 2 — BUBBLE
 *
 * Organic, bubble-cluster rendering that mirrors EFQRCodeStyleBubble.swift:
 *
 *  1. Scan for 3×3 "cross" patterns (center + 4 cardinal dark neighbours)
 *     → draw a large bubble circle centered on the cross, consuming all 9 cells.
 *  2. Scan for 2×2 all-dark corners → draw a circle inscribed in the corner.
 *  3. Scan for vertical 1×2 pairs  → draw a vertically-elongated bubble.
 *  4. Scan for horizontal 2×1 pairs → draw a horizontally-elongated bubble.
 *  5. Remaining isolated dark cells → small random-size circles.
 *  6. Rare: light DATA cells randomly get a tiny ghost bubble for texture.
 *
 * Position detection patterns are drawn with the same shape style as BASIC.
 */
class BubbleRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)

        val n = matrix.size
        val cs = cellSize
        val rng = Random(12345L)

        val outlineColor = params.bubbleOutlineColor
        val centerColor  = params.bubbleCenterColor

        // Availability grids (mirrors Swift's available/ava2 arrays)
        val avail  = Array(n) { BooleanArray(n) { true } }
        val avail2 = Array(n) { BooleanArray(n) { true } }

        // --- Position detection patterns first (on top layer) ---
        val posLayer = mutableListOf<() -> Unit>()

        for (col in 0 until n) {
            for (row in 0 until n) {
                when (matrix.typeAt(col, row)) {
                    ModuleType.POS_CENTER -> {
                        val x = col.toFloat(); val y = row.toFloat()
                        val cx2 = (x + 0.5f) * cs; val cy2 = (y + 0.5f) * cs
                        posLayer.add {
                            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.STROKE
                                strokeWidth = cs * 0.9f
                                color = params.positionColor ?: params.foreground
                            }
                            canvas.drawCircle(cx2, cy2, cs * 3f, ring)
                            canvas.drawCircle(cx2, cy2, cs * 1.5f, solidPaint(params.positionColor ?: params.foreground))
                        }
                        avail[col][row] = false; avail2[col][row] = false
                    }
                    ModuleType.POS_OTHER -> { avail[col][row] = false; avail2[col][row] = false }
                    else -> {}
                }
            }
        }

        // Bubble pass — g1 layer (compound bubbles, drawn on top of base)
        val g1Actions = mutableListOf<() -> Unit>()

        for (col in 0 until n) {
            for (row in 0 until n) {
                val type = matrix.typeAt(col, row)
                if (type == ModuleType.POS_CENTER || type == ModuleType.POS_OTHER) continue
                val dark = matrix.isDark(col, row)

                // 3×3 cross bubble
                if (avail[col][row] && avail2[col][row] && col < n - 2 && row < n - 2) {
                    var allAvail2 = true
                    for (di in 0..2) for (dj in 0..2) if (!avail2[col + di][row + dj]) allAvail2 = false
                    if (allAvail2 &&
                        matrix.isDark(col + 1, row) && matrix.isDark(col + 1, row + 2) &&
                        matrix.isDark(col, row + 1) && matrix.isDark(col + 2, row + 1)) {
                        val cx2 = (col + 1 + 0.5f) * cs; val cy2 = (row + 1 + 0.5f) * cs
                        val sw = (0.33f + rng.nextFloat() * 0.27f) * cs
                        g1Actions.add {
                            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.FILL; color = centerColor
                            }
                            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.STROKE; strokeWidth = sw; color = outlineColor
                            }
                            canvas.drawCircle(cx2, cy2, cs * 1f, p)
                            canvas.drawCircle(cx2, cy2, cs * 1f, sp)
                            if (matrix.isDark(col + 1, row + 1)) {
                                val r2 = (0.25f + rng.nextFloat() * 0.25f) * cs
                                canvas.drawCircle(cx2, cy2, r2, solidPaint(outlineColor))
                            }
                        }
                        for (di in 0..2) for (dj in 0..2) { avail[col + di][row + dj] = false; avail2[col + di][row + dj] = false }
                    }
                }

                // 2×2 filled corner bubble
                if (avail[col][row] && col < n - 1 && row < n - 1) {
                    if (dark && matrix.isDark(col + 1, row) &&
                        matrix.isDark(col, row + 1) && matrix.isDark(col + 1, row + 1)) {
                        val cx2 = (col + 1f) * cs; val cy2 = (row + 1f) * cs
                        val sw = (0.33f + rng.nextFloat() * 0.27f) * cs
                        g1Actions.add {
                            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = centerColor }
                            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = outlineColor }
                            val r = cs * sqrt(0.5f)
                            canvas.drawCircle(cx2, cy2, r, p)
                            canvas.drawCircle(cx2, cy2, r, sp)
                        }
                        for (di in 0..1) for (dj in 0..1) { avail[col + di][row + dj] = false; avail2[col + di][row + dj] = false }
                    }
                }

                // Vertical 1×2 pair
                if (avail[col][row] && row < n - 1) {
                    if (dark && matrix.isDark(col, row + 1)) {
                        val cx2 = (col + 0.5f) * cs; val cy2 = (row + 1f) * cs
                        val sw = (0.36f + rng.nextFloat() * 0.04f) * cs
                        val r = (0.475f + rng.nextFloat() * 0.05f) * cs
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = centerColor }
                        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = outlineColor }
                        canvas.drawCircle(cx2, cy2, r, p)
                        canvas.drawCircle(cx2, cy2, r, sp)
                        avail[col][row] = false; avail[col][row + 1] = false
                    }
                }

                // Horizontal 2×1 pair
                if (avail[col][row] && col < n - 1) {
                    if (dark && matrix.isDark(col + 1, row)) {
                        val cx2 = (col + 1f) * cs; val cy2 = (row + 0.5f) * cs
                        val sw = (0.36f + rng.nextFloat() * 0.04f) * cs
                        val r = (0.475f + rng.nextFloat() * 0.05f) * cs
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = centerColor }
                        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = outlineColor }
                        canvas.drawCircle(cx2, cy2, r, p)
                        canvas.drawCircle(cx2, cy2, r, sp)
                        avail[col][row] = false; avail[col + 1][row] = false
                    }
                }

                // Isolated single dot
                if (avail[col][row] && dark) {
                    val cx2 = (col + 0.5f) * cs; val cy2 = (row + 0.5f) * cs
                    val r = (0.25f + rng.nextFloat() * 0.25f) * cs
                    canvas.drawCircle(cx2, cy2, r, solidPaint(outlineColor))
                }

                // Ghost light-cell decoration (rare)
                if (avail[col][row] && !dark && type == ModuleType.DATA) {
                    if (rng.nextFloat() > 0.85f) {
                        val cx2 = (col + 0.5f) * cs; val cy2 = (row + 0.5f) * cs
                        val r = (0.425f + rng.nextFloat() * 0.45f) * cs
                        val sw = (0.15f + rng.nextFloat() * 0.18f) * cs
                        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.FILL; color = centerColor; alpha = 80
                        }
                        val gsp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = sw; color = outlineColor; alpha = 80
                        }
                        canvas.drawCircle(cx2, cy2, r, gp)
                        canvas.drawCircle(cx2, cy2, r, gsp)
                    }
                }
            }
        }

        // Draw compound bubbles on top
        g1Actions.forEach { it() }

        // Draw position patterns on top of everything
        posLayer.forEach { it() }

        drawLogo(canvas, params, (n * cs).toInt())
    }
}
