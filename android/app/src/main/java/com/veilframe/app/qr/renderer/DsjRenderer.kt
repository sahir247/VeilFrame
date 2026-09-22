package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 4 — DSJ (DJ-cross)
 *
 * Each dark position detection pattern is rendered as a cross/DSJ shape:
 * center block + four cardinal arm stubs (like a DJ mixer layout).
 *
 * Data modules use the same DSJ cross motif scaled to a single cell:
 * the center square and four tiny protruding tabs.
 *
 * Mirrors EFQRCodeStyleDSJ.swift position-pattern rendering and extends it
 * to all data modules for a consistent aesthetic.
 */
class DsjRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val fg = solidPaint(params.foreground)
        val pos = solidPaint(params.positionColor ?: params.foreground)
        val scale = params.dataScale.coerceIn(0.5f, 1.0f)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val x = col * cs; val y = row * cs
                val cx = x + cs * 0.5f; val cy = y + cs * 0.5f

                when (type) {
                    ModuleType.POS_CENTER -> drawDsjPosition(canvas, cx, cy, cs, pos)
                    ModuleType.POS_OTHER  -> { /* position pattern drawn as group from CENTER */ }
                    else -> drawDsjData(canvas, cx, cy, cs, scale, fg)
                }
            }
        }

        drawLogo(canvas, params, (n * cs).toInt())
    }

    /** DSJ position: central 3×3 block + 4 arm stubs extending ±3 cells. */
    private fun drawDsjPosition(canvas: Canvas, cx: Float, cy: Float, cs: Float, paint: android.graphics.Paint) {
        val half = cs * 1.5f   // half of 3-cell central block
        val stubW = cs * 0.6f  // width of arm stub
        val stubLen = cs * 0.8f

        // Central block
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
        // Left arm
        canvas.drawRect(cx - half - stubLen, cy - stubW / 2f, cx - half, cy + stubW / 2f, paint)
        // Right arm
        canvas.drawRect(cx + half, cy - stubW / 2f, cx + half + stubLen, cy + stubW / 2f, paint)
        // Top arm
        canvas.drawRect(cx - stubW / 2f, cy - half - stubLen, cx + stubW / 2f, cy - half, paint)
        // Bottom arm
        canvas.drawRect(cx - stubW / 2f, cy + half, cx + stubW / 2f, cy + half + stubLen, paint)
    }

    /** DSJ data: mini cross in each dark cell. */
    private fun drawDsjData(canvas: Canvas, cx: Float, cy: Float, cs: Float, scale: Float, paint: android.graphics.Paint) {
        val half = cs * scale * 0.5f
        val armW = half * 0.35f

        // Main center square
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
        // Four protruding tabs
        val tab = half * 0.3f
        canvas.drawRect(cx - armW, cy - half - tab, cx + armW, cy - half, paint)  // top
        canvas.drawRect(cx - armW, cy + half, cx + armW, cy + half + tab, paint)  // bottom
        canvas.drawRect(cx - half - tab, cy - armW, cx - half, cy + armW, paint)  // left
        canvas.drawRect(cx + half, cy - armW, cx + half + tab, cy + armW, paint)  // right
    }
}
