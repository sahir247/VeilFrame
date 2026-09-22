package com.veilframe.app.qr.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Semi-transparent overlay drawn on top of the CameraX preview.
 * Draws a rounded-square viewfinder with corner brackets, mirroring
 * the classic QR scanner "targeting reticle" UX pattern.
 */
class QrScanOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00BCD4.toInt()
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val finderRect = RectF()
    private val cornerLen = 40f
    private val cornerRadius = 16f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = min(w, h) * 0.7f
        val cx = w / 2f; val cy = h / 2f
        finderRect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dim overlay with clear viewfinder hole
        canvas.drawPaint(dimPaint)
        canvas.drawRoundRect(finderRect, cornerRadius, cornerRadius, clearPaint)

        // Corner brackets
        val l = finderRect.left; val t = finderRect.top
        val r = finderRect.right; val b = finderRect.bottom
        val cr = cornerLen

        // Top-left
        canvas.drawLine(l, t + cr, l, t + cornerRadius, cornerPaint)
        canvas.drawArc(RectF(l, t, l + cornerRadius * 2, t + cornerRadius * 2), 180f, 90f, false, cornerPaint)
        canvas.drawLine(l + cornerRadius, t, l + cr, t, cornerPaint)

        // Top-right
        canvas.drawLine(r - cr, t, r - cornerRadius, t, cornerPaint)
        canvas.drawArc(RectF(r - cornerRadius * 2, t, r, t + cornerRadius * 2), 270f, 90f, false, cornerPaint)
        canvas.drawLine(r, t + cornerRadius, r, t + cr, cornerPaint)

        // Bottom-left
        canvas.drawLine(l, b - cr, l, b - cornerRadius, cornerPaint)
        canvas.drawArc(RectF(l, b - cornerRadius * 2, l + cornerRadius * 2, b), 90f, 90f, false, cornerPaint)
        canvas.drawLine(l + cornerRadius, b, l + cr, b, cornerPaint)

        // Bottom-right
        canvas.drawLine(r - cr, b, r - cornerRadius, b, cornerPaint)
        canvas.drawArc(RectF(r - cornerRadius * 2, b - cornerRadius * 2, r, b), 0f, 90f, false, cornerPaint)
        canvas.drawLine(r, b - cr, r, b - cornerRadius, cornerPaint)
    }
}
