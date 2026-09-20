package com.veilframe.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutImageStudioBinding
import com.veilframe.app.media.compression.ImageCompressionEngine
import com.veilframe.app.media.dialogs.ImageStudioDialogController
import com.veilframe.app.media.preview.ImagePreviewEngine
import com.veilframe.app.media.transform.ImageTransformEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MotionSpec

/**
 * Controller managing the Mobile Image Studio workspace.
 * Decomposed into specialized engines:
 * - ImagePreviewEngine (memory-safe preview loading and rendering)
 * - ImageTransformEngine (deterministic non-destructive pipeline)
 * - ImageCompressionEngine (iterative binary search target size & export)
 * - ImageStudioDialogController (isolated dialog state management)
 */
data class StudioImageItem(
    val uri: Uri,
    val file: File,
    val displayName: String,
    val originalBytes: Long,
    val origWidth: Int,
    val origHeight: Int,
    var previewBitmap: Bitmap? = null
)

class ImageStudioController(
    private val activity: AppCompatActivity,
    private val binding: LayoutImageStudioBinding,
    private val safManager: SafDestinationManager,
    private val scope: CoroutineScope,
    private val onPickImageRequest: () -> Unit,
    private val onAddMoreImageRequest: () -> Unit = onPickImageRequest,
    private val onPickFolderRequest: () -> Unit,
    private val onExportFileRequest: (File) -> Unit,
    private val onShareFileRequest: (File, String) -> Unit,
    private val onNavigateHome: () -> Unit,
    private val onOpenModelManager: () -> Unit = {}
) {
    // Authoritative state
    val editState = ImageEditState()
    val outputConfig = ImageOutputConfig()

    val selectedMediaList = mutableListOf<StudioImageItem>()
    var currentMediaIndex: Int = 0
        private set
    val currentItem: StudioImageItem? get() = selectedMediaList.getOrNull(currentMediaIndex)

    val selectedUri: Uri? get() = currentItem?.uri
    val originalFile: File? get() = currentItem?.file
    val originalBytes: Long get() = currentItem?.originalBytes ?: 0L

    // Fast downscaled copy (max 1280px) for smooth 60fps live preview & modal interactions
    private var previewSourceBitmap: Bitmap? = null
    var lastResultFile: File? = null
        private set
    private var compressionJob: Job? = null

    // Race-condition guard for async image loading
    private val loadToken = AtomicLong(0L)

    // Dialog coordinator delegating modal interactions
    private val dialogController = ImageStudioDialogController(
        activity = activity,
        scope = scope,
        editState = editState,
        getPreviewSourceBitmap = { previewSourceBitmap },
        getOriginalDimensions = {
            Pair(
                currentItem?.origWidth ?: previewSourceBitmap?.width ?: 1920,
                currentItem?.origHeight ?: previewSourceBitmap?.height ?: 1080
            )
        },
        renderLivePreview = { draftState -> renderLivePreview(draftState, useFullRes = false) },
        onEditsChanged = { refreshPreview() }
    )

    fun initWorkspace() {
        binding.btnImgStudioMenu.setOnClickListener { onNavigateHome() }
        binding.btnImgStudioFavorite.setOnClickListener { onOpenModelManager() }
        binding.btnSelectImage.setOnClickListener { onPickImageRequest() }
        binding.btnImgAddMore.setOnClickListener { onAddMoreImageRequest() }
        binding.btnImgClearAll.setOnClickListener { clear() }
        binding.btnImgRemoveFile.setOnClickListener { removeCurrentItem() }

        binding.btnImgPrev.setOnClickListener {
            if (currentMediaIndex > 0) {
                selectMediaIndex(currentMediaIndex - 1)
            }
        }
        binding.btnImgNext.setOnClickListener {
            if (currentMediaIndex < selectedMediaList.size - 1) {
                selectMediaIndex(currentMediaIndex + 1)
            }
        }

        // SAF destination selection
        binding.tvImgDestinationPath.text = safManager.imageDestinationName
        binding.btnImgChangeDestination.setOnClickListener { onPickFolderRequest() }

        // Reset all edits button in edit summary card
        binding.btnImgResetAllEdits.setOnClickListener { resetEdits() }
        binding.btnImgResetPreview.setOnClickListener { resetEdits() }

        // In-place Format chips (JPG, PNG, WebP, HEIF, HEIC, AVIF, BMP, TIFF, GIF)
        binding.chipGroupImgFormat.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupImgFormat.findViewById<Chip>(checkedIds[0])
                outputConfig.format = when (chip?.id) {
                    R.id.chipImgPng -> "PNG"
                    R.id.chipImgWebp -> "WEBP"
                    R.id.chipImgHeif -> "HEIF"
                    R.id.chipImgHeic -> "HEIC"
                    R.id.chipImgAvif -> "AVIF"
                    R.id.chipImgBmp -> "BMP"
                    R.id.chipImgTiff -> "TIFF"
                    R.id.chipImgGif -> "GIF"
                    else -> "JPG"
                }
                refreshPreview()
            }
        }

        // Compression Goal (Percentage vs Target Size)
        binding.chipGroupImgGoal.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupImgGoal.findViewById<Chip>(checkedIds[0])
                val isTarget = chip?.id == R.id.chipGoalTargetSize
                outputConfig.compressionMode = if (isTarget) "target_size" else "percentage"

                if (isTarget) {
                    binding.layoutQualityContainer.visibility = View.GONE
                    binding.layoutTargetSizeContainer.visibility = View.VISIBLE
                    if (outputConfig.targetSizeKb == null) {
                        outputConfig.targetSizeKb = binding.sliderImgTargetSize.value.toInt()
                    }
                } else {
                    binding.layoutQualityContainer.visibility = View.VISIBLE
                    binding.layoutTargetSizeContainer.visibility = View.GONE
                }
                refreshPreview()
            }
        }

        // Quality slider
        binding.sliderImgQuality.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                outputConfig.quality = value.toInt()
                binding.tvImgQualityValue.text = "${outputConfig.quality}%"
                refreshPreview()
            }
        }

        // Target size slider
        binding.sliderImgTargetSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val kb = value.toInt()
                outputConfig.targetSizeKb = kb
                binding.tvImgTargetSizeValue.text = "$kb KB"
                refreshPreview()
            }
        }

        // Target size preset chips
        binding.chipTarget50k.setOnClickListener {
            binding.sliderImgTargetSize.value = 50f
            outputConfig.targetSizeKb = 50
            binding.tvImgTargetSizeValue.text = "50 KB"
            refreshPreview()
        }
        binding.chipTarget100k.setOnClickListener {
            binding.sliderImgTargetSize.value = 100f
            outputConfig.targetSizeKb = 100
            binding.tvImgTargetSizeValue.text = "100 KB"
            refreshPreview()
        }
        binding.chipTarget250k.setOnClickListener {
            binding.sliderImgTargetSize.value = 250f
            outputConfig.targetSizeKb = 250
            binding.tvImgTargetSizeValue.text = "250 KB"
            refreshPreview()
        }
        binding.chipTarget500k.setOnClickListener {
            binding.sliderImgTargetSize.value = 500f
            outputConfig.targetSizeKb = 500
            binding.tvImgTargetSizeValue.text = "500 KB"
            refreshPreview()
        }
        binding.chipTarget1m.setOnClickListener {
            binding.sliderImgTargetSize.value = 1000f
            outputConfig.targetSizeKb = 1000
            binding.tvImgTargetSizeValue.text = "1000 KB"
            refreshPreview()
        }

        // Tool modal triggers with origin views for transformational morphing
        binding.toolCrop.setOnClickListener { dialogController.showCropDialog(binding.toolCrop) }
        binding.toolResize.setOnClickListener { dialogController.showResizeDialog(binding.toolResize) }
        binding.toolRotate.setOnClickListener { dialogController.showRotateDialog(binding.toolRotate) }
        binding.toolColorFilter.setOnClickListener { dialogController.showColorFilterDialog(binding.toolColorFilter) }
        binding.toolExif.setOnClickListener { dialogController.showExifDialog(binding.toolExif) }
        binding.toolText.setOnClickListener { dialogController.showTextWatermarkDialog(binding.toolText) }

        // Execute / cancel compression
        binding.btnImgExecute.setOnClickListener { handleExecute() }

        // Floating Action Dock wiring
        binding.btnFloatingExecute.setOnClickListener { binding.btnImgExecute.performClick() }
        binding.btnFloatingShare.setOnClickListener { binding.btnImgShareResult.performClick() }

        // Result save & share
        binding.btnImgSaveResult.setOnClickListener {
            val f = lastResultFile
            if (f != null && f.exists()) onExportFileRequest(f)
        }
        binding.btnImgShareResult.setOnClickListener {
            val f = lastResultFile
            if (f != null && f.exists()) onShareFileRequest(f, "image/*")
        }

        // Attach Material 3 Expressive tactile bounce to interactive controls
        val interactiveControls = listOf(
            binding.toolCrop,
            binding.toolResize,
            binding.toolRotate,
            binding.toolColorFilter,
            binding.toolExif,
            binding.toolText,
            binding.btnImgExecute,
            binding.btnFloatingExecute,
            binding.btnFloatingShare,
            binding.btnImgSaveResult,
            binding.btnImgShareResult,
            binding.btnImgResetAllEdits,
            binding.btnImgResetPreview,
            binding.btnSelectImage,
            binding.btnImgAddMore,
            binding.btnImgClearAll,
            binding.btnImgRemoveFile
        )
        interactiveControls.forEach { view ->
            ExpressiveMotion.applyTouchBounce(view)
        }

        // Floating Smart Action Dock scroll-merging
        val thresholdPx = MotionSpec.DOCK_THRESHOLD_DP * binding.root.resources.displayMetrics.density
        var lastScrollY = 0

        binding.scrollImageStudio.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val diff = Math.abs(scrollY - lastScrollY)
            if (diff < MotionSpec.SCROLL_DEADZONE_PX) return@setOnScrollChangeListener
            lastScrollY = scrollY

            val child = binding.scrollImageStudio.getChildAt(0) ?: return@setOnScrollChangeListener
            val scrollBottom = scrollY + v.height
            val totalHeight = child.height
            val distanceToBottom = totalHeight - scrollBottom

            val progress = (1.0f - (distanceToBottom / thresholdPx)).coerceIn(0.0f, 1.0f)
            ExpressiveMotion.updateDockProgress(
                floatingDock = binding.cardFloatingActionDock,
                dockedActions = binding.btnImgExecute,
                progress = progress
            )
        }
    }

    fun onDestinationFolderSelected(uri: Uri) {
        safManager.takePersistablePermission(uri)
        safManager.imageDestinationUri = uri
        val name = safManager.getFolderDisplayName(uri)
        safManager.imageDestinationName = name
        binding.tvImgDestinationPath.text = name
    }

    fun handleImageSelected(uri: Uri) {
        handleImagesSelected(listOf(uri))
    }

    fun handleImagesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        clear()
        loadImages(uris, isAppend = false)
    }

    fun handleImagesAdded(uris: List<Uri>) {
        if (uris.isEmpty()) return
        loadImages(uris, isAppend = true)
    }

    private fun loadImages(uris: List<Uri>, isAppend: Boolean) {
        val currentToken = loadToken.incrementAndGet()
        binding.layoutImgProgress.visibility = View.VISIBLE
        binding.tvImgProgressStatus.text = "Loading ${uris.size} image(s)..."

        scope.launch(Dispatchers.IO) {
            val newItems = mutableListOf<StudioImageItem>()
            for (uri in uris) {
                try {
                    val size = queryFileSize(uri)
                    val displayName = getDisplayName(uri)
                    val cacheFile = File(activity.cacheDir, "studio_input_${System.currentTimeMillis()}_$displayName")
                    copyUriToFile(uri, cacheFile)

                    // 1. Probe full dimensions with inJustDecodeBounds (zero pixel allocation)
                    val dims = ImagePreviewEngine.probeDimensions(cacheFile)
                    val w = dims?.width ?: 1920
                    val h = dims?.height ?: 1080

                    // 2. Decode 1280px preview bitmap (memory-safe preview)
                    // Note: Full resolution is DEFERRED and ONLY loaded when exporting!
                    val previewBmp = ImagePreviewEngine.decodePreviewBitmap(cacheFile, maxDim = 1280)

                    if (previewBmp != null) {
                        newItems.add(
                            StudioImageItem(
                                uri = uri,
                                file = cacheFile,
                                displayName = displayName,
                                originalBytes = size,
                                origWidth = w.takeIf { it > 0 } ?: previewBmp.width,
                                origHeight = h.takeIf { it > 0 } ?: previewBmp.height,
                                previewBitmap = previewBmp
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("VeilFrame.ImageStudio", "Failed loading image: ${e.message}", e)
                }
            }

            withContext(Dispatchers.Main) {
                binding.layoutImgProgress.visibility = View.GONE
                if (currentToken != loadToken.get()) {
                    newItems.forEach { it.previewBitmap?.recycle() }
                    return@withContext
                }

                if (newItems.isEmpty()) {
                    if (!isAppend && selectedMediaList.isEmpty()) {
                        Toast.makeText(activity, "Failed to load image preview(s)", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }

                val previousSize = selectedMediaList.size
                selectedMediaList.addAll(newItems)

                binding.layoutImgEmptyState.visibility = View.GONE
                binding.layoutImgSelectedState.visibility = View.VISIBLE
                binding.btnImgClearAll.isEnabled = true
                binding.toolCrop.isEnabled = true
                binding.toolResize.isEnabled = true
                binding.toolRotate.isEnabled = true
                binding.toolColorFilter.isEnabled = true
                binding.toolExif.isEnabled = true
                binding.toolText.isEnabled = true

                if (!isAppend || previousSize == 0) {
                    selectMediaIndex(0)
                } else {
                    selectMediaIndex(previousSize)
                }
                binding.btnImgExecute.text = "Compress"
            }
        }
    }

    fun selectMediaIndex(index: Int) {
        if (index !in selectedMediaList.indices) return
        currentMediaIndex = index
        val item = selectedMediaList[index]
        previewSourceBitmap = item.previewBitmap

        binding.tvImgFileName.text = item.displayName
        binding.tvImgFileSize.text = "${formatBytes(item.originalBytes)} • ${item.origWidth}x${item.origHeight}"
        binding.imgFileThumb.setImageBitmap(item.previewBitmap)
        binding.imgBeforePreview.setImageBitmap(item.previewBitmap)
        binding.tvImgBeforeSize.text = "${formatBytes(item.originalBytes)} (${item.origWidth} × ${item.origHeight} px)"

        val baseName = item.displayName.substringBeforeLast('.')
        binding.etImgOutputFilename.setText("compressed_${baseName}.${outputConfig.format.lowercase(Locale.US)}")

        updateNavigationUi()
        refreshPreview()
    }

    private fun updateNavigationUi() {
        val total = selectedMediaList.size
        if (total > 1) {
            binding.layoutImgNavRow.visibility = View.VISIBLE
            binding.scrollImgThumbnails.visibility = View.VISIBLE
            binding.tvImgPagination.text = "${currentMediaIndex + 1} / $total"
            binding.btnImgPrev.isEnabled = currentMediaIndex > 0
            binding.btnImgNext.isEnabled = currentMediaIndex < total - 1
            binding.tvImgSelectedCount.text = "$total images selected"
            renderThumbnailStrip()
        } else {
            binding.layoutImgNavRow.visibility = View.GONE
            binding.scrollImgThumbnails.visibility = View.GONE
            if (total == 1) {
                binding.tvImgSelectedCount.text = "1 image selected"
            }
        }
    }

    private fun renderThumbnailStrip() {
        val strip = binding.layoutImgThumbStrip
        strip.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val sizePx = (48 * density).toInt()
        val marginPx = (4 * density).toInt()

        for ((idx, item) in selectedMediaList.withIndex()) {
            val card = com.google.android.material.card.MaterialCardView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                radius = 6 * density
                strokeWidth = if (idx == currentMediaIndex) (2 * density).toInt() else 0
                strokeColor = activity.getColor(R.color.vf_primary)
                cardElevation = if (idx == currentMediaIndex) 4 * density else 0f
            }
            val iv = android.widget.ImageView(activity).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(item.previewBitmap)
            }
            card.addView(iv)
            card.setOnClickListener { selectMediaIndex(idx) }
            strip.addView(card)
        }
    }

    fun removeCurrentItem() {
        if (selectedMediaList.isEmpty()) return
        val item = selectedMediaList.removeAt(currentMediaIndex)
        item.previewBitmap?.recycle()
        if (selectedMediaList.isEmpty()) {
            clear()
        } else {
            currentMediaIndex = currentMediaIndex.coerceAtMost(selectedMediaList.size - 1)
            selectMediaIndex(currentMediaIndex)
        }
    }

    /**
     * Renders live preview using ImageTransformEngine.
     */
    fun renderLivePreview(state: ImageEditState = editState, useFullRes: Boolean = false): Bitmap? {
        val item = currentItem ?: return null
        val src = previewSourceBitmap ?: return null
        val fullW = item.origWidth
        val fullH = item.origHeight

        return ImageTransformEngine.transform(
            src = src,
            state = state,
            origFullW = fullW,
            origFullH = fullH,
            useFullRes = false
        )
    }

    fun refreshPreview() {
        val previewBmp = renderLivePreview(editState) ?: return
        binding.imgAfterPreview.setImageBitmap(previewBmp)
        val visualScale = (editState.resizeScale.toFloat() / 100f).coerceIn(0.2f, 2.5f)
        binding.imgAfterPreview.scaleX = visualScale
        binding.imgAfterPreview.scaleY = visualScale

        val item = currentItem
        val origFullW = item?.origWidth ?: previewBmp.width
        val origFullH = item?.origHeight ?: previewBmp.height

        val targetW = when {
            editState.resizeWidth > 0 -> editState.resizeWidth
            editState.resizeScale != 100 -> ((origFullW * editState.resizeScale) / 100).coerceAtLeast(1)
            else -> origFullW
        }
        val targetH = when {
            editState.resizeHeight > 0 -> editState.resizeHeight
            editState.resizeScale != 100 -> ((origFullH * editState.resizeScale) / 100).coerceAtLeast(1)
            else -> origFullH
        }

        val isPng = outputConfig.format.equals("PNG", ignoreCase = true)
        if (outputConfig.compressionMode == "target_size") {
            val targetKb = outputConfig.targetSizeKb ?: 250
            val targetBytes = targetKb.toLong() * 1024L
            binding.tvImgQualityLabel.text = "Target Ceiling:"
            binding.tvImgQualityValue.text = "$targetKb KB"
            binding.tvImgAfterSize.text = "~${formatBytes(targetBytes)} ($targetW × $targetH px • ${outputConfig.format})"
            val ratio = if (originalBytes > 0) {
                (100.0 - (targetBytes.toDouble() / originalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
            } else 0
            binding.tvImgComparisonRatio.text = "Total: ${formatBytes(originalBytes)} → ~${formatBytes(targetBytes)} (-$ratio%)"
        } else if (isPng) {
            binding.tvImgQualityLabel.text = "Compression:"
            binding.tvImgQualityValue.text = "Deflate (Lossless)"
            binding.tvImgAfterSize.text = "$targetW × $targetH px • Deflate"
            binding.tvImgComparisonRatio.text = "PNG is lossless • Size determined by image complexity"
        } else {
            binding.tvImgQualityLabel.text = "Quality:"
            binding.tvImgQualityValue.text = "${outputConfig.quality}%"

            val estBytes = ImageCompressionEngine.estimateOutputBytes(
                originalBytes = originalBytes,
                origWidth = origFullW,
                origHeight = origFullH,
                targetWidth = targetW,
                targetHeight = targetH,
                outputConfig = outputConfig
            )

            binding.tvImgAfterSize.text = "~${formatBytes(estBytes)} ($targetW × $targetH px • ${outputConfig.format})"
            val ratio = if (originalBytes > 0) {
                (100.0 - (estBytes.toDouble() / originalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
            } else 0
            binding.tvImgComparisonRatio.text = "Total: ${formatBytes(originalBytes)} → ~${formatBytes(estBytes)} (-$ratio%)"
        }

        updateEditSummary()
    }

    private fun updateEditSummary() {
        val hasEdits = editState.hasEdits()
        if (!hasEdits) {
            binding.tvImgSummaryEmpty.visibility = View.VISIBLE
            binding.layoutImgSummaryDetails.visibility = View.GONE
        } else {
            binding.tvImgSummaryEmpty.visibility = View.GONE
            binding.layoutImgSummaryDetails.visibility = View.VISIBLE

            if (editState.isCropped() || (editState.cropAspect != "Free" && editState.cropAspect != "Original")) {
                binding.tvImgSummaryCrop.visibility = View.VISIBLE
                binding.tvImgSummaryCrop.text = "✓ Crop: ${editState.cropAspect}"
            } else {
                binding.tvImgSummaryCrop.visibility = View.GONE
            }

            if (editState.flipH || editState.flipV) {
                binding.tvImgSummaryRotate.visibility = View.VISIBLE
                val flipStr = if (editState.flipH && editState.flipV) "Flip H+V" else if (editState.flipH) "Flip Horizontal" else "Flip Vertical"
                val rotStr = if (editState.rotationAngle != 0f) "Rotate ${editState.rotationAngle.toInt()}° • $flipStr" else flipStr
                binding.tvImgSummaryRotate.text = "✓ $rotStr"
            } else if (editState.rotationAngle != 0f) {
                binding.tvImgSummaryRotate.visibility = View.VISIBLE
                binding.tvImgSummaryRotate.text = "✓ Rotate: ${editState.rotationAngle.toInt()}°"
            } else {
                binding.tvImgSummaryRotate.visibility = View.GONE
            }

            if (editState.watermarkText.isNotEmpty()) {
                binding.tvImgSummaryCrop.visibility = View.VISIBLE
                val curCrop = if (editState.isCropped()) "Crop ${editState.cropAspect} • " else ""
                binding.tvImgSummaryCrop.text = "✓ ${curCrop}Watermark: \"${editState.watermarkText}\""
            }

            if (editState.resizeScale != 100 || editState.resizeWidth > 0) {
                binding.tvImgSummaryResize.visibility = View.VISIBLE
                binding.tvImgSummaryResize.text = if (editState.resizeWidth > 0) {
                    "✓ Resize: ${editState.resizeWidth}×${editState.resizeHeight} px"
                } else {
                    "✓ Resize: ${editState.resizeScale}%"
                }
            } else {
                binding.tvImgSummaryResize.visibility = View.GONE
            }

            if (editState.filter != "Default" && editState.filter != "None") {
                binding.tvImgSummaryFilter.visibility = View.VISIBLE
                binding.tvImgSummaryFilter.text = "✓ Filter: ${editState.filter}"
            } else {
                binding.tvImgSummaryFilter.visibility = View.GONE
            }

            val hasExifEdits = !editState.stripExif || editState.exifMake.isNotEmpty() ||
                    editState.exifModel.isNotEmpty() || editState.exifSoftware.isNotEmpty() ||
                    editState.exifDateTime.isNotEmpty() || editState.exifGps.isNotEmpty()
            if (hasExifEdits) {
                binding.tvImgSummaryExif.visibility = View.VISIBLE
                binding.tvImgSummaryExif.text = if (editState.stripExif) "✓ EXIF: Privacy Scrubbed (Clean)" else "✓ EXIF: Custom Metadata"
            } else {
                binding.tvImgSummaryExif.visibility = View.GONE
            }
        }
        syncFloatingDockState()
    }

    fun resetEdits() {
        editState.reset()
        outputConfig.quality = 85
        binding.sliderImgQuality.value = 85f
        binding.tvImgQualityValue.text = "85%"
        binding.chipImgJpg.isChecked = true
        outputConfig.format = "JPG"
        binding.imgAfterPreview.scaleX = 1f
        binding.imgAfterPreview.scaleY = 1f

        refreshPreview()
        Toast.makeText(activity, "Image edits reset to original", Toast.LENGTH_SHORT).show()
    }

    fun execute() {
        handleExecute()
    }

    fun release() {
        compressionJob?.cancel()
        compressionJob = null
    }

    private fun handleExecute() {
        if (selectedUri == null) {
            onPickImageRequest()
            return
        }

        if (compressionJob?.isActive == true) {
            compressionJob?.cancel()
            binding.layoutImgProgress.visibility = View.GONE
            binding.btnImgExecute.text = "Compress"
            Toast.makeText(activity, "Compression cancelled", Toast.LENGTH_SHORT).show()
            return
        }

        executeCompression()
    }

    private fun executeCompression() {
        val itemsToProcess = selectedMediaList.toList()
        if (itemsToProcess.isEmpty()) return

        val outDir = File(activity.cacheDir, "studio_output").apply { mkdirs() }
        val ext = outputConfig.format.lowercase(Locale.US)
        val rawName = binding.etImgOutputFilename.text.toString().trim()
        val safeBase = File(rawName).name.substringBeforeLast('.').ifBlank { "compressed_image" }

        binding.layoutImgProgress.visibility = View.VISIBLE
        binding.btnImgExecute.text = "Cancel"

        compressionJob = scope.launch(Dispatchers.IO) {
            try {
                var successCount = 0
                val total = itemsToProcess.size
                var lastSavedFile: File? = null
                var totalOriginalBytes = 0L
                var totalCompressedBytes = 0L
                val destUri = safManager.imageDestinationUri

                for ((idx, item) in itemsToProcess.withIndex()) {
                    if (!isActive) break

                    withContext(Dispatchers.Main) {
                        binding.tvImgProgressStatus.text = "Processing ${idx + 1} of $total: ${item.displayName}"
                    }

                    val outFilename = if (total == 1) "$safeBase.$ext" else "${safeBase}_${idx + 1}.$ext"
                    val outFile = File(outDir, outFilename)

                    var fullResBmp: Bitmap? = null
                    var transformedBmp: Bitmap? = null
                    try {
                        // DEFERRED FULL RESOLUTION DECODE: ONLY WHEN EXPORTING!
                        fullResBmp = ImagePreviewEngine.decodeFullResolution(item.file)
                        if (fullResBmp == null) {
                            fullResBmp = ImagePreviewEngine.decodePreviewBitmap(item.file, maxDim = 2560)
                        }
                        if (fullResBmp == null) {
                            Log.e("VeilFrame.ImageStudio", "Could not decode full-res for ${item.displayName}")
                            continue
                        }

                        // Apply non-destructive transforms to full-res bitmap
                        transformedBmp = ImageTransformEngine.transform(
                            src = fullResBmp,
                            state = editState,
                            origFullW = item.origWidth,
                            origFullH = item.origHeight,
                            useFullRes = true
                        )

                        val result = ImageCompressionEngine.compress(
                            srcFile = item.file,
                            outFile = outFile,
                            editState = editState,
                            outputConfig = outputConfig,
                            processedBitmap = transformedBmp ?: fullResBmp
                        )

                        if (result.success && outFile.exists()) {
                            successCount++
                            lastSavedFile = outFile
                            totalOriginalBytes += item.originalBytes
                            totalCompressedBytes += outFile.length()

                            if (destUri != null) {
                                val mime = when (outputConfig.format.uppercase(Locale.US)) {
                                    "PNG" -> "image/png"
                                    "WEBP" -> "image/webp"
                                    "BMP" -> "image/bmp"
                                    "TIFF" -> "image/tiff"
                                    "GIF" -> "image/gif"
                                    "HEIF" -> "image/heif"
                                    "HEIC" -> "image/heic"
                                    "AVIF" -> "image/avif"
                                    else -> "image/jpeg"
                                }
                                safManager.copyFileToDocumentTree(outFile, destUri, mime)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, "Failed for ${item.displayName}: ${result.error}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } finally {
                        transformedBmp?.recycle()
                        fullResBmp?.recycle()
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.layoutImgProgress.visibility = View.GONE
                    binding.btnImgExecute.text = "Compress again"

                    if (successCount > 0 && lastSavedFile != null && lastSavedFile.exists()) {
                        lastResultFile = lastSavedFile
                        val finalBmp = BitmapFactory.decodeFile(lastSavedFile.absolutePath)
                        if (finalBmp != null) {
                            binding.imgAfterPreview.setImageBitmap(finalBmp)
                        }
                        binding.tvImgAfterSize.text = "${formatBytes(lastSavedFile.length())} (${outputConfig.format})"
                        val ratio = if (totalOriginalBytes > 0) {
                            (100.0 - (totalCompressedBytes.toDouble() / totalOriginalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
                        } else 0
                        binding.tvImgComparisonRatio.text = "Saved: ${formatBytes(totalOriginalBytes)} → ${formatBytes(totalCompressedBytes)} (-$ratio%)"
                        binding.tvImgActualStats.visibility = View.VISIBLE
                        binding.tvImgActualStats.text = "Processed $successCount of $total images • Saved $ratio%"
                        binding.layoutImgResultActions.visibility = View.VISIBLE

                        val destMsg = if (safManager.imageDestinationUri != null) "\nSaved to: ${safManager.imageDestinationName}" else ""
                        Toast.makeText(activity, "Successfully compressed $successCount image(s)! (-$ratio%)$destMsg", Toast.LENGTH_SHORT).show()
                        ExpressiveMotion.playJellyBounce(binding.btnImgExecute)
                        ExpressiveMotion.playJellyBounce(binding.btnFloatingExecute)
                    } else {
                        Toast.makeText(activity, "Compression failed to produce valid outputs", Toast.LENGTH_LONG).show()
                    }
                    syncFloatingDockState()
                }
            } catch (e: Exception) {
                Log.e("VeilFrame.ImageStudioController", "Compression failure: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.layoutImgProgress.visibility = View.GONE
                    binding.btnImgExecute.text = "Compress"
                    Toast.makeText(activity, "Compression error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun clear() {
        loadToken.incrementAndGet()
        selectedMediaList.forEach { it.previewBitmap?.recycle() }
        selectedMediaList.clear()
        currentMediaIndex = 0
        previewSourceBitmap = null
        lastResultFile = null
        compressionJob?.cancel()

        binding.layoutImgNavRow.visibility = View.GONE
        binding.scrollImgThumbnails.visibility = View.GONE
        binding.layoutImgThumbStrip.removeAllViews()

        binding.layoutImgEmptyState.visibility = View.VISIBLE
        binding.layoutImgSelectedState.visibility = View.GONE
        binding.btnImgClearAll.isEnabled = false
        binding.toolCrop.isEnabled = false
        binding.toolResize.isEnabled = false
        binding.toolRotate.isEnabled = false
        binding.toolColorFilter.isEnabled = false
        binding.toolExif.isEnabled = false
        binding.toolText.isEnabled = false

        binding.imgFileThumb.setImageDrawable(null)
        binding.imgBeforePreview.setImageDrawable(null)
        binding.imgAfterPreview.setImageDrawable(null)
        binding.tvImgBeforeSize.text = "0 B • Original"
        binding.tvImgAfterSize.text = "~0 B (JPG)"
        binding.tvImgActualStats.visibility = View.GONE
        binding.layoutImgResultActions.visibility = View.GONE
        binding.btnImgExecute.text = "Select image"
        editState.reset()
        updateEditSummary()
    }

    // Helpers
    private fun queryFileSize(uri: Uri): Long {
        return try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) cursor.getString(nameIndex) else "image.jpg"
            } ?: "image.jpg"
        } catch (_: Exception) { "image.jpg" }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        activity.contentResolver.openInputStream(uri)?.use { inStream ->
            FileOutputStream(target).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024.0 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    /**
     * Synchronizes the floating quick action dock with current media and execution state.
     */
    fun syncFloatingDockState() {
        val hasMedia = (selectedMediaList.isNotEmpty() && currentItem != null)
        val isBusy = (compressionJob?.isActive == true)
        val hasResult = (lastResultFile != null && lastResultFile?.exists() == true)

        if (!hasMedia || isBusy) {
            binding.cardFloatingActionDock.visibility = View.GONE
            return
        }

        binding.btnFloatingExecute.text = binding.btnImgExecute.text
        binding.btnFloatingExecute.isEnabled = binding.btnImgExecute.isEnabled
        binding.btnFloatingShare.visibility = if (hasResult) View.VISIBLE else View.GONE

        if (binding.cardFloatingActionDock.alpha > 0.05f) {
            binding.cardFloatingActionDock.visibility = View.VISIBLE
        }
    }
}
