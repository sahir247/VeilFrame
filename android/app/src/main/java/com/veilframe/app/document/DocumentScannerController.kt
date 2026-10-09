package com.veilframe.app.document

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.veilframe.app.R
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.document.DocumentScanner
import com.veilframe.app.cv.document.QuadStabilizer
import com.veilframe.app.databinding.LayoutDocumentScannerBinding
import com.veilframe.app.databinding.SheetDocumentExportBinding
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.ui.motion.VeilFrameInteraction
import com.veilframe.app.cv.document.FrameBufferPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live document scanning output emitted from decoupled background analyzer.
 */
data class ScanResult(
    val corners: List<Point>?,
    val quality: QualityMetrics = QualityMetrics.DEFAULT,
    val frameId: Long = 0L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val stabilizerState: QuadStabilizer.State = QuadStabilizer.State.SEARCHING,
)

/**
 * Quality indicators for the live viewfinder quality gate (sharpness, glare).
 */
data class QualityMetrics(
    val sharpnessScore: Double = 0.0,
    val isSharp: Boolean = true,
    val glarePercentage: Double = 0.0,
    val hasGlare: Boolean = false,
    val isReadyForCapture: Boolean = true,
    val statusMessage: String = "",
) {
    companion object {
        val DEFAULT = QualityMetrics()
    }
}

/**
 * Controller orchestrating the dedicated Document Scanner workspace:
 * - Dedicated CameraX viewfinder with real-time OpenCV quadrilateral tracking
 * - Pinch-to-zoom, zoom preset buttons, tap-to-focus with animated indicator, and lens flipping
 * - Rapid continuous multi-capture (burst scanning into session)
 * - Persistent Page Manager with visual horizontal thumbnail strip (reorder, rotate, duplicate, auto-crop, delete)
 * - Multi-format export pipeline (Single PDF, Separate PDFs, Image Sequence, Custom Paper Sizes & Margins)
 */
