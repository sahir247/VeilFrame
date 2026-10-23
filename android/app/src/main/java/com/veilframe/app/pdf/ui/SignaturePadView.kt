package com.veilframe.app.pdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * High-performance smooth Bézier signature canvas for signing PDF documents.
 * Renders anti-aliased quadratic curves and exports transparent signature bitmaps.
 */
class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var isPathEmpty = true

    var onSignatureChanged: ((isEmpty: Boolean) -> Unit)? = null

    init {
        // In dark mode, visible sign is white, in light mode it is dark
        paint.color = if (isNightMode) Color.WHITE else Color.parseColor("#18181B")
    }

    var penColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    var strokeWidth: Float
        get() = paint.strokeWidth
        set(value) {
            paint.strokeWidth = value
            invalidate()
        }

    fun isEmpty(): Boolean = isPathEmpty

    fun clear() {
        path.reset()
        isPathEmpty = true
        invalidate()
        onSignatureChanged?.invoke(true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                path.moveTo(x, y)
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val midX = (lastX + x) / 2f
                val midY = (lastY + y) / 2f
                path.quadTo(lastX, lastY, midX, midY)
                lastX = x
                lastY = y
                if (isPathEmpty) {
                    isPathEmpty = false
                    onSignatureChanged?.invoke(false)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                path.lineTo(x, y)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun getSignatureBitmap(targetColor: Int? = null, opacity: Float = 1.0f): Bitmap? {
        if (isPathEmpty || width <= 0 || height <= 0) return null

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val padding = 16f
        val rect = RectF(
            (bounds.left - padding).coerceAtLeast(0f),
            (bounds.top - padding).coerceAtLeast(0f),
            (bounds.right + padding).coerceAtMost(width.toFloat()),
            (bounds.bottom + padding).coerceAtMost(height.toFloat())
        )

        val cropW = rect.width().toInt().coerceAtLeast(1)
        val cropH = rect.height().toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-rect.left, -rect.top)

        val renderPaint = Paint(paint).apply {
            if (targetColor != null) {
                color = targetColor
            }
            alpha = (opacity.coerceIn(0.05f, 1.0f) * 255).toInt()
        }
        canvas.drawPath(path, renderPaint)
        return bitmap
    }
}
