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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.veilframe.app.R
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.document.DocumentScanner
import com.veilframe.app.databinding.LayoutDocumentScannerBinding
import com.veilframe.app.storage.SafStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Controller orchestrating the dedicated Document Scanner workspace:
 * - Dedicated CameraX viewfinder with real-time OpenCV quadrilateral tracking
 * - Rapid continuous multi-capture (burst scanning into session)
 * - Persistent Page Manager (reorder, rotate, duplicate, auto-crop, delete)
 * - Multi-format export pipeline (Single PDF, Separate PDFs, Image Sequence)
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
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    fun init() {
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

        binding.btnDocCamImport.setOnClickListener {
            onChoosePhotosRequest()
        }

        binding.btnDocCamShutter.setOnClickListener {
            takeCameraScan()
        }

        binding.btnDocCamDone.setOnClickListener {
            closeCameraViewfinder()
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

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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
        } catch (e: Exception) {
            Toast.makeText(activity, "Camera binding error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeFrameForDocument(imageProxy: ImageProxy) {
        val frameBitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        if (frameBitmap == null) return

        try {
            val srcMat = BitmapBridge.toMat(frameBitmap)
            val corners = DocumentScanner.findCorners(srcMat)
            srcMat.release()

            val detectedPts = if (corners.size == 4) {
                corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }
            } else null

            activity.runOnUiThread {
                binding.docQuadOverlayView.setDetectedQuad(
                    detectedPts,
                    frameBitmap.width,
                    frameBitmap.height
                )
                if (detectedPts != null) {
                    binding.tvDocCamStatus.text = "Document detected (Steady)"
                } else {
                    binding.tvDocCamStatus.text = "Align document inside frame"
                }
            }
        } catch (_: Throwable) {
            activity.runOnUiThread {
                binding.docQuadOverlayView.clear()
            }
        } finally {
            frameBitmap.recycle()
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

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    if (bitmap != null) {
                        activity.runOnUiThread {
                            handleCameraPhotoCaptured(bitmap)
                            updateCameraDoneBadge()
                            Toast.makeText(activity, "Page ${session.pageCount} captured", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
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
        val loaded = DocumentSession.loadFromDisk(activity, sessionId)
        if (loaded != null && !loaded.isEmpty) {
            session.clear()
            session.id = loaded.id
            session.title = loaded.title
            session.addPages(loaded.pages)
            updateUi()
            Toast.makeText(activity, "Resumed ${loaded.title} (${loaded.pageCount} pages)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAutoCropOnCurrentPage() {
        val page = session.currentPage ?: return
        val origBmp = page.getOriginalBitmap(activity) ?: return

        scope.launch(Dispatchers.IO) {
            var warpedBitmap: Bitmap? = null
            try {
                val srcMat = BitmapBridge.toMat(origBmp)
                val scanResult = DocumentScanner.scan(
                    srcMat,
                    DocumentScanner.Options(mode = page.mode)
                )
                if (scanResult != null) {
                    warpedBitmap = BitmapBridge.toBitmap(scanResult.warped)
                    page.corners = scanResult.corners
                    scanResult.warped.release()
                }
                srcMat.release()
            } catch (_: Throwable) {
                warpedBitmap = null
            }

            withContext(Dispatchers.Main) {
                if (warpedBitmap != null) {
                    page.processedBitmapCache = warpedBitmap
                    binding.ivDocPagePreview.setImageBitmap(warpedBitmap)
                    persistSession()
                }
            }
        }
    }

    private fun applyFilterToCurrentPage(mode: DocumentScanner.DocumentMode) {
        val page = session.currentPage ?: return
        val origBmp = page.getOriginalBitmap(activity) ?: return
        page.mode = mode

        scope.launch(Dispatchers.IO) {
            var filteredBitmap: Bitmap? = null
            try {
                val srcMat = BitmapBridge.toMat(origBmp)
                val scanResult = DocumentScanner.scan(
                    srcMat,
                    DocumentScanner.Options(mode = mode)
                )
                if (scanResult != null) {
                    filteredBitmap = BitmapBridge.toBitmap(scanResult.warped)
                    scanResult.warped.release()
                }
                srcMat.release()
            } catch (_: Throwable) {
                filteredBitmap = null
            }

            withContext(Dispatchers.Main) {
                if (filteredBitmap != null) {
                    page.processedBitmapCache = filteredBitmap
                    binding.ivDocPagePreview.setImageBitmap(filteredBitmap)
                    persistSession()
                }
            }
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

        val formats = DocumentExportEngine.OutputFormat.values().map { it.displayName }.toTypedArray()
        var selectedFormatIndex = 0

        val paperSizes = DocumentExportEngine.PaperSize.values().map { it.displayName }.toTypedArray()
        var selectedPaperIndex = 0

        AlertDialog.Builder(activity)
            .setTitle("Export Document (${session.pageCount} Pages)")
            .setSingleChoiceItems(formats, selectedFormatIndex) { _, which ->
                selectedFormatIndex = which
            }
            .setPositiveButton("Export") { _, _ ->
                val format = DocumentExportEngine.OutputFormat.values()[selectedFormatIndex]
                val options = DocumentExportEngine.ExportOptions(
                    format = format,
                    paperSize = DocumentExportEngine.PaperSize.values()[selectedPaperIndex]
                )
                executeExport(options)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeExport(options: DocumentExportEngine.ExportOptions) {
        Toast.makeText(activity, "Compiling document export...", Toast.LENGTH_SHORT).show()

        scope.launch(Dispatchers.IO) {
            try {
                val result = DocumentExportEngine.exportDocument(activity, session, options)
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
        }
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
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

        val rotation = image.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            rotated
        } else {
            bmp
        }
    }
}
