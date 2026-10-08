package com.veilframe.app.upscale.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.DialogModelRequiredBinding
import com.veilframe.app.databinding.DialogUpscaleModelManagerBinding
import com.veilframe.app.databinding.DialogUpscaleModelSelectorBinding
import com.veilframe.app.databinding.LayoutImageUpscalerBinding
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.upscale.download.ModelDownloadManager
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.SourceAccess
import com.veilframe.app.upscale.inference.UpscaleInferenceEngine
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.inference.OnnxSessionManager
import com.veilframe.app.upscale.inference.UpscaleMemoryException
import com.veilframe.app.upscale.inference.UpscaleOutput
import com.veilframe.app.upscale.inference.UriRegionSource
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import com.veilframe.app.upscale.model.UpscaleModelResolver
import com.veilframe.app.upscale.preset.UpscalePreset
import com.veilframe.app.upscale.preset.UpscalePresetRegistry
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controller orchestrating the AI Image Upscaler UI, model management,
 * tiled super-resolution inference execution, and artifact export.
 */
class ImageUpscalerController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onBackRequested: () -> Unit,
    private val onPickImageRequested: () -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "VeilFrame.ImageUpscaler"

        /** Hard output cap (~200 MP) — beyond this even band-streaming is refused. */
        private const val OUTPUT_PIXEL_CAP = 200_000_000L
    }

    private val upscalerBinding: LayoutImageUpscalerBinding
        get() = binding.layoutImageUpscaler

    val repository: UpscaleModelRepository = UpscaleModelRepository(activity)
    val downloadManager: ModelDownloadManager = ModelDownloadManager(activity, repository)
    private val inferenceEngine: UpscaleInferenceEngine = UpscaleInferenceEngine(activity, repository)

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Active state
    var selectedUri: Uri? = null
        private set

    /** Full-resolution source; null when the image streams from disk (F1). */
    private var sourceBitmap: Bitmap? = null

    /** Sampled display preview (<=2048px) — the ImageView never holds 192 MB again. */
    private var previewBitmap: Bitmap? = null

    /** Always-present source accessor (in-memory or region-streaming). */
    private var sourceAccess: SourceAccess? = null
    private var sourceW: Int = 0
    private var sourceH: Int = 0

    /** F2: result may live in RAM (InMemory) or as a streamed PNG file (Streamed). */
    private var resultOutput: UpscaleOutput? = null
    private var resultFile: File? = null
    private var activePreset: UpscalePreset = UpscalePresetRegistry.PHOTO
    private var targetScale: Int = 2
    private var explicitModel: UpscaleModel? = null

    private var inferenceJob: Job? = null
    private var downloadJob: Job? = null
    var onImportCustomModelRequested: (() -> Unit)? = null
    var inferenceParams: UpscaleInferenceParams = UpscaleInferenceParams()

    fun init() {
        // F11: thermal awareness for the whole workspace session.
        com.veilframe.app.runtime.ThermalGovernor.register(activity)

        // Apply Material 3 Expressive tactile touch bounce across all interactive controls
        val interactiveBounceViews = listOf(
            upscalerBinding.btnUpscalerBack,
            upscalerBinding.btnUpscalerModelManager,
            upscalerBinding.btnUpscalerPickImage,
            upscalerBinding.cardUpscalerSelectImage,
            upscalerBinding.btnUpscalerChangeImage,
            upscalerBinding.btnUpscalerChangeModel,
            upscalerBinding.btnUpscalerExecute,
            upscalerBinding.btnUpscalerCancel,
            upscalerBinding.btnUpscalerSave,
            upscalerBinding.btnUpscalerShare
        )
        interactiveBounceViews.forEach { ExpressiveMotion.applyTouchBounce(it) }

        // Navigation back
        upscalerBinding.btnUpscalerBack.setOnClickListener {
            onBackRequested()
        }

        // Model Manager Dialog button (morphs from button)
        upscalerBinding.btnUpscalerModelManager.setOnClickListener {
            showModelManagerDialog(upscalerBinding.btnUpscalerModelManager)
        }

        // Image pickers
        upscalerBinding.btnUpscalerPickImage.setOnClickListener {
            onPickImageRequested()
        }
        upscalerBinding.cardUpscalerSelectImage.setOnClickListener {
            onPickImageRequested()
        }
        upscalerBinding.btnUpscalerChangeImage.setOnClickListener {
            onPickImageRequested()
        }

        // Presets ChipGroup
        setupPresets()

        // Scale ToggleGroup
        setupScales()

        // Performance Diagnostics Panel
        setupDiagnostics()

        // Explicit Model Selector (morphs from change model button)
        upscalerBinding.btnUpscalerChangeModel.setOnClickListener {
            showModelSelectorDialog(upscalerBinding.btnUpscalerChangeModel)
        }

        // Execution button (morphs to required dialog if needed, or handles select / cancel)
        upscalerBinding.btnUpscalerExecute.setOnClickListener {
            when {
                sourceAccess == null -> onPickImageRequested()
                inferenceJob?.isActive == true || downloadJob?.isActive == true -> cancelActiveOperations()
                else -> startUpscalingFlow(upscalerBinding.btnUpscalerExecute)
            }
        }

        // Cancel button
        upscalerBinding.btnUpscalerCancel.setOnClickListener {
            cancelActiveOperations()
        }

        // Save & Share buttons
        upscalerBinding.btnUpscalerSave.setOnClickListener {
            saveUpscaledImage()
        }
        upscalerBinding.btnUpscalerShare.setOnClickListener {
            shareUpscaledImage()
        }

        updateUIState()
    }

    fun handleImageSelected(uri: Uri) {
        selectedUri = uri
        resultOutput = null
        resultFile?.let { runCatching { it.delete() } }
        resultFile = null
        scope.launch(Dispatchers.IO) {
            try {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(uri)?.use { s ->
                    BitmapFactory.decodeStream(s, null, boundsOptions)
                }

                val origW = boundsOptions.outWidth
                val origH = boundsOptions.outHeight
                if (origW <= 0 || origH <= 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Could not decode selected image dimensions", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val heapBytes = Runtime.getRuntime().maxMemory()
                val sourceBudget = heapBytes * 35 / 100
                val fullBytes = origW.toLong() * origH.toLong() * 4L

                // F1: the preview is ALWAYS sampled (<=2048px). The full bitmap
                // is decoded only when it fits the source budget; otherwise the
                // pipeline streams tiles from the Uri (UriRegionSource) and the
                // 192 MB-class source bitmap never exists.
                val preview = decodeSampledPreview(uri, 2048)
                if (preview == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to load image", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                var fullBitmap: Bitmap? = null
                if (fullBytes <= sourceBudget) {
                    fullBitmap = try {
                        activity.contentResolver.openInputStream(uri)?.use { s ->
                            BitmapFactory.decodeStream(s)
                        }
                    } catch (oom: OutOfMemoryError) {
                        Log.e(TAG, "Full decode OOM at ${origW}x${origH}; falling back to streaming", oom)
                        null
                    }
                }
                val access: SourceAccess = if (fullBitmap != null) {
                    BitmapSource(fullBitmap)
                } else {
                    UriRegionSource(activity, uri, origW, origH)
                }

                sourceBitmap = fullBitmap
                sourceAccess = access
                previewBitmap = preview
                sourceW = origW
                sourceH = origH

                withContext(Dispatchers.Main) {
                    upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                    upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                    upscalerBinding.layoutUpscalerResults.visibility = View.GONE
                    upscalerBinding.imgUpscalerPreview.setImageBitmap(preview)
                    upscalerBinding.tvUpscalerBadge.text = "ORIGINAL"
                    upscalerBinding.tvUpscalerBadge.setTextColor(activity.getColor(R.color.vf_accent_blue))

                    val sizeBytes = getUriFileSize(uri)
                    val sizeFormatted = String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
                    val filename = uri.lastPathSegment ?: "image"
                    val streamingNote = if (fullBitmap == null) " • streamed" else ""
                    upscalerBinding.tvUpscalerSourceInfo.text =
                        "$filename • ${origW}×${origH} • $sizeFormatted$streamingNote"

                    updateTargetDimensions()
                    updateModelDisplay()
                    updateUIState()
                    onLog("[UPSCALER] Loaded source image ${origW}×${origH} (in-memory=${fullBitmap != null})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** F1/B5: sampled preview decode via the shared MediaDecoder policy. */
    private fun decodeSampledPreview(uri: Uri, maxEdge: Int): Bitmap? {
        return com.veilframe.app.media.MediaDecoder.decodeSampled(
            activity.contentResolver,
            uri,
            com.veilframe.app.media.MediaDecoder.PREVIEW_PIXEL_CAP
        )
    }

    private fun getUriFileSize(uri: Uri): Long {
        return try {
            activity.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun handleCustomModelImport(uri: Uri) {
        scope.launch {
            try {
                val fileName = getFileName(uri) ?: "custom_model.ort"
                val model = withContext(Dispatchers.IO) {
                    repository.importCustomModelFromUri(uri, fileName)
                }
                explicitModel = model
                updateModelDisplay()
                onLog("[UPSCALER] Successfully imported custom model: ${model.name}")
                Toast.makeText(activity, "Imported custom model: ${model.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import custom model: ${e.message}", e)
                onLog("[UPSCALER] Failed to import model: ${e.message}")
                Toast.makeText(activity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun setupPresets() {
        upscalerBinding.chipGroupUpscalerPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val preset = when (checkedIds.first()) {
                R.id.chipPresetPhotoQuality -> UpscalePresetRegistry.PHOTO_QUALITY
                R.id.chipPresetAnime -> UpscalePresetRegistry.ANIME
                R.id.chipPresetIllustration -> UpscalePresetRegistry.ILLUSTRATION
                R.id.chipPresetFast -> UpscalePresetRegistry.FAST
                R.id.chipPresetStandard -> UpscalePresetRegistry.STANDARD
                R.id.chipPresetCustom -> UpscalePresetRegistry.CUSTOM
                else -> UpscalePresetRegistry.PHOTO
            }
            activePreset = preset
            upscalerBinding.tvUpscalerPresetDesc.text = preset.description

            // Adjust default scale for preset if needed
            if (preset.defaultScale != targetScale && explicitModel == null) {
                targetScale = preset.defaultScale
                when (targetScale) {
                    4 -> upscalerBinding.toggleGroupUpscalerScale.check(R.id.btnScale4x)
                    8 -> upscalerBinding.toggleGroupUpscalerScale.check(R.id.btnScale8x)
                    else -> upscalerBinding.toggleGroupUpscalerScale.check(R.id.btnScale2x)
                }
            }

            updateTargetDimensions()
            updateModelDisplay()
            onLog("[UPSCALER] Selected preset: ${preset.name}")
        }
    }

    private fun setupScales() {
        upscalerBinding.toggleGroupUpscalerScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            targetScale = when (checkedId) {
                R.id.btnScale4x -> 4
                R.id.btnScale8x -> 8
                else -> 2
            }
            updateTargetDimensions()
            updateModelDisplay()
            onLog("[UPSCALER] Target scale set to ${targetScale}×")
        }
    }

    private fun setupDiagnostics() {
        val actManager = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { actManager?.getMemoryInfo(it) }
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()

        upscalerBinding.tvDiagHardware.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL} • $cpuCores CPU cores (~${availMb}MB / ${totalMb}MB RAM)"
        upscalerBinding.tvDiagBackend.text =
            "Active Backend: auto-select (NNAPI → XNNPACK → CPU) • last: ${OnnxSessionManager.lastBackend}"
        upscalerBinding.tvDiagExecutionProfile.text =
            "Engine: VeilFrame ONNX (region-streamed dynamic tiling) • thermal: " +
                com.veilframe.app.runtime.ThermalGovernor.statusName()
        upscalerBinding.tvDiagThroughput.text = "Throughput: Idle"

        var isExpanded = false
        upscalerBinding.layoutDiagnosticsHeader.setOnClickListener {
            isExpanded = !isExpanded
            upscalerBinding.layoutDiagnosticsContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
            upscalerBinding.imgDiagnosticsChevron.animate().rotation(if (isExpanded) 180f else 0f).setDuration(160L).start()
        }

        upscalerBinding.btnRunUpscaleBenchmark.setOnClickListener {
            upscalerBinding.btnRunUpscaleBenchmark.isEnabled = false
            upscalerBinding.tvDiagThroughput.text = "Testing model initialization..."
            scope.launch {
                val model = getResolvedModel()
                val modelFile = repository.getModelFile(model.id)
                val startTime = System.currentTimeMillis()
                val infoText = withContext(Dispatchers.Default) {
                    try {
                        val session = com.veilframe.app.upscale.inference.OnnxSessionManager.createSession(modelFile)
                        try {
                            val info = com.veilframe.app.upscale.inference.ModelInfo(
                                session = session,
                                modelName = modelFile.name,
                                explicitScale = model.nativeScale
                            )
                            val prec = if (info.isFp16) "FP16" else "FP32"
                            val dims = if (info.expectedWidth != null) "${info.expectedWidth}x${info.expectedHeight}" else "Dynamic"
                            "Loaded ${model.name} in ${System.currentTimeMillis() - startTime}ms ($prec, $dims)"
                        } finally {
                            session.close()
                        }
                    } catch (e: Exception) {
                        "Model check completed in ${System.currentTimeMillis() - startTime}ms"
                    }
                }
                withContext(Dispatchers.Main) {
                    upscalerBinding.btnRunUpscaleBenchmark.isEnabled = true
                    upscalerBinding.tvDiagThroughput.text = infoText
                    Toast.makeText(activity, infoText, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTargetDimensions() {
        if (sourceAccess != null && sourceW > 0 && sourceH > 0) {
            val targetW = sourceW * targetScale
            val targetH = sourceH * targetScale
            val mp = (targetW * targetH) / 1_000_000.0
            val model = getResolvedModel()
            val scalePlan = com.veilframe.app.upscale.inference.HybridScalePlan.create(targetScale, model.nativeScale)
            val pipelineSuffix = if (model.type == com.veilframe.app.upscale.model.ModelType.AI_ONNX && scalePlan.requiresRefinement) {
                " • ${targetScale}× Output (${scalePlan.aiScale}× AI + ${scalePlan.refinementScale}× Refinement)"
            } else {
                ""
            }
            upscalerBinding.tvUpscalerTargetDimensions.text =
                String.format(Locale.US, "Output: %d × %d (~%.1f MP)%s", targetW, targetH, mp, pipelineSuffix)
        } else {
            upscalerBinding.tvUpscalerTargetDimensions.text = "Output: - × - (Select an image)"
        }
    }

    private fun getResolvedModel(): UpscaleModel {
        return UpscaleModelResolver.resolve(
            preset = activePreset,
            targetScale = targetScale,
            repository = repository,
            explicitModel = explicitModel
        )
    }

    private fun updateModelDisplay() {
        val model = getResolvedModel()
        upscalerBinding.tvUpscalerModelName.text = if (explicitModel != null) {
            "${model.name} (Custom Override)"
        } else {
            "${model.name} (Auto)"
        }

        val isInstalled = repository.isModelInstalled(model)
        if (isInstalled) {
            upscalerBinding.tvUpscalerModelStatus.text = "Installed & Ready"
            upscalerBinding.tvUpscalerModelStatus.setTextColor(activity.getColor(R.color.vf_accent_green))
        } else {
            val sizeMb = String.format(Locale.US, "%.1f MB", model.sizeBytes / (1024.0 * 1024.0))
            upscalerBinding.tvUpscalerModelStatus.text = "Needs Download ($sizeMb)"
            upscalerBinding.tvUpscalerModelStatus.setTextColor(activity.getColor(R.color.vf_primary))
        }
    }

    private fun updateUIState() {
        val hasImage = (sourceAccess != null)
        val isProcessing = (inferenceJob?.isActive == true || downloadJob?.isActive == true)
        val hasResult = (resultOutput != null)
        val model = getResolvedModel()
        val isInstalled = repository.isModelInstalled(model)

        when {
            isProcessing -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Cancel"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_close)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
            }
            !hasImage -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.VISIBLE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.GONE
                upscalerBinding.btnUpscalerExecute.text = "Select Image"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_file_pick)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
            }
            !isInstalled -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Download Model & Upscale"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_system_update)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
            }
            hasResult -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Upscale Again"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_resize)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
            }
            else -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Upscale (${targetScale}×)"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_resize)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
            }
        }
        updateTargetDimensions()
        updateModelDisplay()
    }

    private fun startUpscalingFlow(originView: View? = null) {
        val src = sourceAccess ?: run {
            Toast.makeText(activity, "Please select an image first", Toast.LENGTH_SHORT).show()
            return
        }

        val model = getResolvedModel()
        // F2/F3/F11 gates + profiles run off the main thread inside
        // executeUpscaling (live MemAvailable + heap + thermal/battery state).
        if (repository.isModelInstalled(model)) {
            executeUpscaling(src, model, targetScale)
        } else {
            showModelRequiredDialog(model, originView)
        }
    }

    private fun showRefusalDialog(message: String, offerTwoX: Boolean) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Cannot run this job")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
        if (offerTwoX && targetScale > 2) {
            builder.setPositiveButton("Switch to 2×") { _, _ ->
                targetScale = 2
                upscalerBinding.toggleGroupUpscalerScale.check(R.id.btnScale2x)
                startUpscalingFlow()
            }
        } else {
            builder.setPositiveButton("OK", null)
        }
        builder.show()
        onLog("[UPSCALER] Refused: $message")
    }

    /**
     * Background execution gate (F2): honest refusals with exact numbers.
     * AI models stream to disk beyond the RAM budget, so they are only refused
     * above the hard output cap; algorithmic models need everything in RAM.
     * Returns null when the job may proceed, else the user-facing reason.
     */
    private fun evaluateExecutionGate(model: UpscaleModel, scale: Int): String? {
        val outPixels = sourceW.toLong() * scale * (sourceH.toLong() * scale)
        if (outPixels > OUTPUT_PIXEL_CAP) {
            return String.format(
                Locale.US,
                "Output would be ~%.0f MP — above the 200 MP device-safety cap. " +
                    "Use a smaller scale or crop the source first.",
                outPixels / 1_000_000.0
            )
        }
        if (model.type == com.veilframe.app.upscale.model.ModelType.ALGORITHMIC) {
            val heap = Runtime.getRuntime().maxMemory()
            val avail = try {
                com.veilframe.app.cv.core.MemInfoMemoryProbe().availableMemoryBytes()
            } catch (_: Throwable) {
                0L
            }
            var budget = minOf(heap * 45 / 100, 220L * 1024L * 1024L)
            if (avail > 0L) budget = minOf(budget, avail / 2)

            if (sourceBitmap == null && sourceW.toLong() * sourceH.toLong() * 4L > heap * 35 / 100) {
                return "Lanczos/Bicubic needs the whole image in memory — too large on this device. " +
                    "Use an AI model (streams from disk) or a smaller source."
            }
            val outBytes = outPixels * 4L
            if (outBytes > budget) {
                return String.format(
                    Locale.US,
                    "Algorithmic output needs ~%d MB in memory (current budget ~%d MB). " +
                        "Use an AI model (streams to disk) or a smaller scale.",
                    outBytes / (1024 * 1024), budget / (1024 * 1024)
                )
            }
        }
        return null
    }

    private fun showModelRequiredDialog(model: UpscaleModel, originView: View? = null) {
        val dialogBinding = DialogModelRequiredBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        val sizeMb = String.format(Locale.US, "%.1f MB", model.sizeBytes / (1024.0 * 1024.0))
        dialogBinding.tvModelReqTitle.text = "${model.name} is required"
        dialogBinding.tvModelReqMessage.text =
            "The selected preset uses the neural network model ${model.name} ($sizeMb). Would you like to download it now over your current connection to proceed?"

        dialogBinding.btnModelReqClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }
        dialogBinding.btnModelReqCancel.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        dialogBinding.btnModelReqUseLanczos.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            explicitModel = UpscaleModelRegistry.LANCZOS
            updateModelDisplay()
            sourceAccess?.let { src -> executeUpscaling(src, UpscaleModelRegistry.LANCZOS, targetScale) }
        }

        dialogBinding.btnModelReqDownload.setOnClickListener {
            dialogBinding.progressModelReq.visibility = View.VISIBLE
            dialogBinding.tvModelReqProgressStatus.visibility = View.VISIBLE
            dialogBinding.btnModelReqDownload.isEnabled = false
            dialogBinding.btnModelReqUseLanczos.isEnabled = false

            downloadJob = scope.launch {
                val res = downloadManager.downloadModel(model, object : ModelDownloadManager.DownloadListener {
                    override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {
                        scope.launch(Dispatchers.Main) {
                            val pct = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                            val speedMb = speedBytesPerSec / (1024.0 * 1024.0)
                            dialogBinding.progressModelReq.isIndeterminate = false
                            dialogBinding.progressModelReq.progress = pct
                            dialogBinding.tvModelReqProgressStatus.text =
                                String.format(Locale.US, "Downloading: %d%% (%.1f MB/s)", pct, speedMb)
                        }
                    }

                    override fun onVerifying() {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModelReq.isIndeterminate = true
                            dialogBinding.tvModelReqProgressStatus.text = "Verifying SHA-256 integrity..."
                        }
                    }

                    override fun onSuccess(m: UpscaleModel, destinationFile: File) {
                        scope.launch(Dispatchers.Main) {
                            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
                            updateModelDisplay()
                            onLog("[UPSCALER] Model ${m.name} successfully downloaded and verified.")
                            sourceAccess?.let { src -> executeUpscaling(src, m, targetScale) }
                        }
                    }

                    override fun onError(error: String) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModelReq.visibility = View.GONE
                            dialogBinding.tvModelReqProgressStatus.text = "Download failed: $error"
                            dialogBinding.tvModelReqProgressStatus.setTextColor(activity.getColor(R.color.vf_accent_red))
                            dialogBinding.btnModelReqDownload.isEnabled = true
                            dialogBinding.btnModelReqUseLanczos.isEnabled = true
                            onLog("[UPSCALER] Download error: $error")
                        }
                    }
                })
            }
        }

        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    private fun executeUpscaling(src: SourceAccess, model: UpscaleModel, scale: Int) {
        upscalerBinding.layoutUpscalerProgress.visibility = View.VISIBLE
        upscalerBinding.layoutUpscalerResults.visibility = View.GONE
        upscalerBinding.progressUpscaler.isIndeterminate = true
        upscalerBinding.tvUpscalerProgressStatus.text = "Preparing ${model.name} pipeline..."
        updateUIState()

        inferenceJob = scope.launch {
            onLog("[UPSCALER] Starting upscale: ${model.name}, scale=${scale}×, input=${src.width}×${src.height}")

            // F2 gate + F3/F11 profile — computed off the main thread (reads
            // /proc/meminfo, thermal status, battery state).
            val prep = withContext(Dispatchers.IO) {
                val gate = evaluateExecutionGate(model, scale)
                var p = UpscaleInferenceParams.forDevice(activity, scale)
                var note: String? = null
                if (gate == null) {
                    when {
                        com.veilframe.app.runtime.ThermalGovernor.isThrottled -> {
                            p = p.copy(
                                parallelWorkers = 1,
                                chunkSize = (p.chunkSize / 2).coerceAtLeast(128)
                            )
                            note = "[UPSCALER] Thermal ${com.veilframe.app.runtime.ThermalGovernor.statusName()}" +
                                " — efficiency profile chunk=${p.chunkSize} workers=1"
                        }
                        com.veilframe.app.runtime.ThermalGovernor.batteryConstrained(activity) -> {
                            p = p.copy(parallelWorkers = 1)
                            note = "[UPSCALER] Battery constrained — efficiency profile workers=1"
                        }
                    }
                }
                Triple(gate, p, note)
            }
            if (prep.first != null) {
                upscalerBinding.layoutUpscalerProgress.visibility = View.GONE
                showRefusalDialog(prep.first!!, scale > 2)
                updateUIState()
                return@launch
            }
            prep.third?.let { onLog(it) }
            val jobParams = prep.second

            // F6: foreground service — the job survives navigation & OEM
            // phantom-process killers, with a cancellable notification.
            com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = { inferenceJob?.cancel() }
            com.veilframe.app.upscale.UpscaleForegroundService.start(activity, "${model.name} • ${scale}×")

            val startTime = System.currentTimeMillis()
            val result = inferenceEngine.upscale(
                source = src,
                model = model,
                targetScale = scale,
                params = jobParams,
                listener = object : UpscaleInferenceEngine.InferenceProgressListener {
                    override fun onProgress(currentTile: Int, totalTiles: Int, percent: Int) {
                        scope.launch(Dispatchers.Main) {
                            upscalerBinding.progressUpscaler.isIndeterminate = false
                            com.veilframe.app.ui.motion.ProcessingMotionController.updateProgress(
                                upscalerBinding.progressUpscaler,
                                percent,
                                true
                            )
                        }
                    }

                    override fun onStatus(message: String) {
                        scope.launch(Dispatchers.Main) {
                            upscalerBinding.tvUpscalerProgressStatus.text = message
                            onLog("[UPSCALER] $message")
                        }
                    }

                    override fun onStage(stage: String, currentTile: Int, totalTiles: Int) {
                        scope.launch(Dispatchers.Main) {
                            if (totalTiles > 1) {
                                upscalerBinding.tvUpscalerProgressStatus.text = "$stage: Tile $currentTile of $totalTiles"
                            }
                        }
                    }
                }
            )

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0

            withContext(Dispatchers.Main) {
                com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = null
                com.veilframe.app.upscale.UpscaleForegroundService.stop(activity)
                upscalerBinding.layoutUpscalerProgress.visibility = View.GONE
                upscalerBinding.btnUpscalerExecute.isEnabled = true

                result.fold(
                    onSuccess = { output ->
                        (resultOutput as? UpscaleOutput.Streamed)?.let { prev ->
                            runCatching { prev.file.delete() }
                        }
                        resultOutput = output
                        resultFile = (output as? UpscaleOutput.Streamed)?.file
                        upscalerBinding.imgUpscalerPreview.setImageBitmap(output.previewBitmap())
                        val resolvedModel = getResolvedModel()
                        val scalePlan = com.veilframe.app.upscale.inference.HybridScalePlan.create(scale, resolvedModel.nativeScale)
                        val badgeText = if (resolvedModel.type == com.veilframe.app.upscale.model.ModelType.AI_ONNX && scalePlan.requiresRefinement) {
                            "${scale}× OUTPUT (${scalePlan.aiScale}× AI + ${scalePlan.refinementScale}× REFINEMENT)"
                        } else {
                            "UPSCALED (${scale}×)"
                        }
                        upscalerBinding.tvUpscalerBadge.text = badgeText
                        upscalerBinding.tvUpscalerBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                        upscalerBinding.layoutUpscalerResults.visibility = View.VISIBLE
                        com.veilframe.app.ui.motion.ProcessingMotionController.confirmCompletion(upscalerBinding.layoutUpscalerResults)

                        val mp = (output.width.toLong() * output.height) / 1_000_000.0
                        val throughput = if (elapsedSec > 0.0) mp / elapsedSec else 0.0
                        upscalerBinding.tvDiagThroughput.text = String.format(
                            Locale.US,
                            "Throughput: ~%.1f MP/s (%.1fs for %.1f MP)%s",
                            throughput,
                            elapsedSec,
                            mp,
                            if (output is UpscaleOutput.Streamed) " • streamed" else ""
                        )
                        onLog(
                            String.format(
                                Locale.US,
                                "[UPSCALER] Upscale completed in %.1fs (~%.1f MP/s): %d×%d (~%.1f MP, streamed=%b)",
                                elapsedSec,
                                throughput,
                                output.width,
                                output.height,
                                mp,
                                output is UpscaleOutput.Streamed
                            )
                        )
                        Toast.makeText(
                            activity,
                            String.format(Locale.US, "Upscaled to %d×%d (~%.1f MP/s) in %.1fs", output.width, output.height, throughput, elapsedSec),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { err ->
                        Log.e(TAG, "Upscale failed: ${err.message}", err)
                        onLog("[UPSCALER] Error: ${err.message}")
                        if (err is UpscaleMemoryException) {
                            // F4: honest degrade offer instead of a crash or a silent no-op.
                            showMemoryDegradeDialog()
                        } else if (err is com.veilframe.app.upscale.inference.ThermalShutdownException) {
                            // F11: honest thermal stop — user decides when to retry.
                            Toast.makeText(
                                activity,
                                "Stopped — device temperature is critical. Let it cool down, then retry.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(activity, "Upscale failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
                updateUIState()
            }
        }
    }

    private fun showMemoryDegradeDialog() {
        val canLanczos = sourceBitmap != null
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Neural upscale ran out of memory")
            .setNegativeButton("Cancel", null)
        if (canLanczos) {
            builder.setMessage(
                "The AI pipeline exceeded this device's memory budget even with reduced tiles.\n\n" +
                    "Apply Lanczos 3 resampling instead? It is fast and memory-safe, with softer " +
                    "detail than the neural model."
            ).setPositiveButton("Apply Lanczos 3") { _, _ ->
                explicitModel = UpscaleModelRegistry.LANCZOS
                updateModelDisplay()
                sourceAccess?.let { executeUpscaling(it, UpscaleModelRegistry.LANCZOS, targetScale) }
            }
        } else if (targetScale > 2) {
            builder.setMessage(
                "The AI pipeline exceeded this device's memory budget even with reduced tiles. " +
                    "This source is streamed from disk, so the in-memory Lanczos fallback is also unavailable."
            ).setPositiveButton("Retry at 2×") { _, _ ->
                targetScale = 2
                upscalerBinding.toggleGroupUpscalerScale.check(R.id.btnScale2x)
                startUpscalingFlow()
            }
        } else {
            builder.setMessage(
                "The AI pipeline exceeded this device's memory budget even with reduced tiles. " +
                    "Try a smaller source image."
            ).setPositiveButton("OK", null)
        }
        builder.show()
        onLog("[UPSCALER] Memory degrade offered (lanczos=$canLanczos)")
    }

    private fun cancelActiveOperations() {
        inferenceJob?.cancel()
        inferenceJob = null
        downloadJob?.cancel()
        downloadJob = null
        com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = null
        com.veilframe.app.upscale.UpscaleForegroundService.stop(activity)
        upscalerBinding.layoutUpscalerProgress.visibility = View.GONE
        onLog("[UPSCALER] Active operation cancelled by user.")
        Toast.makeText(activity, "Cancelled", Toast.LENGTH_SHORT).show()
        updateUIState()
    }

    fun showModelSelectorDialog(originView: View? = null) {
        val dialogBinding = DialogUpscaleModelSelectorBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnModelSelectorClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        // Select current option
        when (explicitModel?.id) {
            "realesrgan-general-2x" -> dialogBinding.rbModelRealEsrgan2x.isChecked = true
            "realesrgan-general-4x" -> dialogBinding.rbModelRealEsrgan4x.isChecked = true
            "realesrgan-anime-4x" -> dialogBinding.rbModelRealEsrganAnime.isChecked = true
            "lanczos-3" -> dialogBinding.rbModelLanczos.isChecked = true
            "bicubic" -> dialogBinding.rbModelBicubic.isChecked = true
            "nearest" -> dialogBinding.rbModelNearest.isChecked = true
            else -> dialogBinding.rbModelAuto.isChecked = true
        }

        dialogBinding.btnModelSelectorApply.setOnClickListener {
            val selectedModel: UpscaleModel? = when (dialogBinding.rgModelSelector.checkedRadioButtonId) {
                R.id.rbModelRealEsrgan2x -> UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X
                R.id.rbModelRealEsrgan4x -> UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X
                R.id.rbModelRealEsrganAnime -> UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X
                R.id.rbModelLanczos -> UpscaleModelRegistry.LANCZOS
                R.id.rbModelBicubic -> UpscaleModelRegistry.BICUBIC
                R.id.rbModelNearest -> UpscaleModelRegistry.NEAREST
                else -> null // Auto
            }
            explicitModel = selectedModel
            updateModelDisplay()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            onLog("[UPSCALER] Model explicitly selected: ${selectedModel?.name ?: "Auto"}")
        }

        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    fun showModelManagerDialog(originView: View? = null) {
        val dialogBinding = DialogUpscaleModelManagerBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnModelManagerClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        dialogBinding.btnImportCustomModel.setOnClickListener {
            onImportCustomModelRequested?.invoke()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        fun refreshCards() {
            val usedBytes = repository.getTotalStorageUsedBytes()
            val availBytes = repository.getAvailableStorageBytes()
            val usedMb = String.format(Locale.US, "%.1f MB", usedBytes / (1024.0 * 1024.0))
            val availGb = String.format(Locale.US, "%.1f GB", availBytes / (1024.0 * 1024.0 * 1024.0))
            dialogBinding.tvModelStorageSummary.text = "Models Storage: $usedMb used • $availGb available"

            val installedAi = repository.getInstalledAiModels()
            dialogBinding.tvModelCountSummary.text = "${installedAi.size} of 3 AI Models Installed"

            // 2x Card
            val is2x = repository.isModelInstalled(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X)
            if (is2x) {
                dialogBinding.tvModelStatus2x.text = "Installed"
                dialogBinding.tvModelStatus2x.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModel2x.visibility = View.GONE
                dialogBinding.btnDeleteModel2x.visibility = View.VISIBLE
            } else {
                dialogBinding.tvModelStatus2x.text = "Not Installed"
                dialogBinding.tvModelStatus2x.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModel2x.visibility = View.VISIBLE
                dialogBinding.btnDeleteModel2x.visibility = View.GONE
            }

            // 4x Card
            val is4x = repository.isModelInstalled(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X)
            if (is4x) {
                dialogBinding.tvModelStatus4x.text = "Installed"
                dialogBinding.tvModelStatus4x.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModel4x.visibility = View.GONE
                dialogBinding.btnDeleteModel4x.visibility = View.VISIBLE
            } else {
                dialogBinding.tvModelStatus4x.text = "Not Installed"
                dialogBinding.tvModelStatus4x.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModel4x.visibility = View.VISIBLE
                dialogBinding.btnDeleteModel4x.visibility = View.GONE
            }

            // Anime Card
            val isAnime = repository.isModelInstalled(UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X)
            if (isAnime) {
                dialogBinding.tvModelStatusAnime.text = "Installed"
                dialogBinding.tvModelStatusAnime.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModelAnime.visibility = View.GONE
                dialogBinding.btnDeleteModelAnime.visibility = View.VISIBLE
            } else {
                dialogBinding.tvModelStatusAnime.text = "Not Installed"
                dialogBinding.tvModelStatusAnime.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModelAnime.visibility = View.VISIBLE
                dialogBinding.btnDeleteModelAnime.visibility = View.GONE
            }
        }

        refreshCards()

        // 2x Actions
        dialogBinding.btnDownloadModel2x.setOnClickListener {
            dialogBinding.progressModel2x.visibility = View.VISIBLE
            dialogBinding.btnDownloadModel2x.isEnabled = false
            scope.launch {
                downloadManager.downloadModel(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X, object : ModelDownloadManager.DownloadListener {
                    override fun onProgress(d: Long, t: Long, s: Long) {
                        scope.launch(Dispatchers.Main) {
                            if (t > 0) {
                                dialogBinding.progressModel2x.isIndeterminate = false
                                dialogBinding.progressModel2x.progress = ((d * 100) / t).toInt()
                            }
                        }
                    }
                    override fun onVerifying() {
                        scope.launch(Dispatchers.Main) { dialogBinding.progressModel2x.isIndeterminate = true }
                    }
                    override fun onSuccess(m: UpscaleModel, destinationFile: File) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModel2x.visibility = View.GONE
                            refreshCards()
                            updateModelDisplay()
                        }
                    }
                    override fun onError(error: String) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModel2x.visibility = View.GONE
                            dialogBinding.btnDownloadModel2x.isEnabled = true
                            Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        }
        dialogBinding.btnDeleteModel2x.setOnClickListener {
            repository.deleteModel(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X.id)
            refreshCards()
            updateModelDisplay()
        }

        // 4x Actions
        dialogBinding.btnDownloadModel4x.setOnClickListener {
            dialogBinding.progressModel4x.visibility = View.VISIBLE
            dialogBinding.btnDownloadModel4x.isEnabled = false
            scope.launch {
                downloadManager.downloadModel(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X, object : ModelDownloadManager.DownloadListener {
                    override fun onProgress(d: Long, t: Long, s: Long) {
                        scope.launch(Dispatchers.Main) {
                            if (t > 0) {
                                dialogBinding.progressModel4x.isIndeterminate = false
                                dialogBinding.progressModel4x.progress = ((d * 100) / t).toInt()
                            }
                        }
                    }
                    override fun onVerifying() {
                        scope.launch(Dispatchers.Main) { dialogBinding.progressModel4x.isIndeterminate = true }
                    }
                    override fun onSuccess(m: UpscaleModel, destinationFile: File) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModel4x.visibility = View.GONE
                            refreshCards()
                            updateModelDisplay()
                        }
                    }
                    override fun onError(error: String) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModel4x.visibility = View.GONE
                            dialogBinding.btnDownloadModel4x.isEnabled = true
                            Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        }
        dialogBinding.btnDeleteModel4x.setOnClickListener {
            repository.deleteModel(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X.id)
            refreshCards()
            updateModelDisplay()
        }

        // Anime Actions
        dialogBinding.btnDownloadModelAnime.setOnClickListener {
            dialogBinding.progressModelAnime.visibility = View.VISIBLE
            dialogBinding.btnDownloadModelAnime.isEnabled = false
            scope.launch {
                downloadManager.downloadModel(UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X, object : ModelDownloadManager.DownloadListener {
                    override fun onProgress(d: Long, t: Long, s: Long) {
                        scope.launch(Dispatchers.Main) {
                            if (t > 0) {
                                dialogBinding.progressModelAnime.isIndeterminate = false
                                dialogBinding.progressModelAnime.progress = ((d * 100) / t).toInt()
                            }
                        }
                    }
                    override fun onVerifying() {
                        scope.launch(Dispatchers.Main) { dialogBinding.progressModelAnime.isIndeterminate = true }
                    }
                    override fun onSuccess(m: UpscaleModel, destinationFile: File) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModelAnime.visibility = View.GONE
                            refreshCards()
                            updateModelDisplay()
                        }
                    }
                    override fun onError(error: String) {
                        scope.launch(Dispatchers.Main) {
                            dialogBinding.progressModelAnime.visibility = View.GONE
                            dialogBinding.btnDownloadModelAnime.isEnabled = true
                            Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        }
        dialogBinding.btnDeleteModelAnime.setOnClickListener {
            repository.deleteModel(UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X.id)
            refreshCards()
            updateModelDisplay()
        }

        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    private fun saveUpscaledImage() {
        val output = resultOutput ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "VeilFrame_Upscaled_${targetScale}x_$timestamp.png"

                var savedUri: Uri? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VeilFrame")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    val resolver = activity.contentResolver
                    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    savedUri = resolver.insert(collection, values)

                    if (savedUri != null) {
                        resolver.openOutputStream(savedUri)?.use { os ->
                            writeOutput(output, os)
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(savedUri, values, null, null)
                    }
                } else {
                    val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VeilFrame")
                    picturesDir.mkdirs()
                    val outFile = File(picturesDir, filename)
                    FileOutputStream(outFile).use { fos ->
                        writeOutput(output, fos)
                    }
                    savedUri = try {
                        FileProvider.getUriForFile(activity, "${activity.packageName}.provider", outFile)
                    } catch (_: Exception) {
                        Uri.fromFile(outFile)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (savedUri != null) {
                        Toast.makeText(activity, "Saved to Pictures/VeilFrame: $filename", Toast.LENGTH_LONG).show()
                        onLog("[UPSCALER] Saved upscaled image: $filename")
                    } else {
                        Toast.makeText(activity, "Failed to save upscaled image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving upscaled image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Writes the output as PNG: in-memory compress, or a byte copy of the streamed file. */
    private fun writeOutput(output: UpscaleOutput, os: java.io.OutputStream) {
        when (output) {
            is UpscaleOutput.InMemory -> output.bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            is UpscaleOutput.Streamed -> output.file.inputStream().use { it.copyTo(os) }
        }
    }

    private fun shareUpscaledImage() {
        val output = resultOutput ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(activity.cacheDir, "shared_upscaled")
                cacheDir.mkdirs()
                val tempFile = File(cacheDir, "upscaled_${System.currentTimeMillis()}.png")
                FileOutputStream(tempFile).use { fos ->
                    writeOutput(output, fos)
                }

                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", tempFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent.createChooser(intent, "Share Upscaled Image"))
                    onLog("[UPSCALER] Triggered share intent for upscaled image.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sharing image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun release() {
        inferenceJob?.cancel()
        downloadJob?.cancel()
        scope.cancel()
        sourceAccess?.close()
        com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = null
        com.veilframe.app.upscale.UpscaleForegroundService.stop(activity)
        resultFile?.let { runCatching { it.delete() } }
        resultFile = null
        com.veilframe.app.runtime.ThermalGovernor.unregister()
    }
}
