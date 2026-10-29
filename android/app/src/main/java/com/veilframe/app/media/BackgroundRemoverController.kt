package com.veilframe.app.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.cv.core.CvPriority
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.segmentation.rembg.CelBackgroundRemover
import com.veilframe.app.cv.segmentation.rembg.RembgSessionManager
import com.veilframe.app.cv.segmentation.rembg.RembgExportFormat
import com.veilframe.app.cv.segmentation.rembg.RembgImageUtils
import com.veilframe.app.cv.segmentation.rembg.RembgModel
import com.veilframe.app.cv.segmentation.rembg.RembgModelDownloadManager
import com.veilframe.app.cv.segmentation.rembg.RembgModelRepository
import com.veilframe.app.databinding.DialogModelRequiredBinding
import com.veilframe.app.databinding.DialogRembgModelManagerBinding
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.ui.motion.VeilFrameInteraction
import com.veilframe.app.ui.views.BeforeAfterSplitView
import com.veilframe.app.ui.views.RefineBrushCanvasView
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Controller managing the on-device AI Background Remover workspace:
 * - 1:1 adoption of Cel-Android neural background removal pipeline
 * - SOTA ONNX models: BiRefNet Lite, ISNet General, U2Net Human
 * - On-demand model download & management (no models bundled with APK)
 * - M3 Expressive Model Manager dialog with download, delete, redownload, storage accounting
 * - Edge post-processing: tighten edges (1px alpha erosion), feather edges (1px blur), trim transparent
 * - Interactive Before / After Split comparison slider with synchronized pan & zoom
 * - Transparent PNG & White-backdrop JPG export to Gallery
 */
