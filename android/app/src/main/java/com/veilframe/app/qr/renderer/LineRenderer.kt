package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 8 — LINE (Horizontal/vertical stripe patterns)
 *
 * Dark modules that are horizontally adjacent are merged into a single
 * horizontal stripe. Similarly, vertically adjacent modules become vertical
 * stripes. Isolated modules are small squares.
 *
 * The horizontal stripe color and vertical stripe color can be set independently
 * via [QrStyleParams.lineHorizontalColor] / [QrStyleParams.lineVerticalColor].
 *
 * Mirrors EFQRCodeStyleLine.swift's run-length merge logic.
 */
class LineRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        canvas.drawColor(params.background)
        val n = matrix.size
        val cs = cellSize
        val hColor = params.lineHorizontalColor ?: params.foreground
        val vColor = params.lineVerticalColor   ?: params.foreground
        val hPaint = solidPaint(hColor)
        val vPaint = solidPaint(vColor)
        val posPaint = solidPaint(params.positionColor ?: params.foreground)

        val usedH = Array(n) { BooleanArray(n) }
        val usedV = Array(n) { BooleanArray(n) }

        // Draw position detection patterns first
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.typeAt(col, row) == ModuleType.POS_CENTER && matrix.isDark(col, row)) {
                    val x = col * cs; val y = row * cs
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = cs * 0.9f
                        color = params.positionColor ?: params.foreground
                    }
                    canvas.drawRect(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs, ring)
                    canvas.drawRect(x - cs, y - cs, x + 2f * cs, y + 2f * cs, posPaint)
                }
            }
        }

        // Horizontal runs
        for (row in 0 until n) {
            var col = 0
            while (col < n) {
                val type = matrix.typeAt(col, row)
                if (type == ModuleType.POS_CENTER || type == ModuleType.POS_OTHER) { col++; continue }
                if (!matrix.isDark(col, row)) { col++; continue }
                // Extend run
                var end = col + 1
                while (end < n && matrix.isDark(end, row)
                    && matrix.typeAt(end, row) != ModuleType.POS_CENTER
                    && matrix.typeAt(end, row) != ModuleType.POS_OTHER
                    && !usedH[end][row]) {
                    end++
                }
                val runLen = end - col
                if (runLen >= 2) {
                    val y = row * cs; val x1 = col * cs; val x2 = end * cs
                    val pad = cs * 0.07f
                    canvas.drawRoundRect(RectF(x1 + pad, y + pad, x2 - pad, y + cs - pad), cs * 0.3f, cs * 0.3f, hPaint)
                    for (c in col until end) usedH[c][row] = true
                }
                col = end
            }
        }

        // Vertical runs (only for cells not already drawn horizontally)
        for (col in 0 until n) {
            var row = 0
            while (row < n) {
                val type = matrix.typeAt(col, row)
                if (type == ModuleType.POS_CENTER || type == ModuleType.POS_OTHER) { row++; continue }
                if (!matrix.isDark(col, row) || usedH[col][row]) { row++; continue }
                var end = row + 1
                while (end < n && matrix.isDark(col, end)
                    && matrix.typeAt(col, end) != ModuleType.POS_CENTER
                    && matrix.typeAt(col, end) != ModuleType.POS_OTHER
                    && !usedH[col][end] && !usedV[col][end]) {
                    end++
                }
                val runLen = end - row
                if (runLen >= 2) {
                    val x = col * cs; val y1 = row * cs; val y2 = end * cs
                    val pad = cs * 0.07f
                    canvas.drawRoundRect(RectF(x + pad, y1 + pad, x + cs - pad, y2 - pad), cs * 0.3f, cs * 0.3f, vPaint)
                    for (r in row until end) usedV[col][r] = true
                } else {
                    // Isolated cell
                    val x = col * cs + cs * 0.1f; val y = row * cs + cs * 0.1f
                    canvas.drawRoundRect(RectF(x, y, x + cs * 0.8f, y + cs * 0.8f), cs * 0.2f, cs * 0.2f, hPaint)
                }
                row = end
            }
        }

        drawLogo(canvas, params, (n * cs).toInt())
    }
}
