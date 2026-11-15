package com.veilframe.app.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive 60 FPS Canvas View for manual selective photo blurring with Brush & Eraser modes.
 * Blends [sourceBitmap] and [blurredBitmap] through a high-precision mask bitmap with zoom & pan.
 */
class BlurBrushCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        BLUR,  // Paint blur onto photo
        ERASE  // Erase painted blur to restore original sharpness
    }

    var mode: Mode = Mode.BLUR
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var brushRadiusPx: Float = 35f
        set(value) {
            field = value.coerceIn(5f, 250f)
            updatePaints()
            invalidate()
        }

    var isPanZoomLocked: Boolean = false

    private var sourceBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    // Undo / Redo history stacks (stores mask snapshots)
    private val undoStack = mutableListOf<Bitmap>()
    private val redoStack = mutableListOf<Bitmap>()
    private val maxHistory = 10

    // Canvas matrix & transform state
    private var scaleFactor = 1.0f
    private var transX = 0f
    private var transY = 0f
    private val minScale = 0.5f
    private val maxScale = 10.0f

    // Live brush touch tracking
    private var currentTouchPoint: PointF? = null
    private var isTouching = false

    // Paints
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val maskFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val compositePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskedLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val cursorOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f
    }
    private val cursorInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xAA000000.toInt()
        strokeWidth = 1.5f
    }

    private val currentPath = Path()
    private var lastX = 0f
    private var lastY = 0f

    // Gesture detectors
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            val newScale = (scaleFactor * scale).coerceIn(minScale, maxScale)
            val focusX = detector.focusX
            val focusY = detector.focusY

            transX = focusX - (focusX - transX) * (newScale / scaleFactor)
            transY = focusY - (focusY - transY) * (newScale / scaleFactor)
            scaleFactor = newScale
            clampTranslation()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isPanZoomLocked || e2.pointerCount > 1) {
                transX -= distanceX
                transY -= distanceY
                clampTranslation()
                invalidate()
                return true
            }
            return false
        }
    })

    init {
        updatePaints()
    }

    private fun updatePaints() {
        val strokeW = brushRadiusPx * 2f
        maskPaint.strokeWidth = strokeW
        if (mode == Mode.BLUR) {
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            maskPaint.color = Color.WHITE
            maskFillPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            maskFillPaint.color = Color.WHITE
        } else {
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            maskPaint.color = Color.TRANSPARENT
            maskFillPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            maskFillPaint.color = Color.TRANSPARENT
        }
    }

    fun setBitmaps(source: Bitmap, blurred: Bitmap, initialMask: Bitmap? = null) {
        sourceBitmap = source
        blurredBitmap = blurred

        val w = source.width
        val h = source.height
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(mask)

        if (initialMask != null) {
            val scaled = if (initialMask.width != w || initialMask.height != h) {
                Bitmap.createScaledBitmap(initialMask, w, h, true)
            } else initialMask
            maskCanvas?.drawBitmap(scaled, 0f, 0f, null)
            if (scaled != initialMask) scaled.recycle()
        }

        maskBitmap = mask
        undoStack.clear()
        redoStack.clear()
        resetZoom()
        invalidate()
    }

    fun getMaskBitmap(): Bitmap? {
        val m = maskBitmap ?: return null
        return Bitmap.createBitmap(m)
    }

    fun updateBlurredBitmap(newBlurred: Bitmap) {
        blurredBitmap = newBlurred
        invalidate()
    }

    fun resetZoom() {
        if (width <= 0 || height <= 0 || sourceBitmap == null) return
        val bmp = sourceBitmap!!
        val scaleX = width.toFloat() / bmp.width.toFloat()
        val scaleY = height.toFloat() / bmp.height.toFloat()
        scaleFactor = min(scaleX, scaleY) * 0.95f
        transX = (width - bmp.width * scaleFactor) / 2f
        transY = (height - bmp.height * scaleFactor) / 2f
        invalidate()
    }

    private fun clampTranslation() {
        val bmp = sourceBitmap ?: return
        val contentW = bmp.width * scaleFactor
        val contentH = bmp.height * scaleFactor
        val padX = width * 0.5f
        val padY = height * 0.5f

        transX = transX.coerceIn(-contentW + padX, width - padX)
        transY = transY.coerceIn(-contentH + padY, height - padY)
    }

    private fun screenToBitmap(sx: Float, sy: Float): PointF {
        val bx = (sx - transX) / scaleFactor
        val by = (sy - transY) / scaleFactor
        return PointF(bx, by)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = sourceBitmap ?: return
        val blr = blurredBitmap ?: return
        val msk = maskBitmap ?: return

        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scaleFactor, scaleFactor)

        // 1. Draw base sharp source
        canvas.drawBitmap(src, 0f, 0f, compositePaint)

        // 2. Draw blurred overlay through mask
        val sc = canvas.saveLayer(0f, 0f, src.width.toFloat(), src.height.toFloat(), null)
        canvas.drawBitmap(blr, 0f, 0f, compositePaint)
        canvas.drawBitmap(msk, 0f, 0f, maskedLayerPaint)
        canvas.restoreToCount(sc)

        // 3. Draw live brush cursor
        val touch = currentTouchPoint
        if (isTouching && touch != null) {
            val r = brushRadiusPx
            canvas.drawCircle(touch.x, touch.y, r, cursorOuterPaint)
            canvas.drawCircle(touch.x, touch.y, r, cursorInnerPaint)
        }

        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.pointerCount > 1 || isPanZoomLocked) {
            if (isTouching) {
                isTouching = false
                currentTouchPoint = null
                currentPath.reset()
                invalidate()
            }
            return true
        }

        val pt = screenToBitmap(event.x, event.y)
        currentTouchPoint = pt

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                saveUndoState()
                redoStack.clear()
                isTouching = true
                lastX = pt.x
                lastY = pt.y
                currentPath.reset()
                currentPath.moveTo(pt.x, pt.y)
                // Also draw point dab
                maskCanvas?.drawCircle(pt.x, pt.y, brushRadiusPx, maskFillPaint)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                isTouching = true
                val dx = kotlin.math.abs(pt.x - lastX)
                val dy = kotlin.math.abs(pt.y - lastY)
                if (dx >= 2 || dy >= 2) {
                    currentPath.quadTo(lastX, lastY, (pt.x + lastX) / 2f, (pt.y + lastY) / 2f)
                    maskCanvas?.drawPath(currentPath, maskPaint)
                    currentPath.reset()
                    currentPath.moveTo((pt.x + lastX) / 2f, (pt.y + lastY) / 2f)
                    lastX = pt.x
                    lastY = pt.y
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                currentTouchPoint = null
                currentPath.reset()
                invalidate()
            }
        }
        return true
    }

    private fun saveUndoState() {
        val m = maskBitmap ?: return
        if (undoStack.size >= maxHistory) {
            val recycled = undoStack.removeAt(0)
            if (!recycled.isRecycled) recycled.recycle()
        }
        undoStack.add(Bitmap.createBitmap(m))
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (!canUndo()) return
        val current = maskBitmap ?: return
        redoStack.add(Bitmap.createBitmap(current))

        val prev = undoStack.removeAt(undoStack.size - 1)
        maskCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        maskCanvas?.drawBitmap(prev, 0f, 0f, null)
        prev.recycle()
        invalidate()
    }

    fun redo() {
        if (!canRedo()) return
        val current = maskBitmap ?: return
        undoStack.add(Bitmap.createBitmap(current))

        val next = redoStack.removeAt(redoStack.size - 1)
        maskCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        maskCanvas?.drawBitmap(next, 0f, 0f, null)
        next.recycle()
        invalidate()
    }

    fun clearMask() {
        saveUndoState()
        redoStack.clear()
        maskCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
    }
}
