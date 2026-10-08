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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            persistSession()
            updateUi()
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
            if (checkedIds.isNotEmpty()) {
                val mode = when (checkedIds[0]) {
                    R.id.chipFilterOriginal -> DocumentScanner.DocumentMode.ORIGINAL
                    R.id.chipFilterEnhanced -> DocumentScanner.DocumentMode.ENHANCED
                    R.id.chipFilterBw -> DocumentScanner.DocumentMode.BLACK_AND_WHITE
                    R.id.chipFilterGrayscale -> DocumentScanner.DocumentMode.GRAYSCALE
                    R.id.chipFilterReceipt -> DocumentScanner.DocumentMode.RECEIPT
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

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun closeCameraViewfinder() {
        // Phase 2/3: no stale tracks or capture flags across sessions.
        quadStabilizer.reset()
        lastStabState = QuadStabilizer.State.SEARCHING
        isCapturing = false
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

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            analyzeFrameForDocument(imageProxy)
        }

        try {
            camera = provider.bindToLifecycle(
                activity,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
            setCameraZoom(currentZoomRatio)
        } catch (e: Exception) {
            Toast.makeText(activity, "Camera binding error: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun analyzeFrameForDocument(imageProxy: ImageProxy) {
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

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // Direct Y-plane -> grayscale Mat, decimated to <=1024px (CV-5).
        // Replaces the old NV21 -> JPEG(85) -> decode -> Bitmap -> Mat round-trip
        // that ran at full camera resolution on EVERY frame. QuadDetector
        // grayscales internally and accepts single-channel input natively.
        val grayMat = try {
            yPlaneToGrayMat(mediaImage)
        } catch (t: Throwable) {
            android.util.Log.w("VeilFrame.DocScanner", "Frame conversion failed", t)
            null
        }
        imageProxy.close()
        val mat = grayMat ?: return

        try {
            val corners = DocumentScanner.findCorners(mat)
            val frameW = mat.cols()
            val frameH = mat.rows()

            // Phase 3: EMA-smoothed quad with hysteresis — the overlay stops
            // flickering and "Ready" requires consecutive matched frames.
            val stable = quadStabilizer.update(if (corners.size == 4) corners else null)
            val detectedPts = stable?.map { pt -> PointF(pt.x.toFloat(), pt.y.toFloat()) }
            val stabState = quadStabilizer.state
            val becameStable = stabState == QuadStabilizer.State.STABLE &&
                lastStabState != QuadStabilizer.State.STABLE
            lastStabState = stabState

            activity.runOnUiThread {
                binding.docQuadOverlayView.setDetectedQuad(detectedPts, frameW, frameH)
                binding.tvDocCamStatus.text = when (stabState) {
                    QuadStabilizer.State.STABLE -> "Ready — tap shutter"
                    QuadStabilizer.State.TRACKING -> "Document detected — hold still"
                    QuadStabilizer.State.SEARCHING -> "Align document inside frame"
                }
                if (becameStable) {
                    binding.docQuadOverlayView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        } catch (t: Throwable) {
            // Honest, rate-limited logging — silent per-frame catch-alls hid
            // dead-CV battery drain (CV-6).
            if (System.currentTimeMillis() - lastFrameErrorLogMs > 5_000L) {
                lastFrameErrorLogMs = System.currentTimeMillis()
                android.util.Log.w("VeilFrame.DocScanner", "Frame analysis failed", t)
            }
            activity.runOnUiThread {
                binding.docQuadOverlayView.clear()
            }
        } finally {
            mat.release() // CV-4: released even when findCorners throws
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

    /** Camera Y (luma) plane -> CV_8UC1 Mat, decimated to <=1024px on the long edge. */
    private fun yPlaneToGrayMat(mediaImage: android.media.Image): org.opencv.core.Mat? {
        val plane = mediaImage.planes.firstOrNull() ?: return null
        val width = mediaImage.width
        val height = mediaImage.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val data = ByteArray(width * height)
        try {
            if (rowStride == width) {
                buffer.position(0)
                buffer.get(data, 0, width * height)
            } else {
                var offset = 0
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(data, offset, width)
                    offset += width
                }
            }
        } catch (_: Throwable) {
            return null
        }
        val full = org.opencv.core.Mat(height, width, org.opencv.core.CvType.CV_8UC1)
        full.put(0, 0, data)
        val maxEdge = maxOf(width, height)
        if (maxEdge <= 1024) return full
        val scaled = org.opencv.core.Mat()
        return try {
            val scale = 1024.0 / maxEdge
            org.opencv.imgproc.Imgproc.resize(
                full,
                scaled,
                org.opencv.core.Size(
                    maxOf(1, Math.round(width * scale).toInt()).toDouble(),
                    maxOf(1, Math.round(height * scale).toInt()).toDouble(),
                ),
                0.0,
                0.0,
                org.opencv.imgproc.Imgproc.INTER_AREA,
            )
            scaled
        } catch (t: Throwable) {
            scaled.release()
            throw t
        } finally {
            full.release() // CV-4: released on BOTH success and failure paths
        }
    }

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

    private fun runAutoCropOnCurrentPage() {
        val page = session.currentPage ?: return
        val bmp = page.getDisplayBitmap(activity) ?: return

        // A2: governed execution — INTERACTIVE lane + memory admission.
        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(bmp.width, bmp.height, 3)
        scope.launch {
            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-auto-crop",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L, // B1 watchdog
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                val srcMat = BitmapBridge.toMat(bmp)
                try {
                    val corners = DocumentScanner.findCorners(srcMat, ctx) // B6
                    if (corners.size != 4) {
                        null
                    } else {
                        val croppedMat = DocumentScanner.warpPerspective(srcMat, corners)
                        val croppedBmp = try {
                            BitmapBridge.toBitmap(croppedMat)
                        } finally {
                            croppedMat.release()
                        }
                        croppedBmp to corners
                    }
                } finally {
                    srcMat.release() // CV-4: released on every path
                }
            }

            job.await().fold(
                onOk = { outcome ->
                    if (outcome != null) {
                        page.processedBitmapCache = outcome.first
                        page.corners = outcome.second
                        persistSession()
                        updateUi()
                    }
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "Auto-crop failed for page ${page.id} [${err.code}]: ${err.message}")
                    Toast.makeText(activity, "Auto-crop unavailable — page kept as captured", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun applyFilterToCurrentPage(mode: DocumentScanner.DocumentMode) {
        val page = session.currentPage ?: return
        val baseBmp = page.originalBitmapCache ?: page.processedBitmapCache ?: return

        // A2: governed execution — INTERACTIVE lane + memory admission.
        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(baseBmp.width, baseBmp.height, 3)
        scope.launch {
            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-enhance-filter",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L, // B1 watchdog
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                val srcMat = BitmapBridge.toMat(baseBmp)
                try {
                    val filteredMat = DocumentScanner.process(srcMat, mode, ctx) // B6
                    try {
                        BitmapBridge.toBitmap(filteredMat)
                    } finally {
                        filteredMat.release()
                    }
                } finally {
                    srcMat.release() // CV-4
                }
            }

            job.await().fold(
                onOk = { filteredBmp ->
                    page.processedBitmapCache = filteredBmp
                    page.mode = mode
                    persistSession()
                    updateUi()
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "Filter $mode failed for page ${page.id} [${err.code}]: ${err.message}")
                    Toast.makeText(activity, "Filter could not be applied", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /**
     * v2.3.0: per-page Adjust (exposure/contrast/saturation) — device feedback said the
     * page screen only offered colour filters and auto-crop. Wires the dormant
     * [com.veilframe.app.cv.color.ColorEngine] under CvEngine governance (ADR 0006).
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
        }
        dlgBinding.btnAdjustReset.setOnClickListener { resetSliders() }
        dlgBinding.btnAdjustApply.setOnClickListener {
            dialog.dismiss()
            applyPageAdjust(
                exposureStops = dlgBinding.sliderAdjustExposure.value.toDouble(),
                contrast = dlgBinding.sliderAdjustContrast.value.toDouble(),
                saturation = dlgBinding.sliderAdjustSaturation.value.toDouble(),
            )
        }
        dialog.show()
    }

    private fun applyPageAdjust(exposureStops: Double, contrast: Double, saturation: Double) {
        val page = session.currentPage ?: return
        val baseBmp = page.originalBitmapCache ?: page.processedBitmapCache ?: return
        if (exposureStops == 0.0 && contrast == 1.0 && saturation == 1.0) return

        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(baseBmp.width, baseBmp.height, 3)
        scope.launch {
            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-page-adjust",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L,
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                val srcMat = BitmapBridge.toMat(baseBmp)
                try {
                    val colorEngine = com.veilframe.app.cv.color.ColorEngine
                    val exposed = colorEngine.exposure(srcMat, exposureStops)
                    val contrasted = colorEngine.contrast(exposed, contrast)
                    if (contrasted !== exposed && exposed !== srcMat) exposed.release()
                    val saturated = colorEngine.saturation(contrasted, saturation)
                    if (saturated !== contrasted && contrasted !== srcMat) contrasted.release()
                    try {
                        BitmapBridge.toBitmap(saturated)
                    } finally {
                        if (saturated !== srcMat) saturated.release()
                    }
                } finally {
                    srcMat.release() // CV-4
                }
            }

            job.await().fold(
                onOk = { adjusted ->
                    page.processedBitmapCache = adjusted
                    persistSession()
                    updateUi()
                    Toast.makeText(activity, "Adjustments applied", Toast.LENGTH_SHORT).show()
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "Page adjust failed [${err.code}]: ${err.message}")
                    Toast.makeText(activity, "Adjust failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            )
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
        val bmp = try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("VeilFrame.DocScanner", "Capture decode OOM", oom)
            null
        } ?: return null

        val rotation = image.imageInfo.rotationDegrees
        return try {
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                rotated
            } else {
                bmp
            }
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("VeilFrame.DocScanner", "Capture rotation OOM", oom)
            bmp.recycle()
            null
        }
    }
}
