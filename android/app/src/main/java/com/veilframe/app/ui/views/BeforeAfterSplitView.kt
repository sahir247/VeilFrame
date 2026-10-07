package com.veilframe.app.ui.views

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

/**
 * Interactive Before / After Split-View Comparison Slider:
 * - Dual-layer rendering: original image on the left, cutout/processed image on the right
 * - Alpha transparency checkerboard pattern rendered beneath the processed cutout
 * - Central vertical divider line with a >=48dp touch handle
 * - Interactive left/right drag-to-compare with haptic feedback at 0%, 50%, 100%
 * - Synchronized dual pan & pinch-to-zoom across both layers
 * - Spring / overshoot animated settling on reset
 */
class BeforeAfterSplitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var beforeBitmap: Bitmap? = null
    private var afterBitmap: Bitmap? = null

    enum class BackgroundMode {
        TRANSPARENT_CHECKERBOARD,
        PURE_WHITE,
        PURE_BLACK
    }

    var backgroundMode: BackgroundMode = BackgroundMode.TRANSPARENT_CHECKERBOARD
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    // Split position fraction: 0.0f (all after) to 1.0f (all before)
    var splitFraction: Float = 0.5f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                postInvalidateOnAnimation()
                contentDescription = "Comparison slider at ${(clamped * 100).toInt()}%"
            }
        }

    // Transform parameters (shared pan & zoom)
    private var scaleFactor = 1.0f
    private var focusX = 0f
    private var focusY = 0f
    private var translationX = 0f
    private var translationY = 0f

    // Rendering Paints
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dpToPx(3f)
        style = Paint.Style.STROKE
        setShadowLayer(dpToPx(4f), 0f, 0f, 0x88000000.toInt())
    }
    private val handleCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(dpToPx(6f), 0f, dpToPx(2f), 0xAA000000.toInt())
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2D68FF.toInt() // VeilFrame accent blue
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
    }
    private val handleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1B1F.toInt()
        style = Paint.Style.FILL
        textSize = dpToPx(12f)
        textAlign = Paint.Align.CENTER
    }
    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(11f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val checkerDarkPaint = Paint().apply { color = 0xFF222226.toInt() }
    private val checkerLightPaint = Paint().apply { color = 0xFF2E2E34.toInt() }

    // Touch state
    private var isDraggingDivider = false
    private val touchTargetRadius = dpToPx(28f) // >= 56dp diameter touch target
    private var lastHapticMilestone = 1 // 0 = 0%, 1 = 50%, 2 = 100%

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prevScale = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.8f, 5.0f)
            focusX = detector.focusX
            focusY = detector.focusY
            postInvalidateOnAnimation()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isDraggingDivider) {
                translationX -= distanceX
                translationY -= distanceY
                clampTranslations()
                postInvalidateOnAnimation()
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetViewAnimated()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for clear shadow layers on hardware canvas
        contentDescription = "Before and After comparison slider"
    }

    fun setBitmaps(before: Bitmap?, after: Bitmap?) {
        this.beforeBitmap = before
        this.afterBitmap = after
        resetTransform()
        splitFraction = 0.5f
        postInvalidateOnAnimation()
    }

    fun resetViewAnimated() {
        val startFraction = splitFraction
        val startScale = scaleFactor
        val startTransX = translationX
        val startTransY = translationY

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                splitFraction = startFraction + (0.5f - startFraction) * f
                scaleFactor = startScale + (1.0f - startScale) * f
                translationX = startTransX + (0f - startTransX) * f
                translationY = startTransY + (0f - startTransY) * f
                postInvalidateOnAnimation()
            }
        }
        anim.start()
    }

    private fun resetTransform() {
        scaleFactor = 1.0f
        translationX = 0f
        translationY = 0f
    }

    private fun clampTranslations() {
        val maxTransX = width * (scaleFactor - 1f) / 2f + dpToPx(100f)
        val maxTransY = height * (scaleFactor - 1f) / 2f + dpToPx(100f)
        translationX = translationX.coerceIn(-maxTransX, maxTransX)
        translationY = translationY.coerceIn(-maxTransY, maxTransY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val splitX = width * splitFraction
        val handleY = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx = event.x - splitX
                val dy = event.y - handleY
                // If user touched within the handle area or within 48dp of the divider line
                if (abs(dx) <= touchTargetRadius * 1.5f || (dx * dx + dy * dy <= touchTargetRadius * touchTargetRadius * 4)) {
                    isDraggingDivider = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingDivider) {
                    val newFraction = (event.x / width).coerceIn(0.01f, 0.99f)
                    splitFraction = newFraction

                    // Haptic feedback at milestones (0%, 50%, 100%)
                    val milestone = when {
                        newFraction < 0.08f -> 0
                        abs(newFraction - 0.5f) < 0.04f -> 1
                        newFraction > 0.92f -> 2
                        else -> -1
                    }
                    if (milestone != -1 && milestone != lastHapticMilestone) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastHapticMilestone = milestone
                    } else if (milestone == -1) {
                        lastHapticMilestone = -1
                    }

                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingDivider) {
                    isDraggingDivider = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    postInvalidateOnAnimation()
                    return true
                }
            }
        }

        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val splitX = w * splitFraction

        // Compute destination bounds maintaining aspect ratio
        val refBmp = beforeBitmap ?: afterBitmap
        if (refBmp != null) {
            val bmpW = refBmp.width.toFloat()
            val bmpH = refBmp.height.toFloat()
            val scale = (w / bmpW).coerceAtMost(h / bmpH) * scaleFactor
            val drawW = bmpW * scale
            val drawH = bmpH * scale
            val left = (w - drawW) / 2f + translationX
            val top = (h - drawH) / 2f + translationY
            val dstRect = RectF(left, top, left + drawW, top + drawH)

            // 1. Draw Checkerboard background for transparent areas on the After side (splitX to w)
            canvas.save()
            canvas.clipRect(splitX, 0f, w, h)
            drawCheckerboard(canvas, dstRect)
            canvas.restore()

            // 2. Draw "Before" image clipped to [0, 0, splitX, h]
            if (beforeBitmap != null && splitX > 0f) {
                canvas.save()
                canvas.clipRect(0f, 0f, splitX, h)
                canvas.drawBitmap(beforeBitmap!!, null, dstRect, bitmapPaint)
                canvas.restore()
            }

            // 3. Draw "After" image clipped to [splitX, 0, w, h]
            if (afterBitmap != null && splitX < w) {
                canvas.save()
                canvas.clipRect(splitX, 0f, w, h)
                canvas.drawBitmap(afterBitmap!!, null, dstRect, bitmapPaint)
                canvas.restore()
            }
        }

        // 4. Draw Vertical Divider Line
        canvas.drawLine(splitX, 0f, splitX, h, linePaint)

        // 5. Draw Circular Drag Handle at (splitX, h / 2)
        val handleY = h / 2f
        val handleRadius = dpToPx(20f)
        canvas.drawCircle(splitX, handleY, handleRadius, handleCirclePaint)
        canvas.drawCircle(splitX, handleY, handleRadius, handleBorderPaint)

        // Draw left/right arrows inside handle "◀ ▶"
        val fontMetrics = handleIconPaint.fontMetrics
        val textBaseline = handleY - (fontMetrics.descent + fontMetrics.ascent) / 2f
        canvas.drawText("◀ ▶", splitX, textBaseline, handleIconPaint)

        // 6. Draw Subtle Floating Badges ("BEFORE" on left, "AFTER" on right)
        drawBadge(canvas, "ORIGINAL", dpToPx(16f), dpToPx(24f))
        drawBadge(canvas, "CUTOUT", w - dpToPx(76f), dpToPx(24f))
    }

    private fun drawCheckerboard(canvas: Canvas, bounds: RectF) {
        val startX = bounds.left.coerceAtLeast(0f)
        val endX = bounds.right.coerceAtMost(width.toFloat())
        val startY = bounds.top.coerceAtLeast(0f)
        val endY = bounds.bottom.coerceAtMost(height.toFloat())

        when (backgroundMode) {
            BackgroundMode.PURE_WHITE -> {
                canvas.drawRect(startX, startY, endX, endY, Paint().apply { color = Color.WHITE })
                return
            }
            BackgroundMode.PURE_BLACK -> {
                canvas.drawRect(startX, startY, endX, endY, Paint().apply { color = Color.BLACK })
                return
            }
            BackgroundMode.TRANSPARENT_CHECKERBOARD -> {
                // proceed with checkerboard
            }
        }

        val checkSize = dpToPx(10f)

        var y = startY
        var row = 0
        while (y < endY) {
            var x = startX
            var col = row % 2
            while (x < endX) {
                val p = if (col % 2 == 0) checkerDarkPaint else checkerLightPaint
                canvas.drawRect(x, y, (x + checkSize).coerceAtMost(endX), (y + checkSize).coerceAtMost(endY), p)
                x += checkSize
                col++
            }
            y += checkSize
            row++
        }
    }

    private fun drawBadge(canvas: Canvas, text: String, x: Float, y: Float) {
        val badgeW = dpToPx(60f)
        val badgeH = dpToPx(22f)
        val rect = RectF(x, y, x + badgeW, y + badgeH)
        canvas.drawRoundRect(rect, dpToPx(11f), dpToPx(11f), badgeBackgroundPaint)

        val fontMetrics = badgeTextPaint.fontMetrics
        val textY = rect.centerY() - (fontMetrics.descent + fontMetrics.ascent) / 2f
        canvas.drawText(text, rect.centerX(), textY, badgeTextPaint)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
