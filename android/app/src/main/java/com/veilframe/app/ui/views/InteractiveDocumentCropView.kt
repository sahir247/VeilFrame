package com.veilframe.app.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Interactive 4-corner document cropping view (FairScan style).
 * Displays full uncropped source photo with draggable corner handles and
 * darkened background scrim outside the selected quadrilateral.
 */
class InteractiveDocumentCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    // Corners in source bitmap pixel coordinates: [0: TL, 1: TR, 2: BR, 3: BL]
    private val corners = mutableListOf<PointF>()
    private var activeCornerIndex = -1

    private val touchRadius = dpToPx(40f)
    private val handleOuterRadius = dpToPx(14f)
    private val handleInnerRadius = dpToPx(6f)

    private val quadPath = Path()
    private val maskPath = Path()

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt() // 66% black scrim outside the quad
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt() // Neon cyan edge line
        strokeWidth = dpToPx(2.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val handleOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.FILL
    }

    private val handleInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val imageMatrixInverse = Matrix()
    private val displayMatrix = Matrix()

    fun setSource(bitmap: Bitmap, initialCorners: List<PointF>?) {
        this.sourceBitmap = bitmap
        corners.clear()
        if (initialCorners != null && initialCorners.size == 4) {
            initialCorners.forEach { corners.add(PointF(it.x, it.y)) }
        } else {
            // Default to inset 5% boundary
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            corners.add(PointF(w * 0.05f, h * 0.05f)) // TL
            corners.add(PointF(w * 0.95f, h * 0.05f)) // TR
            corners.add(PointF(w * 0.95f, h * 0.95f)) // BR
            corners.add(PointF(w * 0.05f, h * 0.95f)) // BL
        }
        activeCornerIndex = -1
        invalidate()
    }

    fun getCornersInSource(): List<PointF> {
        return corners.toList()
    }

    fun setCorners(newCorners: List<PointF>) {
        if (newCorners.size == 4) {
            corners.clear()
            newCorners.forEach { corners.add(PointF(it.x, it.y)) }
            activeCornerIndex = -1
            invalidate()
        }
    }

    fun resetToFullImage() {
        val bmp = sourceBitmap ?: return
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        corners.clear()
        corners.add(PointF(0f, 0f))
        corners.add(PointF(w, 0f))
        corners.add(PointF(w, h))
        corners.add(PointF(0f, h))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return
        if (bmp.isRecycled) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = minOf(viewW / bmp.width, viewH / bmp.height)
        val drawnW = bmp.width * scale
        val drawnH = bmp.height * scale
        val offsetX = (viewW - drawnW) / 2f
        val offsetY = (viewH - drawnH) / 2f

        displayMatrix.reset()
        displayMatrix.postScale(scale, scale)
        displayMatrix.postTranslate(offsetX, offsetY)
        displayMatrix.invert(imageMatrixInverse)

        // Draw image
        canvas.drawBitmap(bmp, displayMatrix, null)

        if (corners.size != 4) return

        // Transform corners to view coordinates
        val viewCorners = corners.map {
            PointF(it.x * scale + offsetX, it.y * scale + offsetY)
        }

        // Draw dark mask outside quad
        maskPath.reset()
        maskPath.addRect(0f, 0f, viewW, viewH, Path.Direction.CW)
        quadPath.reset()
        quadPath.moveTo(viewCorners[0].x, viewCorners[0].y)
        quadPath.lineTo(viewCorners[1].x, viewCorners[1].y)
        quadPath.lineTo(viewCorners[2].x, viewCorners[2].y)
        quadPath.lineTo(viewCorners[3].x, viewCorners[3].y)
        quadPath.close()

        canvas.save()
        canvas.clipOutPath(quadPath)
        canvas.drawPaint(maskPaint)
        canvas.restore()

        // Draw boundary stroke
        canvas.drawPath(quadPath, strokePaint)

        // Draw corner handles
        for ((idx, pt) in viewCorners.withIndex()) {
            val rOuter = if (idx == activeCornerIndex) handleOuterRadius * 1.35f else handleOuterRadius
            val rInner = if (idx == activeCornerIndex) handleInnerRadius * 1.35f else handleInnerRadius
            canvas.drawCircle(pt.x, pt.y, rOuter, handleOuterPaint)
            canvas.drawCircle(pt.x, pt.y, rInner, handleInnerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = sourceBitmap ?: return super.onTouchEvent(event)
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = minOf(viewW / bmp.width, viewH / bmp.height)
        val offsetX = (viewW - bmp.width * scale) / 2f
        val offsetY = (viewH - bmp.height * scale) / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = event.x
                val touchY = event.y

                var closestDist = Float.MAX_VALUE
                var closestIdx = -1
                for ((idx, corner) in corners.withIndex()) {
                    val vx = corner.x * scale + offsetX
                    val vy = corner.y * scale + offsetY
                    val dist = hypot(touchX - vx, touchY - vy)
                    if (dist < touchRadius && dist < closestDist) {
                        closestDist = dist
                        closestIdx = idx
                    }
                }

                if (closestIdx != -1) {
                    activeCornerIndex = closestIdx
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCornerIndex in 0..3) {
                    val pts = floatArrayOf(event.x, event.y)
                    imageMatrixInverse.mapPoints(pts)
                    val imgX = pts[0].coerceIn(0f, bmp.width.toFloat())
                    val imgY = pts[1].coerceIn(0f, bmp.height.toFloat())

                    corners[activeCornerIndex].x = imgX
                    corners[activeCornerIndex].y = imgY
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCornerIndex != -1) {
                    activeCornerIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
