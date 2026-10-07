package com.veilframe.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * Custom overlay canvas drawn on top of CameraX PreviewView to render live
 * quadrilateral document boundaries detected by OpenCV / DocumentScanner.
 */
class DocumentQuadOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetCorners: List<PointF>? = null
    private var currentCorners: MutableList<PointF> = mutableListOf()
    private var frameWidth: Int = 1
    private var frameHeight: Int = 1

    private val quadStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt() // Neon cyan
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val quadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2E00E5FF.toInt() // Semi-transparent cyan fill
        style = Paint.Style.FILL
    }

    private val cornerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val cornerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
    }

    private val quadPath = Path()

    fun setDetectedQuad(corners: List<PointF>?, sourceWidth: Int, sourceHeight: Int) {
        this.targetCorners = corners
        this.frameWidth = if (sourceWidth > 0) sourceWidth else 1
        this.frameHeight = if (sourceHeight > 0) sourceHeight else 1

        if (corners == null) {
            currentCorners.clear()
        } else if (currentCorners.isEmpty() || currentCorners.size != corners.size) {
            currentCorners.clear()
            corners.forEach { currentCorners.add(PointF(it.x, it.y)) }
        } else {
            // Smooth exponential moving average for jitter-free live boundary tracking
            val alpha = 0.35f
            for (i in corners.indices) {
                currentCorners[i].x = currentCorners[i].x + alpha * (corners[i].x - currentCorners[i].x)
                currentCorners[i].y = currentCorners[i].y + alpha * (corners[i].y - currentCorners[i].y)
            }
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        targetCorners = null
        currentCorners.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentCorners.size != 4) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        // Scale coordinates from camera frame space into overlay view coordinates
        val scaleX = viewW / frameWidth.toFloat()
        val scaleY = viewH / frameHeight.toFloat()

        quadPath.reset()
        val pts = currentCorners.map { PointF(it.x * scaleX, it.y * scaleY) }

        quadPath.moveTo(pts[0].x, pts[0].y)
        quadPath.lineTo(pts[1].x, pts[1].y)
        quadPath.lineTo(pts[2].x, pts[2].y)
        quadPath.lineTo(pts[3].x, pts[3].y)
        quadPath.close()

        // 1. Draw quad interior tint
        canvas.drawPath(quadPath, quadFillPaint)

        // 2. Draw outer polygon boundary
        canvas.drawPath(quadPath, quadStrokePaint)

        // 3. Draw corner handles
        val dotRadius = dpToPx(5f)
        val ringRadius = dpToPx(8f)
        for (pt in pts) {
            canvas.drawCircle(pt.x, pt.y, ringRadius, cornerRingPaint)
            canvas.drawCircle(pt.x, pt.y, dotRadius, cornerDotPaint)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
