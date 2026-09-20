package com.veilframe.app.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Interactive touch-enabled Crop Overlay View for VeilFrame Mobile Image Studio.
 * Renders a darkened semi-transparent background outside the crop selection,
 * a crisp border with rule-of-thirds grid lines, and 8 proportional touch grab handles.
 * Supports free-form and constrained aspect ratios (1:1, 4:3, 3:4, 16:9, 9:16).
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class TouchMode {
        NONE,
        MOVE,
        TOP_LEFT,
        TOP_CENTER,
        TOP_RIGHT,
        CENTER_LEFT,
        CENTER_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_CENTER,
        BOTTOM_RIGHT
    }

    // Display bounds of the underlying image (calculated from view dimensions and bitmap aspect)
    val imageBounds = RectF()

    // Current crop rectangle in view coordinates
    val cropRect = RectF()

    // Target aspect ratio (width / height), null for Free
    var targetAspectRatio: Float? = null
        private set

    // Callback fired live on any crop rectangle change
    var onCropChanged: ((cropNorm: RectF) -> Unit)? = null

    // Touch tracking
    private var touchMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val density = resources.displayMetrics.density
    private val touchRadius = 28f * density
    private val minCropSize = 40f * density
    private val handleCornerLength = 16f * density
    private val handleCornerThickness = 3.5f * density
    private val handleDotRadius = 4.5f * density

    // Rendering paints
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6366F1") // vf_primary accent
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6366F1")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private var originalBitmapWidth: Int = 0
    private var originalBitmapHeight: Int = 0

    fun setImageDimensions(bmpWidth: Int, bmpHeight: Int, initialCropNorm: RectF? = null) {
        if (bmpWidth <= 0 || bmpHeight <= 0) return
        originalBitmapWidth = bmpWidth
        originalBitmapHeight = bmpHeight
        recalculateBounds(initialCropNorm)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateBounds(null)
    }

    private fun recalculateBounds(restoreNorm: RectF?) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f || originalBitmapWidth <= 0 || originalBitmapHeight <= 0) return

        val bmpRatio = originalBitmapWidth.toFloat() / originalBitmapHeight.toFloat()
        val viewRatio = viewW / viewH

        val drawW: Float
        val drawH: Float
        if (bmpRatio > viewRatio) {
            drawW = viewW
            drawH = viewW / bmpRatio
        } else {
            drawH = viewH
            drawW = viewH * bmpRatio
        }

        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        imageBounds.set(left, top, left + drawW, top + drawH)

        if (restoreNorm != null && !restoreNorm.isEmpty) {
            cropRect.set(
                imageBounds.left + restoreNorm.left * imageBounds.width(),
                imageBounds.top + restoreNorm.top * imageBounds.height(),
                imageBounds.left + restoreNorm.right * imageBounds.width(),
                imageBounds.top + restoreNorm.bottom * imageBounds.height()
            )
        } else if (cropRect.isEmpty) {
            cropRect.set(imageBounds)
        } else {
            cropRect.left = cropRect.left.coerceIn(imageBounds.left, imageBounds.right - minCropSize)
            cropRect.top = cropRect.top.coerceIn(imageBounds.top, imageBounds.bottom - minCropSize)
            cropRect.right = cropRect.right.coerceIn(cropRect.left + minCropSize, imageBounds.right)
            cropRect.bottom = cropRect.bottom.coerceIn(cropRect.top + minCropSize, imageBounds.bottom)
        }

        targetAspectRatio?.let { ratio ->
            applyAspectRatioConstraint(ratio)
        }

        invalidate()
        onCropChanged?.invoke(getCropNormalized())
    }

    fun setCropAspect(aspect: String) {
        targetAspectRatio = when {
            aspect == "1:1" || aspect.contains("Passport", ignoreCase = true) -> 1.0f
            aspect == "4:3" -> 4f / 3f
            aspect == "3:4" -> 3f / 4f
            aspect == "16:9" -> 16f / 9f
            aspect == "9:16" -> 9f / 16f
            else -> null
        }

        if (targetAspectRatio != null) {
            applyAspectRatioConstraint(targetAspectRatio!!)
        } else {
            cropRect.set(imageBounds)
        }

        invalidate()
        onCropChanged?.invoke(getCropNormalized())
    }

    private fun applyAspectRatioConstraint(ratio: Float) {
        if (imageBounds.isEmpty) return
        val imgW = imageBounds.width()
        val imgH = imageBounds.height()

        var cropW = imgW
        var cropH = cropW / ratio
        if (cropH > imgH) {
            cropH = imgH
            cropW = cropH * ratio
        }

        val centerX = imageBounds.centerX()
        val centerY = imageBounds.centerY()
        cropRect.set(
            centerX - cropW / 2f,
            centerY - cropH / 2f,
            centerX + cropW / 2f,
            centerY + cropH / 2f
        )
    }

    fun getCropNormalized(): RectF {
        if (imageBounds.isEmpty || cropRect.isEmpty) return RectF(0f, 0f, 1f, 1f)
        val imgW = imageBounds.width()
        val imgH = imageBounds.height()

        val normL = ((cropRect.left - imageBounds.left) / imgW).coerceIn(0f, 1f)
        val normT = ((cropRect.top - imageBounds.top) / imgH).coerceIn(0f, 1f)
        val normR = ((cropRect.right - imageBounds.left) / imgW).coerceIn(normL + 0.01f, 1f)
        val normB = ((cropRect.bottom - imageBounds.top) / imgH).coerceIn(normT + 0.01f, 1f)

        return RectF(normL, normT, normR, normB)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageBounds.isEmpty || cropRect.isEmpty) return

        // 1. Draw 4 dimming rectangles outside cropRect
        // Top
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        // Bottom
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        // Left
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        // Right
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        // 2. Draw 3x3 Rule-of-Thirds Grid lines
        val oneThirdW = cropRect.width() / 3f
        val oneThirdH = cropRect.height() / 3f

        canvas.drawLine(cropRect.left + oneThirdW, cropRect.top, cropRect.left + oneThirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + oneThirdW * 2f, cropRect.top, cropRect.left + oneThirdW * 2f, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + oneThirdH, cropRect.right, cropRect.top + oneThirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + oneThirdH * 2f, cropRect.right, cropRect.top + oneThirdH * 2f, gridPaint)

        // 3. Draw Crop Border
        canvas.drawRect(cropRect, borderPaint)

        // 4. Draw 8 proportional handles (Corners as L-brackets, Edges as centered grab pills)
        drawCornerBrackets(canvas)
        drawEdgeHandles(canvas)
    }

    private fun drawCornerBrackets(canvas: Canvas) {
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val len = handleCornerLength
        val thick = handleCornerThickness

        val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = thick
            strokeCap = Paint.Cap.ROUND
        }

        // Top-Left
        canvas.drawLine(l, t, l + len, t, bracketPaint)
        canvas.drawLine(l, t, l, t + len, bracketPaint)

        // Top-Right
        canvas.drawLine(r - len, t, r, t, bracketPaint)
        canvas.drawLine(r, t, r, t + len, bracketPaint)

        // Bottom-Left
        canvas.drawLine(l, b - len, l, b, bracketPaint)
        canvas.drawLine(l, b, l + len, b, bracketPaint)

        // Bottom-Right
        canvas.drawLine(r - len, b, r, b, bracketPaint)
        canvas.drawLine(r, b - len, r, b, bracketPaint)
    }

    private fun drawEdgeHandles(canvas: Canvas) {
        val midX = cropRect.centerX()
        val midY = cropRect.centerY()

        // Top-Center
        canvas.drawCircle(midX, cropRect.top, handleDotRadius, handlePaint)
        canvas.drawCircle(midX, cropRect.top, handleDotRadius, handleBorderPaint)

        // Bottom-Center
        canvas.drawCircle(midX, cropRect.bottom, handleDotRadius, handlePaint)
        canvas.drawCircle(midX, cropRect.bottom, handleDotRadius, handleBorderPaint)

        // Center-Left
        canvas.drawCircle(cropRect.left, midY, handleDotRadius, handlePaint)
        canvas.drawCircle(cropRect.left, midY, handleDotRadius, handleBorderPaint)

        // Center-Right
        canvas.drawCircle(cropRect.right, midY, handleDotRadius, handlePaint)
        canvas.drawCircle(cropRect.right, midY, handleDotRadius, handleBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchMode = determineTouchMode(x, y)
                if (touchMode != TouchMode.NONE) {
                    lastTouchX = x
                    lastTouchY = y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode != TouchMode.NONE) {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    handleMove(dx, dy)
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    onCropChanged?.invoke(getCropNormalized())
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCropChanged?.invoke(getCropNormalized())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun determineTouchMode(x: Float, y: Float): TouchMode {
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val midX = cropRect.centerX()
        val midY = cropRect.centerY()
        val rad = touchRadius

        // Check corners first
        if (abs(x - l) <= rad && abs(y - t) <= rad) return TouchMode.TOP_LEFT
        if (abs(x - r) <= rad && abs(y - t) <= rad) return TouchMode.TOP_RIGHT
        if (abs(x - l) <= rad && abs(y - b) <= rad) return TouchMode.BOTTOM_LEFT
        if (abs(x - r) <= rad && abs(y - b) <= rad) return TouchMode.BOTTOM_RIGHT

        // Check edges
        if (abs(x - midX) <= rad && abs(y - t) <= rad) return TouchMode.TOP_CENTER
        if (abs(x - midX) <= rad && abs(y - b) <= rad) return TouchMode.BOTTOM_CENTER
        if (abs(x - l) <= rad && abs(y - midY) <= rad) return TouchMode.CENTER_LEFT
        if (abs(x - r) <= rad && abs(y - midY) <= rad) return TouchMode.CENTER_RIGHT

        // Check inside for move
        if (cropRect.contains(x, y)) return TouchMode.MOVE

        return TouchMode.NONE
    }

    private fun handleMove(dx: Float, dy: Float) {
        val ratio = targetAspectRatio

        if (touchMode == TouchMode.MOVE) {
            val clampedDx = when {
                cropRect.left + dx < imageBounds.left -> imageBounds.left - cropRect.left
                cropRect.right + dx > imageBounds.right -> imageBounds.right - cropRect.right
                else -> dx
            }
            val clampedDy = when {
                cropRect.top + dy < imageBounds.top -> imageBounds.top - cropRect.top
                cropRect.bottom + dy > imageBounds.bottom -> imageBounds.bottom - cropRect.bottom
                else -> dy
            }
            cropRect.offset(clampedDx, clampedDy)
            return
        }

        // Resizing
        var newLeft = cropRect.left
        var newTop = cropRect.top
        var newRight = cropRect.right
        var newBottom = cropRect.bottom

        when (touchMode) {
            TouchMode.TOP_LEFT -> {
                newLeft = (newLeft + dx).coerceIn(imageBounds.left, newRight - minCropSize)
                newTop = (newTop + dy).coerceIn(imageBounds.top, newBottom - minCropSize)
                if (ratio != null) {
                    val w = newRight - newLeft
                    newTop = (newBottom - w / ratio).coerceAtLeast(imageBounds.top)
                }
            }
            TouchMode.TOP_RIGHT -> {
                newRight = (newRight + dx).coerceIn(newLeft + minCropSize, imageBounds.right)
                newTop = (newTop + dy).coerceIn(imageBounds.top, newBottom - minCropSize)
                if (ratio != null) {
                    val w = newRight - newLeft
                    newTop = (newBottom - w / ratio).coerceAtLeast(imageBounds.top)
                }
            }
            TouchMode.BOTTOM_LEFT -> {
                newLeft = (newLeft + dx).coerceIn(imageBounds.left, newRight - minCropSize)
                newBottom = (newBottom + dy).coerceIn(newTop + minCropSize, imageBounds.bottom)
                if (ratio != null) {
                    val w = newRight - newLeft
                    newBottom = (newTop + w / ratio).coerceAtMost(imageBounds.bottom)
                }
            }
            TouchMode.BOTTOM_RIGHT -> {
                newRight = (newRight + dx).coerceIn(newLeft + minCropSize, imageBounds.right)
                newBottom = (newBottom + dy).coerceIn(newTop + minCropSize, imageBounds.bottom)
                if (ratio != null) {
                    val w = newRight - newLeft
                    newBottom = (newTop + w / ratio).coerceAtMost(imageBounds.bottom)
                }
            }
            TouchMode.TOP_CENTER -> {
                newTop = (newTop + dy).coerceIn(imageBounds.top, newBottom - minCropSize)
                if (ratio != null) {
                    val h = newBottom - newTop
                    val w = h * ratio
                    val mid = cropRect.centerX()
                    newLeft = (mid - w / 2f).coerceAtLeast(imageBounds.left)
                    newRight = (mid + w / 2f).coerceAtMost(imageBounds.right)
                }
            }
            TouchMode.BOTTOM_CENTER -> {
                newBottom = (newBottom + dy).coerceIn(newTop + minCropSize, imageBounds.bottom)
                if (ratio != null) {
                    val h = newBottom - newTop
                    val w = h * ratio
                    val mid = cropRect.centerX()
                    newLeft = (mid - w / 2f).coerceAtLeast(imageBounds.left)
                    newRight = (mid + w / 2f).coerceAtMost(imageBounds.right)
                }
            }
            TouchMode.CENTER_LEFT -> {
                newLeft = (newLeft + dx).coerceIn(imageBounds.left, newRight - minCropSize)
                if (ratio != null) {
                    val w = newRight - newLeft
                    val h = w / ratio
                    val mid = cropRect.centerY()
                    newTop = (mid - h / 2f).coerceAtLeast(imageBounds.top)
                    newBottom = (mid + h / 2f).coerceAtMost(imageBounds.bottom)
                }
            }
            TouchMode.CENTER_RIGHT -> {
                newRight = (newRight + dx).coerceIn(newLeft + minCropSize, imageBounds.right)
                if (ratio != null) {
                    val w = newRight - newLeft
                    val h = w / ratio
                    val mid = cropRect.centerY()
                    newTop = (mid - h / 2f).coerceAtLeast(imageBounds.top)
                    newBottom = (mid + h / 2f).coerceAtMost(imageBounds.bottom)
                }
            }
            else -> {}
        }

        cropRect.set(newLeft, newTop, newRight, newBottom)
    }
}
