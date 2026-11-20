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
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.cv.core.CvPriority
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.segmentation.rembg.CelBackgroundRemover
import com.veilframe.app.cv.segmentation.rembg.ColorOsMattingPlugin
import com.veilframe.app.cv.segmentation.rembg.RembgProgress
import com.veilframe.app.cv.segmentation.rembg.RembgSessionManager
import com.veilframe.app.cv.segmentation.rembg.RembgExportFormat
import com.veilframe.app.cv.segmentation.rembg.RembgImageUtils
import com.veilframe.app.cv.segmentation.rembg.RembgImageSource
import com.veilframe.app.cv.segmentation.rembg.RembgResult
import com.veilframe.app.cv.segmentation.rembg.RembgAlphaMatte
import com.veilframe.app.cv.segmentation.rembg.RembgSourceInfo
import com.veilframe.app.cv.segmentation.rembg.OriginalResolutionCutout
import com.veilframe.app.ui.dialogs.M3ExpressiveProgressDialog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference
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
import androidx.core.content.FileProvider
import androidx.appcompat.widget.PopupMenu
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
    private val onExportPngRequest: (File, Boolean) -> Boolean,
    private val onNavigateBack: () -> Unit
) : com.veilframe.app.navigation.ToolWorkStateContract {
    private var processingJob: Job? = null
    private var sourceLoadJob: Job? = null
    private var sourceGeneration = 0L

    private fun invalidateSourceLoad() {
        sourceGeneration++
        originalExportJob?.cancel()
        originalExportJob = null
        sourceLoadJob?.cancel()
        sourceLoadJob = null
    }
    private var sourceImage: RembgImageSource.Staged? = null
    private val sourceLeases = ResourceLeases<RembgImageSource.Staged> { it.file.delete() }
    private var removalGeneration = 0L
    private var originalMatte: RembgAlphaMatte? = null
    private var originalInfo: RembgSourceInfo? = null
    private var originalExportJob: Job? = null

    private fun invalidateOriginalExport() {
        originalExportJob?.cancel()
        originalExportJob = null
        originalMatte = null
        originalInfo = null
    }

    private fun releaseSourceImage() {
        sourceImage?.let(sourceLeases::retire)
        sourceImage = null
    }
    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var rawCutoutBitmap: Bitmap? = null
    private val cutoutLeases = ResourceLeases<Bitmap> { if (!it.isRecycled) it.recycle() }

    // Acquire before scheduling: even a queued encoder owns its pixels until completion.
    private fun launchCutoutExport(bitmap: Bitmap, block: suspend CoroutineScope.() -> Unit) {
        val release = cutoutLeases.acquire(bitmap)
        try {
            scope.launch(Dispatchers.IO, block = block).invokeOnCompletion { release() }
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    private fun releaseCutoutBitmaps() {
        invalidateOriginalExport()
        if (resultBitmap !== rawCutoutBitmap) {
            resultBitmap?.let(cutoutLeases::retire)
        }
        rawCutoutBitmap?.let(cutoutLeases::retire)
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
        MODNET,
        LIFT_AND_SHIFT
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
                RembgMode.LIFT_AND_SHIFT -> binding.btnModeSelectLiftAndShift.id
            }
        )
        binding.toggleGroupMattingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = when (checkedId) {
                    binding.btnModeSelectLiftAndShift.id -> {
                        currentModel = RembgModel.COLOROS_LIFT_SHIFT
                        if (sourceImage == null && !repository.isModelReady(RembgModel.COLOROS_LIFT_SHIFT)) {
                            showRequiredModelDownloadDialog(
                                title = "Download ColorOS Lift & Shift?",
                                message = "ColorOS Lift & Shift (8.4 MB) provides Snapdragon 8 Gen 5 hardware-accelerated subject separation.\n\nWould you like to download Lift & Shift now, or proceed with the bundled engine?",
                                targetModel = RembgModel.COLOROS_LIFT_SHIFT,
                                originView = binding.btnModeSelectLiftAndShift,
                                onProceedWithBundled = {
                                    currentMode = RembgMode.U2NETP
                                    currentModel = RembgModel.U2NETP
                                    binding.toggleGroupMattingMode.check(binding.btnModeSelectU2Net.id)
                                    updateActiveModelBadge()
                                }
                            )
                        }
                        RembgMode.LIFT_AND_SHIFT
                    }
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
                if (sourceImage != null) {
                    executeRemoval()
                }
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

        binding.btnBgMoreOptions.setOnClickListener {
            showMoreOptionsMenu(binding.btnBgMoreOptions)
        }

        binding.btnBgSavePng.setOnClickListener {
            if (currentSaveCutoutState == SaveCutoutState.SAVING) return@setOnClickListener
            exportImage()
        }

        binding.btnBgToolbarExport.setOnClickListener {
            if (currentSaveCutoutState == SaveCutoutState.SAVING) return@setOnClickListener
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
                    prev?.let(cutoutLeases::retire)
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
        invalidateSourceLoad()
        cancelProcessing()
        try {
            binding.refineBrushCanvas.cleanup()
            com.veilframe.app.cv.segmentation.rembg.ColorOsMattingPlugin.close()
            sessionManager.closeAll()
            downloadManager.cancelDownload()
            sourceBitmap?.recycle()
            sourceBitmap = null
            releaseCutoutBitmaps()
            releaseSourceImage()
            currentSourceUri = null
        } catch (ignored: Throwable) {}
    }

    fun handleImageSelected(uri: Uri) {
        if (processingJob?.isActive == true) {
            Toast.makeText(activity, "Finish or cancel the current removal first.", Toast.LENGTH_SHORT).show()
            return
        }
        invalidateSourceLoad()
        val generation = sourceGeneration
        sourceLoadJob = scope.launch(Dispatchers.Main) {
            var pendingBitmap: Bitmap? = null
            var pendingSource: RembgImageSource.Staged? = null
            try {
                // Stage first: no unbounded compressed ByteArray lives on the Java heap.
                withContext(Dispatchers.IO) {
                    val cc = kotlin.coroutines.coroutineContext
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        pendingSource = RembgImageSource.stage(input, activity.cacheDir) { cc.ensureActive() }
                    } ?: throw java.io.IOException("Could not open selected image")
                    pendingBitmap = RembgImageUtils.decodeOrientedBitmap(
                        checkNotNull(pendingSource), maxDimension = RembgImageUtils.MAX_DISPLAY_PX
                    )
                }
                if (generation != sourceGeneration) return@launch
                val decoded = checkNotNull(pendingBitmap)
                val oldSrc = sourceBitmap
                releaseSourceImage()
                sourceImage = checkNotNull(pendingSource)
                pendingSource = null
                sourceBitmap = decoded
                currentSourceUri = uri
                pendingBitmap = null // ownership transferred to the accepted source
                releaseCutoutBitmaps()
                oldSrc?.recycle()
                lastFailure = null
                val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
                sourceFileName = rawName.substringBeforeLast('.', rawName)
                updateUi()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Image import failed", e)
                if (generation == sourceGeneration) {
                    Toast.makeText(activity, "Could not open selected image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingBitmap?.recycle()
                pendingSource?.file?.delete()
            }
        }
    }

    private fun executeRemoval() {
        if (sourceLoadJob?.isActive == true || processingJob?.isActive == true) return
        val source = sourceImage ?: return

        if (currentMode == RembgMode.LIFT_AND_SHIFT && !repository.isModelReady(RembgModel.COLOROS_LIFT_SHIFT)) {
            showRequiredModelDownloadDialog(
                title = "ColorOS Lift & Shift Model Required",
                message = "ColorOS Lift & Shift (8.4 MB) provides Snapdragon 8 Gen 5 hardware-accelerated subject separation.\n\nWould you like to download Lift & Shift now, or switch to the bundled U²-Net-P model?",
                targetModel = RembgModel.COLOROS_LIFT_SHIFT,
                originView = binding.btnBgExecuteRemoval,
                onProceedWithBundled = {
                    currentMode = RembgMode.U2NETP
                    currentModel = RembgModel.U2NETP
                    binding.toggleGroupMattingMode.check(binding.btnModeSelectU2Net.id)
                    updateActiveModelBadge()
                    runRemovalPipeline(source)
                }
            )
            return
        }

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
                    runRemovalPipeline(source)
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
                    runRemovalPipeline(source)
                }
            )
            return
        }

        runRemovalPipeline(source)
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
        dialogBinding.btnModelReqUseLanczos.text = "Use Bundled U²-Net-P"
        val intendedSource = sourceImage
        val intendedGeneration = sourceGeneration

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
                    val stillIntended = dialog.isShowing && intendedSource != null &&
                        intendedSource === sourceImage && intendedGeneration == sourceGeneration
                    if (dialog.isShowing) {
                        MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
                    }
                    updateActiveModelBadge()
                    if (stillIntended) {
                        Toast.makeText(activity, "${targetModel.displayName} ready! Starting removal…", Toast.LENGTH_SHORT).show()
                        runRemovalPipeline(checkNotNull(intendedSource))
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

    private fun runRemovalPipeline(source: RembgImageSource.Staged) {
        // Dialog/download callbacks must not process an obsolete source.
        if (source !== sourceImage || sourceLoadJob?.isActive == true || processingJob?.isActive == true) return
        val mode = currentMode
        val generation = ++removalGeneration
        fun ownsResult(): Boolean = generation == removalGeneration && source === sourceImage &&
            !activity.isFinishing && !activity.isDestroyed
        val effectiveModel = when (mode) {
            RembgMode.LIFT_AND_SHIFT -> RembgModel.COLOROS_LIFT_SHIFT
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
            RembgMode.LIFT_AND_SHIFT -> "Running Lift & Shift Cutout…"
            RembgMode.HYBRID -> "Running Hybrid Engine (YOLO + ${effectiveModel.displayName})…"
            else -> "Loading ${effectiveModel.displayName}…"
        }

        // Governed execution: INTERACTIVE lane + memory admission check via CvRuntime.
        val srcBmp = sourceBitmap
        val w = srcBmp?.width ?: 1024
        val h = srcBmp?.height ?: 1024
        val estimate = CvRuntime.estimateBytes(w, h, 4)

        val releaseSource = sourceLeases.acquire(source)
        try {
            processingJob = scope.launch {
                val pending = AtomicReference<RembgResult?>(null)
                var worker: com.veilframe.app.cv.core.CvJob<RembgResult>? = null
                try {
                    val job = CvRuntime.engine.submit(
                        name = "background-removal",
                        priority = CvPriority.INTERACTIVE,
                        memoryEstimate = estimate,
                        timeoutMs = 60_000L,
                    ) { ctx ->
                        ctx.ensureActive()
                        val progress: suspend (RembgProgress) -> Unit = { update ->
                            ctx.ensureActive()
                            ctx.reportProgress(update.percent / 100f)
                            withContext(Dispatchers.Main) {
                                if (!ownsResult()) throw kotlinx.coroutines.CancellationException("Obsolete removal")
                                binding.progressBgInference.progress = update.percent
                                binding.tvBgProgressPercent.text = "${update.percent}%"
                                binding.tvBgProgressMessage.text = update.message
                            }
                        }
                        val result = if (mode == RembgMode.LIFT_AND_SHIFT) {
                            ColorOsMattingPlugin.process(activity, source, trim = false, onProgress = progress)
                        } else {
                            remover.removeBackground(source, effectiveModel, trim = false, onProgress = progress)
                        }
                        // Retain ownership even if the worker's deferred is cancelled
                        // after native inference returns but before the UI can receive it.
                        pending.set(result)
                        result
                    }
                    worker = job
                    val outcome = job.await()
                    withContext(Dispatchers.Main) {
                        if (!ownsResult()) return@withContext
                        binding.cardBgProcessingOverlay.visibility = View.GONE
                        outcome.fold(
                            onOk = { result ->
                                val newRaw = result.cutoutBitmap
                                val newResult = if (binding.switchTrimTransparent.isChecked) {
                                    com.veilframe.app.cv.segmentation.rembg.MaskPostprocessor.trimTransparent(newRaw)
                                } else newRaw
                                val prevRaw = rawCutoutBitmap
                                val prevRes = resultBitmap
                                invalidateOriginalExport()
                                rawCutoutBitmap = newRaw
                                resultBitmap = newResult
                                originalMatte = result.originalMatte
                                originalInfo = result.sourceInfo.takeIf { result.originalMatte != null }
                                pending.compareAndSet(result, null)
                                if (prevRaw !== newRaw && prevRaw !== newResult) prevRaw?.let(cutoutLeases::retire)
                                if (prevRes !== newResult && prevRes !== newRaw && prevRes !== prevRaw) prevRes?.let(cutoutLeases::retire)
                                lastFailure = null
                                updateUi()
                                if (result.warnings.isNotEmpty()) {
                                    Toast.makeText(activity, result.warnings.first(), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onErr = { err ->
                                lastFailure = err.message
                                Log.e(TAG, "Background removal failed: [${err.code}] ${err.message}")
                                Toast.makeText(activity, "Removal failed: ${err.message}", Toast.LENGTH_LONG).show()
                                updateUi()
                            }
                        )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    worker?.cancel("Background removal cancelled")
                    throw cancelled
                } catch (failure: Exception) {
                    if (ownsResult()) {
                        lastFailure = failure.message
                        binding.cardBgProcessingOverlay.visibility = View.GONE
                        Toast.makeText(activity, "Removal failed: ${failure.message}", Toast.LENGTH_LONG).show()
                        updateUi()
                    }
                } finally {
                    // The governed dispatcher owns a separate coroutine scope. UI cancellation
                    // alone is not worker cancellation. Join it before retiring any resources.
                    withContext(NonCancellable) {
                        worker?.let {
                            if (it.isActive) it.cancel("Removal owner left")
                            it.joinWorker()
                        }
                        pending.getAndSet(null)?.cutoutBitmap?.takeUnless { it.isRecycled }?.recycle()
                    }
                }
            }.also { it.invokeOnCompletion { releaseSource() } }
        } catch (failure: Throwable) {
            releaseSource()
            binding.cardBgProcessingOverlay.visibility = View.GONE
            throw failure
        }
    }

    private fun showModelInfoDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("AI Matting Engines")
            .setMessage(
                "• Lift & Shift (Cutout)\n" +
                "Hardware-accelerated neural subject separation running on-device via LiteRT GPU/CPU for clean subject cutouts.\n\n" +
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
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        if (modelId == RembgModel.MODNET.id) {
                            dialogBinding.progressModelIsNet.visibility = View.VISIBLE
                            dialogBinding.progressModelIsNet.isIndeterminate = false
                            dialogBinding.progressModelIsNet.progress = percent
                            dialogBinding.btnDownloadModelIsNet.isEnabled = false
                            dialogBinding.btnRedownloadModelIsNet.isEnabled = false
                        } else if (modelId == RembgModel.COLOROS_LIFT_SHIFT.id) {
                            dialogBinding.progressModelU2Net.visibility = View.VISIBLE
                            dialogBinding.progressModelU2Net.isIndeterminate = false
                            dialogBinding.progressModelU2Net.progress = percent
                            dialogBinding.btnDownloadModelU2Net.isEnabled = false
                            dialogBinding.btnRedownloadModelU2Net.isEnabled = false
                        }
                    }
                }
            }

            override fun onVerifying(modelId: String) {
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        if (modelId == RembgModel.MODNET.id) {
                            dialogBinding.progressModelIsNet.visibility = View.VISIBLE
                            dialogBinding.progressModelIsNet.isIndeterminate = true
                        } else if (modelId == RembgModel.COLOROS_LIFT_SHIFT.id) {
                            dialogBinding.progressModelU2Net.visibility = View.VISIBLE
                            dialogBinding.progressModelU2Net.isIndeterminate = true
                        }
                    }
                }
            }

            override fun onSuccess(modelId: String, modelType: String) {
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        refreshCards()
                    }
                    updateActiveModelBadge()
                    val name = if (modelId == RembgModel.COLOROS_LIFT_SHIFT.id) "ColorOS Lift & Shift" else "MODNet"
                    Toast.makeText(activity, "$name ready!", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(modelId: String, error: String) {
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        refreshCards()
                        Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onCancelled(modelId: String) {
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
            dialogBinding.tvModelCountSummary.text = "$readyCount of 3 AI Models Ready"

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

            // Card 3: ColorOS Lift & Shift (Downloadable)
            dialogBinding.cardModelU2Net.visibility = View.VISIBLE
            val isLiftShiftReady = repository.isModelReady(RembgModel.COLOROS_LIFT_SHIFT)
            if (isLiftShiftReady) {
                dialogBinding.tvModelStatusU2Net.text = "Installed"
                dialogBinding.tvModelStatusU2Net.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModelU2Net.visibility = View.GONE
                dialogBinding.btnDeleteModelU2Net.visibility = View.VISIBLE
                dialogBinding.btnRedownloadModelU2Net.visibility = View.VISIBLE
                dialogBinding.btnSelectModelU2Net.visibility = View.VISIBLE
                if (currentMode == RembgMode.LIFT_AND_SHIFT) {
                    dialogBinding.btnSelectModelU2Net.text = "Active"
                    dialogBinding.btnSelectModelU2Net.isEnabled = false
                } else {
                    dialogBinding.btnSelectModelU2Net.text = "Select"
                    dialogBinding.btnSelectModelU2Net.isEnabled = true
                }
            } else {
                dialogBinding.tvModelStatusU2Net.text = "Not Installed"
                dialogBinding.tvModelStatusU2Net.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModelU2Net.visibility = View.VISIBLE
                dialogBinding.btnDeleteModelU2Net.visibility = View.GONE
                dialogBinding.btnRedownloadModelU2Net.visibility = View.GONE
                dialogBinding.btnSelectModelU2Net.visibility = View.GONE
            }

            val isLiftShiftDownloading = ModelDownloadBackgroundService.isDownloading(RembgModel.COLOROS_LIFT_SHIFT.id)
            if (isLiftShiftDownloading) {
                dialogBinding.progressModelU2Net.visibility = View.VISIBLE
                dialogBinding.progressModelU2Net.isIndeterminate = true
                dialogBinding.btnDownloadModelU2Net.isEnabled = false
                dialogBinding.btnRedownloadModelU2Net.isEnabled = false
            } else {
                dialogBinding.progressModelU2Net.visibility = View.GONE
                dialogBinding.btnDownloadModelU2Net.isEnabled = true
                dialogBinding.btnRedownloadModelU2Net.isEnabled = true
            }
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

        // Lift & Shift (Card 3) listeners
        dialogBinding.btnDownloadModelU2Net.setOnClickListener {
            download(RembgModel.COLOROS_LIFT_SHIFT, dialogBinding.progressModelU2Net, dialogBinding.btnDownloadModelU2Net)
        }
        dialogBinding.btnRedownloadModelU2Net.setOnClickListener {
            repository.deleteModel(RembgModel.COLOROS_LIFT_SHIFT)
            ColorOsMattingPlugin.close()
            download(RembgModel.COLOROS_LIFT_SHIFT, dialogBinding.progressModelU2Net, dialogBinding.btnRedownloadModelU2Net)
        }
        dialogBinding.btnDeleteModelU2Net.setOnClickListener {
            ModelDownloadBackgroundService.cancelDownload(activity, RembgModel.COLOROS_LIFT_SHIFT.id)
            repository.deleteModel(RembgModel.COLOROS_LIFT_SHIFT)
            ColorOsMattingPlugin.close()
            if (currentMode == RembgMode.LIFT_AND_SHIFT) {
                currentMode = RembgMode.U2NETP
                binding.toggleGroupMattingMode.check(binding.btnModeSelectU2Net.id)
            }
            currentModel = RembgModel.U2NETP
            refreshCards()
            updateActiveModelBadge()
        }
        dialogBinding.btnSelectModelU2Net.setOnClickListener {
            currentMode = RembgMode.LIFT_AND_SHIFT
            currentModel = RembgModel.COLOROS_LIFT_SHIFT
            binding.toggleGroupMattingMode.check(binding.btnModeSelectLiftAndShift.id)
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
            RembgMode.LIFT_AND_SHIFT -> {
                binding.tvBgCurrentModelName.text = "Lift & Shift (Cutout)"
                val isReady = repository.isModelReady(RembgModel.COLOROS_LIFT_SHIFT)
                if (isReady) {
                    binding.tvBgModelBadge.text = "Ready (LiteRT)"
                    binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                    binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                } else {
                    binding.tvBgModelBadge.text = "Download Needed"
                    binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_amber))
                    binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                }
            }
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
        // Trim is an export/display option, never the coordinate system of a brush.
        val res = rawCutoutBitmap ?: return
        if (!binding.refineBrushCanvas.canEdit(res)) {
            Toast.makeText(activity, "Cutout exceeds the safe manual-refinement memory budget.", Toast.LENGTH_LONG).show()
            return
        }
        binding.refineBrushCanvas.setBitmaps(src, res)

        isRefineMode = true
        binding.splitViewBgCompare.visibility = View.GONE
        binding.containerBgControls.visibility = View.GONE
        binding.cardBgActionDock.visibility = View.GONE

        binding.refineBrushCanvas.visibility = View.VISIBLE
        binding.cardBgRefineDock.visibility = View.VISIBLE

        binding.toolbarBgRemover.title = "Refine Cutout"
        binding.toolbarBgRemover.subtitle = "Erase residue or restore details"
        (activity as? com.veilframe.app.MainActivity)?.navigationController?.updateBackCallbackState()
    }

    fun exitRefineMode(applyEdits: Boolean) {
        if (applyEdits) {
            val edited = if (binding.refineBrushCanvas.hasEdits) binding.refineBrushCanvas.getRefinedBitmap() else null
            if (edited != null) {
                val prevRaw = rawCutoutBitmap
                val prevRes = resultBitmap
                // The original neural matte no longer represents manual brush edits.
                invalidateOriginalExport()
                rawCutoutBitmap = edited
                resultBitmap = if (binding.switchTrimTransparent.isChecked) {
                    com.veilframe.app.cv.segmentation.rembg.MaskPostprocessor.trimTransparent(edited)
                } else {
                    edited
                }
                if (prevRaw !== rawCutoutBitmap && prevRaw !== resultBitmap) {
                    prevRaw?.let(cutoutLeases::retire)
                }
                if (prevRes !== resultBitmap && prevRes !== rawCutoutBitmap) {
                    prevRes?.let(cutoutLeases::retire)
                }
                binding.splitViewBgCompare.setBitmaps(sourceBitmap, resultBitmap)
                Toast.makeText(activity, "Refinement applied successfully", Toast.LENGTH_SHORT).show()
            }
        }

        binding.refineBrushCanvas.cleanup()
        isRefineMode = false
        binding.cardBgRefineDock.translationY = 0f
        binding.refineBrushCanvas.alpha = 1f
        binding.refineBrushCanvas.visibility = View.GONE
        binding.cardBgRefineDock.visibility = View.GONE

        binding.splitViewBgCompare.visibility = View.VISIBLE
        binding.containerBgControls.visibility = View.VISIBLE
        binding.cardBgActionDock.visibility = View.VISIBLE

        binding.toolbarBgRemover.title = "BG Remover"
        binding.toolbarBgRemover.subtitle = "On-Device Neural Matting"
        updateUi()
        (activity as? com.veilframe.app.MainActivity)?.navigationController?.updateBackCallbackState()
    }

    fun getRefineDockView(): View = binding.cardBgRefineDock

    fun handlePredictiveBackProgress(progress: Float, edge: Int) {
        if (!isRefineMode) return
        val dockHeight = binding.cardBgRefineDock.height.toFloat().coerceAtLeast(100f)
        binding.cardBgRefineDock.translationY = progress * dockHeight
        binding.refineBrushCanvas.alpha = (1f - (progress * 0.4f)).coerceIn(0f, 1f)
    }

    fun handlePredictiveBackCancelled() {
        if (!isRefineMode) return
        binding.cardBgRefineDock.animate()
            ?.translationY(0f)
            ?.setDuration(180L)
            ?.setInterpolator(FastOutSlowInInterpolator())
            ?.start()
        binding.refineBrushCanvas.animate()
            ?.alpha(1f)
            ?.setDuration(180L)
            ?.setInterpolator(FastOutSlowInInterpolator())
            ?.start()
    }

    fun handleBackPressed(): Boolean {
        if (isRefineMode) {
            exitRefineMode(applyEdits = false)
            return true
        }
        return false
    }

    private fun updateSaveCutoutButton() {
        if (resultBitmap == null) {
            binding.btnBgExecuteRemoval.visibility = View.VISIBLE
            binding.btnBgExecuteRemoval.text = "Remove Background"
            binding.btnBgExecuteRemoval.setIconResource(R.drawable.ic_eraser)
            binding.btnBgExecuteRemoval.isEnabled = true
            binding.btnBgExecuteRemoval.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            binding.btnBgSavePng.visibility = View.GONE
            binding.btnBgSavePng.isEnabled = false
            binding.btnBgRefineBrush.visibility = View.GONE
            binding.btnBgMoreOptions.visibility = View.GONE
            return
        }

        binding.btnBgExecuteRemoval.visibility = View.GONE
        binding.btnBgRefineBrush.visibility = if (!isRefineMode) View.VISIBLE else View.GONE
        binding.btnBgRefineBrush.text = "Refine"
        binding.btnBgRefineBrush.setIconResource(R.drawable.ic_brush)
        binding.btnBgSavePng.visibility = View.VISIBLE
        binding.btnBgMoreOptions.visibility = View.VISIBLE
        binding.btnBgMoreOptions.isEnabled = true

        val colorPrimary = ContextCompat.getColor(activity, R.color.vf_primary)
        val colorOnPrimary = ContextCompat.getColor(activity, R.color.vf_on_primary)
        val primaryStateList = ColorStateList.valueOf(colorPrimary)
        val onPrimaryStateList = ColorStateList.valueOf(colorOnPrimary)

        when (currentSaveCutoutState) {
            SaveCutoutState.IDLE -> {
                binding.btnBgSavePng.text = "Save"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_action_save)
                binding.btnBgSavePng.isEnabled = true
                binding.btnBgSavePng.backgroundTintList = primaryStateList
                binding.btnBgSavePng.setTextColor(colorOnPrimary)
                binding.btnBgSavePng.iconTint = onPrimaryStateList
            }
            SaveCutoutState.SAVING -> {
                binding.btnBgSavePng.text = "Saving…"
                binding.btnBgSavePng.isEnabled = true
                binding.btnBgSavePng.backgroundTintList = primaryStateList
                binding.btnBgSavePng.setTextColor(colorOnPrimary)
                binding.btnBgSavePng.iconTint = onPrimaryStateList

                try {
                    val density = activity.resources.displayMetrics.density
                    val spec = com.google.android.material.progressindicator.CircularProgressIndicatorSpec(
                        activity,
                        null,
                        0,
                        com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator
                    ).apply {
                        indicatorColors = intArrayOf(colorOnPrimary)
                        indicatorSize = (16 * density).toInt()
                        trackThickness = (2.2f * density).toInt()
                    }
                    val progressDrawable = com.google.android.material.progressindicator.IndeterminateDrawable.createCircularDrawable(activity, spec)
                    binding.btnBgSavePng.icon = progressDrawable
                    progressDrawable.start()
                } catch (_: Throwable) {
                    binding.btnBgSavePng.setIconResource(R.drawable.ic_loading_spinner)
                }
            }
            SaveCutoutState.SAVED -> {
                binding.btnBgSavePng.text = "Saved"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_done)
                binding.btnBgSavePng.isEnabled = true
                binding.btnBgSavePng.backgroundTintList = primaryStateList
                binding.btnBgSavePng.setTextColor(colorOnPrimary)
                binding.btnBgSavePng.iconTint = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.vf_accent_green))
            }
            SaveCutoutState.SAVE_AGAIN -> {
                binding.btnBgSavePng.text = "Save"
                binding.btnBgSavePng.setIconResource(R.drawable.ic_action_save)
                binding.btnBgSavePng.isEnabled = true
                binding.btnBgSavePng.backgroundTintList = primaryStateList
                binding.btnBgSavePng.setTextColor(colorOnPrimary)
                binding.btnBgSavePng.iconTint = onPrimaryStateList
            }
        }
    }

    private fun showMoreOptionsMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "Save as Sticker").setIcon(R.drawable.ic_sticker)
        popup.menu.add(0, 2, 1, "Copy Image").setIcon(R.drawable.ic_action_copy)
        popup.menu.add(0, 3, 2, "Share").setIcon(R.drawable.ic_action_share)
        popup.menu.add(0, 4, 3, "Re-process").setIcon(R.drawable.ic_reset)
        if (originalMatte != null && originalInfo != null) {
            popup.menu.add(0, 5, 4, "Save original-resolution PNG").setIcon(R.drawable.ic_action_save)
                .isEnabled = originalExportJob?.isActive != true && processingJob?.isActive != true
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup?.javaClass?.getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)?.invoke(mPopup, true)
        } catch (_: Throwable) {}

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> saveAsSticker()
                2 -> copyImageToClipboard()
                3 -> shareCutoutImage()
                4 -> executeRemoval()
                5 -> confirmOriginalResolutionExport()
            }
            true
        }
        popup.show()
    }

    private fun confirmOriginalResolutionExport() {
        val source = sourceImage ?: return
        val matte = originalMatte ?: return
        val info = originalInfo ?: return
        val generation = sourceGeneration
        val trim = binding.switchTrimTransparent.isChecked
        MaterialAlertDialogBuilder(activity)
            .setTitle("Original-resolution transparent PNG")
            .setMessage("Export original colors from ${info.width}×${info.height} with the neural matte. " +
                if (trim) "Transparent borders will be cropped at original pixel resolution. Background-color settings do not apply."
                else "The full image dimensions will be preserved. Background-color settings do not apply.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Export") { _, _ ->
                if (source === sourceImage && matte === originalMatte && generation == sourceGeneration) {
                    exportOriginalResolution(source, info, matte, generation, trim)
                }
            }.show()
    }

    private fun exportOriginalResolution(
        source: RembgImageSource.Staged, info: RembgSourceInfo,
        matte: RembgAlphaMatte, generation: Long, trim: Boolean
    ) {
        if (originalExportJob?.isActive == true || processingJob?.isActive == true) return
        val release = sourceLeases.acquire(source)
        val progress = M3ExpressiveProgressDialog(activity)
        try {
            progress.show(title = "Original-resolution export",
                initialStatus = if (trim) "Finding original-pixel alpha bounds and encoding PNG…"
                    else "Decoding original pixels in strips and encoding transparent PNG…",
                isIndeterminate = true, cancelable = true, onCancel = { originalExportJob?.cancel() })
            originalExportJob = scope.launch(Dispatchers.Main) {
                var destination: File? = null
                var handedOff = false
                try {
                    withContext(Dispatchers.IO) {
                        val cc = kotlin.coroutines.coroutineContext
                        val dir = File(activity.cacheDir, "cutout_transfers").apply { mkdirs() }
                        val out = File.createTempFile("cutout_original_", ".png", dir)
                        destination = out
                        OriginalResolutionCutout.export(source, info, matte, out, trim = trim) { cc.ensureActive() }
                    }
                    if (generation != sourceGeneration || source !== sourceImage ||
                        matte !== originalMatte || activity.isFinishing || activity.isDestroyed) return@launch
                    progress.dismiss()
                    handedOff = onExportPngRequest(checkNotNull(destination), true)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == sourceGeneration && source === sourceImage) {
                        Toast.makeText(activity, "Original-resolution export failed: ${failure.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    progress.dismiss()
                    if (!handedOff) destination?.delete()
                }
            }.also { it.invokeOnCompletion { release() } }
        } catch (failure: Throwable) {
            release()
            progress.dismiss()
            throw failure
        }
    }

    private fun saveAsSticker() {
        val bmp = resultBitmap ?: run {
            Toast.makeText(activity, "No cutout available", Toast.LENGTH_SHORT).show()
            return
        }
        val capturedName = sourceFileName
        launchCutoutExport(bmp) {
            val trimmed = com.veilframe.app.cv.segmentation.rembg.MaskPostprocessor.trimTransparent(bmp)
            val resolver = activity.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val fileName = "${capturedName}_STICKER_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame/Stickers")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            var success = false
            var savedUri: Uri? = null
            try {
                val target = resolver.insert(collection, values)
                    ?: throw java.io.IOException("MediaStore insert failed")
                savedUri = target
                resolver.openOutputStream(target)?.use { out ->
                    if (!trimmed.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw java.io.IOException("Sticker PNG encoding failed")
                    }
                } ?: throw java.io.IOException("openOutputStream failed")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(target, values, null, null) > 0) { "Could not publish sticker" }
                }
                success = true
            } catch (e: Exception) {
                savedUri?.let { runCatching { resolver.delete(it, null, null) } }
                Log.e(TAG, "Save sticker failed", e)
            } finally {
                if (trimmed !== bmp) {
                    trimmed.recycle()
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(activity, "Saved as sticker to Gallery (Pictures/VeilFrame/Stickers)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Failed to save sticker", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyImageToClipboard() {
        val bmp = resultBitmap ?: run {
            Toast.makeText(activity, "No cutout available", Toast.LENGTH_SHORT).show()
            return
        }
        val capturedGeneration = sourceGeneration
        launchCutoutExport(bmp) {
            try {
                val cacheDir = File(activity.cacheDir, "clipboard").apply { mkdirs() }
                val cacheFile = File(cacheDir, "cutout_${java.util.UUID.randomUUID()}.png")
                FileOutputStream(cacheFile).use { fos ->
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        throw java.io.IOException("Cutout PNG encoding failed")
                    }
                }
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.provider",
                    cacheFile
                )
                val clip = ClipData.newUri(activity.contentResolver, "VeilFrame Cutout", uri)
                withContext(Dispatchers.Main) {
                    if (capturedGeneration != sourceGeneration) return@withContext
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(activity, "Cutout copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Copy to clipboard failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Could not copy image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareCutoutImage() {
        val bmp = resultBitmap ?: run {
            Toast.makeText(activity, "No cutout available", Toast.LENGTH_SHORT).show()
            return
        }
        val capturedName = sourceFileName
        val capturedGeneration = sourceGeneration
        launchCutoutExport(bmp) {
            try {
                val cacheDir = File(activity.cacheDir, "shares").apply { mkdirs() }
                val cacheFile = File(cacheDir, "${capturedName}_${java.util.UUID.randomUUID()}_cutout.png")
                FileOutputStream(cacheFile).use { fos ->
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        throw java.io.IOException("Cutout PNG encoding failed")
                    }
                }
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.provider",
                    cacheFile
                )
                withContext(Dispatchers.Main) {
                    if (capturedGeneration != sourceGeneration) return@withContext
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "Share Cutout"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Share failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
        // View state is owned by the main thread; never inspect it in the encoder worker.
        val backgroundMode = binding.splitViewBgCompare.backgroundMode
        val exportBaseName = sourceFileName
        val capturedGeneration = sourceGeneration
        launchCutoutExport(bmp) {
            val exportDir = File(activity.filesDir, "exports").apply { mkdirs() }
            val ext = if (isJpg) "jpg" else "png"
            val mimeType = if (isJpg) "image/jpeg" else "image/png"
            val outFile = File(exportDir, "${exportBaseName}_BGREMOVED_${System.currentTimeMillis()}.$ext")

            val finalExportBmp = if (isJpg) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flat)
                val bgColor = when (backgroundMode) {
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK -> Color.BLACK
                    else -> Color.WHITE
                }
                canvas.drawColor(bgColor)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                flat
            } else {
                when (backgroundMode) {
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
            var encodedSuccessfully = false
            var saveErrorMessage: String? = null
            var galleryUri: Uri? = null
            try {
                FileOutputStream(outFile).buffered().use { output ->
                    val encoded = if (isJpg) {
                        finalExportBmp.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    } else {
                        finalExportBmp.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                    if (!encoded) throw java.io.IOException("Cutout image encoding failed")
                }
                encodedSuccessfully = true

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
                galleryUri = savedUri
                resolver.openOutputStream(savedUri)?.use { out ->
                    outFile.inputStream().buffered().use { inp -> inp.copyTo(out, 64 * 1024) }
                } ?: throw java.io.IOException("openOutputStream returned null")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(savedUri, values, null, null) > 0) { "Could not publish gallery image" }
                }
                savedToGallery = true
            } catch (e: Exception) {
                galleryUri?.let { runCatching { activity.contentResolver.delete(it, null, null) } }
                if (!encodedSuccessfully) outFile.delete()
                Log.e(TAG, "Gallery save failed, falling back to SAF", e)
                saveErrorMessage = e.message
            } finally {
                if (finalExportBmp !== bmp) {
                    finalExportBmp.recycle()
                }
            }

            withContext(Dispatchers.Main) {
                if (capturedGeneration != sourceGeneration) {
                    outFile.delete()
                    return@withContext
                }
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
                    // Launching a destination chooser is not a completed save.
                    onExportPngRequest(outFile, false)
                    currentSaveCutoutState = SaveCutoutState.SAVE_AGAIN
                    updateSaveCutoutButton()
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

    override fun hasPickedMedia(): Boolean = sourceLoadJob?.isActive == true || currentSourceUri != null || sourceBitmap != null
    override fun hasUnsavedWork(): Boolean = sourceLoadJob?.isActive == true || currentSourceUri != null || resultBitmap != null
    override val isProcessing: Boolean
        get() = processingJob?.isActive == true || originalExportJob?.isActive == true ||
            binding.cardBgProcessingOverlay.visibility == View.VISIBLE
    override val supportsBackgroundExecution: Boolean get() = false

    override fun discardAndReleaseAllResources() {
        invalidateSourceLoad()
        cancelProcessing()
        binding.splitViewBgCompare.setBitmaps(null, null)
        binding.refineBrushCanvas.cleanup()
        sessionManager.closeAll()
        com.veilframe.app.cv.segmentation.rembg.ColorOsMattingPlugin.close()
        downloadManager.cancelDownload()
        sourceBitmap?.recycle()
        sourceBitmap = null
        releaseCutoutBitmaps()
        releaseSourceImage()
        currentSourceUri = null
        updateUi()
    }

    fun cancelProcessing() {
        removalGeneration++
        originalExportJob?.cancel()
        originalExportJob = null
        processingJob?.cancel()
        processingJob = null
        binding.cardBgProcessingOverlay.visibility = View.GONE
    }
}
