package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.ModuleShape
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrMatrix.ModuleType
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.min
import kotlin.random.Random

/**
 * Style 1 — BASIC
 *
 * Classic QR code with per-zone customizable module shapes:
 *   - Data modules: rectangle | circle | rounded-rect | random-circle
 *   - Alignment patterns: rectangle | circle | rounded-rect
 *   - Timing patterns: rectangle | circle | rounded-rect
 *   - Position detection patterns: rectangle | circle | rounded-rect | planets | DSJ cross
 *
 * Mirrors EFQRCodeStyleBasic.swift's writeQRCode() logic, translated to
 * Android Canvas/Paint calls with a cell-size coordinate system.
 */
class BasicRenderer : QrRenderer {

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        // Fill background
        canvas.drawColor(params.background)

        val n = matrix.size
        val fgPaint = solidPaint(params.foreground)
        val posPaint = solidPaint(params.positionColor ?: params.foreground)
        val rng = Random(42L)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.typeAt(col, row)
                val cx = (col + 0.5f) * cellSize
                val cy = (row + 0.5f) * cellSize
                val left = col * cellSize
                val top  = row * cellSize

                when (type) {
                    ModuleType.POS_CENTER -> drawPositionCenter(canvas, col, row, n, cellSize, params, posPaint)
                    ModuleType.POS_OTHER  -> { /* drawn by drawPositionCenter as a group */ }
                    ModuleType.ALIGN_CENTER, ModuleType.ALIGN_OTHER ->
                        drawShapedModule(canvas, cx, cy, left, top, cellSize, 0.9f, params.alignShape, fgPaint)
                    ModuleType.TIMING ->
                        drawShapedModule(canvas, cx, cy, left, top, cellSize, 0.85f, params.timingShape, fgPaint)
                    ModuleType.DATA ->
                        drawDataModule(canvas, cx, cy, left, top, cellSize, params, fgPaint, rng)
                }
            }
        }

        drawLogo(canvas, params, (n * cellSize).toInt())
    }

    // --- Position detection pattern (7×7 finder square) ---
    private fun drawPositionCenter(
        canvas: Canvas, cx: Int, cy: Int, n: Int,
        cs: Float, params: QrStyleParams, paint: Paint
    ) {
        val posColor = params.positionColor ?: params.foreground
        val posSize = params.positionColor?.let { 1.0f } ?: 1.0f
        val x = cx * cs; val y = cy * cs

        when (params.positionShape) {
            ModuleShape.RECTANGLE -> {
                // Outer square ring (stroke)
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = cs * 0.9f; color = posColor
                }
                canvas.drawRect(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs, ring)
                // Inner filled square (3 modules wide)
                val fill = solidPaint(posColor)
                canvas.drawRect(x - cs, y - cs, x + 2f * cs, y + 2f * cs, fill)
            }
            ModuleShape.ROUND -> {
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = cs * 0.9f; color = posColor
                }
                canvas.drawCircle(x + cs * 0.5f, y + cs * 0.5f, cs * 3f, ring)
                canvas.drawCircle(x + cs * 0.5f, y + cs * 0.5f, cs * 1.5f, solidPaint(posColor))
            }
            ModuleShape.ROUNDED_RECTANGLE -> {
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = cs * 0.9f; color = posColor
                }
                val r = cs * 1.2f
                canvas.drawRoundRect(
                    RectF(x - 2.5f * cs, y - 2.5f * cs, x + 3.5f * cs, y + 3.5f * cs), r, r, ring
                )
                canvas.drawCircle(x + cs * 0.5f, y + cs * 0.5f, cs * 1.5f, solidPaint(posColor))
            }
            ModuleShape.PLANETS -> {
                // Central dot + dashed orbit ring + two satellite dots on each axis
                val fill = solidPaint(posColor)
                canvas.drawCircle(x + cs * 0.5f, y + cs * 0.5f, cs * 1.5f, fill)
                val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = cs * 0.15f; color = posColor
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(cs * 0.5f, cs * 0.5f), 0f)
                }
                canvas.drawCircle(x + cs * 0.5f, y + cs * 0.5f, cs * 3f, orbit)
                listOf(-3f, 3f).forEach { offset ->
                    canvas.drawCircle(x + cs * 0.5f + offset * cs, y + cs * 0.5f, cs * 0.5f, fill)
                    canvas.drawCircle(x + cs * 0.5f, y + cs * 0.5f + offset * cs, cs * 0.5f, fill)
                }
            }
            ModuleShape.DSJ -> {
                // DSJ cross: center block + four arm stubs
                val fill = solidPaint(posColor)
                val w = cs * 3f
                canvas.drawRect(x - cs, y - cs, x - cs + w, y - cs + w, fill)
                // Left, Right, Top, Bottom stubs
                canvas.drawRect(x - cs * 3f, y - cs, x - cs * 2f, y - cs + w, fill)
                canvas.drawRect(x - cs + w, y - cs, x - cs + w + cs, y - cs + w, fill)
                canvas.drawRect(x - cs, y - cs * 3f, x - cs + w, y - cs * 2f, fill)
                canvas.drawRect(x - cs, y - cs + w, x - cs + w, y - cs + w + cs, fill)
            }
        }
    }

    // --- Data & misc module shapes ---
    private fun drawShapedModule(
        canvas: Canvas, cx: Float, cy: Float,
        left: Float, top: Float,
        cs: Float, scale: Float,
        shape: ModuleShape, paint: Paint
    ) {
        val half = cs * scale / 2f
        val l = cx - half; val t = cy - half
        val r = cx + half; val b = cy + half
        when (shape) {
            ModuleShape.RECTANGLE, ModuleShape.DSJ ->
                canvas.drawRect(l, t, r, b, paint)
            ModuleShape.ROUND ->
                canvas.drawCircle(cx, cy, half, paint)
            ModuleShape.ROUNDED_RECTANGLE ->
                canvas.drawRoundRect(RectF(l, t, r, b), half / 2f, half / 2f, paint)
            ModuleShape.PLANETS ->
                canvas.drawCircle(cx, cy, half, paint)
        }
    }

    private fun drawDataModule(
        canvas: Canvas, cx: Float, cy: Float,
        left: Float, top: Float,
        cs: Float, params: QrStyleParams,
        paint: Paint, rng: Random
    ) {
        val scale = params.dataScale.coerceIn(0.1f, 1.0f)
        val half = cs * scale / 2f
        val l = cx - half; val t = cy - half; val r = cx + half; val b = cy + half

        when (params.dataShape) {
            ModuleShape.RECTANGLE, ModuleShape.PLANETS, ModuleShape.DSJ ->
                canvas.drawRect(l, t, r, b, paint)
            ModuleShape.ROUND -> {
                val randomR = half * rng.nextFloat().coerceIn(0.33f, 1.0f)
                canvas.drawCircle(cx, cy, randomR, paint)
            }
            ModuleShape.ROUNDED_RECTANGLE ->
                canvas.drawRoundRect(RectF(l, t, r, b), half / 2f, half / 2f, paint)
        }
    }
}
