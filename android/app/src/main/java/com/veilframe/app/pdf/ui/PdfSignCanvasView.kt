package com.veilframe.app.pdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.veilframe.app.R
import com.veilframe.app.pdf.operations.SignatureItemPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Full hardware-accelerated interactive canvas displaying entire multi-page PDF documents
 * with multi-signature placement, corner rotation, two-finger rotation gestures (>3° threshold),
 * position-aware SVG copy button, and save signature button.
 */
class PdfSignCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class PageInfo(
        val index: Int,
        val width: Float,
        val height: Float,
        val topY: Float
    )

    data class SignatureBox(
        val id: String = UUID.randomUUID().toString(),
        var pageIndex: Int,
        var x: Float, // PDF points relative to page top-left
        var y: Float, // PDF points relative to page top-left (top-down)
        var width: Float = 160f,
        var height: Float = 80f,
        var rotationDegrees: Float = 0f,
        val bitmap: Bitmap
    )

    private val viewScope = CoroutineScope(Dispatchers.Main + Job())
    private var renderJob: Job? = null

    // Document state
    private var sourceUri: Uri? = null
    private var tempPdfFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val pages = mutableListOf<PageInfo>()
    private var maxDocWidth = 0f
    private var totalDocHeight = 0f
    private val pageSpacing = dpToPx(16f)

    // Page render cache
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 6).coerceAtLeast(1024 * 16)
    private val pageBitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    // Signatures
    private val signatures = mutableListOf<SignatureBox>()
    var selectedBox: SignatureBox? = null
        private set
    private var pendingSignature: Bitmap? = null

    // Callbacks
    var onSaveSignatureClicked: ((Bitmap) -> Unit)? = null
    var onSignaturesCountChanged: ((Int) -> Unit)? = null

    // Viewport transform
    private var scale = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var minScale = 0.35f
    private var maxScale = 4.0f

    // Touch interaction modes
    private enum class TouchMode {
        NONE,
        PAN_CANVAS,
        DRAG_BOX,
        ROTATE_CORNER,
        TWO_FINGER_ROTATE
    }

    private var activeMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragStartBoxX = 0f
    private var dragStartBoxY = 0f

    // Corner rotate tracking
    private var rotateStartAngle = 0.0
    private var initialBoxRotation = 0f

    // Two-finger rotate tracking
    private var twoFingerStartAngle = 0.0
    private var twoFingerCumAngle = 0.0
    private val TWO_FINGER_ROTATE_THRESHOLD_DEG = 3.0

    // Auto-scroll state for drag-to-edge
    private var autoScrollSpeed = 0f
    private var isAutoScrolling = false

    // Paints
    private val backgroundPaint = Paint().apply {
        color = 0xFF18181C.toInt()
    }
    private val pageBackgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000.toInt()
        style = Paint.Style.FILL
    }
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33888888.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val cornerHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.FILL
    }
    private val cornerHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E293B.toInt() // Dark slate boxed button
        style = Paint.Style.FILL
    }
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textSize = dpToPx(11f)
    }

    // Drawables
    private val rotateIcon = ContextCompat.getDrawable(context, R.drawable.ic_rotate)?.mutate()?.apply {
        setTint(Color.WHITE)
    }
    private val copyIcon = ContextCompat.getDrawable(context, R.drawable.ic_action_copy)?.mutate()?.apply {
        setTint(Color.WHITE)
    }
    private val saveIcon = ContextCompat.getDrawable(context, R.drawable.ic_action_save)?.mutate()?.apply {
        setTint(Color.WHITE)
    }

    // Gesture Detectors
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val rendererLock = Any()

    init {
        // LAYER_TYPE_HARDWARE on a large multi-page interactive canvas causes GPU texture limit crashes
        setLayerType(LAYER_TYPE_NONE, null)
        initDetectors()
    }

    private fun initDetectors() {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (activeMode == TouchMode.TWO_FINGER_ROTATE) {
                    return true // Block zoom when in signature two-finger rotation
                }
                val prevScale = scale
                scale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val focusX = detector.focusX
                val focusY = detector.focusY
                panX = focusX - (focusX - panX) * (scale / prevScale)
                panY = focusY - (focusY - panY) * (scale / prevScale)
                clampPan()
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleSingleTap(e.x, e.y)
                return true
            }
        })
    }

    /**
     * Load PDF document into the canvas with optional initial signature to place once rendered.
     */
    fun setDocument(uri: Uri, initialSignature: Bitmap? = null) {
        sourceUri = uri
        cleanupRenderer()
        pageBitmapCache.evictAll()
        pages.clear()
        signatures.clear()
        selectedBox = null
        pendingSignature = initialSignature

        viewScope.launch(Dispatchers.IO) {
            try {
                // Ensure seekable parcel file descriptor
                val tempFile = File(context.cacheDir, "canvas_view_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }

                val loadedPages = mutableListOf<PageInfo>()
                var currentY = pageSpacing
                var maxWidth = 0f

                synchronized(rendererLock) {
                    tempPdfFile = tempFile
                    val filePfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = filePfd
                    val renderer = PdfRenderer(filePfd)
                    pdfRenderer = renderer

                    val count = renderer.pageCount
                    for (i in 0 until count) {
                        val page = renderer.openPage(i)
                        val pw = page.width.toFloat()
                        val ph = page.height.toFloat()
                        page.close()

                        loadedPages.add(PageInfo(i, pw, ph, currentY))
                        currentY += ph + pageSpacing
                        if (pw > maxWidth) maxWidth = pw
                    }
                }

                withContext(Dispatchers.Main) {
                    pages.clear()
                    pages.addAll(loadedPages)
                    maxDocWidth = maxWidth
                    totalDocHeight = currentY
                    resetViewportToFit()
                    pendingSignature?.let { bmp ->
                        if (signatures.isEmpty()) {
                            addSignature(bmp)
                        }
                        pendingSignature = null
                    }
                    invalidate()
                }
            } catch (e: Exception) {
                // Defensive
            }
        }
    }

    private fun resetViewportToFit() {
        if (width <= 0 || height <= 0 || maxDocWidth <= 0f) return
        val availableW = width - dpToPx(32f)
        scale = (availableW / maxDocWidth).coerceIn(minScale, 1.8f)
        panX = (width - maxDocWidth * scale) / 2f
        panY = dpToPx(24f)
        clampPan()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0 && maxDocWidth > 0f) {
            resetViewportToFit()
        }
    }

    private fun clampPan() {
        if (width <= 0 || height <= 0 || maxDocWidth <= 0f) return
        val scaledW = maxDocWidth * scale
        val scaledH = totalDocHeight * scale

        if (scaledW < width) {
            panX = (width - scaledW) / 2f
        } else {
            panX = panX.coerceIn(width - scaledW - dpToPx(100f), dpToPx(100f))
        }

        if (scaledH < height) {
            panY = (height - scaledH) / 2f
        } else {
            panY = panY.coerceIn(height - scaledH - dpToPx(150f), dpToPx(150f))
        }
    }

    /**
     * Add a signature box onto the canvas.
     */
    fun addSignature(bitmap: Bitmap, pageIndex: Int? = null) {
        if (pages.isEmpty()) {
            pendingSignature = bitmap
            return
        }

        // Place on requested page or currently most visible page
        val targetPageIndex = pageIndex ?: getCurrentlyVisiblePageIndex()
        val targetPage = pages.getOrNull(targetPageIndex) ?: pages.first()

        val sigW = 160f.coerceAtMost(targetPage.width * 0.6f)
        val sigH = (sigW * (bitmap.height.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f)))
            .coerceIn(40f, 120f)

        // Center on the target page
        val posX = (targetPage.width - sigW) / 2f
        val posY = (targetPage.height - sigH) / 2f

        val box = SignatureBox(
            pageIndex = targetPage.index,
            x = posX,
            y = posY,
            width = sigW,
            height = sigH,
            rotationDegrees = 0f,
            bitmap = bitmap
        )

        signatures.add(box)
        selectedBox = box
        onSignaturesCountChanged?.invoke(signatures.size)
        invalidate()
    }

    private fun getCurrentlyVisiblePageIndex(): Int {
        if (pages.isEmpty()) return 0
        val centerDocY = (-panY + height / 2f) / scale
        for (page in pages) {
            if (centerDocY in page.topY..(page.topY + page.height + pageSpacing)) {
                return page.index
            }
        }
        return 0
    }

    /**
     * Retrieve all placed signatures.
     */
    fun getAllPlacements(): List<SignatureItemPlacement> {
        return signatures.map { box ->
            SignatureItemPlacement(
                pageIndex = box.pageIndex,
                x = box.x,
                y = box.y,
                width = box.width,
                height = box.height,
                rotationDegrees = box.rotationDegrees,
                bitmap = box.bitmap
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (pages.isEmpty()) return

        // 1. Draw PDF Pages
        for (page in pages) {
            val pageLeftDoc = (maxDocWidth - page.width) / 2f
            val pageTopDoc = page.topY
            val pageRightDoc = pageLeftDoc + page.width
            val pageBottomDoc = pageTopDoc + page.height

            val screenLeft = pageLeftDoc * scale + panX
            val screenTop = pageTopDoc * scale + panY
            val screenRight = pageRightDoc * scale + panX
            val screenBottom = pageBottomDoc * scale + panY

            // Frustum culling
            if (screenBottom < 0 || screenTop > height || screenRight < 0 || screenLeft > width) {
                continue
            }

            // Drop shadow
            canvas.drawRect(
                screenLeft + dpToPx(3f),
                screenTop + dpToPx(4f),
                screenRight + dpToPx(3f),
                screenBottom + dpToPx(4f),
                pageShadowPaint
            )

            // Page Background
            canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, pageBackgroundPaint)

            // Page Bitmap
            val cached = pageBitmapCache.get(page.index)
            if (cached != null && !cached.isRecycled) {
                canvas.drawBitmap(cached, null, RectF(screenLeft, screenTop, screenRight, screenBottom), null)
            } else {
                requestPageRender(page.index)
            }

            // Border
            canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, pageBorderPaint)

            // Page number indicator below page
            val pageLabel = "Page ${page.index + 1} of ${pages.size}"
            canvas.drawText(pageLabel, screenLeft + dpToPx(8f), screenBottom + dpToPx(14f), textPaint)
        }

        // 2. Draw Signatures
        for (box in signatures) {
            drawSignatureBox(canvas, box, isSelected = (box == selectedBox))
        }
    }

    private fun drawSignatureBox(canvas: Canvas, box: SignatureBox, isSelected: Boolean) {
        val targetPage = pages.getOrNull(box.pageIndex) ?: return
        val pageLeftDoc = (maxDocWidth - targetPage.width) / 2f
        val pageTopDoc = targetPage.topY

        val docCenterX = pageLeftDoc + box.x + box.width / 2f
        val docCenterY = pageTopDoc + box.y + box.height / 2f

        val screenCenterX = docCenterX * scale + panX
        val screenCenterY = docCenterY * scale + panY
        val screenW = box.width * scale
        val screenH = box.height * scale

        canvas.save()
        canvas.translate(screenCenterX, screenCenterY)
        canvas.rotate(box.rotationDegrees)

        // Draw signature bitmap
        canvas.drawBitmap(box.bitmap, null, RectF(-screenW / 2f, -screenH / 2f, screenW / 2f, screenH / 2f), null)

        if (isSelected) {
            // Selection bounding box
            val borderPadding = dpToPx(4f)
            canvas.drawRoundRect(
                -screenW / 2f - borderPadding,
                -screenH / 2f - borderPadding,
                screenW / 2f + borderPadding,
                screenH / 2f + borderPadding,
                dpToPx(6f),
                dpToPx(6f),
                selectionBoxPaint
            )

            // Corner Rotate Handle at Top-Right
            val handleRadius = dpToPx(14f)
            val handleX = screenW / 2f + borderPadding
            val handleY = -screenH / 2f - borderPadding
            canvas.drawCircle(handleX, handleY, handleRadius, cornerHandleFillPaint)
            canvas.drawCircle(handleX, handleY, handleRadius, cornerHandleStrokePaint)

            rotateIcon?.let { icon ->
                val iconSize = dpToPx(16f).toInt()
                icon.setBounds(
                    (handleX - iconSize / 2f).toInt(),
                    (handleY - iconSize / 2f).toInt(),
                    (handleX + iconSize / 2f).toInt(),
                    (handleY + iconSize / 2f).toInt()
                )
                icon.draw(canvas)
            }
        }

        canvas.restore()
    }

    private fun requestPageRender(pageIndex: Int) {
        if (pdfRenderer == null) return
        if (renderJob?.isActive == true) return

        renderJob = viewScope.launch(Dispatchers.IO) {
            try {
                var bmp: Bitmap? = null
                synchronized(rendererLock) {
                    val r = pdfRenderer ?: return@launch
                    if (pageIndex < 0 || pageIndex >= r.pageCount) return@launch
                    val page = r.openPage(pageIndex)
                    try {
                        val renderScale = 1.6f
                        val bw = (page.width * renderScale).toInt().coerceAtLeast(1)
                        val bh = (page.height * renderScale).toInt().coerceAtLeast(1)
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp!!)
                        c.drawColor(Color.WHITE)
                        page.render(bmp!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        page.close()
                    }
                }
                bmp?.let { pageBitmapCache.put(pageIndex, it) }
                withContext(Dispatchers.Main) {
                    invalidate()
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Multi-touch 2-finger evaluation
        if (event.pointerCount >= 2 && event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
            try {
                val p0x = event.getX(0)
                val p0y = event.getY(0)
                val p1x = event.getX(1)
                val p1y = event.getY(1)

                val currentAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble()))

                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    twoFingerStartAngle = currentAngle
                    twoFingerCumAngle = 0.0
                } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    var deltaAngle = currentAngle - twoFingerStartAngle
                    if (deltaAngle > 180) deltaAngle -= 360
                    if (deltaAngle < -180) deltaAngle += 360

                    twoFingerCumAngle += deltaAngle
                    twoFingerStartAngle = currentAngle

                    // If rotating past threshold and a box is selected, rotate the box directly
                    if (selectedBox != null && (Math.abs(twoFingerCumAngle) > TWO_FINGER_ROTATE_THRESHOLD_DEG || activeMode == TouchMode.TWO_FINGER_ROTATE)) {
                        activeMode = TouchMode.TWO_FINGER_ROTATE
                        selectedBox?.let { box ->
                            box.rotationDegrees = (box.rotationDegrees + deltaAngle.toFloat()) % 360f
                        }
                        invalidate()
                        return true
                    }
                }
            } catch (_: Exception) {}
        }

        if (activeMode == TouchMode.TWO_FINGER_ROTATE) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                activeMode = TouchMode.NONE
            }
            return true
        }

        // Forward to Scale & Tap Detectors
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y

                // 1. Check if tapped on corner rotate handle of selected box
                selectedBox?.let { box ->
                    if (isTouchOnCornerRotateHandle(box, event.x, event.y)) {
                        activeMode = TouchMode.ROTATE_CORNER
                        val targetPage = pages.getOrNull(box.pageIndex) ?: return true
                        val pageLeftDoc = (maxDocWidth - targetPage.width) / 2f
                        val centerDocX = pageLeftDoc + box.x + box.width / 2f
                        val centerDocY = targetPage.topY + box.y + box.height / 2f
                        val screenCenterX = centerDocX * scale + panX
                        val screenCenterY = centerDocY * scale + panY

                        rotateStartAngle = Math.toDegrees(atan2((event.y - screenCenterY).toDouble(), (event.x - screenCenterX).toDouble()))
                        initialBoxRotation = box.rotationDegrees
                        return true
                    }
                }

                // 2. Check if tapped inside selected box body
                selectedBox?.let { box ->
                    if (isTouchInsideBox(box, event.x, event.y)) {
                        activeMode = TouchMode.DRAG_BOX
                        dragStartBoxX = box.x
                        dragStartBoxY = box.y
                        return true
                    }
                }

                // 3. Check if tapped inside any other box
                val hitBox = findBoxAt(event.x, event.y)
                if (hitBox != null) {
                    selectedBox = hitBox
                    activeMode = TouchMode.DRAG_BOX
                    dragStartBoxX = hitBox.x
                    dragStartBoxY = hitBox.y
                    invalidate()
                    return true
                }

                // 4. Otherwise, start canvas pan
                activeMode = TouchMode.PAN_CANVAS
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                when (activeMode) {
                    TouchMode.ROTATE_CORNER -> {
                        selectedBox?.let { box ->
                            val targetPage = pages.getOrNull(box.pageIndex) ?: return true
                            val pageLeftDoc = (maxDocWidth - targetPage.width) / 2f
                            val centerDocX = pageLeftDoc + box.x + box.width / 2f
                            val centerDocY = targetPage.topY + box.y + box.height / 2f
                            val screenCenterX = centerDocX * scale + panX
                            val screenCenterY = centerDocY * scale + panY

                            val currentAngle = Math.toDegrees(atan2((event.y - screenCenterY).toDouble(), (event.x - screenCenterX).toDouble()))
                            val diff = (currentAngle - rotateStartAngle).toFloat()
                            box.rotationDegrees = (initialBoxRotation + diff) % 360f
                            invalidate()
                        }
                    }
                    TouchMode.DRAG_BOX -> {
                        selectedBox?.let { box ->
                            if (pages.isEmpty()) return@let
                            val docDx = dx / scale
                            val docDy = dy / scale

                            val currentTargetPage = pages.getOrNull(box.pageIndex) ?: pages.first()
                            val currentPageLeftDoc = (maxDocWidth - currentTargetPage.width) / 2f

                            val centerDocX = currentPageLeftDoc + box.x + box.width / 2f + docDx
                            var centerDocY = currentTargetPage.topY + box.y + box.height / 2f + docDy

                            val firstPage = pages.first()
                            val lastPage = pages.last()
                            val minDocY = firstPage.topY
                            val maxDocY = lastPage.topY + lastPage.height
                            centerDocY = centerDocY.coerceIn(minDocY, maxDocY)

                            // Resolve which page contains this document Y (smooth cross-page dragging)
                            val newPageIndex = pages.indexOfLast { it.topY <= centerDocY }.coerceIn(0, pages.size - 1)
                            val newPage = pages[newPageIndex]
                            val newPageLeftDoc = (maxDocWidth - newPage.width) / 2f

                            box.pageIndex = newPageIndex
                            box.x = centerDocX - newPageLeftDoc - box.width / 2f
                            box.y = centerDocY - newPage.topY - box.height / 2f
                            box.x = box.x.coerceIn(-box.width * 0.5f, newPage.width - box.width * 0.5f)

                            invalidate()
                            checkAndTriggerAutoScroll(event.y)
                        }
                    }
                    TouchMode.PAN_CANVAS -> {
                        if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                            panX += dx
                            panY += dy
                            clampPan()
                            invalidate()
                        }
                    }
                    else -> {}
                }

                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeMode = TouchMode.NONE
                stopAutoScroll()
                return true
            }
        }

        return true
    }

    private fun handleSingleTap(screenX: Float, screenY: Float) {
        // Tapping a signature box selects it; tapping outside deselects
        val hit = findBoxAt(screenX, screenY)
        selectedBox = hit
        invalidate()
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (activeMode != TouchMode.DRAG_BOX || autoScrollSpeed == 0f || pages.isEmpty()) {
                isAutoScrolling = false
                return
            }

            val prevPanY = panY
            panY += autoScrollSpeed
            clampPan()
            val actualPanDelta = panY - prevPanY

            if (actualPanDelta != 0f) {
                selectedBox?.let { box ->
                    if (pages.isEmpty()) return@let
                    val currentTargetPage = pages.getOrNull(box.pageIndex) ?: return@let
                    val currentPageLeftDoc = (maxDocWidth - currentTargetPage.width) / 2f
                    val docDy = -actualPanDelta / scale

                    val centerDocX = currentPageLeftDoc + box.x + box.width / 2f
                    var centerDocY = currentTargetPage.topY + box.y + box.height / 2f + docDy

                    val minDocY = pages.firstOrNull()?.topY ?: 0f
                    val lastP = pages.lastOrNull()
                    val maxDocY = (lastP?.topY ?: 0f) + (lastP?.height ?: 0f)
                    centerDocY = centerDocY.coerceIn(minDocY, maxDocY)

                    val newPageIndex = pages.indexOfLast { it.topY <= centerDocY }.coerceIn(0, pages.size - 1)
                    val newPage = pages[newPageIndex]
                    val newPageLeftDoc = (maxDocWidth - newPage.width) / 2f

                    box.pageIndex = newPageIndex
                    box.x = (centerDocX - newPageLeftDoc - box.width / 2f).coerceIn(-box.width * 0.5f, newPage.width - box.width * 0.5f)
                    box.y = centerDocY - newPage.topY - box.height / 2f
                }
                invalidate()
                postOnAnimation(this)
            } else {
                isAutoScrolling = false
            }
        }
    }

    private fun checkAndTriggerAutoScroll(touchY: Float) {
        if (height <= 0) return
        val edgeMargin = dpToPx(80f)
        val maxSpeed = dpToPx(16f)

        autoScrollSpeed = when {
            touchY > height - edgeMargin -> {
                val factor = ((touchY - (height - edgeMargin)) / edgeMargin).coerceIn(0f, 1f)
                -maxSpeed * (0.35f + 0.65f * factor)
            }
            touchY < edgeMargin -> {
                val factor = ((edgeMargin - touchY) / edgeMargin).coerceIn(0f, 1f)
                maxSpeed * (0.35f + 0.65f * factor)
            }
            else -> 0f
        }

        if (autoScrollSpeed != 0f) {
            if (!isAutoScrolling) {
                isAutoScrolling = true
                postOnAnimation(autoScrollRunnable)
            }
        } else {
            stopAutoScroll()
        }
    }

    private fun stopAutoScroll() {
        autoScrollSpeed = 0f
        isAutoScrolling = false
        removeCallbacks(autoScrollRunnable)
    }

    fun copySelectedBox(): Boolean {
        val current = selectedBox ?: signatures.lastOrNull() ?: return false
        selectedBox = current
        duplicateSelectedBox()
        return true
    }

    fun reset() {
        stopAutoScroll()
        cleanupRenderer()
        pageBitmapCache.evictAll()
        pages.clear()
        signatures.clear()
        selectedBox = null
        pendingSignature = null
        sourceUri = null
        panX = 0f
        panY = 0f
        scale = 1.0f
        activeMode = TouchMode.NONE
        invalidate()
    }

    private fun duplicateSelectedBox() {
        val current = selectedBox ?: return
        val targetPage = pages.getOrNull(current.pageIndex) ?: return

        val newBox = SignatureBox(
            id = UUID.randomUUID().toString(),
            pageIndex = current.pageIndex,
            x = (current.x + 20f).coerceAtMost(targetPage.width - current.width),
            y = (current.y + 20f).coerceAtMost(targetPage.height - current.height),
            width = current.width,
            height = current.height,
            rotationDegrees = current.rotationDegrees,
            bitmap = current.bitmap
        )
        signatures.add(newBox)
        selectedBox = newBox
        onSignaturesCountChanged?.invoke(signatures.size)
        invalidate()
    }

    private fun isTouchInsideBox(box: SignatureBox, screenX: Float, screenY: Float): Boolean {
        val targetPage = pages.getOrNull(box.pageIndex) ?: return false
        val pageLeftDoc = (maxDocWidth - targetPage.width) / 2f
        val docCenterX = pageLeftDoc + box.x + box.width / 2f
        val docCenterY = targetPage.topY + box.y + box.height / 2f

        val screenCenterX = docCenterX * scale + panX
        val screenCenterY = docCenterY * scale + panY

        // Translate to box center
        val relX = screenX - screenCenterX
        val relY = screenY - screenCenterY

        // Rotate into box space
        val rad = Math.toRadians(-box.rotationDegrees.toDouble())
        val localX = (relX * cos(rad) - relY * sin(rad)).toFloat()
        val localY = (relX * sin(rad) + relY * cos(rad)).toFloat()

        val halfW = (box.width * scale) / 2f + dpToPx(8f)
        val halfH = (box.height * scale) / 2f + dpToPx(8f)

        return localX in -halfW..halfW && localY in -halfH..halfH
    }

    private fun isTouchOnCornerRotateHandle(box: SignatureBox, screenX: Float, screenY: Float): Boolean {
        val targetPage = pages.getOrNull(box.pageIndex) ?: return false
        val pageLeftDoc = (maxDocWidth - targetPage.width) / 2f
        val docCenterX = pageLeftDoc + box.x + box.width / 2f
        val docCenterY = targetPage.topY + box.y + box.height / 2f

        val screenCenterX = docCenterX * scale + panX
        val screenCenterY = docCenterY * scale + panY
        val screenW = box.width * scale
        val screenH = box.height * scale

        val borderPadding = dpToPx(4f)
        val hLocalX = screenW / 2f + borderPadding
        val hLocalY = -screenH / 2f - borderPadding

        val rad = Math.toRadians(box.rotationDegrees.toDouble())
        val handleScreenX = (screenCenterX + hLocalX * cos(rad) - hLocalY * sin(rad)).toFloat()
        val handleScreenY = (screenCenterY + hLocalX * sin(rad) + hLocalY * cos(rad)).toFloat()

        val touchDist = hypot((screenX - handleScreenX).toDouble(), (screenY - handleScreenY).toDouble()).toFloat()
        return touchDist <= dpToPx(24f) // Generous touch target
    }

    private fun findBoxAt(screenX: Float, screenY: Float): SignatureBox? {
        for (i in signatures.indices.reversed()) {
            val box = signatures[i]
            if (isTouchInsideBox(box, screenX, screenY)) {
                return box
            }
        }
        return null
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun cleanupRenderer() {
        renderJob?.cancel()
        renderJob = null
        synchronized(rendererLock) {
            try { pdfRenderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            try { tempPdfFile?.delete() } catch (_: Throwable) {}
            pdfRenderer = null
            pfd = null
            tempPdfFile = null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAutoScroll()
        cleanupRenderer()
        pageBitmapCache.evictAll()
    }
}
