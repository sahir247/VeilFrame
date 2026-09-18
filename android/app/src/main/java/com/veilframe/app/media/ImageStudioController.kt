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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Controller managing the Mobile Image Studio workspace.
 * Decomposed into specialized engines:
 * - ImagePreviewEngine (memory-safe preview loading and rendering)
 * - ImageTransformEngine (deterministic non-destructive pipeline)
 * - ImageCompressionEngine (iterative binary search target size & export)
 * - ImageStudioDialogController (isolated dialog state management)
 */
class ImageStudioController(
    private val activity: AppCompatActivity,
    private val binding: LayoutImageStudioBinding,
    private val safManager: SafDestinationManager,
    private val scope: CoroutineScope,
    private val onPickImageRequest: () -> Unit,
    private val onPickFolderRequest: () -> Unit,
    private val onExportFileRequest: (File) -> Unit,
    private val onShareFileRequest: (File, String) -> Unit,
    private val onNavigateHome: () -> Unit
) {
    // Authoritative state
    val editState = ImageEditState()
    val outputConfig = ImageOutputConfig()

    var selectedUri: Uri? = null
        private set
    var originalFile: File? = null
        private set
    var originalBytes: Long = 0L
        private set

    // Immutable original bitmap source — probed or loaded safely
    private var originalBitmap: Bitmap? = null
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
        getOriginalBitmap = { originalBitmap },
        renderLivePreview = { draftState -> renderLivePreview(draftState, useFullRes = false) },
        onEditsChanged = { refreshPreview() }
    )

    fun initWorkspace() {
        binding.btnImgStudioMenu.setOnClickListener { onNavigateHome() }
        binding.btnSelectImage.setOnClickListener { onPickImageRequest() }
        binding.btnImgAddMore.setOnClickListener { onPickImageRequest() }
        binding.btnImgClearAll.setOnClickListener { clear() }
        binding.btnImgRemoveFile.setOnClickListener { clear() }

        // SAF destination selection
        binding.tvImgDestinationPath.text = safManager.imageDestinationName
        binding.btnImgChangeDestination.setOnClickListener { onPickFolderRequest() }

        // Reset all edits button in edit summary card
        binding.btnImgResetAllEdits.setOnClickListener { resetEdits() }
        binding.btnImgResetPreview.setOnClickListener { resetEdits() }

        // In-place Format chips (JPG, PNG, WebP)
        binding.chipGroupImgFormat.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupImgFormat.findViewById<Chip>(checkedIds[0])
                outputConfig.format = when (chip?.id) {
                    R.id.chipImgPng -> "PNG"
                    R.id.chipImgWebp -> "WEBP"
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

        // Tool modal triggers delegated to ImageStudioDialogController
        binding.toolCrop.setOnClickListener { dialogController.showCropDialog() }
        binding.toolResize.setOnClickListener { dialogController.showResizeDialog() }
        binding.toolRotate.setOnClickListener { dialogController.showRotateDialog() }
        binding.toolColorFilter.setOnClickListener { dialogController.showColorFilterDialog() }
        binding.toolExif.setOnClickListener { dialogController.showExifDialog() }
        binding.toolText.setOnClickListener { dialogController.showTextWatermarkDialog() }

        // Execute / cancel compression
        binding.btnImgExecute.setOnClickListener { handleExecute() }

        // Result save & share
        binding.btnImgSaveResult.setOnClickListener {
            val f = lastResultFile
            if (f != null && f.exists()) onExportFileRequest(f)
        }
        binding.btnImgShareResult.setOnClickListener {
            val f = lastResultFile
            if (f != null && f.exists()) onShareFileRequest(f, "image/*")
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
        val currentToken = loadToken.incrementAndGet()
        selectedUri = uri
        originalBytes = queryFileSize(uri)
        val displayName = getDisplayName(uri)

        scope.launch(Dispatchers.IO) {
            val cacheFile = File(activity.cacheDir, "studio_input_$displayName")
            copyUriToFile(uri, cacheFile)
            originalFile = cacheFile

            // Memory-safe loading via ImagePreviewEngine
            val previewBmp = ImagePreviewEngine.decodePreviewBitmap(cacheFile, maxDim = 1280)
            val fullBmp = ImagePreviewEngine.decodeFullResolution(cacheFile)

            withContext(Dispatchers.Main) {
                if (currentToken != loadToken.get()) {
                    // Stale selection; user selected another image or cleared
                    previewBmp?.recycle()
                    fullBmp?.recycle()
                    return@withContext
                }

                originalBitmap = fullBmp
                previewSourceBitmap = previewBmp ?: fullBmp

                if (previewSourceBitmap == null) {
                    Toast.makeText(activity, "Failed to load image preview", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val origW = fullBmp?.width ?: previewSourceBitmap!!.width
                val origH = fullBmp?.height ?: previewSourceBitmap!!.height

                binding.layoutImgEmptyState.visibility = View.GONE
                binding.layoutImgSelectedState.visibility = View.VISIBLE
                binding.tvImgSelectedCount.text = "1 image selected"
                binding.tvImgFileName.text = displayName
                binding.tvImgFileSize.text = "${formatBytes(originalBytes)} • ${origW}x${origH}"
                binding.imgFileThumb.setImageBitmap(previewSourceBitmap)
                binding.imgBeforePreview.setImageBitmap(previewSourceBitmap)
                binding.tvImgBeforeSize.text = "${formatBytes(originalBytes)} ($origW × $origH px)"
                binding.btnImgClearAll.isEnabled = true

                // Enable all tools
                binding.toolCrop.isEnabled = true
                binding.toolResize.isEnabled = true
                binding.toolRotate.isEnabled = true
                binding.toolColorFilter.isEnabled = true
                binding.toolExif.isEnabled = true
                binding.toolText.isEnabled = true

                val baseName = displayName.substringBeforeLast('.')
                binding.etImgOutputFilename.setText("compressed_${baseName}.${outputConfig.format.lowercase(Locale.US)}")

                // Reset edits and refresh
                editState.reset()
                binding.tvImgActualStats.visibility = View.GONE
                binding.layoutImgResultActions.visibility = View.GONE

                refreshPreview()
                binding.btnImgExecute.text = "Compress"
            }
        }
    }

    /**
     * Renders live preview using ImageTransformEngine.
     */
    fun renderLivePreview(state: ImageEditState = editState, useFullRes: Boolean = false): Bitmap? {
        val src = (if (useFullRes) (originalBitmap ?: previewSourceBitmap) else (previewSourceBitmap ?: originalBitmap)) ?: return null
        val fullW = originalBitmap?.width ?: src.width
        val fullH = originalBitmap?.height ?: src.height

        return ImageTransformEngine.transform(
            src = src,
            state = state,
            origFullW = fullW,
            origFullH = fullH,
            useFullRes = useFullRes
        )
    }

    fun refreshPreview() {
        val previewBmp = renderLivePreview(editState) ?: return
        binding.imgAfterPreview.setImageBitmap(previewBmp)

        val origFullW = originalBitmap?.width ?: previewBmp.width
        val origFullH = originalBitmap?.height ?: previewBmp.height

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
    }

    fun resetEdits() {
        editState.reset()
        outputConfig.quality = 85
        binding.sliderImgQuality.value = 85f
        binding.tvImgQualityValue.text = "85%"
        binding.chipImgJpg.isChecked = true
        outputConfig.format = "JPG"

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
        val srcFile = originalFile ?: return
        val outDir = File(activity.cacheDir, "studio_output").apply { mkdirs() }
        val ext = outputConfig.format.lowercase(Locale.US)
        val rawName = binding.etImgOutputFilename.text.toString().trim()
        val safeBase = File(rawName).name.substringBeforeLast('.').ifBlank { "compressed_image" }
        val outFilename = "$safeBase.$ext"
        val outFile = File(outDir, outFilename)

        binding.layoutImgProgress.visibility = View.VISIBLE
        binding.btnImgExecute.text = "Cancel"

        compressionJob = scope.launch(Dispatchers.IO) {
            try {
                // High-fidelity full-resolution render for final output
                val fullResBmp = renderLivePreview(editState, useFullRes = true)
                if (fullResBmp == null) {
                    withContext(Dispatchers.Main) {
                        binding.layoutImgProgress.visibility = View.GONE
                        binding.btnImgExecute.text = "Compress"
                        Toast.makeText(activity, "Failed to render processed image", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val result = ImageCompressionEngine.compress(
                    srcFile = srcFile,
                    outFile = outFile,
                    editState = editState,
                    outputConfig = outputConfig,
                    processedBitmap = fullResBmp
                )

                // Copy to SAF Destination Folder if chosen
                val destUri = safManager.imageDestinationUri
                var copiedToDest = false
                if (result.success && destUri != null && outFile.exists()) {
                    val mime = when (outputConfig.format.uppercase(Locale.US)) {
                        "PNG" -> "image/png"
                        "WEBP" -> "image/webp"
                        else -> "image/jpeg"
                    }
                    val copyUri = safManager.copyFileToDocumentTree(outFile, destUri, mime)
                    copiedToDest = (copyUri != null)
                }

                withContext(Dispatchers.Main) {
                    binding.layoutImgProgress.visibility = View.GONE
                    binding.btnImgExecute.text = "Compress again"
                    if (result.success && outFile.exists()) {
                        lastResultFile = outFile
                        val finalBmp = BitmapFactory.decodeFile(outFile.absolutePath) ?: fullResBmp
                        binding.imgAfterPreview.setImageBitmap(finalBmp)
                        binding.tvImgAfterSize.text = "${formatBytes(outFile.length())} (${outputConfig.format})"
                        val ratio = if (originalBytes > 0) {
                            (100.0 - (outFile.length().toDouble() / originalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
                        } else 0
                        binding.tvImgComparisonRatio.text = "Saved: ${formatBytes(originalBytes)} → ${formatBytes(outFile.length())} (-$ratio%)"
                        binding.tvImgActualStats.visibility = View.VISIBLE
                        binding.tvImgActualStats.text = "Compression finished • Saved $ratio%"
                        binding.layoutImgResultActions.visibility = View.VISIBLE

                        val destMsg = if (copiedToDest) "\nSaved to destination folder: ${safManager.imageDestinationName}" else ""
                        Toast.makeText(activity, "Image compressed successfully! (-$ratio%)$destMsg", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Image compression failed: ${result.error}", Toast.LENGTH_LONG).show()
                    }
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
        selectedUri = null
        originalFile = null
        originalBytes = 0L
        originalBitmap = null
        previewSourceBitmap = null
        lastResultFile = null
        compressionJob?.cancel()

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
}
