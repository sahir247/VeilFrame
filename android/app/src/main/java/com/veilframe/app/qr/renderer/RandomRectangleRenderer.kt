package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.model.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams
import kotlin.random.Random

/**
 * Style 9 — RANDOM_RECTANGLE
 *
 * Each dark data module is drawn as a rectangle with randomly jittered
 * size (within ±15% of cell size) and a slight random position offset.
 * The jitter seed is deterministic via [QrStyleParams.randomRectSeed]
 * so re-renders are reproducible.
 *
 * Position patterns are drawn as solid squares for reliable scanning.
 *
 * Mirrors EFQRCodeStyleRandomRectangle.swift's randomized-rect approach.
 */
class RandomRectangleRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val rng = Random(params.randomRectSeed)
        val fgPaint = solidPaint(params.foreground)
        val posPaint = solidPaint(params.positionColor ?: params.foreground)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val x = col * cs; val y = row * cs

                when (type) {
                    ModuleType.POS_CENTER -> {
                        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            style = android.graphics.Paint.Style.STROKE; strokeWidth = cs * 0.9f
                            color = params.positionColor ?: params.foreground
                        }
                        canvas.drawRect(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs, ring)
                        canvas.drawRect(x - cs, y - cs, x + 2f * cs, y + 2f * cs, posPaint)
                    }
                    ModuleType.POS_OTHER -> {}
                    else -> {
                        // Random size: base scale ± 15%
                        val baseScale = params.dataScale.coerceIn(0.5f, 1.0f)
                        val jitter = rng.nextFloat() * 0.3f - 0.15f     // [-0.15, +0.15]
                        val scale = (baseScale + jitter).coerceIn(0.25f, 1.0f)
                        // Random offset within the cell (up to ±10%)
                        val dx = (rng.nextFloat() - 0.5f) * cs * 0.1f
                        val dy = (rng.nextFloat() - 0.5f) * cs * 0.1f
                        val half = cs * scale / 2f
                        val cx2 = x + cs * 0.5f + dx; val cy2 = y + cs * 0.5f + dy
                        val r = half * rng.nextFloat().coerceIn(0.1f, 0.4f) // corner radius
                        canvas.drawRoundRect(
                            RectF(cx2 - half, cy2 - half, cx2 + half, cy2 + half),
                            r, r, fgPaint
                        )
                    }
                }
            }
        }

        drawLogo(canvas, params, (n * cs).toInt())
    }
}