class BackgroundRemoverController(
    private val activity: AppCompatActivity,
    private val binding: LayoutBackgroundRemoverBinding,
    private val scope: CoroutineScope,
    private val onPickImageRequest: () -> Unit,
    private val onExportPngRequest: (File) -> Unit,
    private val onNavigateBack: () -> Unit
) : com.veilframe.app.navigation.ToolWorkStateContract {
    private var processingJob: Job? = null
    private var sourceBytes: ByteArray? = null
    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var rawCutoutBitmap: Bitmap? = null

    private fun releaseCutoutBitmaps() {
        if (resultBitmap !== rawCutoutBitmap) {
            resultBitmap?.recycle()
        }
        rawCutoutBitmap?.recycle()
        resultBitmap = null
        rawCutoutBitmap = null
    }
    private var sourceFileName: String = "image"
    var currentSourceUri: Uri? = null

    enum class SaveCutoutState {
        IDLE,
        SAVING,
        SAVED,
        SAVE_AGAIN
    }

    private var currentSaveCutoutState: SaveCutoutState = SaveCutoutState.IDLE
    private var saveStateJob: Job? = null

    enum class RembgMode {
        HYBRID,
        U2NETP,
        MODNET
    }

    var currentMode: RembgMode = RembgMode.HYBRID
        private set

    /** Selected AI model (defaults to bundled U^2-Net-P) */
    private var currentModel: RembgModel = RembgModel.U2NETP

    /** Last error message if processing failed */
    private var lastFailure: String? = null

    /** Model storage and execution subsystems */
    private val repository = RembgModelRepository(activity)
    private val downloadManager = RembgModelDownloadManager(activity, repository)
    private val sessionManager = RembgSessionManager(repository)
    private val remover = CelBackgroundRemover(activity, repository, sessionManager)

    private companion object {
        const val TAG = "VeilFrame.BgRemover"
    }

    fun init() {
        binding.toolbarBgRemover.setNavigationOnClickListener {
            if (isRefineMode) {
                exitRefineMode(applyEdits = false)
            } else {
                onNavigateBack()
            }
        }

        binding.btnBgModelManager.setOnClickListener {
            showModelManagerDialog(binding.btnBgModelManager)
        }

        binding.btnBgModelInfo.setOnClickListener {
            showModelInfoDialog()
        }

        binding.toggleGroupMattingMode.check(
            when (currentMode) {
                RembgMode.HYBRID -> binding.btnModeSelectHybrid.id
                RembgMode.U2NETP -> binding.btnModeSelectU2Net.id
                RembgMode.MODNET -> binding.btnModeSelectModNet.id
            }
        )
        binding.toggleGroupMattingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = when (checkedId) {
                    binding.btnModeSelectU2Net.id -> {
                        currentModel = RembgModel.U2NETP
                        RembgMode.U2NETP
                    }
                    binding.btnModeSelectModNet.id -> {
                        currentModel = RembgModel.MODNET
                        RembgMode.MODNET
                    }
                    else -> {
                        currentModel = if (repository.isModelReady(RembgModel.MODNET)) RembgModel.MODNET else RembgModel.U2NETP
                        RembgMode.HYBRID
                    }
                }
                updateActiveModelBadge()
            }
        }

        binding.btnBgPickImage.setOnClickListener {
            onPickImageRequest()
        }

        binding.btnBgChangeImage.setOnClickListener {
            onPickImageRequest()
        }

        binding.btnBgExecuteRemoval.setOnClickListener {
            executeRemoval()
        }


        binding.btnBgSavePng.setOnClickListener {
            exportImage()
        }

        binding.btnBgToolbarExport.setOnClickListener {
            exportImage()
        }

        binding.btnBgRefineBrush.setOnClickListener {
            enterRefineMode()
        }

        binding.chipGroupBgBackdrop.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(binding.chipBackdropWhite.id) ->
                    BeforeAfterSplitView.BackgroundMode.PURE_WHITE
                checkedIds.contains(binding.chipBackdropBlack.id) ->
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK
                else ->
                    BeforeAfterSplitView.BackgroundMode.TRANSPARENT_CHECKERBOARD
            }
            binding.splitViewBgCompare.backgroundMode = mode
        }

        // Toggle transparency trimming dynamically without re-running inference or losing edits
        binding.switchTrimTransparent.setOnCheckedChangeListener { _, isChecked ->
            val raw = rawCutoutBitmap
            if (raw != null) {
                val prev = resultBitmap
                resultBitmap = if (isChecked) {
                    com.veilframe.app.cv.segmentation.rembg.MaskPostprocessor.trimTransparent(raw)
                } else {
                    raw
                }
                if (prev !== resultBitmap && prev !== raw) {
                    prev?.recycle()
                }
                binding.splitViewBgCompare.setBitmaps(sourceBitmap, resultBitmap)
                updateUi()
            }
        }

        // Picsart-style manual brush refine listeners
        binding.toggleGroupBrushMode.check(binding.btnModeErase.id)
        binding.toggleGroupBrushMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.btnModeErase.id -> binding.refineBrushCanvas.brushMode = RefineBrushCanvasView.BrushMode.ERASE
                    binding.btnModeRestore.id -> binding.refineBrushCanvas.brushMode = RefineBrushCanvasView.BrushMode.RESTORE
                }
            }
        }

        binding.sliderBrushSize.addOnChangeListener { _, value, _ ->
            binding.refineBrushCanvas.brushRadiusPx = value
            binding.tvBrushSizeVal.text = "${value.toInt()} px"
            binding.refineBrushCanvas.showStrokePreview(value, keepVisible = true)
        }

        binding.sliderBrushSize.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                binding.refineBrushCanvas.showStrokePreview(slider.value, keepVisible = true)
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                binding.refineBrushCanvas.hideStrokePreview()
            }
        })

        binding.refineBrushCanvas.onHistoryChangedListener = { canUndo, canRedo ->
            binding.btnRefineUndo.isEnabled = canUndo
            binding.btnRefineRedo.isEnabled = canRedo
            binding.btnRefineUndo.alpha = if (canUndo) 1.0f else 0.4f
            binding.btnRefineRedo.alpha = if (canRedo) 1.0f else 0.4f
        }

        binding.btnRefineUndo.setOnClickListener {
            binding.refineBrushCanvas.undo()
        }

        binding.btnRefineRedo.setOnClickListener {
            binding.refineBrushCanvas.redo()
        }

        binding.btnRefineResetZoom.setOnClickListener {
            binding.refineBrushCanvas.resetView()
        }

        binding.btnRefineCancel.setOnClickListener {
            exitRefineMode(applyEdits = false)
        }

        binding.btnRefineDone.setOnClickListener {
            exitRefineMode(applyEdits = true)
        }

        VeilFrameInteraction.bindWorkspace(binding.root)
        updateActiveModelBadge()
        updateUi()
    }

    fun cleanup() {
        try {
            binding.refineBrushCanvas.cleanup()
            sessionManager.closeAll()
            downloadManager.cancelDownload()
            sourceBitmap?.recycle()
            sourceBitmap = null
            releaseCutoutBitmaps()
            sourceBytes = null
            currentSourceUri = null
        } catch (ignored: Throwable) {}
    }

    fun handleImageSelected(uri: Uri) {
        currentSourceUri = uri
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed reading image stream", e)
                null
            }

            if (bytes == null || bytes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Could not open selected image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val decoded = try {
                RembgImageUtils.decodeOrientedBitmap(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Decoding oriented bitmap failed", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (decoded != null) {
                    val oldSrc = sourceBitmap
                    sourceBytes = bytes
                    sourceBitmap = decoded
                    releaseCutoutBitmaps()
                    oldSrc?.recycle()
                    lastFailure = null
                    val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
                    sourceFileName = rawName.substringBeforeLast('.', rawName)
                    updateUi()
                } else {
                    Toast.makeText(activity, "Could not decode image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeRemoval() {
        val bytes = sourceBytes ?: return

        if (currentMode == RembgMode.MODNET && !repository.isModelReady(RembgModel.MODNET)) {
            showRequiredModelDownloadDialog(
                title = "MODNet HD Model Required",
                message = "MODNet HD Portrait Matting (12.9 MB) is required to run in MODNet mode. Download it now to continue, or switch to the bundled U²-Net-P model instantly.",
                targetModel = RembgModel.MODNET,
                originView = binding.btnBgExecuteRemoval,
                onProceedWithBundled = {
                    currentMode = RembgMode.U2NETP
                    currentModel = RembgModel.U2NETP
                    binding.toggleGroupMattingMode.check(binding.btnModeSelectU2Net.id)
                    updateActiveModelBadge()
                    runRemovalPipeline(bytes)
                }
            )
            return
        }

        if (currentMode == RembgMode.HYBRID && !repository.isModelReady(RembgModel.MODNET)) {
            showRequiredModelDownloadDialog(
                title = "Enhance Hybrid with HD Portrait Model?",
                message = "MODNet HD Portrait Model (12.9 MB) enables Hybrid Mode to perform sub-pixel human hair matting while keeping U²-Net-P for objects.\n\nWould you like to download MODNet now, or proceed immediately with the bundled engine?",
                targetModel = RembgModel.MODNET,
                originView = binding.btnBgExecuteRemoval,
                onProceedWithBundled = {
                    runRemovalPipeline(bytes)
                }
            )
            return
        }

        runRemovalPipeline(bytes)
    }

    private fun showRequiredModelDownloadDialog(
        title: String,
        message: String,
        targetModel: RembgModel,
        originView: View? = null,
        onProceedWithBundled: () -> Unit
    ) {
        val dialogBinding = DialogModelRequiredBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.tvModelReqTitle.text = title
        dialogBinding.tvModelReqMessage.text = message
        dialogBinding.btnModelReqDownload.text = "Download & Process"
        dialogBinding.btnModelReqUseLanczos.text = "Use Bundled U²-Net-P (Instant)"

        dialogBinding.btnModelReqClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }
        dialogBinding.btnModelReqCancel.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        dialogBinding.btnModelReqUseLanczos.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            onProceedWithBundled()
        }

        val reqListener = object : ModelDownloadBackgroundService.DownloadListener {
            override fun onProgress(modelId: String, downloaded: Long, total: Long, percent: Int, speedBytesPerSec: Long) {
                if (modelId != targetModel.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        val speedMb = speedBytesPerSec / (1024.0 * 1024.0)
                        dialogBinding.progressModelReq.isIndeterminate = false
                        dialogBinding.progressModelReq.progress = percent
                        dialogBinding.tvModelReqProgressStatus.text =
                            String.format(Locale.US, "Downloading %s: %d%% (%.1f MB/s)", targetModel.displayName, percent, speedMb)
                    }
                }
            }

            override fun onVerifying(modelId: String) {
                if (modelId != targetModel.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        dialogBinding.progressModelReq.isIndeterminate = true
                        dialogBinding.tvModelReqProgressStatus.text = "Verifying integrity…"
                    }
                }
            }

            override fun onSuccess(modelId: String, modelType: String) {
                if (modelId != targetModel.id) return
                ModelDownloadBackgroundService.removeListener(this)
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
                    }
                    updateActiveModelBadge()
                    Toast.makeText(activity, "${targetModel.displayName} ready! Starting removal…", Toast.LENGTH_SHORT).show()
                    sourceBytes?.let { bytes ->
                        runRemovalPipeline(bytes)
                    }
                }
            }

            override fun onError(modelId: String, error: String) {
                if (modelId != targetModel.id) return
                ModelDownloadBackgroundService.removeListener(this)
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        dialogBinding.progressModelReq.visibility = View.GONE
                        dialogBinding.tvModelReqProgressStatus.text = "Download failed: $error"
                        dialogBinding.tvModelReqProgressStatus.setTextColor(activity.getColor(R.color.vf_accent_red))
                        dialogBinding.btnModelReqDownload.isEnabled = true
                        dialogBinding.btnModelReqUseLanczos.isEnabled = true
                    }
                }
            }

            override fun onCancelled(modelId: String) {
                if (modelId != targetModel.id) return
                ModelDownloadBackgroundService.removeListener(this)
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        dialogBinding.progressModelReq.visibility = View.GONE
                        dialogBinding.btnModelReqDownload.isEnabled = true
                        dialogBinding.btnModelReqUseLanczos.isEnabled = true
                    }
                }
            }
        }
        ModelDownloadBackgroundService.addListener(reqListener)

        dialog.setOnDismissListener {
            ModelDownloadBackgroundService.removeListener(reqListener)
            if (ModelDownloadBackgroundService.isDownloading(targetModel.id)) {
                ModelDownloadBackgroundService.promoteToBackground(activity)
                Toast.makeText(activity, "Download continuing in background. Check notifications.", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnModelReqDownload.setOnClickListener {
            dialogBinding.progressModelReq.visibility = View.VISIBLE
            dialogBinding.progressModelReq.isIndeterminate = true
            dialogBinding.tvModelReqProgressStatus.visibility = View.VISIBLE
            dialogBinding.tvModelReqProgressStatus.text = "Connecting to download server…"
            dialogBinding.tvModelReqProgressStatus.setTextColor(activity.getColor(R.color.vf_accent_blue))
            dialogBinding.btnModelReqDownload.isEnabled = false
            dialogBinding.btnModelReqUseLanczos.isEnabled = false
            ModelDownloadBackgroundService.startDownload(activity, "REMBG", targetModel.id, targetModel.displayName, runInBackground = false)
        }

        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    private fun runRemovalPipeline(bytes: ByteArray) {
        val effectiveModel = when (currentMode) {
            RembgMode.U2NETP -> RembgModel.U2NETP
            RembgMode.MODNET -> RembgModel.MODNET
            RembgMode.HYBRID -> {
                if (repository.isModelReady(RembgModel.MODNET)) {
                    RembgModel.MODNET
                } else {
                    RembgModel.U2NETP
                }
            }
        }

        binding.cardBgProcessingOverlay.visibility = View.VISIBLE
        binding.progressBgInference.isIndeterminate = false
        binding.progressBgInference.progress = 10
        binding.tvBgProgressPercent.text = "10%"
        binding.tvBgProgressMessage.text = when (currentMode) {
            RembgMode.HYBRID -> "Running Hybrid Engine (YOLO + ${effectiveModel.displayName})…"
            else -> "Loading ${effectiveModel.displayName}…"
        }

        val trim = binding.switchTrimTransparent.isChecked
        val tighten = false
        val feather = false

        // Governed execution: INTERACTIVE lane + memory admission check via CvRuntime.
        val srcBmp = sourceBitmap
        val w = srcBmp?.width ?: 1024
        val h = srcBmp?.height ?: 1024
        val estimate = CvRuntime.estimateBytes(w, h, 4)

        processingJob = scope.launch {
            val job = CvRuntime.engine.submit(
                name = "background-removal",
                priority = CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 60_000L,
            ) { ctx ->
                // Cooperative cancellation check
                ctx.ensureActive()

                remover.removeBackground(
                    imageBytes = bytes,
                    model = effectiveModel,
                    trim = false,
                    tightenEdges = tighten,
                    featherEdges = feather,
                    onProgress = { progress ->
                        ctx.ensureActive()
                        ctx.reportProgress(progress.percent / 100f)
                        withContext(Dispatchers.Main) {
                            binding.progressBgInference.progress = progress.percent
                            binding.tvBgProgressPercent.text = "${progress.percent}%"
                            binding.tvBgProgressMessage.text = progress.message
                        }
                    }
                )
            }

            val outcome = job.await()
            withContext(Dispatchers.Main) {
                binding.cardBgProcessingOverlay.visibility = View.GONE
                outcome.fold(
                    onOk = { result ->
                        val prevRaw = rawCutoutBitmap
                        val prevRes = resultBitmap
                        rawCutoutBitmap = result.cutoutBitmap
                        resultBitmap = if (binding.switchTrimTransparent.isChecked) {
                            com.veilframe.app.cv.segmentation.rembg.MaskPostprocessor.trimTransparent(result.cutoutBitmap)
                        } else {
                            result.cutoutBitmap
                        }
                        if (prevRaw !== rawCutoutBitmap && prevRaw !== resultBitmap) {
                            prevRaw?.recycle()
                        }
                        if (prevRes !== resultBitmap && prevRes !== rawCutoutBitmap) {
                            prevRes?.recycle()
                        }
                        lastFailure = null
                        updateUi()

                        if (result.warnings.isNotEmpty()) {
                            Toast.makeText(activity, result.warnings.first(), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onErr = { err ->
                        Log.e(TAG, "Background removal failed: [${err.code}] ${err.message}")
                        lastFailure = err.message
                        Toast.makeText(
                            activity,
                            "Removal failed: ${err.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        updateUi()
                    }
                )
            }
        }
    }

    private fun showModelInfoDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("AI Matting Engines")
            .setMessage(
                "• Hybrid (Smart Auto)\n" +
                "Intelligently routes humans, animals, and objects for optimal edge preservation. Uses MODNet for portraits when available, and U\u00b2-Net-P for general objects.\n\n" +
                "• U\u00b2-Net-P (Bundled)\n" +
                "Ultra-fast, on-device saliency model optimized for products, items, and general subjects with zero downloads needed.\n\n" +
                "• MODNet (HD Portrait)\n" +
                "High-resolution neural portrait matting engine specialized in fine human hair strands, transparent textures, and complex outlines."
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    fun showModelManagerDialog(originView: View? = null) {
        val dialogBinding = DialogRembgModelManagerBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnModelManagerClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        lateinit var refreshCards: () -> Unit

        val managerListener = object : ModelDownloadBackgroundService.DownloadListener {
            override fun onProgress(modelId: String, downloaded: Long, total: Long, percent: Int, speedBytesPerSec: Long) {
                if (modelId != RembgModel.MODNET.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        dialogBinding.progressModelIsNet.visibility = View.VISIBLE
                        dialogBinding.progressModelIsNet.isIndeterminate = false
                        dialogBinding.progressModelIsNet.progress = percent
                        dialogBinding.btnDownloadModelIsNet.isEnabled = false
                        dialogBinding.btnRedownloadModelIsNet.isEnabled = false
                    }
                }
            }

            override fun onVerifying(modelId: String) {
                if (modelId != RembgModel.MODNET.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        dialogBinding.progressModelIsNet.visibility = View.VISIBLE
                        dialogBinding.progressModelIsNet.isIndeterminate = true
                    }
                }
            }

            override fun onSuccess(modelId: String, modelType: String) {
                if (modelId != RembgModel.MODNET.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        refreshCards()
                    }
                    updateActiveModelBadge()
                    Toast.makeText(activity, "MODNet ready!", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(modelId: String, error: String) {
                if (modelId != RembgModel.MODNET.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        refreshCards()
                        Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onCancelled(modelId: String) {
                if (modelId != RembgModel.MODNET.id) return
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        refreshCards()
                    }
                }
            }
        }
        ModelDownloadBackgroundService.addListener(managerListener)

        dialog.setOnDismissListener {
            ModelDownloadBackgroundService.removeListener(managerListener)
            if (ModelDownloadBackgroundService.isAnyDownloading()) {
                ModelDownloadBackgroundService.promoteToBackground(activity)
                Toast.makeText(activity, "Download continuing in background. Check notifications.", Toast.LENGTH_SHORT).show()
            }
        }

        refreshCards = {
            val usedBytes = repository.getTotalStorageUsedBytes()
            val availBytes = repository.getAvailableStorageBytes()
            val usedMb = String.format(Locale.US, "%.1f MB", usedBytes / (1024.0 * 1024.0))
            val availGb = String.format(Locale.US, "%.1f GB", availBytes / (1024.0 * 1024.0 * 1024.0))
            dialogBinding.tvModelStorageSummary.text = "Models Storage: $usedMb used • $availGb available"

            val readyCount = repository.listReadyModels().size
            dialogBinding.tvModelCountSummary.text = "$readyCount of 2 AI Models Ready"

            // Card 1: U^2-Net-P (Bundled)
            dialogBinding.tvModelStatusBiRefNet.text = "Bundled in App"
            dialogBinding.tvModelStatusBiRefNet.setTextColor(activity.getColor(R.color.vf_accent_green))
            dialogBinding.btnDownloadModelBiRefNet.visibility = View.GONE
            dialogBinding.btnDeleteModelBiRefNet.visibility = View.GONE
            dialogBinding.btnRedownloadModelBiRefNet.visibility = View.GONE
            dialogBinding.btnSelectModelBiRefNet.visibility = View.VISIBLE
            if (currentMode == RembgMode.U2NETP) {
                dialogBinding.btnSelectModelBiRefNet.text = "Active"
                dialogBinding.btnSelectModelBiRefNet.isEnabled = false
            } else {
                dialogBinding.btnSelectModelBiRefNet.text = "Select"
                dialogBinding.btnSelectModelBiRefNet.isEnabled = true
            }

            // Card 2: MODNet (HD Matting, Downloadable)
            val isModNetReady = repository.isModelReady(RembgModel.MODNET)
            if (isModNetReady) {
                dialogBinding.tvModelStatusIsNet.text = "Installed"
                dialogBinding.tvModelStatusIsNet.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModelIsNet.visibility = View.GONE
                dialogBinding.btnDeleteModelIsNet.visibility = View.VISIBLE
                dialogBinding.btnRedownloadModelIsNet.visibility = View.VISIBLE
                dialogBinding.btnSelectModelIsNet.visibility = View.VISIBLE
                if (currentMode == RembgMode.MODNET) {
                    dialogBinding.btnSelectModelIsNet.text = "Active"
                    dialogBinding.btnSelectModelIsNet.isEnabled = false
                } else {
                    dialogBinding.btnSelectModelIsNet.text = "Select"
                    dialogBinding.btnSelectModelIsNet.isEnabled = true
                }
            } else {
                dialogBinding.tvModelStatusIsNet.text = "Not Installed"
                dialogBinding.tvModelStatusIsNet.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModelIsNet.visibility = View.VISIBLE
                dialogBinding.btnDeleteModelIsNet.visibility = View.GONE
                dialogBinding.btnRedownloadModelIsNet.visibility = View.GONE
                dialogBinding.btnSelectModelIsNet.visibility = View.GONE
            }

            val isModNetDownloading = ModelDownloadBackgroundService.isDownloading(RembgModel.MODNET.id)
            if (isModNetDownloading) {
                dialogBinding.progressModelIsNet.visibility = View.VISIBLE
                dialogBinding.progressModelIsNet.isIndeterminate = true
                dialogBinding.btnDownloadModelIsNet.isEnabled = false
                dialogBinding.btnRedownloadModelIsNet.isEnabled = false
            } else {
                dialogBinding.progressModelIsNet.visibility = View.GONE
                dialogBinding.btnDownloadModelIsNet.isEnabled = true
                dialogBinding.btnRedownloadModelIsNet.isEnabled = true
            }

            // Card 3: Hidden
            dialogBinding.cardModelU2Net.visibility = View.GONE
        }

        fun download(model: RembgModel, progressView: com.google.android.material.progressindicator.LinearProgressIndicator, triggerButton: View) {
            progressView.visibility = View.VISIBLE
            progressView.isIndeterminate = true
            triggerButton.isEnabled = false
            ModelDownloadBackgroundService.startDownload(activity, "REMBG", model.id, model.displayName, runInBackground = false)
        }

        // U2Net-P (Card 1) listeners
        dialogBinding.btnSelectModelBiRefNet.setOnClickListener {
            currentMode = RembgMode.U2NETP
            currentModel = RembgModel.U2NETP
            binding.toggleGroupMattingMode.check(binding.btnModeSelectU2Net.id)
            refreshCards()
            updateActiveModelBadge()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            releaseCutoutBitmaps()
            updateUi()
        }

        // MODNet (Card 2) listeners
        dialogBinding.btnDownloadModelIsNet.setOnClickListener {
            download(RembgModel.MODNET, dialogBinding.progressModelIsNet, dialogBinding.btnDownloadModelIsNet)
        }
        dialogBinding.btnRedownloadModelIsNet.setOnClickListener {
            repository.deleteModel(RembgModel.MODNET)
            sessionManager.closeSession(RembgModel.MODNET)
            download(RembgModel.MODNET, dialogBinding.progressModelIsNet, dialogBinding.btnRedownloadModelIsNet)
        }
        dialogBinding.btnDeleteModelIsNet.setOnClickListener {
            ModelDownloadBackgroundService.cancelDownload(activity, RembgModel.MODNET.id)
            repository.deleteModel(RembgModel.MODNET)
            sessionManager.closeSession(RembgModel.MODNET)
            if (currentMode == RembgMode.MODNET) {
                currentMode = RembgMode.HYBRID
                binding.toggleGroupMattingMode.check(binding.btnModeSelectHybrid.id)
            }
            currentModel = RembgModel.U2NETP
            refreshCards()
            updateActiveModelBadge()
        }
        dialogBinding.btnSelectModelIsNet.setOnClickListener {
            currentMode = RembgMode.MODNET
            currentModel = RembgModel.MODNET
            binding.toggleGroupMattingMode.check(binding.btnModeSelectModNet.id)
            refreshCards()
            updateActiveModelBadge()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            releaseCutoutBitmaps()
            updateUi()
        }

        refreshCards()
        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    private fun updateActiveModelBadge() {
        val isModNetReady = repository.isModelReady(RembgModel.MODNET)
        when (currentMode) {
            RembgMode.HYBRID -> {
                binding.tvBgCurrentModelName.text = "Hybrid (Smart Auto)"
                if (isModNetReady) {
                    binding.tvBgModelBadge.text = "Studio Ready"
                    binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                    binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                } else {
                    binding.tvBgModelBadge.text = "Bundled Engine"
                    binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_primary))
                    binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                }
            }
            RembgMode.U2NETP -> {
                binding.tvBgCurrentModelName.text = "U²-Net-P (Bundled)"
                binding.tvBgModelBadge.text = "Ready (Bundled)"
                binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
            }
            RembgMode.MODNET -> {
                binding.tvBgCurrentModelName.text = "MODNet (HD Portrait)"
                if (isModNetReady) {
                    binding.tvBgModelBadge.text = "Installed (12.9 MB)"
                    binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                    binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                } else {
                    binding.tvBgModelBadge.text = "Download Needed"
                    binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_amber))
                    binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_warn)
                }
            }
        }
    }

    var isRefineMode: Boolean = false
        private set

    fun enterRefineMode() {
        val src = sourceBitmap ?: return
        val res = resultBitmap ?: return

        isRefineMode = true
        binding.splitViewBgCompare.visibility = View.GONE
        binding.containerBgControls.visibility = View.GONE
        binding.cardBgActionDock.visibility = View.GONE

        binding.refineBrushCanvas.visibility = View.VISIBLE
        binding.cardBgRefineDock.visibility = View.VISIBLE

        binding.refineBrushCanvas.setBitmaps(src, res)
        binding.toolbarBgRemover.title = "Refine Cutout"
        binding.toolbarBgRemover.subtitle = "Erase residue or restore details"
    }

    fun exitRefineMode(applyEdits: Boolean) {
        if (applyEdits) {
            val edited = binding.refineBrushCanvas.getRefinedBitmap()
            if (edited != null) {
                val prevRaw = rawCutoutBitmap
                val prevRes = resultBitmap
                rawCutoutBitmap = edited
                resultBitmap = if (binding.switchTrimTransparent.isChecked) {
                    com.veilframe.app.cv.segmentation.rembg.MaskPostprocessor.trimTransparent(edited)
                } else {
                    edited
                }
                if (prevRaw !== rawCutoutBitmap && prevRaw !== resultBitmap) {
                    prevRaw?.recycle()
                }
                if (prevRes !== resultBitmap && prevRes !== rawCutoutBitmap) {
                    prevRes?.recycle()
                }
                binding.splitViewBgCompare.setBitmaps(sourceBitmap, resultBitmap)
                Toast.makeText(activity, "Refinement applied successfully", Toast.LENGTH_SHORT).show()
            }
        }

        isRefineMode = false
        binding.refineBrushCanvas.visibility = View.GONE
        binding.cardBgRefineDock.visibility = View.GONE

        binding.splitViewBgCompare.visibility = View.VISIBLE
        binding.containerBgControls.visibility = View.VISIBLE
        binding.cardBgActionDock.visibility = View.VISIBLE

        binding.toolbarBgRemover.title = "BG Remover"
        binding.toolbarBgRemover.subtitle = "On-Device Neural Matting"
        updateUi()
    }

    private fun updateSaveCutoutButton() {
        if (resultBitmap == null) {
            binding.btnBgExecuteRemoval.text = "Remove Background"
            binding.btnBgExecuteRemoval.setIconResource(R.drawable.ic_eraser)
            binding.btnBgExecuteRemoval.isEnabled = true
            binding.btnBgExecuteRemoval.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            binding.btnBgSavePng.visibility = View.GONE
            binding.btnBgSavePng.isEnabled = false
            binding.btnBgRefineBrush.visibility = View.GONE
            return
        }

        binding.btnBgExecuteRemoval.text = "Re-process"
        binding.btnBgExecuteRemoval.setIconResource(R.drawable.ic_reset)
        binding.btnBgExecuteRemoval.isEnabled = true
        binding.btnBgExecuteRemoval.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_surface_variant)

        binding.btnBgRefineBrush.visibility = if (!isRefineMode) View.VISIBLE else View.GONE
        binding.btnBgSavePng.visibility = View.VISIBLE
        when (currentSaveCutoutState) {
            SaveCutoutState.IDLE -> {
                binding.btnBgSavePng.text = "Save Cutout"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_action_save)
                binding.btnBgSavePng.isEnabled = true
                binding.btnBgSavePng.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            }
            SaveCutoutState.SAVING -> {
                binding.btnBgSavePng.text = "Saving…"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_loading_spinner)
                binding.btnBgSavePng.isEnabled = false
                binding.btnBgSavePng.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            }
            SaveCutoutState.SAVED -> {
                binding.btnBgSavePng.text = "Saved"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_done)
                binding.btnBgSavePng.isEnabled = false
                binding.btnBgSavePng.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_accent_green)
            }
            SaveCutoutState.SAVE_AGAIN -> {
                binding.btnBgSavePng.text = "Save Again"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_action_save)
                binding.btnBgSavePng.isEnabled = true
                binding.btnBgSavePng.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            }
        }
    }

    private fun exportImage() {
        val bmp = resultBitmap ?: run {
            Toast.makeText(
                activity,
                if (lastFailure != null) "No cutout to save — removal failed ($lastFailure)"
                else "No cutout to save yet",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        saveStateJob?.cancel()
        currentSaveCutoutState = SaveCutoutState.SAVING
        updateSaveCutoutButton()

        val isJpg = binding.chipFormatJpg.isChecked
        scope.launch(Dispatchers.IO) {
            val exportDir = File(activity.filesDir, "exports").apply { mkdirs() }
            val ext = if (isJpg) "jpg" else "png"
            val mimeType = if (isJpg) "image/jpeg" else "image/png"
            val outFile = File(exportDir, "${sourceFileName}_BGREMOVED_${System.currentTimeMillis()}.$ext")

            val finalExportBmp = if (isJpg) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flat)
                val bgColor = when (binding.splitViewBgCompare.backgroundMode) {
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK -> Color.BLACK
                    else -> Color.WHITE
                }
                canvas.drawColor(bgColor)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                flat
            } else {
                when (binding.splitViewBgCompare.backgroundMode) {
                    BeforeAfterSplitView.BackgroundMode.PURE_WHITE -> {
                        val comp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(comp)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        comp
                    }
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK -> {
                        val comp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(comp)
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        comp
                    }
                    else -> bmp
                }
            }

            var savedToGallery = false
            var saveErrorMessage: String? = null
            try {
                FileOutputStream(outFile).use { fos ->
                    if (isJpg) {
                        finalExportBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    } else {
                        finalExportBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }

                // MediaStore gallery save
                val resolver = activity.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, outFile.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val savedUri = resolver.insert(collection, values)
                    ?: throw java.io.IOException("MediaStore insert refused")
                resolver.openOutputStream(savedUri)?.use { out ->
                    outFile.inputStream().use { inp -> inp.copyTo(out) }
                } ?: throw java.io.IOException("openOutputStream returned null")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(savedUri, values, null, null)
                }
                savedToGallery = true
            } catch (e: Exception) {
                Log.e(TAG, "Gallery save failed, falling back to SAF", e)
                saveErrorMessage = e.message
            } finally {
                if (finalExportBmp !== bmp) {
                    finalExportBmp.recycle()
                }
            }

            withContext(Dispatchers.Main) {
                if (savedToGallery) {
                    Toast.makeText(activity, "Saved to Gallery (Pictures/VeilFrame)", Toast.LENGTH_SHORT).show()
                    currentSaveCutoutState = SaveCutoutState.SAVED
                    updateSaveCutoutButton()
                    saveStateJob = scope.launch {
                        delay(2000)
                        currentSaveCutoutState = SaveCutoutState.SAVE_AGAIN
                        updateSaveCutoutButton()
                    }
                } else if (outFile.exists() && outFile.length() > 0) {
                    onExportPngRequest(outFile)
                    currentSaveCutoutState = SaveCutoutState.SAVED
                    updateSaveCutoutButton()
                    saveStateJob = scope.launch {
                        delay(2000)
                        currentSaveCutoutState = SaveCutoutState.SAVE_AGAIN
                        updateSaveCutoutButton()
                    }
                } else {
                    Toast.makeText(activity, "Save failed: ${saveErrorMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                    currentSaveCutoutState = SaveCutoutState.SAVE_AGAIN
                    updateSaveCutoutButton()
                }
            }
        }
    }

    private fun updateUi() {
        updateActiveModelBadge()
        if (sourceBitmap == null) {
            currentSaveCutoutState = SaveCutoutState.IDLE
            saveStateJob?.cancel()
            binding.containerBgEmptyState.visibility = View.VISIBLE
            binding.splitViewBgCompare.visibility = View.GONE
            binding.cardBgActionDock.visibility = View.GONE
            binding.containerBgControls.visibility = View.GONE
            binding.btnBgToolbarExport.visibility = View.GONE
            binding.btnBgSavePng.visibility = View.GONE
            binding.btnBgSavePng.isEnabled = false
            binding.btnBgToolbarExport.isEnabled = false
        } else {
            binding.containerBgEmptyState.visibility = View.GONE
            binding.splitViewBgCompare.visibility = View.VISIBLE
            binding.cardBgActionDock.visibility = View.VISIBLE
            binding.containerBgControls.visibility = View.VISIBLE
            binding.btnBgToolbarExport.visibility = View.GONE
            binding.btnBgToolbarExport.isEnabled = false

            updateSaveCutoutButton()

            binding.splitViewBgCompare.setBitmaps(sourceBitmap, resultBitmap)
        }
    }

    override fun hasPickedMedia(): Boolean = currentSourceUri != null || sourceBitmap != null
    override fun hasUnsavedWork(): Boolean = currentSourceUri != null || resultBitmap != null
    override val isProcessing: Boolean
        get() = processingJob?.isActive == true || binding.cardBgProcessingOverlay.visibility == View.VISIBLE
    override val supportsBackgroundExecution: Boolean get() = false

    override fun discardAndReleaseAllResources() {
        cancelProcessing()
        sourceBitmap?.recycle()
        sourceBitmap = null
        resultBitmap?.recycle()
        resultBitmap = null
        sourceBytes = null
        currentSourceUri = null
        binding.splitViewBgCompare.setBitmaps(null, null)
        updateUi()
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        binding.cardBgProcessingOverlay.visibility = View.GONE
    }
}
