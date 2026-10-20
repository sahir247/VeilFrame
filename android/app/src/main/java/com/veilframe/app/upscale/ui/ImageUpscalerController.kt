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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogModelRequiredBinding
import com.veilframe.app.databinding.DialogUpscaleModelManagerBinding
import com.veilframe.app.databinding.DialogUpscaleModelSelectorBinding
import com.veilframe.app.databinding.ItemUpscaleModelCardBinding
import com.veilframe.app.databinding.LayoutImageUpscalerBinding
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.upscale.download.ModelDownloadManager
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.SourceAccess
import com.veilframe.app.upscale.inference.UpscaleInferenceEngine
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.inference.NcnnNative
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
    private val upscalerBinding: LayoutImageUpscalerBinding,
    private val onBackRequested: () -> Unit,
    private val onPickImageRequested: () -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "VeilFrame.ImageUpscaler"

        /** Hard output cap (~200 MP) — beyond this even band-streaming is refused. */
        private const val OUTPUT_PIXEL_CAP = 200_000_000L
    }

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
    private var targetScale: Int = UpscalePresetRegistry.PHOTO.defaultScale
    private var explicitModel: UpscaleModel? = null
    private var inferenceJob: Job? = null
    private var downloadJob: Job? = null
    private var currentLoadJob: Job? = null
    private var imageLoadGeneration: Long = 0L
    private var suppressScaleListener: Boolean = false
    private val activeExportCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var isCancelling: Boolean = false

    var onImportCustomModelRequested: (() -> Unit)? = null
    var inferenceParams: UpscaleInferenceParams = UpscaleInferenceParams()

    private fun safelyDeleteResultFile(file: File?) {
        if (file == null) return
        scope.launch(Dispatchers.IO) {
            var retries = 0
            while (activeExportCount.get() > 0 && retries < 50) {
                delay(100)
                retries++
            }
            runCatching { file.delete() }
        }
    }

    fun init() {
        // F11: thermal awareness for the whole workspace session.
        com.veilframe.app.runtime.ThermalGovernor.register(activity)

        // Audit fix (UP5): prune stale streamed outputs and shared exports across all three cache dirs
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            listOf("upscale_outputs", "upscale_streaming", "shared_upscaled").forEach { dirName ->
                runCatching {
                    val dir = File(activity.cacheDir, dirName)
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.lastModified() < cutoff) f.delete()
                    }
                }
            }
        }

        // Apply Material 3 Expressive tactile touch bounce across all interactive controls
        val interactiveBounceViews = listOf(
            upscalerBinding.btnUpscalerBack,
            upscalerBinding.btnUpscalerModelManager,
            upscalerBinding.btnUpscalerPickImage,
            upscalerBinding.cardUpscalerSelectImage,
            upscalerBinding.btnUpscalerChangeImage,
            upscalerBinding.btnUpscalerChangeModel,
            upscalerBinding.btnUpscalerExecute,
            upscalerBinding.btnUpscalerSave,
            upscalerBinding.btnUpscalerShare,
            upscalerBinding.layoutAdvancedHeader
        )
        interactiveBounceViews.forEach { ExpressiveMotion.applyTouchBounce(it) }

        // Navigation back
        upscalerBinding.btnUpscalerBack.setOnClickListener {
            onBackRequested()
        }

        // Advanced Settings expand/collapse toggle
        var isAdvancedExpanded = false
        upscalerBinding.layoutAdvancedHeader.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            upscalerBinding.layoutAdvancedContent.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            upscalerBinding.imgAdvancedChevron.animate().rotation(if (isAdvancedExpanded) 180f else 0f).setDuration(160L).start()
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
                isCancelling -> { /* cancellation in progress, ignore duplicate clicks */ }
                sourceAccess == null -> onPickImageRequested()
                isProcessing -> cancelActiveOperations()
                else -> startUpscalingFlow(upscalerBinding.btnUpscalerExecute)
            }
        }

        // Save & Share buttons
        upscalerBinding.btnUpscalerSave.setOnClickListener {
            saveUpscaledImage()
        }
        upscalerBinding.btnUpscalerShare.setOnClickListener {
            shareUpscaledImage()
        }

        syncScaleToggleWithModel(getResolvedModel())
        updateUIState()
    }

    private fun syncScaleToggleWithModel(model: UpscaleModel) {
        val supported = model.supportedOutputScales
        upscalerBinding.btnScale2x.visibility = if (2 in supported) View.VISIBLE else View.GONE
        upscalerBinding.btnScale3x.visibility = if (3 in supported) View.VISIBLE else View.GONE
        upscalerBinding.btnScale4x.visibility = if (4 in supported) View.VISIBLE else View.GONE

        upscalerBinding.btnScale2x.isEnabled = 2 in supported
        upscalerBinding.btnScale3x.isEnabled = 3 in supported
        upscalerBinding.btnScale4x.isEnabled = 4 in supported

        if (targetScale !in supported) {
            targetScale = supported.firstOrNull() ?: model.nativeScale
        }
        val targetButtonId = when (targetScale) {
            3 -> R.id.btnScale3x
            4 -> R.id.btnScale4x
            else -> R.id.btnScale2x
        }
        if (upscalerBinding.toggleGroupUpscalerScale.checkedButtonId != targetButtonId) {
            suppressScaleListener = true
            upscalerBinding.toggleGroupUpscalerScale.check(targetButtonId)
            suppressScaleListener = false
        }
    }

    fun handleImageSelected(uri: Uri) {
        // Cancel active inference or background download if running
        if (isProcessing) {
            cancelActiveOperations()
        }
        currentLoadJob?.cancel()
        val generation = ++imageLoadGeneration

        selectedUri = uri
        resultOutput = null
        val oldResultFile = resultFile
        resultFile = null
        safelyDeleteResultFile(oldResultFile)

        // Visually reset results and indicate loading immediately
        upscalerBinding.layoutUpscalerResults.visibility = View.GONE
        upscalerBinding.tvUpscalerBadge.text = "LOADING..."
        upscalerBinding.tvUpscalerBadge.setTextColor(activity.getColor(R.color.vf_accent_blue))
        updateUIState()

        currentLoadJob = scope.launch(Dispatchers.IO) {
            try {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(uri)?.use { s ->
                    BitmapFactory.decodeStream(s, null, boundsOptions)
                }

                if (generation != imageLoadGeneration || !isActive) return@launch

                val origW = boundsOptions.outWidth
                val origH = boundsOptions.outHeight
                if (origW <= 0 || origH <= 0) {
                    withContext(Dispatchers.Main) {
                        if (generation == imageLoadGeneration) {
                            Toast.makeText(activity, "Could not decode selected image dimensions", Toast.LENGTH_SHORT).show()
                        }
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
                if (preview == null || generation != imageLoadGeneration || !isActive) {
                    withContext(Dispatchers.Main) {
                        if (generation == imageLoadGeneration) {
                            Toast.makeText(activity, "Failed to load image", Toast.LENGTH_SHORT).show()
                        }
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
                if (generation != imageLoadGeneration || !isActive) {
                    return@launch
                }

                val access: SourceAccess = if (fullBitmap != null) {
                    BitmapSource(fullBitmap)
                } else {
                    UriRegionSource(activity, uri, origW, origH)
                }

                // Safely close prior sourceAccess if present
                val oldAccess = sourceAccess
                sourceAccess = access
                oldAccess?.close()

                sourceBitmap = fullBitmap
                previewBitmap = preview
                sourceW = origW
                sourceH = origH

                withContext(Dispatchers.Main) {
                    if (generation != imageLoadGeneration) return@withContext

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
                if (e is CancellationException) return@launch
                Log.e(TAG, "Error loading image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (generation == imageLoadGeneration) {
                        Toast.makeText(activity, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
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
                val fileName = getFileName(uri) ?: "custom_model.zip"
                val model = withContext(Dispatchers.IO) {
                    if (fileName.endsWith(".zip", ignoreCase = true)) {
                        repository.importCustomNcnnFromZip(uri, fileName)
                    } else {
                        repository.importCustomModelFromUri(uri, fileName)
                    }
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
            if (preset != UpscalePresetRegistry.CUSTOM) {
                explicitModel = null
            }
            upscalerBinding.tvUpscalerPresetDesc.text = preset.description

            val resolved = getResolvedModel()
            syncScaleToggleWithModel(resolved)

            updateTargetDimensions()
            updateModelDisplay()
            onLog("[UPSCALER] Selected preset: ${preset.name}")
        }
    }

    private fun setupScales() {
        upscalerBinding.toggleGroupUpscalerScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressScaleListener) return@addOnButtonCheckedListener
            val requestedScale = when (checkedId) {
                R.id.btnScale3x -> 3
                R.id.btnScale4x -> 4
                else -> 2
            }
            val model = runCatching { getResolvedModel() }.getOrNull()
            if (model != null && requestedScale !in model.supportedOutputScales) {
                syncScaleToggleWithModel(model)
                Toast.makeText(
                    activity,
                    "${model.name} supports ${model.supportedOutputScales.joinToString("×, ")}× output.",
                    Toast.LENGTH_SHORT
                ).show()
                return@addOnButtonCheckedListener
            }
            targetScale = requestedScale
            updateTargetDimensions()
            updateModelDisplay()
            onLog("[UPSCALER] Target scale set to ${targetScale}×")
        }
    }

    private fun refreshDiagnosticsSummary() {
        val actManager = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { actManager?.getMemoryInfo(it) }
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()

        upscalerBinding.tvDiagHardware.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL} • $cpuCores CPU cores (~${availMb}MB / ${totalMb}MB RAM)"
        val activeModel = getResolvedModel()
        val ncnnSelected = activeModel.type == com.veilframe.app.upscale.model.ModelType.AI_NCNN
        if (ncnnSelected) {
            val vulkanAvailable = NcnnNative.isAvailable
            val gpuCount = if (vulkanAvailable) NcnnNative.getGpuCount() else 0
            val gpuName = if (gpuCount > 0) NcnnNative.getGpuDeviceName(0) ?: "GPU 0" else "CPU only"
            upscalerBinding.tvDiagBackend.text = if (gpuCount > 0) {
                "Active Backend: NCNN Vulkan ($gpuName, $gpuCount GPU detected)"
            } else if (vulkanAvailable) {
                "Active Backend: NCNN CPU (Vulkan library active, 0 discrete GPU)"
            } else {
                "Active Backend: NCNN CPU (Fallback / Host)"
            }
            upscalerBinding.tvDiagExecutionProfile.text =
                "Engine: NCNN Native Tile Pipeline (${activeModel.nativeScale}×) • thermal: ${com.veilframe.app.runtime.ThermalGovernor.statusName()}"
        } else {
            upscalerBinding.tvDiagBackend.text =
                "Active Backend: CPU (Algorithmic Scaler - ${activeModel.name})"
            upscalerBinding.tvDiagExecutionProfile.text =
                "Engine: Mathematical Reconstruction (${activeModel.name}) • thermal: ${com.veilframe.app.runtime.ThermalGovernor.statusName()}"
        }
    }

    private fun setupDiagnostics() {
        refreshDiagnosticsSummary()
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
                val startTime = System.currentTimeMillis()
                val infoText = if (model.type == com.veilframe.app.upscale.model.ModelType.AI_NCNN) {
                    if (!repository.isModelInstalled(model)) {
                        "Model not installed. Download ${model.name} to benchmark."
                    } else if (!NcnnNative.isAvailable) {
                        "NCNN native library not available on this platform/architecture."
                    } else {
                        withContext(Dispatchers.Default) {
                            try {
                                val paramFile = repository.getNcnnParamFile(model.id)
                                val binFile = repository.getNcnnBinFile(model.id)
                                val gpuCount = NcnnNative.getGpuCount()
                                val gpuid = if (gpuCount > 0) 0 else -1
                                val backendName = if (gpuid >= 0) "Vulkan GPU" else "CPU"
                                val elapsed = System.currentTimeMillis() - startTime
                                "Verified ${model.name} ($backendName) in ${elapsed}ms (param=${paramFile.length() / 1024}KB, bin=${binFile.length() / (1024 * 1024)}MB)"
                            } catch (e: Exception) {
                                "NCNN check failed: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                } else {
                    val elapsed = System.currentTimeMillis() - startTime
                    "Built-in Algorithmic filter (${model.name}): Instant CPU execution (${elapsed}ms)."
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
            val pipelineSuffix = if (model.type != com.veilframe.app.upscale.model.ModelType.ALGORITHMIC && scalePlan.requiresRefinement) {
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
            "${model.name} (Manual Override)"
        } else {
            "${model.name} (Auto - ${activePreset.name})"
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

        syncScaleToggleWithModel(model)
        refreshDiagnosticsSummary()
    }

    private fun updateUIState() {
        val hasImage = (sourceAccess != null)
        val hasResult = (resultOutput != null)
        val model = getResolvedModel()
        val isInstalled = repository.isModelInstalled(model)

        when {
            isCancelling -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Cancelling…"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_close)
                upscalerBinding.btnUpscalerExecute.isEnabled = false
                upscalerBinding.btnUpscalerExecute.alpha = 0.6f
                upscalerBinding.btnUpscalerExecute.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_accent_red)
            }
            isProcessing -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Cancel"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_close)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
                upscalerBinding.btnUpscalerExecute.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_accent_red)
            }
            !hasImage -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.VISIBLE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.GONE
                upscalerBinding.btnUpscalerExecute.text = "Select Image"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_file_pick)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
                upscalerBinding.btnUpscalerExecute.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            }
            !isInstalled -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Download Model & Upscale"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_system_update)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
                upscalerBinding.btnUpscalerExecute.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            }
            hasResult -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Upscale Again"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_resize)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
                upscalerBinding.btnUpscalerExecute.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
            }
            else -> {
                upscalerBinding.cardUpscalerSelectImage.visibility = View.GONE
                upscalerBinding.layoutUpscalerWorkspace.visibility = View.VISIBLE
                upscalerBinding.btnUpscalerExecute.text = "Upscale (${targetScale}×)"
                upscalerBinding.btnUpscalerExecute.setIconResource(R.drawable.ic_resize)
                upscalerBinding.btnUpscalerExecute.isEnabled = true
                upscalerBinding.btnUpscalerExecute.alpha = 1.0f
                upscalerBinding.btnUpscalerExecute.backgroundTintList = ContextCompat.getColorStateList(activity, R.color.vf_primary)
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

        val job = scope.launch {
            try {
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
                com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = { cancelActiveOperations() }
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
                    result.fold(
                        onSuccess = { output ->
                            (resultOutput as? UpscaleOutput.Streamed)?.let { prev ->
                                safelyDeleteResultFile(prev.file)
                            }
                            resultOutput = output
                            resultFile = (output as? UpscaleOutput.Streamed)?.file
                            upscalerBinding.imgUpscalerPreview.setImageBitmap(displayBitmapFor(output))
                            val resolvedModel = getResolvedModel()
                            val scalePlan = com.veilframe.app.upscale.inference.HybridScalePlan.create(scale, resolvedModel.nativeScale)
                            val badgeText = if (resolvedModel.type != com.veilframe.app.upscale.model.ModelType.ALGORITHMIC && scalePlan.requiresRefinement) {
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
                            val formatText = if (output is UpscaleOutput.Streamed) "Streaming PNG" else "Lossless PNG"
                            upscalerBinding.tvUpscalerResultDetails.text = String.format(
                                Locale.US,
                                "Upscaled %d×%d (~%.1f MP) • %s • %.1fs elapsed • Ready to save or share",
                                output.width,
                                output.height,
                                mp,
                                formatText,
                                elapsedSec
                            )
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
                }
            } catch (ce: CancellationException) {
                onLog("[UPSCALER] Upscaling job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled exception in upscale job: ${e.message}", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = null
                    com.veilframe.app.upscale.UpscaleForegroundService.stop(activity)
                    upscalerBinding.layoutUpscalerProgress.visibility = View.GONE
                    isCancelling = false
                    inferenceJob = null
                    updateUIState()
                }
            }
        }
        inferenceJob = job
        updateUIState()
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

    val isProcessing: Boolean
        get() = (inferenceJob?.isActive == true || downloadJob?.isActive == true) || isCancelling

    fun cancelProcessing() {
        cancelActiveOperations()
    }

    private fun cancelActiveOperations() {
        if (isCancelling) return
        isCancelling = true
        onLog("[UPSCALER] Active operation cancellation requested.")
        upscalerBinding.tvUpscalerProgressStatus.text = "Cancelling upscale..."
        upscalerBinding.btnUpscalerExecute.isEnabled = false
        upscalerBinding.btnUpscalerExecute.text = "Cancelling…"
        upscalerBinding.btnUpscalerExecute.alpha = 0.6f

        com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = null
        com.veilframe.app.upscale.UpscaleForegroundService.stop(activity)

        inferenceJob?.cancel()
        downloadJob?.cancel()

        if (inferenceJob == null && downloadJob == null) {
            isCancelling = false
            upscalerBinding.layoutUpscalerProgress.visibility = View.GONE
            updateUIState()
        }
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

        val dynamicModelMap = mutableMapOf<Int, UpscaleModel>()
        var foundChecked = false

        fun addDivider() {
            val divider = View(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * activity.resources.displayMetrics.density).toInt()
                ).apply {
                    val m = (6 * activity.resources.displayMetrics.density).toInt()
                    setMargins(0, m, 0, m)
                }
                setBackgroundColor(activity.getColor(R.color.vf_surface_stroke))
            }
            dialogBinding.rgModelSelector.addView(divider)
        }

        // 1. Divider after Auto
        addDivider()

        // 2. All NCNN Models (all 7 models + custom models)
        val allNcnnModels = (UpscaleModelRegistry.NCNN_MODELS + UpscaleModelRegistry.ALL_MODELS.filter { it.isImported }).distinctBy { it.id }
        for (m in allNcnnModels) {
            val rb = android.widget.RadioButton(activity).apply {
                id = View.generateViewId()
                val mb = if (m.sizeBytes > 0) String.format(Locale.US, "%.1f MB", m.sizeBytes / (1024.0 * 1024.0)) else ""
                val info = if (mb.isNotEmpty()) " (${m.nativeScale}× • $mb)" else " (${m.nativeScale}×)"
                text = "${m.name}$info\n${m.description}"
                setTextColor(activity.getColor(R.color.vf_text_primary))
                textSize = 13f
                setPadding(0, (8 * activity.resources.displayMetrics.density).toInt(), 0, (8 * activity.resources.displayMetrics.density).toInt())
            }
            dialogBinding.rgModelSelector.addView(rb)
            dynamicModelMap[rb.id] = m
            if (explicitModel?.id == m.id) {
                rb.isChecked = true
                foundChecked = true
            }
        }

        // 3. Divider before Algorithmic
        addDivider()

        // 4. Built-in algorithmic models
        val algorithmicModels = listOf(UpscaleModelRegistry.LANCZOS, UpscaleModelRegistry.BICUBIC, UpscaleModelRegistry.NEAREST)
        for (m in algorithmicModels) {
            val rb = android.widget.RadioButton(activity).apply {
                id = View.generateViewId()
                text = "${m.name} (Built-in • 0 MB)\n${m.description}"
                setTextColor(activity.getColor(R.color.vf_text_primary))
                textSize = 13f
                setPadding(0, (8 * activity.resources.displayMetrics.density).toInt(), 0, (8 * activity.resources.displayMetrics.density).toInt())
            }
            dialogBinding.rgModelSelector.addView(rb)
            dynamicModelMap[rb.id] = m
            if (explicitModel?.id == m.id) {
                rb.isChecked = true
                foundChecked = true
            }
        }

        if (!foundChecked) {
            dialogBinding.rbModelAuto.isChecked = true
        }

        dialogBinding.btnModelSelectorApply.setOnClickListener {
            val checkedId = dialogBinding.rgModelSelector.checkedRadioButtonId
            val selectedModel: UpscaleModel? = if (checkedId == dialogBinding.rbModelAuto.id) null else dynamicModelMap[checkedId]
            explicitModel = selectedModel
            val modelForScale = selectedModel ?: runCatching { getResolvedModel() }.getOrNull()
            if (modelForScale?.type == com.veilframe.app.upscale.model.ModelType.AI_NCNN &&
                targetScale !in modelForScale.supportedOutputScales
            ) {
                val correctedScale = modelForScale.supportedOutputScales.minByOrNull {
                    kotlin.math.abs(it - targetScale)
                }
                if (correctedScale != null) {
                    targetScale = correctedScale
                    val correctedButton = when (correctedScale) {
                        3 -> R.id.btnScale3x
                        4 -> R.id.btnScale4x
                        else -> R.id.btnScale2x
                    }
                    upscalerBinding.toggleGroupUpscalerScale.check(correctedButton)
                    Toast.makeText(
                        activity,
                        "Selected NCNN model supports ${modelForScale.supportedOutputScales.joinToString(", ") { scale -> "${scale}×" }} only. Output scale set to ${correctedScale}×.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            updateModelDisplay()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            onLog("[UPSCALER] Model explicitly selected: ${selectedModel?.name ?: "Auto"}")
        }

        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    fun showCustomModelImportInstructionsDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Import Custom NCNN Model")
            .setMessage(
                "VeilFrame's high-performance neural upscaler is accelerated on GPU using Vulkan and NCNN (RealSR / Real-ESRGAN / Waifu2x). It runs purely native models and does NOT use ONNX/ORT.\n\n" +
                "Archive Requirements (.zip):\n" +
                "• Must be a .zip file containing both model files:\n" +
                "  1. <model_name>.param (Layer & network graph)\n" +
                "  2. <model_name>.bin (Binary model weights)\n\n" +
                "Compatible Model Architectures:\n" +
                "• RealSR (models-DF2K, models-DF2K_JPEG)\n" +
                "• Real-ESRGAN (x4plus, x4plus-anime, animevideov3, x4v3)\n" +
                "• Real-ESRNet (x4plus)\n" +
                "• Waifu2x (cunet, upconv_7)\n" +
                "• Real-CUGAN (pro, se, nose)\n" +
                "• SPAN / Compact SR\n\n" +
                "Scale Factor:\n" +
                "• Model will be imported at its native scale factor (e.g. 4× or 2×).\n\n" +
                "Tap 'Select Model (.zip)' to browse your device files."
            )
            .setPositiveButton("Select Model (.zip)") { _, _ ->
                onImportCustomModelRequested?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showModelManagerDialog(originView: View? = null) {
        val dialogBinding = DialogUpscaleModelManagerBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        val dialogDownloadJobs = mutableMapOf<String, Job>()
        dialog.setOnDismissListener {
            dialogDownloadJobs.values.forEach { it.cancel() }
            dialogDownloadJobs.clear()
        }

        dialogBinding.btnModelManagerClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        dialogBinding.btnImportCustomModel.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
            showCustomModelImportInstructionsDialog()
        }

        val allNcnnModels = (UpscaleModelRegistry.NCNN_MODELS + UpscaleModelRegistry.ALL_MODELS.filter { it.isImported }).distinctBy { it.id }

        fun refreshCards() {
            val usedBytes = repository.getTotalStorageUsedBytes()
            val availBytes = repository.getAvailableStorageBytes()
            val usedMb = String.format(Locale.US, "%.1f MB", usedBytes / (1024.0 * 1024.0))
            val availGb = String.format(Locale.US, "%.1f GB", availBytes / (1024.0 * 1024.0 * 1024.0))
            dialogBinding.tvModelStorageSummary.text = "Models Storage: $usedMb used • $availGb available"

            val installedNcnn = allNcnnModels.count { repository.isModelInstalled(it) }
            val customCount = allNcnnModels.count { it.isImported }
            dialogBinding.tvModelCountSummary.text = "${installedNcnn} of ${allNcnnModels.size} NCNN Models Installed" + (if (customCount > 0) " • $customCount Custom" else "")

            dialogBinding.llModelList.removeAllViews()
            val inflater = LayoutInflater.from(activity)

            for (model in allNcnnModels) {
                val cardBinding = ItemUpscaleModelCardBinding.inflate(inflater, dialogBinding.llModelList, false)
                cardBinding.tvItemModelName.text = "${model.name} (${model.nativeScale}×)"
                val sizeFormatted = String.format(Locale.US, "%.1f MB", model.sizeBytes / (1024.0 * 1024.0))
                cardBinding.tvItemModelDescription.text = "$sizeFormatted • ${model.description}"

                val isInstalled = repository.isModelInstalled(model)
                if (isInstalled) {
                    cardBinding.tvItemModelStatus.text = "Installed"
                    cardBinding.tvItemModelStatus.setTextColor(activity.getColor(R.color.vf_accent_green))
                    cardBinding.btnItemModelDownload.visibility = View.GONE
                    cardBinding.btnItemModelDelete.visibility = View.VISIBLE
                } else {
                    cardBinding.tvItemModelStatus.text = "Not Installed"
                    cardBinding.tvItemModelStatus.setTextColor(activity.getColor(R.color.vf_text_muted))
                    cardBinding.btnItemModelDownload.visibility = View.VISIBLE
                    cardBinding.btnItemModelDownload.text = "Download ($sizeFormatted)"
                    cardBinding.btnItemModelDelete.visibility = View.GONE
                }

                val activeJob = dialogDownloadJobs[model.id]
                if (activeJob != null && activeJob.isActive) {
                    cardBinding.progressItemModel.visibility = View.VISIBLE
                    cardBinding.btnItemModelDownload.isEnabled = false
                } else {
                    cardBinding.progressItemModel.visibility = View.GONE
                    cardBinding.btnItemModelDownload.isEnabled = true
                }

                cardBinding.btnItemModelDownload.setOnClickListener {
                    cardBinding.progressItemModel.visibility = View.VISIBLE
                    cardBinding.btnItemModelDownload.isEnabled = false
                    dialogDownloadJobs[model.id]?.cancel()

                    val job = scope.launch {
                        downloadManager.downloadModel(model, object : ModelDownloadManager.DownloadListener {
                            override fun onProgress(d: Long, t: Long, s: Long) {
                                scope.launch(Dispatchers.Main) {
                                    if (dialog.isShowing && t > 0) {
                                        cardBinding.progressItemModel.isIndeterminate = false
                                        cardBinding.progressItemModel.progress = ((d * 100) / t).toInt()
                                    }
                                }
                            }
                            override fun onVerifying() {
                                scope.launch(Dispatchers.Main) {
                                    if (dialog.isShowing) cardBinding.progressItemModel.isIndeterminate = true
                                }
                            }
                            override fun onSuccess(m: UpscaleModel, destinationFile: File) {
                                scope.launch(Dispatchers.Main) {
                                    dialogDownloadJobs.remove(model.id)
                                    if (dialog.isShowing) {
                                        cardBinding.progressItemModel.visibility = View.GONE
                                        refreshCards()
                                    }
                                    updateModelDisplay()
                                }
                            }
                            override fun onError(error: String) {
                                scope.launch(Dispatchers.Main) {
                                    dialogDownloadJobs.remove(model.id)
                                    if (dialog.isShowing) {
                                        cardBinding.progressItemModel.visibility = View.GONE
                                        cardBinding.btnItemModelDownload.isEnabled = true
                                        Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        })
                    }
                    dialogDownloadJobs[model.id] = job
                }

                cardBinding.btnItemModelDelete.setOnClickListener {
                    dialogDownloadJobs[model.id]?.cancel()
                    dialogDownloadJobs.remove(model.id)
                    repository.deleteModel(model.id)
                    refreshCards()
                    updateModelDisplay()
                }

                dialogBinding.llModelList.addView(cardBinding.root)
            }
        }

        refreshCards()
        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    private fun saveUpscaledImage() {
        val output = resultOutput ?: return
        activeExportCount.incrementAndGet()
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
                        upscalerBinding.tvUpscalerResultDetails.text = "Saved to Pictures/VeilFrame ($filename)"
                        Toast.makeText(activity, "Saved to Pictures/VeilFrame: $filename", Toast.LENGTH_LONG).show()
                        onLog("[UPSCALER] Saved upscaled image: $filename")
                    } else {
                        upscalerBinding.tvUpscalerResultDetails.text = "Failed to save upscaled image. Tap Save to retry."
                        Toast.makeText(activity, "Failed to save upscaled image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving upscaled image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    upscalerBinding.tvUpscalerResultDetails.text = "Save error: ${e.message}. Tap Save to retry."
                    Toast.makeText(activity, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                activeExportCount.decrementAndGet()
            }
        }
    }

    /**
     * Audit fix: never hand a >4096px bitmap to an ImageView — it exceeds GPU
     * max-texture limits on many devices (blank preview / RenderThread crash).
     * In-memory results keep the full bitmap for saving; the view gets a copy.
     */
    private fun displayBitmapFor(output: com.veilframe.app.upscale.inference.UpscaleOutput): Bitmap {
        val bmp = output.previewBitmap() ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val maxEdge = maxOf(bmp.width, bmp.height)
        if (maxEdge <= 4096) return bmp
        val scale = 4096f / maxEdge
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        )
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
        activeExportCount.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                val shareFile = if (output is UpscaleOutput.Streamed && output.file.isFile) {
                    output.file
                } else {
                    val cacheDir = File(activity.cacheDir, "shared_upscaled")
                    cacheDir.mkdirs()
                    val tempFile = File(cacheDir, "upscaled_${System.currentTimeMillis()}.png")
                    FileOutputStream(tempFile).use { fos ->
                        writeOutput(output, fos)
                    }
                    tempFile
                }

                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", shareFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newUri(activity.contentResolver, "Share Upscaled Image", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent.createChooser(intent, "Share Upscaled Image").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    onLog("[UPSCALER] Triggered share intent for upscaled image.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sharing image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                activeExportCount.decrementAndGet()
            }
        }
    }

    fun release() {
        currentLoadJob?.cancel()
        inferenceJob?.cancel()
        downloadJob?.cancel()
        scope.cancel()
        sourceAccess?.close()
        com.veilframe.app.upscale.UpscaleForegroundService.cancelHook = null
        com.veilframe.app.upscale.UpscaleForegroundService.stop(activity)
        safelyDeleteResultFile(resultFile)
        resultFile = null
        com.veilframe.app.runtime.ThermalGovernor.unregister()
    }
}
