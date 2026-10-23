package com.veilframe.app.document

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.veilframe.app.cv.geometry.CameraIntrinsics
import com.veilframe.app.cv.geometry.OpticalMeasures
import kotlin.math.max
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
    val frameId: Long = 0L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val stabilizerState: QuadStabilizer.State = QuadStabilizer.State.SEARCHING,
)

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
    private val onNavigateBack: () -> Unit,
    private val onRequestCameraPermission: () -> Unit = {}
) : com.veilframe.app.navigation.ToolWorkStateContract {
    val session = DocumentSession()

    // Decoupled live analyzer flow: 60fps UI observation without GC pressure
    private val _scannerState = MutableSharedFlow<ScanResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val scannerState: SharedFlow<ScanResult> = _scannerState.asSharedFlow()

    private val isInternalProcessing = AtomicBoolean(false)
    private val viewfinderSessionId = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeAnalysisJob: Job? = null
    private var frameIdCounter = 0L
    private var scannerJob: Job? = null
    private val segmentationService: com.veilframe.app.cv.segmentation.DocumentSegmentationService by lazy {
        com.veilframe.app.cv.segmentation.DocumentSegmentationService.getInstance(activity)
    }

    // CameraX runtime
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isBackCamera = true
    private var currentZoomRatio = 1.0f
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private val cameraExecutor: java.util.concurrent.ExecutorService =
        com.veilframe.app.cv.core.CvRuntime.cameraExecutor
    private var isSyncingFilterChips = false
    private var isSyncingAdjustments = false

    // FairScan optical measures & camera intrinsics
    private var cameraIntrinsics: CameraIntrinsics? = null
    private var canUseFocusDistance = false
    @Volatile
    private var lastFocusDistanceDiopters: Float? = null
    private var isThermalRegistered = false

    private fun registerThermalGovernor() {
        if (!isThermalRegistered) {
            com.veilframe.app.runtime.ThermalGovernor.register(activity)
            isThermalRegistered = true
        }
    }

    private fun unregisterThermalGovernor() {
        if (isThermalRegistered) {
            com.veilframe.app.runtime.ThermalGovernor.unregister()
            isThermalRegistered = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun init() {
        // B4: thermal awareness for the sustained viewfinder pipeline.
        registerThermalGovernor()

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

        binding.btnDocPageCrop.setOnClickListener {
            showCropEditorDialog()
        }

        binding.ivDocPagePreview.setOnClickListener {
            showCropEditorDialog()
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

        // Filter & Palette chips
        binding.chipGroupDocFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isSyncingFilterChips || isSyncingAdjustments) return@setOnCheckedStateChangeListener
            if (checkedIds.isNotEmpty()) {
                val (mode, preset) = when (checkedIds[0]) {
                    R.id.chipFilterOriginal -> DocumentScanner.DocumentMode.ORIGINAL to "None"
                    R.id.chipFilterEnhanced -> DocumentScanner.DocumentMode.ENHANCED to "None"
                    R.id.chipFilterBw -> DocumentScanner.DocumentMode.BLACK_AND_WHITE to "None"
                    R.id.chipFilterGrayscale -> DocumentScanner.DocumentMode.GRAYSCALE to "None"
                    R.id.chipFilterReceipt -> DocumentScanner.DocumentMode.RECEIPT to "None"
                    R.id.chipFilterIdDoc -> DocumentScanner.DocumentMode.ID_DOCUMENT to "None"
                    R.id.chipFilterSepia -> DocumentScanner.DocumentMode.ORIGINAL to "Sepia"
                    R.id.chipFilterVintage -> DocumentScanner.DocumentMode.ORIGINAL to "Vintage"
                    R.id.chipFilterCool -> DocumentScanner.DocumentMode.ORIGINAL to "Cool"
                    R.id.chipFilterWarm -> DocumentScanner.DocumentMode.ORIGINAL to "Warm"
                    else -> DocumentScanner.DocumentMode.ENHANCED to "None"
                }
                applyFilterToCurrentPage(mode, preset)
            }
        }

        // Inline Image Studio: Fine Tuning Sliders
        val applySlidersToPage = {
            val page = session.currentPage
            if (page != null && !isSyncingAdjustments) {
                page.exposureStops = binding.sliderDocExposure.value.toDouble()
                page.contrast = binding.sliderDocContrast.value.toDouble()
                page.saturation = binding.sliderDocSaturation.value.toDouble()
                page.warmth = binding.sliderDocWarmth.value.toDouble()
                page.adjustSharpen = binding.sliderDocSharpen.value.toDouble()
                page.adjustDenoise = binding.sliderDocDenoise.value.toDouble()
                renderCurrentPage()
            }
        }

        val sliderTouchListener = object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applySlidersToPage()
            }
        }

        binding.sliderDocExposure.addOnSliderTouchListener(sliderTouchListener)
        binding.sliderDocContrast.addOnSliderTouchListener(sliderTouchListener)
        binding.sliderDocSaturation.addOnSliderTouchListener(sliderTouchListener)
        binding.sliderDocWarmth.addOnSliderTouchListener(sliderTouchListener)
        binding.sliderDocSharpen.addOnSliderTouchListener(sliderTouchListener)
        binding.sliderDocDenoise.addOnSliderTouchListener(sliderTouchListener)

        // Inline Image Studio: Watermark & Stamp Controls
        val applyWatermarkToPage = {
            val page = session.currentPage
            if (page != null && !isSyncingAdjustments) {
                page.watermarkText = binding.etDocWatermarkText.text?.toString()?.trim() ?: ""
                val resolvedPosition = when (binding.chipGroupDocWmPosition.checkedChipId) {
                    R.id.chipDocPosCenter -> "center"
                    R.id.chipDocPosTopRight -> "top-right"
                    R.id.chipDocPosTopLeft -> "top-left"
                    R.id.chipDocPosBottomLeft -> "bottom-left"
                    else -> "bottom-right"
                }
                page.watermarkPosition = resolvedPosition
                page.watermarkSize = binding.sliderDocWmSize.value.toInt()
                page.watermarkOpacity = binding.sliderDocWmOpacity.value / 100f
                renderCurrentPage()
            }
        }

        val wmSliderTouchListener = object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyWatermarkToPage()
            }
        }
        binding.sliderDocWmSize.addOnSliderTouchListener(wmSliderTouchListener)
        binding.sliderDocWmOpacity.addOnSliderTouchListener(wmSliderTouchListener)

        binding.chipGroupDocWmPosition.setOnCheckedStateChangeListener { _, _ ->
            if (!isSyncingAdjustments) applyWatermarkToPage()
        }

        binding.chipDocWmConfidential.setOnClickListener {
            binding.etDocWatermarkText.setText("CONFIDENTIAL")
            applyWatermarkToPage()
        }
        binding.chipDocWmCopy.setOnClickListener {
            binding.etDocWatermarkText.setText("COPY")
            applyWatermarkToPage()
        }
        binding.chipDocWmDraft.setOnClickListener {
            binding.etDocWatermarkText.setText("DRAFT")
            applyWatermarkToPage()
        }
        binding.chipDocWmDoNotShare.setOnClickListener {
            binding.etDocWatermarkText.setText("DO NOT SHARE")
            applyWatermarkToPage()
        }

        binding.etDocWatermarkText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyWatermarkToPage()
                true
            } else false
        }
        binding.etDocWatermarkText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !isSyncingAdjustments) {
                applyWatermarkToPage()
            }
        }

        binding.btnDocResetAdjustments.setOnClickListener {
            val page = session.currentPage ?: return@setOnClickListener
            page.mode = DocumentScanner.DocumentMode.ENHANCED
            page.filterPreset = "None"
            page.exposureStops = 0.0
            page.contrast = 1.0
            page.saturation = 1.0
            page.warmth = 0.0
            page.adjustSharpen = 0.0
            page.adjustDenoise = 0.0
            page.watermarkText = ""
            page.watermarkPosition = "bottom-right"
            page.watermarkSize = 28
            page.watermarkOpacity = 0.6f
            updateUi()
            renderCurrentPage {
                Toast.makeText(activity, "Adjustments reset", Toast.LENGTH_SHORT).show()
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
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onRequestCameraPermission()
            return
        }
        registerThermalGovernor()
        viewfinderSessionId.incrementAndGet()
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
        viewfinderSessionId.incrementAndGet()
        activeAnalysisJob?.cancel()
        activeAnalysisJob = null
        stopScannerStateCollection()
        quadStabilizer.reset()
        lastStabState = QuadStabilizer.State.SEARCHING
        isCapturing = false
        isInternalProcessing.set(false)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        binding.docQuadOverlayView.clear()
        binding.containerDocCamera.visibility = View.GONE
        binding.containerDocMainFlow.visibility = View.VISIBLE
        updateUi()
    }

    fun release() {
        closeCameraViewfinder()
        unregisterThermalGovernor()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val cameraSelector = if (isBackCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val preview = Preview.Builder()
            .setResolutionSelector(ratio43)
            .build().also {
                it.setSurfaceProvider(binding.docCameraPreviewView.surfaceProvider)
            }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4400, 3300),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .build()
            )
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)

        Camera2Interop.Extender(imageCaptureBuilder)
            .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                        lastFocusDistanceDiopters = it
                    }
                }
            })

        val capture = imageCaptureBuilder.build()
        imageCapture = capture

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(ratio43)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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
            val cam = provider.bindToLifecycle(
                activity,
                cameraSelector,
                useCaseGroup
            )
            camera = cam
            updateCameraCharacteristics(Camera2CameraInfo.from(cam.cameraInfo))
            setCameraZoom(currentZoomRatio)
        } catch (e: Exception) {
            try {
                val cam = provider.bindToLifecycle(
                    activity,
                    cameraSelector,
                    preview,
                    capture,
                    imageAnalysis
                )
                camera = cam
                updateCameraCharacteristics(Camera2CameraInfo.from(cam.cameraInfo))
                setCameraZoom(currentZoomRatio)
            } catch (fallbackEx: Exception) {
                Toast.makeText(activity, "Camera binding error: ${fallbackEx.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun updateCameraCharacteristics(cameraInfo: Camera2CameraInfo) {
        val focalLengths = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )
        val sensorSize = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        )
        cameraIntrinsics = if (focalLengths == null || focalLengths.size != 1 || sensorSize == null) {
            null
        } else {
            CameraIntrinsics(
                focalLengths[0],
                max(sensorSize.width, sensorSize.height)
            )
        }
        val calibration = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
        )
        canUseFocusDistance =
            calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED ||
            calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE
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
                val (iconRes, iconTintRes, statusText) = when (result.stabilizerState) {
                    QuadStabilizer.State.STABLE -> Triple(
                        R.drawable.ic_check_circle,
                        R.color.vf_accent_green,
                        "Ready — tap shutter"
                    )
                    QuadStabilizer.State.TRACKING -> Triple(
                        R.drawable.ic_search,
                        R.color.vf_accent_blue,
                        "Document detected — hold still"
                    )
                    QuadStabilizer.State.SEARCHING -> Triple(
                        R.drawable.ic_crop,
                        R.color.vf_text_secondary,
                        "Align document inside frame"
                    )
                }
                binding.ivDocCamStatusIcon.setImageResource(iconRes)
                binding.ivDocCamStatusIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, iconTintRes)
                )
                binding.tvDocCamStatus.text = statusText
            }
        }
    }

    private fun stopScannerStateCollection() {
        scannerJob?.cancel()
        scannerJob = null
    }

    private fun processFrameAsync(imageProxy: ImageProxy) {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            imageProxy.close()
            return
        }

        if (isCapturing) {
            imageProxy.close()
            return
        }

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

        if (!isInternalProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val currentFrameId = frameIdCounter++

        val rawBitmap = try {
            imageProxy.toBitmap()
        } catch (_: Throwable) {
            null
        } finally {
            imageProxy.close()
        }

        if (rawBitmap == null) {
            isInternalProcessing.set(false)
            return
        }

        val frameBitmap = if (rotation != 0) {
            try {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                rawBitmap.recycle()
                rotated
            } catch (_: Throwable) {
                rawBitmap
            }
        } else {
            rawBitmap
        }

        val width = frameBitmap.width
        val height = frameBitmap.height

        val currentSession = viewfinderSessionId.get()

        val job = scope.launch(Dispatchers.Default) {
            try {
                ensureActive()
                if (viewfinderSessionId.get() != currentSession) {
                    return@launch
                }

                // FairScan Live Analysis: MobileNetV2 neural segmentation inference
                val segResult = segmentationService.runSegmentation(frameBitmap)

                if (viewfinderSessionId.get() != currentSession) {
                    return@launch
                }

                val corners = if (segResult != null) {
                    com.veilframe.app.cv.geometry.QuadDetector.detectFromSegmentation(
                        segmentation = segResult.segmentation,
                        originalWidth = width,
                        originalHeight = height,
                        isCaptureMode = false
                    )
                } else null

                // FairScan live quad stabilization with EMA smoothing
                val stable = quadStabilizer.update(if (corners != null && corners.size == 4) corners else null)
                val stabState = quadStabilizer.state
                val becameStable = stabState == QuadStabilizer.State.STABLE &&
                    lastStabState != QuadStabilizer.State.STABLE
                lastStabState = stabState

                if (becameStable) {
                    withContext(Dispatchers.Main) {
                        binding.docQuadOverlayView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                if (viewfinderSessionId.get() != currentSession) {
                    return@launch
                }

                _scannerState.emit(
                    ScanResult(
                        corners = stable,
                        frameId = currentFrameId,
                        frameWidth = width,
                        frameHeight = height,
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
                frameBitmap.recycle()
                isInternalProcessing.set(false)
            }
        }
        activeAnalysisJob = job
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
                    val diopters = lastFocusDistanceDiopters
                    val subjectDistanceMm = if (canUseFocusDistance && diopters != null && diopters != 0.0f) {
                        1000f / diopters
                    } else null
                    val opticalMeasures = cameraIntrinsics?.let {
                        OpticalMeasures(it, subjectDistanceMm)
                    }

                    val bitmap = try {
                        imageProxyToBitmap(image)
                    } finally {
                        image.close()
                        isCapturing = false
                    }

                    if (bitmap != null) {
                        activity.runOnUiThread {
                            processCapturedPhotoThroughFirstPipeline(bitmap, opticalMeasures)
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
        binding.btnDocCamDone.contentDescription = "Done (${session.pageCount})"
    }

    // =========================================================================
    // PAGE MANAGEMENT & PHOTO IMPORT
    // =========================================================================

    fun handleCameraPhotoCaptured(bitmap: Bitmap) {
        processCapturedPhotoThroughFirstPipeline(bitmap, null)
    }

    fun processCapturedPhotoThroughFirstPipeline(
        sourceBitmap: Bitmap,
        opticalMeasures: OpticalMeasures? = null
    ) {
        val page = ScannedPage(
            originalBitmapCache = sourceBitmap,
            processedBitmapCache = sourceBitmap
        )
        session.addPage(page)
        persistSession()
        updateUi()

        val pageId = page.id
        val targetVersion = ++page.editVersion
        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(sourceBitmap.width, sourceBitmap.height, 3)

        scope.launch {
            // Step 1: FairScan MobileNetV2 Neural Segmentation (run inside coroutine)
            val segResult = segmentationService.runSegmentation(sourceBitmap)

            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-first-pipeline",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L,
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()

                // Step 2: Continuous Probability-Map Quad Detection
                val corners = if (segResult != null) {
                    com.veilframe.app.cv.geometry.QuadDetector.detectFromSegmentation(
                        segmentation = segResult.segmentation,
                        originalWidth = sourceBitmap.width,
                        originalHeight = sourceBitmap.height,
                        isCaptureMode = true
                    )
                } else null

                val srcMat = BitmapBridge.toMat(sourceBitmap)
                try {
                    ctx.ensureActive()
                    val resolvedCorners = if (corners != null && corners.size == 4) {
                        corners
                    } else {
                        com.veilframe.app.cv.geometry.QuadDetector.detect(srcMat, context = ctx)?.corners
                    }
                    if (resolvedCorners != null && resolvedCorners.size == 4) {
                        // Step 3: CIELab Chroma Auto Color Mode Detection
                        val colorMode = com.veilframe.app.cv.document.ColorDetector.autoColorMode(srcMat, resolvedCorners)
                        ctx.ensureActive()

                        // Step 4: Single-View Metrology & Perspective Rectification
                        val warpedMat = com.veilframe.app.cv.geometry.PerspectiveCorrector.correct(
                            image = srcMat,
                            sourceCorners = resolvedCorners,
                            opticalMeasures = opticalMeasures,
                        )

                        ctx.ensureActive()
                        // Step 5: Multi-Scale Retinex / Log Retinex Enhancement
                        val docMode = if (colorMode == com.veilframe.app.cv.document.DocumentColorMode.COLOR) {
                            DocumentScanner.DocumentMode.ENHANCED
                        } else {
                            DocumentScanner.DocumentMode.GRAYSCALE
                        }
                        val enhancedMat = DocumentScanner.enhance(warpedMat, docMode, ctx)
                        val resultBitmap = BitmapBridge.toBitmap(enhancedMat)
                        if (enhancedMat !== warpedMat) {
                            enhancedMat.release()
                        }
                        warpedMat.release()
                        Triple(resolvedCorners, docMode, resultBitmap)
                    } else {
                        null
                    }
                } finally {
                    srcMat.release()
                }
            }

            job.await().fold(
                onOk = { result ->
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        if (result != null) {
                            page.corners = result.first
                            page.mode = result.second
                            page.processedBitmapCache = result.third
                        } else {
                            page.corners = null
                        }
                        persistSession()
                        updateUi()
                    }
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "First pipeline failed for page $pageId: ${err.message}")
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        updateUi()
                    }
                }
            )
        }
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
                for (p in loadedPages) {
                    runAutoCropOnPage(p)
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
                page.render(activity, ctx, updateCaches = false)
            }

            job.await().fold(
                onOk = { renderedBmp ->
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        if (renderedBmp != null) {
                            page.updateCaches(renderedBmp)
                        }
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

    private suspend fun detectCornersFromBitmap(origBmp: Bitmap): List<Point>? {
        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(origBmp.width, origBmp.height, 3)
        return try {
            val segResult = segmentationService.runSegmentation(origBmp)
            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-detect-corners",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                val corners = if (segResult != null) {
                    com.veilframe.app.cv.geometry.QuadDetector.detectFromSegmentation(
                        segmentation = segResult.segmentation,
                        originalWidth = origBmp.width,
                        originalHeight = origBmp.height,
                        isCaptureMode = true
                    )
                } else null

                if (corners != null && corners.size == 4) {
                    corners
                } else {
                    val srcMat = BitmapBridge.toMat(origBmp)
                    try {
                        com.veilframe.app.cv.geometry.QuadDetector.detect(srcMat, context = ctx)?.corners
                    } finally {
                        srcMat.release()
                    }
                }
            }
            job.await().getOrNull()
        } catch (e: Throwable) {
            android.util.Log.w("VeilFrame.DocScanner", "detectCornersFromBitmap failed", e)
            null
        }
    }

    private fun runAutoCropOnPage(page: ScannedPage) {
        val origBmp = page.getOriginalBitmap(activity) ?: return
        val targetVersion = ++page.editVersion
        val pageId = page.id

        val estimate = com.veilframe.app.cv.core.CvRuntime.estimateBytes(origBmp.width, origBmp.height, 3)
        scope.launch {
            val segResult = segmentationService.runSegmentation(origBmp)

            val job = com.veilframe.app.cv.core.CvRuntime.engine.submit(
                name = "doc-auto-crop",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 15_000L,
            ) { ctx ->
                com.veilframe.app.cv.core.CvRuntime.requireAvailable()
                ctx.ensureActive()
                val corners = if (segResult != null) {
                    com.veilframe.app.cv.geometry.QuadDetector.detectFromSegmentation(
                        segmentation = segResult.segmentation,
                        originalWidth = origBmp.width,
                        originalHeight = origBmp.height,
                        isCaptureMode = true
                    )
                } else null

                val srcMat = BitmapBridge.toMat(origBmp)
                try {
                    val resolvedCorners = if (corners != null && corners.size == 4) {
                        corners
                    } else {
                        com.veilframe.app.cv.geometry.QuadDetector.detect(srcMat, context = ctx)?.corners
                    }
                    if (resolvedCorners != null && resolvedCorners.size == 4) {
                        val autoColor = com.veilframe.app.cv.document.ColorDetector.autoColorMode(srcMat, resolvedCorners)
                        Pair(resolvedCorners, autoColor)
                    } else {
                        null
                    }
                } finally {
                    srcMat.release()
                }
            }

            job.await().fold(
                onOk = { result ->
                    if (page.editVersion == targetVersion) {
                        if (result != null) {
                            page.corners = result.first
                            if (page.mode == DocumentScanner.DocumentMode.AUTO) {
                                page.mode = if (result.second == com.veilframe.app.cv.document.DocumentColorMode.COLOR) {
                                    DocumentScanner.DocumentMode.ENHANCED
                                } else {
                                    DocumentScanner.DocumentMode.GRAYSCALE
                                }
                            }
                        } else {
                            page.corners = null
                        }
                        if (session.currentPage?.id == pageId) {
                            renderCurrentPage()
                        } else {
                            scope.launch(Dispatchers.IO) {
                                page.render(activity)
                                withContext(Dispatchers.Main) {
                                    updateUi()
                                }
                            }
                        }
                    }
                },
                onErr = { err ->
                    android.util.Log.w("VeilFrame.DocScanner", "Auto-crop failed for page ${page.id} [${err.code}]: ${err.message}")
                    if (session.currentPage?.id == pageId && page.editVersion == targetVersion) {
                        renderCurrentPage()
                    }
                }
            )
        }
    }

    private fun runAutoCropOnCurrentPage() {
        val page = session.currentPage ?: return
        runAutoCropOnPage(page)
    }

    private fun resetCropOnCurrentPage() {
        val page = session.currentPage ?: return
        page.corners = null
        renderCurrentPage {
            Toast.makeText(activity, "Reset to full photo", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * FairScan-style interactive document crop & perspective boundary editor.
     * Displays the full uncropped source photo with draggable corners and darkened scrim.
     */
    private fun showCropEditorDialog() {
        val page = session.currentPage ?: run {
            Toast.makeText(activity, "No page selected", Toast.LENGTH_SHORT).show()
            return
        }
        val origBmp = page.getOriginalBitmap(activity) ?: run {
            Toast.makeText(activity, "Original image unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val dlgBinding = com.veilframe.app.databinding.DialogDocumentCropEditorBinding
            .inflate(activity.layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            activity,
            com.veilframe.app.R.style.ThemeOverlay_VeilFrame_MaterialAlertDialog
        )
            .setView(dlgBinding.root)
            .create()

        val initialCorners = page.corners?.map { PointF(it.x.toFloat(), it.y.toFloat()) }
        dlgBinding.docInteractiveCropView.setSource(origBmp, initialCorners)

        // If corners were not yet detected, run model detection automatically
        if (initialCorners == null) {
            scope.launch {
                val detected = detectCornersFromBitmap(origBmp)
                if (detected != null && detected.size == 4) {
                    dlgBinding.docInteractiveCropView.setCorners(
                        detected.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                    )
                }
            }
        }

        dlgBinding.btnCropAutoDetect.setOnClickListener {
            dlgBinding.btnCropAutoDetect.isEnabled = false
            dlgBinding.btnCropAutoDetect.text = "Detecting…"
            scope.launch {
                val detected = detectCornersFromBitmap(origBmp)
                if (detected != null && detected.size == 4) {
                    dlgBinding.docInteractiveCropView.setCorners(
                        detected.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                    )
                    Toast.makeText(activity, "Document corners auto-detected", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Could not detect document boundaries", Toast.LENGTH_SHORT).show()
                }
                dlgBinding.btnCropAutoDetect.isEnabled = true
                dlgBinding.btnCropAutoDetect.text = "Auto Detect"
            }
        }

        dlgBinding.btnCropResetFull.setOnClickListener {
            dlgBinding.docInteractiveCropView.resetToFullImage()
        }

        dlgBinding.btnCropCancel.setOnClickListener {
            dialog.dismiss()
        }

        dlgBinding.btnCropApply.setOnClickListener {
            val sourceCorners = dlgBinding.docInteractiveCropView.getCornersInSource()
            if (sourceCorners.size == 4) {
                val cvCorners = sourceCorners.map { Point(it.x.toDouble(), it.y.toDouble()) }
                page.corners = cvCorners
                renderCurrentPage {
                    Toast.makeText(activity, "Crop adjusted", Toast.LENGTH_SHORT).show()
                }
                persistSession()
                updateUi()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun applyFilterToCurrentPage(mode: DocumentScanner.DocumentMode, preset: String = "None") {
        val page = session.currentPage ?: return
        if (page.mode == mode && page.filterPreset == preset) return
        page.mode = mode
        page.filterPreset = preset
        renderCurrentPage()
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

        val isSinglePage = session.pageCount <= 1
        sheetBinding.tvExportSubtitle.text = "${session.title} • ${session.pageCount} page(s)"

        if (isSinglePage) {
            sheetBinding.tvExportScopeHeader.visibility = View.GONE
            sheetBinding.chipGroupExportScope.visibility = View.GONE
            sheetBinding.chipScopeAllPages.isChecked = true

            sheetBinding.chipFormatSeparatePdfs.visibility = View.GONE
            sheetBinding.chipFormatSinglePdf.text = "PDF Document"
            sheetBinding.chipFormatSinglePdf.isChecked = true
        } else {
            sheetBinding.tvExportScopeHeader.visibility = View.VISIBLE
            sheetBinding.chipGroupExportScope.visibility = View.VISIBLE
            sheetBinding.chipScopeAllPages.text = "All Pages (${session.pageCount})"
            sheetBinding.chipScopeCurrentPage.text = "Page ${session.activePageIndex + 1} Only"

            sheetBinding.chipFormatSeparatePdfs.visibility = View.VISIBLE
            sheetBinding.chipFormatSinglePdf.text = "Combined PDF"
        }

        // Expressive export UX: format-dependent visibility
        val imageOptionsViews = listOf(
            sheetBinding.tvExportImageFormatHeader, sheetBinding.chipGroupImageFormat,
            sheetBinding.tvExportPackagingHeader, sheetBinding.chipGroupExportPackaging
        )
        val updateFormatViews = {
            val isImages = sheetBinding.chipFormatImages.isChecked
            imageOptionsViews.forEach { v -> v.visibility = if (isImages) View.VISIBLE else View.GONE }
        }
        sheetBinding.chipGroupExportFormat.setOnCheckedStateChangeListener { _, _ ->
            updateFormatViews()
        }
        updateFormatViews()

        // Progressive disclosure (expressive export UX): paper / orientation /
        // fitting+margins / compression collapse behind "More options".
        val advancedExportViews = listOf(
            sheetBinding.tvExportPaperHeader, sheetBinding.hsvExportPaper,
            sheetBinding.tvExportOrientationHeader, sheetBinding.chipGroupExportOrientation,
            sheetBinding.tvExportFitHeader, sheetBinding.hsvExportFit,
            sheetBinding.tvExportMarginsHeader, sheetBinding.hsvExportMargins,
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

            // Resolve Image Format & Packaging
            val imageFormat = when {
                sheetBinding.chipImageFormatPng.isChecked -> DocumentExportEngine.ImageFormat.PNG
                sheetBinding.chipImageFormatWebp.isChecked -> DocumentExportEngine.ImageFormat.WEBP
                else -> DocumentExportEngine.ImageFormat.JPEG
            }
            val isZip = sheetBinding.chipPackageZip.isChecked

            // Resolve Output Format
            val format = when {
                sheetBinding.chipFormatSeparatePdfs.isChecked -> DocumentExportEngine.OutputFormat.SEPARATE_PDFS
                sheetBinding.chipFormatImages.isChecked -> if (isZip) DocumentExportEngine.OutputFormat.IMAGES_ZIP else DocumentExportEngine.OutputFormat.IMAGES
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

            // Resolve Quality: Default to ORIGINAL (100%)
            val quality = when {
                sheetBinding.chipQualityCompact.isChecked -> DocumentExportEngine.ExportQuality.LOW
                sheetBinding.chipQualityBalanced.isChecked -> DocumentExportEngine.ExportQuality.MEDIUM
                sheetBinding.chipQualityHigh.isChecked -> DocumentExportEngine.ExportQuality.HIGH
                else -> DocumentExportEngine.ExportQuality.ORIGINAL
            }

            val isCurrentPageOnly = sheetBinding.chipScopeCurrentPage.isChecked

            val options = DocumentExportEngine.ExportOptions(
                format = format,
                imageFormat = imageFormat,
                isZipArchive = isZip,
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
                    if (result.files.size > 1 && options.format == DocumentExportEngine.OutputFormat.IMAGES && !options.isZipArchive) {
                        // User exported multiple images as individual images (not ZIP)
                        scope.launch(Dispatchers.IO) {
                            var savedCount = 0
                            for (imgFile in result.files) {
                                val savedUri = saveImageToMediaStore(activity, imgFile, options.imageFormat.mimeType)
                                if (savedUri != null) savedCount++
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    activity,
                                    "Saved $savedCount images to Pictures/VeilFrame",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        onExportFileRequest(result.file, result.mimeType)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveImageToMediaStore(context: android.content.Context, file: File, mimeType: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VeilFrame")
                dir.mkdirs()
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (e: Throwable) {
            android.util.Log.e("VeilFrame.DocScanner", "Failed to save image to MediaStore", e)
            null
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

            // Sync inline Image Studio controls
            isSyncingFilterChips = true
            isSyncingAdjustments = true
            try {
                if (currentPage != null) {
                    when (currentPage.filterPreset) {
                        "Sepia" -> binding.chipFilterSepia.isChecked = true
                        "Vintage" -> binding.chipFilterVintage.isChecked = true
                        "Cool" -> binding.chipFilterCool.isChecked = true
                        "Warm" -> binding.chipFilterWarm.isChecked = true
                        else -> {
                            when (currentPage.mode) {
                                DocumentScanner.DocumentMode.ORIGINAL -> binding.chipFilterOriginal.isChecked = true
                                DocumentScanner.DocumentMode.BLACK_AND_WHITE -> binding.chipFilterBw.isChecked = true
                                DocumentScanner.DocumentMode.GRAYSCALE -> binding.chipFilterGrayscale.isChecked = true
                                DocumentScanner.DocumentMode.RECEIPT -> binding.chipFilterReceipt.isChecked = true
                                DocumentScanner.DocumentMode.ID_DOCUMENT -> binding.chipFilterIdDoc.isChecked = true
                                else -> binding.chipFilterEnhanced.isChecked = true
                            }
                        }
                    }

                    // Sync sliders
                    binding.sliderDocExposure.value = currentPage.exposureStops.toFloat().coerceIn(-2f, 2f)
                    binding.sliderDocContrast.value = currentPage.contrast.toFloat().coerceIn(0.5f, 2f)
                    binding.sliderDocSaturation.value = currentPage.saturation.toFloat().coerceIn(0f, 2f)
                    binding.sliderDocWarmth.value = currentPage.warmth.toFloat().coerceIn(-1f, 1f)
                    binding.sliderDocSharpen.value = currentPage.adjustSharpen.toFloat().coerceIn(0f, 2f)
                    binding.sliderDocDenoise.value = currentPage.adjustDenoise.toFloat().coerceIn(0f, 10f)

                    // Sync watermark
                    if (binding.etDocWatermarkText.text?.toString() != currentPage.watermarkText) {
                        binding.etDocWatermarkText.setText(currentPage.watermarkText)
                    }
                    when (currentPage.watermarkPosition.lowercase()) {
                        "center" -> binding.chipDocPosCenter.isChecked = true
                        "top-right" -> binding.chipDocPosTopRight.isChecked = true
                        "top-left" -> binding.chipDocPosTopLeft.isChecked = true
                        "bottom-left" -> binding.chipDocPosBottomLeft.isChecked = true
                        else -> binding.chipDocPosBottomRight.isChecked = true
                    }
                    binding.sliderDocWmSize.value = currentPage.watermarkSize.toFloat().coerceIn(12f, 72f)
                    val normDocWmOpacity = if (currentPage.watermarkOpacity > 1.0f) currentPage.watermarkOpacity / 100f else currentPage.watermarkOpacity
                    binding.sliderDocWmOpacity.value = (normDocWmOpacity * 100f).coerceIn(10f, 100f)
                }
            } finally {
                isSyncingFilterChips = false
                isSyncingAdjustments = false
            }

            // Enable/disable navigation buttons based on current index
            binding.btnDocPageMoveLeft.isEnabled = (session.activePageIndex > 0)
            binding.btnDocPageMoveRight.isEnabled = (session.activePageIndex < session.pageCount - 1)

            // Populate visual horizontal thumbnail strip
            renderThumbnailStrip()
        }
    }

    private var thumbnailAdapter: DocumentThumbnailAdapter? = null

    private fun renderThumbnailStrip() {
        if (thumbnailAdapter == null) {
            thumbnailAdapter = DocumentThumbnailAdapter(
                onPageClick = { index ->
                    session.selectPage(index)
                    updateUi()
                },
                onAddClick = {
                    openCameraViewfinder()
                }
            )
            binding.rvDocThumbnails.adapter = thumbnailAdapter
        }
        thumbnailAdapter?.notifyDataSetChanged()
        if (session.activePageIndex in 0 until session.pageCount) {
            binding.rvDocThumbnails.scrollToPosition(session.activePageIndex)
        }
    }

    private inner class DocumentThumbnailAdapter(
        private val onPageClick: (Int) -> Unit,
        private val onAddClick: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val density = activity.resources.displayMetrics.density

        override fun getItemViewType(position: Int): Int {
            return if (position < session.pageCount) 0 else 1
        }

        override fun getItemCount(): Int = session.pageCount + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val card = MaterialCardView(parent.context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        (62 * density).toInt(),
                        (82 * density).toInt()
                    ).apply {
                        setMargins(0, 0, (8 * density).toInt(), 0)
                    }
                    radius = 10 * density
                    setCardBackgroundColor(activity.getColor(R.color.vf_surface_variant))
                    isClickable = true
                    isFocusable = true
                }
                val cardInner = LinearLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    orientation = LinearLayout.VERTICAL
                }
                val ivThumb = ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (58 * density).toInt()
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val tvBadge = TextView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (24 * density).toInt()
                    )
                    gravity = android.view.Gravity.CENTER
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                cardInner.addView(ivThumb)
                cardInner.addView(tvBadge)
                card.addView(cardInner)
                PageViewHolder(card, ivThumb, tvBadge)
            } else {
                val addCard = MaterialCardView(parent.context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        (62 * density).toInt(),
                        (82 * density).toInt()
                    )
                    radius = 10 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = activity.getColor(R.color.vf_surface_stroke)
                    setCardBackgroundColor(activity.getColor(R.color.vf_surface))
                    isClickable = true
                    isFocusable = true
                }
                val addInner = LinearLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    gravity = android.view.Gravity.CENTER
                    orientation = LinearLayout.VERTICAL
                }
                val addIcon = ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (24 * density).toInt(),
                        (24 * density).toInt()
                    )
                    setImageResource(R.drawable.ic_camera)
                    imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.vf_primary))
                    contentDescription = "Add Page"
                }
                val addText = TextView(parent.context).apply {
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
                AddViewHolder(addCard)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is PageViewHolder) {
                val page = session.pages[position]
                val isSelected = (position == session.activePageIndex)
                holder.card.strokeWidth = if (isSelected) (2.5f * density).toInt() else (1 * density).toInt()
                holder.card.strokeColor = if (isSelected) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_surface_stroke)
                holder.ivThumb.contentDescription = "Page ${position + 1}"
                val thumbBmp = page.thumbnailBitmapCache ?: page.getDisplayBitmap(activity)
                holder.ivThumb.setImageBitmap(thumbBmp)
                holder.tvBadge.text = "%02d".format(position + 1)
                holder.tvBadge.setTextColor(if (isSelected) activity.getColor(R.color.vf_primary) else activity.getColor(R.color.vf_text_primary))
                holder.card.setOnClickListener { onPageClick(position) }
            } else if (holder is AddViewHolder) {
                holder.card.setOnClickListener { onAddClick() }
            }
        }
    }

    private class PageViewHolder(
        val card: MaterialCardView,
        val ivThumb: ImageView,
        val tvBadge: TextView
    ) : RecyclerView.ViewHolder(card)

    private class AddViewHolder(
        val card: MaterialCardView
    ) : RecyclerView.ViewHolder(card)

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

    val isBusyProcessing: Boolean
        get() = isInternalProcessing.get() || scannerJob?.isActive == true || activeAnalysisJob?.isActive == true

    override fun hasPickedMedia(): Boolean = session.pageCount > 0
    override fun hasUnsavedWork(): Boolean = session.pageCount > 0
    override val isProcessing: Boolean
        get() = isBusyProcessing
    override val supportsBackgroundExecution: Boolean
        get() = false

    override fun discardAndReleaseAllResources() {
        cancelProcessing()
        session.replaceAllPages(emptyList())
        binding.docQuadOverlayView.clear()
    }

    fun cancelProcessing() {
        scannerJob?.cancel()
        scannerJob = null
        activeAnalysisJob?.cancel()
        activeAnalysisJob = null
        isInternalProcessing.set(false)
    }
}