class DocumentScannerController(
    private val activity: AppCompatActivity,
    private val binding: LayoutDocumentScannerBinding,
    private val safStorageManager: SafStorageManager,
    private val scope: CoroutineScope,
    private val onTakePhotoRequest: () -> Unit,
    private val onChoosePhotosRequest: () -> Unit,
    private val onExportFileRequest: (File, String) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    val session = DocumentSession()

    // Decoupled live analyzer flow: 60fps UI observation without GC pressure
    private val _scannerState = MutableSharedFlow<ScanResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val scannerState: SharedFlow<ScanResult> = _scannerState.asSharedFlow()

    private val isProcessing = AtomicBoolean(false)
    private var bufferPool: FrameBufferPool? = null
    private var frameIdCounter = 0L
    private var scannerJob: Job? = null

    // CameraX runtime
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isBackCamera = true
    private var currentZoomRatio = 1.0f
    private var scaleGestureDetector: ScaleGestureDetector? = null
    // App-scoped shared executor (CV-7): per-controller executors leaked a
    // non-daemon thread on every activity recreate (e.g. theme change).
    private val cameraExecutor: java.util.concurrent.ExecutorService =
        com.veilframe.app.cv.core.CvRuntime.cameraExecutor
    private var isSyncingFilterChips = false

    @SuppressLint("ClickableViewAccessibility")
    fun init() {
        // B4: thermal awareness for the sustained viewfinder pipeline.
        com.veilframe.app.runtime.ThermalGovernor.register(activity)

        binding.toolbarDocScanner.setNavigationOnClickListener {
            onNavigateBack()
        }

        // Empty state buttons
        binding.btnDocEmptyCamera.setOnClickListener {
            openCameraViewfinder()
        }

        binding.btnDocEmptyImport.setOnClickListener {
            onChoosePhotosRequest()
        }

        // Bottom action dock buttons
        binding.btnDocAddCamera.setOnClickListener {
            openCameraViewfinder()
        }

        binding.btnDocAddPhotos.setOnClickListener {
            onChoosePhotosRequest()
        }

        binding.btnDocAutoDetect.setOnClickListener {
            runAutoCropOnCurrentPage()
        }

        binding.btnDocExportPdf.setOnClickListener {
            showExportOptionsDialog()
        }

        // Page manager strip actions
        binding.btnDocPageMoveLeft.setOnClickListener {
            val curr = session.activePageIndex
            if (curr > 0) {
                session.movePage(curr, curr - 1)
                persistSession()
                updateUi()
            }
        }

        binding.btnDocPageMoveRight.setOnClickListener {
            val curr = session.activePageIndex
            if (curr < session.pageCount - 1) {
                session.movePage(curr, curr + 1)
                persistSession()
                updateUi()
            }
        }

        binding.btnDocPageRotate.setOnClickListener {
            session.rotatePage(session.activePageIndex, 90, activity)
            renderCurrentPage()
        }

        binding.btnDocPageAdjust.setOnClickListener {
            showPageAdjustDialog()
        }

        binding.btnDocPageDuplicate.setOnClickListener {
            session.duplicatePage(session.activePageIndex, activity)
            persistSession()
            updateUi()
            Toast.makeText(activity, "Page duplicated", Toast.LENGTH_SHORT).show()
        }

        binding.btnDocPageResetCrop.setOnClickListener {
            resetCropOnCurrentPage()
        }

        binding.btnDocPageDelete.setOnClickListener {
            if (!session.isEmpty) {
                session.removePage(session.activePageIndex)
                persistSession()
                updateUi()
                Toast.makeText(activity, "Page deleted", Toast.LENGTH_SHORT).show()
            }
        }

        // Camera overlay controls
        binding.btnDocCamClose.setOnClickListener {
            closeCameraViewfinder()
        }

        binding.btnDocCamFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnDocCamFlip.setOnClickListener {
            isBackCamera = !isBackCamera
            isFlashOn = false
            binding.btnDocCamFlash.setIconResource(R.drawable.ic_flash_off)
            bindCameraUseCases()
        }

        binding.btnDocCamImport.setOnClickListener {
            onChoosePhotosRequest()
        }

        binding.btnDocCamShutter.setOnClickListener {
            takeCameraScan()
        }

        binding.btnDocCamDone.setOnClickListener {
            closeCameraViewfinder()
        }

        // Camera zoom presets
        binding.btnDocZoomHalf.setOnClickListener { setCameraZoom(0.5f) }
        binding.btnDocZoom1x.setOnClickListener { setCameraZoom(1.0f) }
        binding.btnDocZoom2x.setOnClickListener { setCameraZoom(2.0f) }

        // Camera pinch-to-zoom & tap-to-focus gesture detectors
        scaleGestureDetector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoomState = cam.cameraInfo.zoomState.value ?: return false
                val nextZoom = (zoomState.zoomRatio * detector.scaleFactor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                cam.cameraControl.setZoomRatio(nextZoom)
                currentZoomRatio = nextZoom
                updateZoomButtons(nextZoom)
                return true
            }
        })

        binding.docCameraPreviewView.setOnTouchListener { v, event ->
            scaleGestureDetector?.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && scaleGestureDetector?.isInProgress != true) {
                handleTapToFocus(event.x, event.y)
                v.performClick()
            }
            true
        }

        // Filter chips
        binding.chipGroupDocFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isSyncingFilterChips) return@setOnCheckedStateChangeListener
            if (checkedIds.isNotEmpty()) {
                val mode = when (checkedIds[0]) {
                    R.id.chipFilterOriginal -> DocumentScanner.DocumentMode.ORIGINAL
                    R.id.chipFilterEnhanced -> DocumentScanner.DocumentMode.ENHANCED
                    R.id.chipFilterBw -> DocumentScanner.DocumentMode.BLACK_AND_WHITE
                    R.id.chipFilterGrayscale -> DocumentScanner.DocumentMode.GRAYSCALE
                    R.id.chipFilterReceipt -> DocumentScanner.DocumentMode.RECEIPT
                    R.id.chipFilterIdDoc -> DocumentScanner.DocumentMode.ID_DOCUMENT
                    else -> DocumentScanner.DocumentMode.ENHANCED
                }
                applyFilterToCurrentPage(mode)
            }
        }

        // Universal interaction tactile bounce
        VeilFrameInteraction.bindWorkspace(binding.root)

        updateUi()
    }

    // =========================================================================
    // CAMERA-X VIEWFINDER & LIVE QUAD TRACKING
    // =========================================================================

    fun openCameraViewfinder() {
        binding.containerDocMainFlow.visibility = View.GONE
        binding.containerDocCamera.visibility = View.VISIBLE
        updateCameraDoneBadge()

        startScannerStateCollection()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun closeCameraViewfinder() {
        stopScannerStateCollection()
        quadStabilizer.reset()
        lastStabState = QuadStabilizer.State.SEARCHING
        isCapturing = false
        isProcessing.set(false)
        bufferPool?.release()
        bufferPool = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        binding.docQuadOverlayView.clear()
        binding.containerDocCamera.visibility = View.GONE
        binding.containerDocMainFlow.visibility = View.VISIBLE
        updateUi()
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = if (isBackCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.docCameraPreviewView.surfaceProvider)
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processFrameAsync(imageProxy)
        }

        val viewPort = binding.docCameraPreviewView.viewPort
        val useCaseGroupBuilder = androidx.camera.core.UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(imageAnalysis)
        if (viewPort != null) {
            useCaseGroupBuilder.setViewPort(viewPort)
        }
        val useCaseGroup = useCaseGroupBuilder.build()

        try {
            camera = provider.bindToLifecycle(
                activity,
                cameraSelector,
                useCaseGroup
            )
            setCameraZoom(currentZoomRatio)
        } catch (e: Exception) {
            try {
                camera = provider.bindToLifecycle(
                    activity,
                    cameraSelector,
                    preview,
                    capture,
                    imageAnalysis
                )
                setCameraZoom(currentZoomRatio)
            } catch (fallbackEx: Exception) {
                Toast.makeText(activity, "Camera binding error: ${fallbackEx.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setCameraZoom(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        currentZoomRatio = clamped
        updateZoomButtons(clamped)
    }

    private fun updateZoomButtons(ratio: Float) {
        binding.btnDocZoomHalf.setTextColor(if (ratio < 0.8f) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_text_secondary))
        binding.btnDocZoom1x.setTextColor(if (ratio in 0.8f..1.5f) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_text_secondary))
        binding.btnDocZoom2x.setTextColor(if (ratio > 1.5f) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_text_secondary))
    }

    private fun handleTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = binding.docCameraPreviewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)

        // Show animated focus ring centered at touch coordinate
        binding.ivDocCamFocusRing.apply {
            this.x = x - (width / 2f)
            this.y = y - (height / 2f)
            alpha = 1.0f
            scaleX = 1.3f
            scaleY = 1.3f
            visibility = View.VISIBLE
            animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(0.0f)
                .setDuration(600)
                .withEndAction { visibility = View.GONE }
                .start()
        }
    }

    private fun startScannerStateCollection() {
        scannerJob?.cancel()
        scannerJob = scope.launch(Dispatchers.Main) {
            scannerState.collect { result ->
                binding.docQuadOverlayView.setCorners(
                    corners = result.corners,
                    sourceWidth = result.frameWidth,
                    sourceHeight = result.frameHeight,
                    frameId = result.frameId,
                )
                binding.tvDocCamStatus.text = when (result.stabilizerState) {
                    QuadStabilizer.State.STABLE -> "Ready — tap shutter"
                    QuadStabilizer.State.TRACKING -> "Document detected — hold still"
                    QuadStabilizer.State.SEARCHING -> "Align document inside frame"
                }
            }
        }
    }

    private fun stopScannerStateCollection() {
        scannerJob?.cancel()
        scannerJob = null
    }

    private fun obtainBufferPool(width: Int, height: Int, rotation: Int): FrameBufferPool {
        val current = bufferPool
        if (current != null && current.matches(width, height, rotation)) {
            return current
        }
        current?.release()
        val newPool = FrameBufferPool(width, height, rotation)
        bufferPool = newPool
        return newPool
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processFrameAsync(imageProxy: ImageProxy) {
        // Fast bail-out when the CV engine is unavailable: never burn frames
        // converting JPEGs just to swallow UnsatisfiedLinkError per frame (CV-6).
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            imageProxy.close()
            return
        }

        // Phase 2: freeze analysis while a capture is in flight (shared
        // single-thread executor + full-res JPEG work owns the lane).
        if (isCapturing) {
            imageProxy.close()
            return
        }

        // B4: thermal governor — drop every other frame when the device is
        // throttled, stop analysis entirely when critical (battery + heat).
        if (com.veilframe.app.runtime.ThermalGovernor.isCritical) {
            imageProxy.close()
            return
        }
        if (com.veilframe.app.runtime.ThermalGovernor.isThrottled) {
            frameSeq++
            if (frameSeq % 2L == 1L) {
                imageProxy.close()
                return
            }
        }

        // Concurrency gate: If background thread is currently crunching a frame,
        // drop this frame immediately to maintain a non-blocking 60fps CameraX stream
        // without backpressure queue accumulation.
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height

        // Pre-allocated zero-allocation buffer pool
        val pool = obtainBufferPool(width, height, rotation)

        // --- PHASE A: EXTRACTION (Fast, on CameraX thread) ---
        val analysisMat = try {
            pool.extractFrame(mediaImage)
        } catch (t: Throwable) {
            android.util.Log.w("VeilFrame.DocScanner", "Frame extraction failed", t)
            null
        }

        val currentFrameId = frameIdCounter++

        // CLOSE PROXY IMMEDIATELY to release the camera hardware buffer!
        imageProxy.close()

        if (analysisMat == null || analysisMat.empty()) {
            isProcessing.set(false)
            return
        }

        // --- PHASE B: PROCESSING (Heavy, on Background Thread) ---
        scope.launch(Dispatchers.Default) {
            try {
                ensureActive()

                // Run heavy OpenCV pipeline using ONLY pooled Mats
                val corners = DocumentScanner.findCorners(
                    source = pool.analysisMat ?: return@launch,
                    blurredMat = pool.blurredMat,
                    edgesMat = pool.edgesMat,
                )

                val frameW = pool.analysisMat?.cols() ?: 1
                val frameH = pool.analysisMat?.rows() ?: 1

                // Phase 3: EMA-smoothed quad with hysteresis — the overlay stops
                // flickering and "Ready" requires consecutive matched frames.
                val stable = quadStabilizer.update(if (corners.size == 4) corners else null)
                val stabState = quadStabilizer.state
                val becameStable = stabState == QuadStabilizer.State.STABLE &&
                    lastStabState != QuadStabilizer.State.STABLE
                lastStabState = stabState

                if (becameStable) {
                    withContext(Dispatchers.Main) {
                        binding.docQuadOverlayView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                _scannerState.emit(
                    ScanResult(
                        corners = stable,
                        quality = QualityMetrics.DEFAULT,
                        frameId = currentFrameId,
                        frameWidth = frameW,
                        frameHeight = frameH,
                        stabilizerState = stabState,
                    )
                )
            } catch (t: Throwable) {
                if (System.currentTimeMillis() - lastFrameErrorLogMs > 5_000L) {
                    lastFrameErrorLogMs = System.currentTimeMillis()
                    android.util.Log.w("VeilFrame.DocScanner", "Frame analysis failed", t)
                }
                withContext(Dispatchers.Main) {
                    binding.docQuadOverlayView.clear()
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    @Volatile
    private var lastFrameErrorLogMs = 0L

    private var frameSeq = 0L

    // Phase 3: temporal stabilization of the live quad (anti-flicker +
    // hit-count hysteresis before "Ready").
    private val quadStabilizer = QuadStabilizer()
    private var lastStabState = QuadStabilizer.State.SEARCHING

    // Phase 2: capture discipline — analysis pauses during capture so the
    // shared single-thread executor is never double-booked, and teardown
    // never races an in-flight shutter.
    @Volatile
    private var isCapturing = false

    private fun toggleFlash() {
        val cam = camera ?: return
        isFlashOn = !isFlashOn
        cam.cameraControl.enableTorch(isFlashOn)
        binding.btnDocCamFlash.setIconResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun takeCameraScan() {
        val capture = imageCapture ?: return
        binding.btnDocCamShutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        isCapturing = true

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        imageProxyToBitmap(image)
                    } finally {
                        image.close()
                        isCapturing = false
                    }

                    if (bitmap != null) {
                        activity.runOnUiThread {
                            handleCameraPhotoCaptured(bitmap)
                            updateCameraDoneBadge()
                            Toast.makeText(activity, "Page ${session.pageCount} captured", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Could not process captured frame — try again", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    android.util.Log.w("VeilFrame.DocScanner", "Capture failed", exception)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun updateCameraDoneBadge() {
        binding.btnDocCamDone.text = "Done (${session.pageCount})"
    }

    // =========================================================================
    // PAGE MANAGEMENT & PHOTO IMPORT
    // =========================================================================

    fun handleCameraPhotoCaptured(bitmap: Bitmap) {
        val page = ScannedPage(
            originalBitmapCache = bitmap,
            processedBitmapCache = bitmap
        )
        session.addPage(page)
        persistSession()
        updateUi()
        runAutoCropOnCurrentPage()
    }

    fun handlePhotosImported(uris: List<Uri>) {
        if (uris.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val loadedPages = mutableListOf<ScannedPage>()
            for (uri in uris) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            loadedPages.add(
                                ScannedPage(
                                    sourceUri = uri,
                                    originalBitmapCache = bmp,
                                    processedBitmapCache = bmp
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                session.addPages(loadedPages)
                persistSession()
                updateUi()
                updateCameraDoneBadge()
                if (loadedPages.isNotEmpty()) {
                    runAutoCropOnCurrentPage()
                }
            }
        }
    }

    fun resumeSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            val loaded = DocumentSession.loadFromDisk(activity, sessionId)
            withContext(Dispatchers.Main) {
                if (loaded != null && !loaded.isEmpty) {
                    session.replaceAllPages(loaded.pages)
                    session.id = loaded.id
                    session.title = loaded.title
                    updateUi()
                    Toast.makeText(activity, "Resumed document with ${session.pageCount} pages", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderCurrentPage(onComplete: (() -> Unit)? = null) {
        val page = session.currentPage ?: return
        val origBmp = page.getOriginalBitmap(activity) ?: return
        val targetVersion = ++page.editVersion
        val pageId = page.id

        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(origBmp.width, origBmp.height, 3)
        scope.launch {
            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-render-page",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L,
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                page.render(activity, ctx)
            }

            job.await().fold(
                onOk = { renderedBmp ->
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        page.processedBitmapCache = renderedBmp
                        persistSession()
                        updateUi()
                        onComplete?.invoke()
                    }
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "Render failed for page $pageId: ${err.message}")
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        updateUi()
                    }
                }
            )
        }
    }

    private fun runAutoCropOnCurrentPage() {
        val page = session.currentPage ?: return
        val origBmp = page.getOriginalBitmap(activity) ?: return
        val targetVersion = ++page.editVersion
        val pageId = page.id

        // A2: governed execution — INTERACTIVE lane + memory admission.
        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(origBmp.width, origBmp.height, 3)
        scope.launch {
            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-auto-crop",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L, // B1 watchdog
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                val srcMat = BitmapBridge.toMat(origBmp)
                try {
                    val detection = com.veilframe.app.cv.geometry.QuadDetector.detect(srcMat, context = ctx)
                    val corners = detection?.corners ?: emptyList()
                    // Sanity check: require 4 corners and at least 10% document coverage to avoid false sliver crop
                    if (corners.size != 4 || (detection != null && detection.coverage < 0.10)) {
                        null
                    } else {
                        corners
                    }
                } finally {
                    srcMat.release() // CV-4: released on every path
                }
            }

            job.await().fold(
                onOk = { detectedCorners ->
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        if (detectedCorners != null && detectedCorners.size == 4) {
                            page.corners = detectedCorners
                        } else {
                            page.corners = null
                            Toast.makeText(activity, "Document boundaries uncertain — full photo retained", Toast.LENGTH_SHORT).show()
                        }
                        renderCurrentPage()
                    }
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "Auto-crop failed for page ${page.id} [${err.code}]: ${err.message}")
                    Toast.makeText(activity, "Auto-crop unavailable — page kept as captured", Toast.LENGTH_SHORT).show()
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        renderCurrentPage()
                    }
                }
            )
        }
    }

    private fun resetCropOnCurrentPage() {
        val page = session.currentPage ?: return
        page.corners = null
        renderCurrentPage {
            Toast.makeText(activity, "Reset to full photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFilterToCurrentPage(mode: DocumentScanner.DocumentMode) {
        val page = session.currentPage ?: return
        if (page.mode == mode) return
        page.mode = mode
        renderCurrentPage()
    }

    /**
     * v2.3.0: per-page Adjust (exposure/contrast/saturation) — wires dormant ColorEngine.
     */
    private fun showPageAdjustDialog() {
        val page = session.currentPage ?: run {
            Toast.makeText(activity, "No page selected", Toast.LENGTH_SHORT).show()
            return
        }
        val dlgBinding = com.veilframe.app.databinding.DialogPageAdjustBinding
            .inflate(activity.layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            activity,
            com.veilframe.app.R.style.ThemeOverlay_VeilFrame_MaterialAlertDialog
        )
            .setTitle("Adjust Page ${session.activePageIndex + 1}")
            .setView(dlgBinding.root)
            .create()

        fun resetSliders() {
            dlgBinding.sliderAdjustExposure.value = 0f
            dlgBinding.sliderAdjustContrast.value = 1f
            dlgBinding.sliderAdjustSaturation.value = 1f
            dlgBinding.sliderAdjustSharpen.value = 0f
            dlgBinding.sliderAdjustDenoise.value = 0f
        }
        dlgBinding.sliderAdjustExposure.value = page.exposureStops.toFloat().coerceIn(-2f, 2f)
        dlgBinding.sliderAdjustContrast.value = page.contrast.toFloat().coerceIn(0.5f, 2f)
        dlgBinding.sliderAdjustSaturation.value = page.saturation.toFloat().coerceIn(0f, 2f)
        dlgBinding.sliderAdjustSharpen.value = page.adjustSharpen.toFloat().coerceIn(0f, 2f)
        dlgBinding.sliderAdjustDenoise.value = page.adjustDenoise.toFloat().coerceIn(0f, 10f)

        dlgBinding.btnAdjustReset.setOnClickListener { resetSliders() }
        dlgBinding.btnAdjustApply.setOnClickListener {
            dialog.dismiss()
            applyPageAdjust(
                exposureStops = dlgBinding.sliderAdjustExposure.value.toDouble(),
                contrast = dlgBinding.sliderAdjustContrast.value.toDouble(),
                saturation = dlgBinding.sliderAdjustSaturation.value.toDouble(),
                sharpen = dlgBinding.sliderAdjustSharpen.value.toDouble(),
                denoise = dlgBinding.sliderAdjustDenoise.value.toDouble(),
            )
        }
        dialog.show()
    }

    private fun applyPageAdjust(
        exposureStops: Double,
        contrast: Double,
        saturation: Double,
        sharpen: Double = 0.0,
        denoise: Double = 0.0
    ) {
        val page = session.currentPage ?: return
        page.exposureStops = exposureStops
        page.contrast = contrast
        page.saturation = saturation
        page.adjustSharpen = sharpen
        page.adjustDenoise = denoise
        renderCurrentPage {
            Toast.makeText(activity, "Adjustments applied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistSession() {
        scope.launch(Dispatchers.IO) {
            session.saveToDisk(activity)
        }
    }

    // =========================================================================
    // ADVANCED DOCUMENT EXPORT SYSTEM
    // =========================================================================

    private fun showExportOptionsDialog() {
        if (session.isEmpty) {
            Toast.makeText(activity, "Add at least one page before exporting", Toast.LENGTH_SHORT).show()
            return
        }

        val sheetBinding = SheetDocumentExportBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvExportSubtitle.text = "${session.title} • ${session.pageCount} page(s)"
        sheetBinding.chipScopeAllPages.text = "All Pages (${session.pageCount})"
        sheetBinding.chipScopeCurrentPage.text = "Page ${session.activePageIndex + 1} Only"

        // Progressive disclosure (expressive export UX): paper / orientation /
        // fitting+margins / compression collapse behind "More options". All chip
        // ids and defaults are untouched — the export click listener reads the
        // same checked states whether the section is visible or not.
        val advancedExportViews = listOf(
            sheetBinding.tvExportPaperHeader, sheetBinding.hsvExportPaper,
            sheetBinding.tvExportOrientationHeader, sheetBinding.chipGroupExportOrientation,
            sheetBinding.tvExportFitHeader, sheetBinding.hsvExportFit,
            sheetBinding.tvExportQualityHeader, sheetBinding.chipGroupExportQuality
        )
        sheetBinding.btnExportAdvancedToggle.setOnClickListener {
            val expand = sheetBinding.btnExportAdvancedToggle.tag != "open"
            advancedExportViews.forEach { v -> v.visibility = if (expand) View.VISIBLE else View.GONE }
            sheetBinding.btnExportAdvancedToggle.text = if (expand) "Fewer options" else "More options"
            sheetBinding.btnExportAdvancedToggle.setIconResource(
                if (expand) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            sheetBinding.btnExportAdvancedToggle.tag = if (expand) "open" else "closed"
        }

        sheetBinding.btnExportExecute.setOnClickListener {
            dialog.dismiss()

            // Resolve Format
            val format = when {
                sheetBinding.chipFormatSeparatePdfs.isChecked -> DocumentExportEngine.OutputFormat.SEPARATE_PDFS
                sheetBinding.chipFormatImages.isChecked -> DocumentExportEngine.OutputFormat.IMAGES_ZIP
                else -> DocumentExportEngine.OutputFormat.SINGLE_PDF
            }

            // Resolve Paper Size
            val paperSize = when {
                sheetBinding.chipPaperLetter.isChecked -> DocumentExportEngine.PaperSize.LETTER
                sheetBinding.chipPaperLegal.isChecked -> DocumentExportEngine.PaperSize.LEGAL
                sheetBinding.chipPaperA3.isChecked -> DocumentExportEngine.PaperSize.A3
                sheetBinding.chipPaperA5.isChecked -> DocumentExportEngine.PaperSize.A5
                sheetBinding.chipPaperTabloid.isChecked -> DocumentExportEngine.PaperSize.TABLOID
                sheetBinding.chipPaperOriginal.isChecked -> DocumentExportEngine.PaperSize.ORIGINAL_IMAGE
                else -> DocumentExportEngine.PaperSize.A4
            }

            // Resolve Orientation
            val orientation = when {
                sheetBinding.chipOrientPortrait.isChecked -> DocumentExportEngine.Orientation.PORTRAIT
                sheetBinding.chipOrientLandscape.isChecked -> DocumentExportEngine.Orientation.LANDSCAPE
                else -> DocumentExportEngine.Orientation.AUTO
            }

            // Resolve Fit & Margins
            val fit = when {
                sheetBinding.chipFitFill.isChecked -> DocumentExportEngine.PageFit.FILL_PAGE
                sheetBinding.chipFitPage.isChecked -> DocumentExportEngine.PageFit.FIT_PAGE
                else -> DocumentExportEngine.PageFit.ORIGINAL
            }

            val margin = when {
                sheetBinding.chipMarginZero.isChecked -> DocumentExportEngine.Margin.NONE
                sheetBinding.chipMarginCompact.isChecked -> DocumentExportEngine.Margin.COMPACT
                else -> DocumentExportEngine.Margin.NORMAL
            }

            // Resolve Quality
            val quality = when {
                sheetBinding.chipQualityCompact.isChecked -> DocumentExportEngine.ExportQuality.LOW
                sheetBinding.chipQualityBalanced.isChecked -> DocumentExportEngine.ExportQuality.MEDIUM
                else -> DocumentExportEngine.ExportQuality.HIGH
            }

            val isCurrentPageOnly = sheetBinding.chipScopeCurrentPage.isChecked

            val options = DocumentExportEngine.ExportOptions(
                format = format,
                paperSize = paperSize,
                orientation = orientation,
                fit = fit,
                margin = margin,
                quality = quality
            )

            executeExport(options, isCurrentPageOnly)
        }

        dialog.show()
    }

    private fun executeExport(options: DocumentExportEngine.ExportOptions, isCurrentPageOnly: Boolean) {
        Toast.makeText(activity, "Compiling document export...", Toast.LENGTH_SHORT).show()

        scope.launch(Dispatchers.IO) {
            try {
                val result = if (isCurrentPageOnly) {
                    val activeIndex = session.activePageIndex
                    val page = session.pages[activeIndex]
                    DocumentExportEngine.exportSinglePage(activity, page, activeIndex, session.title, options)
                } else {
                    DocumentExportEngine.exportDocument(activity, session, options)
                }

                withContext(Dispatchers.Main) {
                    onExportFileRequest(result.file, result.mimeType)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUi() {
        if (session.isEmpty) {
            binding.containerDocEmptyState.visibility = View.VISIBLE
            binding.containerDocPreview.visibility = View.GONE
            binding.btnDocExportPdf.isEnabled = false
            binding.cardDocActionDock.visibility = View.GONE
        } else {
            binding.containerDocEmptyState.visibility = View.GONE
            binding.containerDocPreview.visibility = View.VISIBLE
            binding.btnDocExportPdf.isEnabled = true
            binding.cardDocActionDock.visibility = View.VISIBLE

            val currentPage = session.currentPage
            val bmp = currentPage?.getDisplayBitmap(activity)
            binding.ivDocPagePreview.setImageBitmap(bmp)
            binding.tvDocPageIndicator.text = "Page ${session.activePageIndex + 1} of ${session.pageCount}"

            // Sync filter chip selection to active page mode
            isSyncingFilterChips = true
            try {
                when (currentPage?.mode) {
                    DocumentScanner.DocumentMode.ORIGINAL -> binding.chipFilterOriginal.isChecked = true
                    DocumentScanner.DocumentMode.ENHANCED -> binding.chipFilterEnhanced.isChecked = true
                    DocumentScanner.DocumentMode.BLACK_AND_WHITE -> binding.chipFilterBw.isChecked = true
                    DocumentScanner.DocumentMode.GRAYSCALE -> binding.chipFilterGrayscale.isChecked = true
                    DocumentScanner.DocumentMode.RECEIPT -> binding.chipFilterReceipt.isChecked = true
                    DocumentScanner.DocumentMode.ID_DOCUMENT -> binding.chipFilterIdDoc.isChecked = true
                    null -> {}
                }
            } finally {
                isSyncingFilterChips = false
            }

            // Enable/disable navigation buttons based on current index
            binding.btnDocPageMoveLeft.isEnabled = (session.activePageIndex > 0)
            binding.btnDocPageMoveRight.isEnabled = (session.activePageIndex < session.pageCount - 1)

            // Populate visual horizontal thumbnail strip
            renderThumbnailStrip()
        }
    }

    private fun renderThumbnailStrip() {
        val density = activity.resources.displayMetrics.density
        val container = binding.containerDocThumbnails
        container.removeAllViews()

        for (i in 0 until session.pageCount) {
            val page = session.pages[i]
            val isSelected = (i == session.activePageIndex)

            val card = MaterialCardView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (62 * density).toInt(),
                    (82 * density).toInt()
                ).apply {
                    setMargins(0, 0, (8 * density).toInt(), 0)
                }
                radius = 10 * density
                strokeWidth = if (isSelected) (2.5f * density).toInt() else (1 * density).toInt()
                strokeColor = if (isSelected) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_surface_stroke)
                setCardBackgroundColor(activity.getColor(R.color.vf_surface_variant))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    session.selectPage(i)
                    updateUi()
                }
            }

            val cardInner = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
            }

            val ivThumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (58 * density).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Page ${i + 1}"
                val thumbBmp = page.thumbnailBitmapCache ?: page.getDisplayBitmap(activity)
                setImageBitmap(thumbBmp)
            }

            val tvBadge = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (24 * density).toInt()
                )
                gravity = android.view.Gravity.CENTER
                text = "%02d".format(i + 1)
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_text_primary))
            }

            cardInner.addView(ivThumb)
            cardInner.addView(tvBadge)
            card.addView(cardInner)
            container.addView(card)
        }

        // Add trailing "+ Add Page" card
        val addCard = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                (62 * density).toInt(),
                (82 * density).toInt()
            )
            radius = 10 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = activity.getColor(R.color.vf_surface_stroke)
            setCardBackgroundColor(activity.getColor(R.color.vf_surface))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openCameraViewfinder()
            }
        }

        val addInner = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.CENTER
            orientation = LinearLayout.VERTICAL
        }

        val addIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            setImageResource(R.drawable.ic_camera)
            imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.vf_primary))
            contentDescription = "Add Page"
        }

        val addText = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "+ Add"
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.vf_primary))
        }

        addInner.addView(addIcon)
        addInner.addView(addText)
        addCard.addView(addInner)
        container.addView(addCard)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val bmp: Bitmap? = if (image.format == ImageFormat.JPEG || planes.size == 1) {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (oom: OutOfMemoryError) {
                android.util.Log.e("VeilFrame.DocScanner", "Capture decode OOM", oom)
                null
            }
        } else {
            try {
                image.toBitmap()
            } catch (t: Throwable) {
                try {
                    val yBuffer = planes[0].buffer
                    val uBuffer = planes[1].buffer
                    val vBuffer = planes[2].buffer
                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()
                    val nv21 = ByteArray(ySize + uSize + vSize)
                    yBuffer.get(nv21, 0, ySize)
                    vBuffer.get(nv21, ySize, vSize)
                    uBuffer.get(nv21, ySize + vSize, uSize)
                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
                    val imageBytes = out.toByteArray()
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                } catch (oom: OutOfMemoryError) {
                    android.util.Log.e("VeilFrame.DocScanner", "Capture decode OOM", oom)
                    null
                } catch (e: Exception) {
                    android.util.Log.e("VeilFrame.DocScanner", "Fallback decode failed", e)
                    null
                }
            }
        }

        val decoded = bmp ?: return null
        val rotation = image.imageInfo.rotationDegrees
        return try {
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                decoded.recycle()
                rotated
            } else {
                decoded
            }
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("VeilFrame.DocScanner", "Capture rotation OOM", oom)
            decoded.recycle()
            null
        }
    }
}
