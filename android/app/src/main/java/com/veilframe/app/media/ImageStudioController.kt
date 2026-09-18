package com.veilframe.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogColorFilterBinding
import com.veilframe.app.databinding.DialogCropBinding
import com.veilframe.app.databinding.DialogExifBinding
import com.veilframe.app.databinding.DialogResizeBinding
import com.veilframe.app.databinding.DialogRotateBinding
import com.veilframe.app.databinding.DialogTextWatermarkBinding
import com.veilframe.app.databinding.LayoutImageStudioBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Controller managing the Mobile Image Studio workspace.
 * Implements an immutable original source bitmap pipeline to prevent cumulative distortion.
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

    // Immutable original bitmap source — never modified or accumulated onto directly
    private var originalBitmap: Bitmap? = null
    // Fast downscaled copy (max 1280px) for smooth 60fps live preview & modal interactions
    private var previewSourceBitmap: Bitmap? = null
    var lastResultFile: File? = null
        private set
    private var compressionJob: Job? = null

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
                binding.layoutQualityContainer.visibility = if (isTarget) View.GONE else View.VISIBLE
                binding.layoutTargetSizeContainer.visibility = if (isTarget) View.VISIBLE else View.GONE
                refreshPreview()
            }
        }

        // Quality slider
        binding.sliderImgQuality.addOnChangeListener { _, value, _ ->
            outputConfig.quality = value.toInt()
            binding.tvImgQualityValue.text = "${outputConfig.quality}%"
            refreshPreview()
        }

        // Target size slider & presets
        binding.sliderImgTargetSize.addOnChangeListener { _, value, _ ->
            outputConfig.targetSizeKb = value.toInt()
            binding.tvImgTargetSizeValue.text = "${outputConfig.targetSizeKb} KB"
            refreshPreview()
        }

        binding.chipGroupTargetPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupTargetPresets.findViewById<Chip>(checkedIds[0])
                val targetKb = when (chip?.id) {
                    R.id.chipTarget50k -> 50
                    R.id.chipTarget100k -> 100
                    R.id.chipTarget250k -> 250
                    R.id.chipTarget500k -> 500
                    R.id.chipTarget1m -> 1024
                    else -> 250
                }
                outputConfig.targetSizeKb = targetKb
                binding.sliderImgTargetSize.value = targetKb.toFloat().coerceIn(binding.sliderImgTargetSize.valueFrom, binding.sliderImgTargetSize.valueTo)
                binding.tvImgTargetSizeValue.text = "$targetKb KB"
                refreshPreview()
            }
        }

        // Tool buttons
        binding.toolCrop.setOnClickListener { showCropDialog() }
        binding.toolResize.setOnClickListener { showResizeDialog() }
        binding.toolRotate.setOnClickListener { showRotateDialog() }
        binding.toolColorFilter.setOnClickListener { showColorFilterDialog() }
        binding.toolExif.setOnClickListener { showExifDialog() }
        binding.toolText.setOnClickListener { showTextWatermarkDialog() }

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
        selectedUri = uri
        originalBytes = queryFileSize(uri)
        val displayName = getDisplayName(uri)

        scope.launch(Dispatchers.IO) {
            val cacheFile = File(activity.cacheDir, "studio_input_$displayName")
            copyUriToFile(uri, cacheFile)
            originalFile = cacheFile

            val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
            originalBitmap = bmp // Set immutable full-resolution source

            // Generate low-memory fast preview source (max 1280px) for smooth 60fps interaction
            previewSourceBitmap = if (bmp != null && (bmp.width > 1280 || bmp.height > 1280)) {
                val scale = 1280f / maxOf(bmp.width, bmp.height).toFloat()
                val targetW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            } else {
                bmp
            }

            withContext(Dispatchers.Main) {
                if (bmp == null) {
                    Toast.makeText(activity, "Failed to load image preview", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                binding.layoutImgEmptyState.visibility = View.GONE
                binding.layoutImgSelectedState.visibility = View.VISIBLE
                binding.tvImgSelectedCount.text = "1 image selected"
                binding.tvImgFileName.text = displayName
                binding.tvImgFileSize.text = "${formatBytes(originalBytes)} • ${bmp.width}x${bmp.height}"
                binding.imgFileThumb.setImageBitmap(previewSourceBitmap ?: bmp)
                binding.imgBeforePreview.setImageBitmap(previewSourceBitmap ?: bmp)
                binding.tvImgBeforeSize.text = "${formatBytes(originalBytes)} (${bmp.width} × ${bmp.height} px)"
                binding.btnImgClearAll.isEnabled = true

                // Enable all tools
                binding.toolCrop.isEnabled = true
                binding.toolResize.isEnabled = true
                binding.toolRotate.isEnabled = true
                binding.toolColorFilter.isEnabled = true
                binding.toolExif.isEnabled = true
                binding.toolText.isEnabled = true

                val baseName = displayName.substringBeforeLast('.')
                binding.etImgOutputFilename.setText("compressed_${baseName}.${outputConfig.format.lowercase()}")

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
     * Authoritative non-destructive image transformation pipeline.
     * Consumes an explicit ImageEditState (either the global authoritative editState or a modal's draftState).
     * Order of operations is deterministic and identical between preview and export:
     * Crop -> Rotate/Flip -> Resize -> Background Fill -> Color Filter -> Text Watermark
     */
    fun renderLivePreview(state: ImageEditState = editState, useFullRes: Boolean = false): Bitmap? {
        val src = (if (useFullRes) originalBitmap else (previewSourceBitmap ?: originalBitmap)) ?: return null

        var result = src

        // 1. Crop
        if (state.isCropped()) {
            val cropL = (state.cropLeft * result.width).toInt().coerceIn(0, result.width - 1)
            val cropT = (state.cropTop * result.height).toInt().coerceIn(0, result.height - 1)
            val cropR = (state.cropRight * result.width).toInt().coerceIn(cropL + 1, result.width)
            val cropB = (state.cropBottom * result.height).toInt().coerceIn(cropT + 1, result.height)
            try {
                result = Bitmap.createBitmap(result, cropL, cropT, cropR - cropL, cropB - cropT)
            } catch (_: Exception) {}
        } else if (state.cropAspect != "Free" && state.cropAspect != "Original") {
            val ratio = when (state.cropAspect) {
                "1:1" -> 1.0f
                "4:3" -> 4f / 3f
                "3:4" -> 3f / 4f
                "16:9" -> 16f / 9f
                "9:16" -> 9f / 16f
                else -> null
            }
            if (ratio != null) {
                val origW = result.width.toFloat()
                val origH = result.height.toFloat()
                var targetW = origW
                var targetH = origW / ratio
                if (targetH > origH) {
                    targetH = origH
                    targetW = origH * ratio
                }
                val left = ((origW - targetW) / 2f).toInt().coerceAtLeast(0)
                val top = ((origH - targetH) / 2f).toInt().coerceAtLeast(0)
                val w = targetW.toInt().coerceIn(1, result.width - left)
                val h = targetH.toInt().coerceIn(1, result.height - top)
                try {
                    result = Bitmap.createBitmap(result, left, top, w, h)
                } catch (_: Exception) {}
            }
        }

        // 2. Rotate & Flip
        if (state.rotationAngle != 0f || state.flipH || state.flipV) {
            try {
                val matrix = Matrix()
                if (state.rotationAngle != 0f) {
                    matrix.postRotate(state.rotationAngle)
                }
                if (state.flipH || state.flipV) {
                    val sx = if (state.flipH) -1f else 1f
                    val sy = if (state.flipV) -1f else 1f
                    matrix.postScale(sx, sy)
                }
                result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
            } catch (_: Exception) {}
        }

        // 3. Resize
        val origFullW = originalBitmap?.width ?: result.width
        val origFullH = originalBitmap?.height ?: result.height
        val targetWidth: Int
        val targetHeight: Int
        if (state.resizeWidth > 0 && state.resizeHeight > 0) {
            if (useFullRes) {
                targetWidth = state.resizeWidth
                targetHeight = state.resizeHeight
            } else {
                val ratioW = result.width.toFloat() / origFullW.toFloat()
                val ratioH = result.height.toFloat() / origFullH.toFloat()
                targetWidth = (state.resizeWidth * ratioW).toInt().coerceAtLeast(1)
                targetHeight = (state.resizeHeight * ratioH).toInt().coerceAtLeast(1)
            }
        } else if (state.resizeScale != 100) {
            targetWidth = ((result.width * state.resizeScale) / 100).coerceAtLeast(1)
            targetHeight = ((result.height * state.resizeScale) / 100).coerceAtLeast(1)
        } else {
            targetWidth = result.width
            targetHeight = result.height
        }
        if (targetWidth != result.width || targetHeight != result.height) {
            try {
                result = Bitmap.createScaledBitmap(result, targetWidth, targetHeight, true)
            } catch (_: Exception) {}
        }

        // 4. Background Fill (for alpha transparency)
        if (state.bgType != "Transparent") {
            try {
                val bgBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bgBmp)
                val bgPaint = Paint().apply {
                    color = if (state.bgType.contains("White", ignoreCase = true)) {
                        android.graphics.Color.WHITE
                    } else {
                        android.graphics.Color.BLACK
                    }
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), bgPaint)
                canvas.drawBitmap(result, 0f, 0f, null)
                result = bgBmp
            } catch (_: Exception) {}
        }

        // 5. Color filter
        if (state.filter != "Default" && state.filter != "None") {
            try {
                val filteredBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(filteredBmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                val colorMatrix = ColorMatrix()
                when (state.filter) {
                    "Grayscale" -> colorMatrix.setSaturation(0f)
                    "Sepia" -> {
                        val sepia = ColorMatrix(
                            floatArrayOf(
                                0.393f, 0.769f, 0.189f, 0f, 0f,
                                0.349f, 0.686f, 0.168f, 0f, 0f,
                                0.272f, 0.534f, 0.131f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(sepia)
                    }
                    "Cool" -> {
                        val cool = ColorMatrix(
                            floatArrayOf(
                                0.8f, 0f, 0f, 0f, 0f,
                                0f, 0.9f, 0f, 0f, 0f,
                                0f, 0f, 1.2f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(cool)
                    }
                    "Warm" -> {
                        val warm = ColorMatrix(
                            floatArrayOf(
                                1.2f, 0f, 0f, 0f, 0f,
                                0f, 1.0f, 0f, 0f, 0f,
                                0f, 0f, 0.8f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(warm)
                    }
                    "Vintage" -> {
                        val vintage = ColorMatrix(
                            floatArrayOf(
                                0.9f, 0f, 0f, 0f, 10f,
                                0f, 0.8f, 0f, 0f, 10f,
                                0f, 0.6f, 0f, 0f, 20f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(vintage)
                    }
                }
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(result, 0f, 0f, paint)
                result = filteredBmp
            } catch (_: Exception) {}
        }

        // 6. Text Watermark Overlay
        if (state.watermarkText.isNotEmpty()) {
            try {
                val wmBmp = result.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(wmBmp)
                val scale = (result.width.toFloat() / 1080f).coerceIn(0.5f, 3.0f)
                val textSizePx = (state.watermarkSize.toFloat() * scale).coerceAtLeast(16f)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = when (state.watermarkColor.uppercase()) {
                        "BLACK", "#000000" -> android.graphics.Color.BLACK
                        "RED", "#EF4444" -> android.graphics.Color.RED
                        "YELLOW", "#EAB308" -> android.graphics.Color.YELLOW
                        else -> android.graphics.Color.WHITE
                    }
                    textSize = textSizePx
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setShadowLayer(4f * scale, 2f * scale, 2f * scale, android.graphics.Color.argb(160, 0, 0, 0))
                }

                val text = state.watermarkText
                val textWidth = textPaint.measureText(text)
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                val textHeight = textBounds.height().toFloat()

                val margin = 32f * scale
                val (x, y) = when (state.watermarkPosition.lowercase()) {
                    "top-left" -> margin to (margin + textHeight)
                    "top-right" -> (result.width - textWidth - margin) to (margin + textHeight)
                    "bottom-left" -> margin to (result.height - margin)
                    "center" -> ((result.width - textWidth) / 2f) to ((result.height + textHeight) / 2f)
                    else -> (result.width - textWidth - margin) to (result.height - margin)
                }

                canvas.drawText(text, x, y, textPaint)
                result = wmBmp
            } catch (_: Exception) {}
        }

        return result
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
            // Strict single source of truth for quality label — never duplicate "Quality: 50% 50%"
            binding.tvImgQualityLabel.text = "Quality:"
            binding.tvImgQualityValue.text = "${outputConfig.quality}%"

            val scaleFactor = (targetW.toDouble() * targetH.toDouble()) /
                    (origFullW.toDouble() * origFullH.toDouble()).coerceAtLeast(1.0)
            val qualityFactor = outputConfig.quality.toDouble() / 100.0
            val estBytes = (originalBytes.toDouble() * scaleFactor * qualityFactor * 0.4).toLong().coerceAtLeast(1024L)

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
        val base = rawName.substringBeforeLast('.', rawName).ifBlank { "compressed_image" }
        val outFilename = "$base.$ext"
        val outFile = File(outDir, outFilename)

        binding.layoutImgProgress.visibility = View.VISIBLE
        binding.btnImgExecute.text = "Cancel"

        compressionJob = scope.launch(Dispatchers.IO) {
            try {
                // High-fidelity full-resolution render for final output
                val fullResBmp = renderLivePreview(editState, useFullRes = true)
                val result = MediaProcessor.image.process(
                    srcFile = srcFile,
                    outFile = outFile,
                    editState = editState,
                    outputConfig = outputConfig,
                    previewBitmap = fullResBmp
                )

                // Copy to SAF Destination Folder if chosen
                val destUri = safManager.imageDestinationUri
                if (result.success && destUri != null && outFile.exists()) {
                    val mime = when (outputConfig.format.uppercase()) {
                        "PNG" -> "image/png"
                        "WEBP" -> "image/webp"
                        else -> "image/jpeg"
                    }
                    safManager.copyFileToDocumentTree(outFile, destUri, mime)
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
                        binding.tvImgComparisonRatio.text = "Total: ${formatBytes(originalBytes)} → ${formatBytes(outFile.length())} (-$ratio%)"
                        binding.tvImgActualStats.visibility = View.VISIBLE
                        binding.tvImgActualStats.text = "Actual Output: ${formatBytes(outFile.length())} (Saved: $ratio%)"
                        binding.layoutImgResultActions.visibility = View.VISIBLE
                        Toast.makeText(activity, "Image compressed successfully! (-$ratio%)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Compression failed: ${result.error ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.layoutImgProgress.visibility = View.GONE
                    binding.btnImgExecute.text = "Compress"
                    Toast.makeText(activity, "Compression error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun clear() {
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

    // =========================================================================
    // Tool Dialogs with Isolated Draft States & Non-destructive Live Previews
    // =========================================================================

    private fun showCropDialog() {
        val dialogBinding = DialogCropBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        // Draft state isolated from authoritative editState until Apply
        val draftState = editState.deepCopy()
        val baseBmp = previewSourceBitmap ?: originalBitmap
        dialogBinding.imgCropPreview.setImageBitmap(baseBmp)

        if (baseBmp != null) {
            val initialNorm = if (draftState.isCropped()) {
                android.graphics.RectF(draftState.cropLeft, draftState.cropTop, draftState.cropRight, draftState.cropBottom)
            } else null
            dialogBinding.cropOverlayView.setImageDimensions(baseBmp.width, baseBmp.height, initialNorm)
            dialogBinding.cropOverlayView.setCropAspect(draftState.cropAspect)
        }

        // Live interactive crop handle dragging
        dialogBinding.cropOverlayView.onCropChanged = { normRect ->
            draftState.cropLeft = normRect.left
            draftState.cropTop = normRect.top
            draftState.cropRight = normRect.right
            draftState.cropBottom = normRect.bottom
        }

        when (draftState.cropAspect) {
            "1:1" -> dialogBinding.chipAspect11.isChecked = true
            "4:3" -> dialogBinding.chipAspect43.isChecked = true
            "3:4" -> dialogBinding.chipAspect34.isChecked = true
            "16:9" -> dialogBinding.chipAspect169.isChecked = true
            "9:16" -> dialogBinding.chipAspect916.isChecked = true
            else -> dialogBinding.chipAspectFree.isChecked = true
        }

        dialogBinding.chipGroupAspectRatio.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupAspectRatio.findViewById<Chip>(checkedIds[0])
                draftState.cropAspect = when (chip?.id) {
                    R.id.chipAspect11 -> "1:1"
                    R.id.chipAspect43 -> "4:3"
                    R.id.chipAspect34 -> "3:4"
                    R.id.chipAspect169 -> "16:9"
                    R.id.chipAspect916 -> "9:16"
                    else -> "Free"
                }
                dialogBinding.cropOverlayView.setCropAspect(draftState.cropAspect)
            }
        }

        dialogBinding.btnCropApply.setOnClickListener {
            val normRect = dialogBinding.cropOverlayView.getCropNormalized()
            editState.cropLeft = normRect.left
            editState.cropTop = normRect.top
            editState.cropRight = normRect.right
            editState.cropBottom = normRect.bottom
            editState.cropAspect = draftState.cropAspect
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnCropReset.setOnClickListener {
            draftState.cropLeft = 0f
            draftState.cropTop = 0f
            draftState.cropRight = 1f
            draftState.cropBottom = 1f
            draftState.cropAspect = "Free"
            dialogBinding.cropOverlayView.setCropAspect("Free")
            dialogBinding.chipAspectFree.isChecked = true
        }
        dialogBinding.btnCropCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCropClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showResizeDialog() {
        val dialogBinding = DialogResizeBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        val origW = originalBitmap?.width ?: 1920
        val origH = originalBitmap?.height ?: 1080
        val origAspect = origW.toDouble() / origH.toDouble()

        var draftScale = editState.resizeScale
        var draftW = if (editState.resizeWidth > 0) editState.resizeWidth else origW
        var draftH = if (editState.resizeHeight > 0) editState.resizeHeight else origH
        var draftKeepAspect = editState.keepAspect
        var isUpdatingText = false

        fun updateResizePreview() {
            val previewDraft = editState.deepCopy().apply {
                resizeScale = draftScale
                resizeWidth = draftW
                resizeHeight = draftH
                keepAspect = draftKeepAspect
            }
            dialogBinding.imgResizePreview.setImageBitmap(renderLivePreview(previewDraft))
        }

        dialogBinding.sliderResizeScale.value = draftScale.toFloat().coerceIn(10f, 200f)
        dialogBinding.tvResizeScaleLabel.text = "$draftScale%"
        dialogBinding.etResizeWidth.setText(draftW.toString())
        dialogBinding.etResizeHeight.setText(draftH.toString())
        dialogBinding.cbResizeKeepAspect.isChecked = draftKeepAspect
        updateResizePreview()

        dialogBinding.cbResizeKeepAspect.setOnCheckedChangeListener { _, isChecked ->
            draftKeepAspect = isChecked
        }

        // Two-way aspect ratio calculation: width updates height, height updates width
        dialogBinding.etResizeWidth.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdatingText) return
                val newW = s?.toString()?.toIntOrNull()
                if (newW != null && newW > 0) {
                    isUpdatingText = true
                    draftW = newW
                    if (draftKeepAspect) {
                        draftH = Math.round(newW / origAspect).toInt().coerceAtLeast(1)
                        dialogBinding.etResizeHeight.setText(draftH.toString())
                    }
                    val scale = Math.round((draftW.toDouble() / origW.toDouble()) * 100).toInt().coerceIn(10, 200)
                    draftScale = scale
                    dialogBinding.sliderResizeScale.value = scale.toFloat()
                    dialogBinding.tvResizeScaleLabel.text = "$scale%"
                    updateResizePreview()
                    isUpdatingText = false
                }
            }
        })

        dialogBinding.etResizeHeight.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdatingText) return
                val newH = s?.toString()?.toIntOrNull()
                if (newH != null && newH > 0) {
                    isUpdatingText = true
                    draftH = newH
                    if (draftKeepAspect) {
                        draftW = Math.round(newH * origAspect).toInt().coerceAtLeast(1)
                        dialogBinding.etResizeWidth.setText(draftW.toString())
                    }
                    val scale = Math.round((draftH.toDouble() / origH.toDouble()) * 100).toInt().coerceIn(10, 200)
                    draftScale = scale
                    dialogBinding.sliderResizeScale.value = scale.toFloat()
                    dialogBinding.tvResizeScaleLabel.text = "$scale%"
                    updateResizePreview()
                    isUpdatingText = false
                }
            }
        })

        dialogBinding.sliderResizeScale.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingText) return@addOnChangeListener
            isUpdatingText = true
            draftScale = value.toInt()
            dialogBinding.tvResizeScaleLabel.text = "$draftScale%"
            draftW = ((origW * draftScale) / 100).coerceAtLeast(1)
            draftH = ((origH * draftScale) / 100).coerceAtLeast(1)
            dialogBinding.etResizeWidth.setText(draftW.toString())
            dialogBinding.etResizeHeight.setText(draftH.toString())
            updateResizePreview()
            isUpdatingText = false
        }

        dialogBinding.btnResizeApply.setOnClickListener {
            editState.resizeScale = draftScale
            editState.resizeWidth = draftW
            editState.resizeHeight = draftH
            editState.keepAspect = draftKeepAspect
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnResizeReset.setOnClickListener {
            isUpdatingText = true
            draftScale = 100
            draftW = origW
            draftH = origH
            draftKeepAspect = true
            dialogBinding.sliderResizeScale.value = 100f
            dialogBinding.tvResizeScaleLabel.text = "100%"
            dialogBinding.etResizeWidth.setText(origW.toString())
            dialogBinding.etResizeHeight.setText(origH.toString())
            dialogBinding.cbResizeKeepAspect.isChecked = true
            updateResizePreview()
            isUpdatingText = false
        }
        dialogBinding.btnResizeCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnResizeClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRotateDialog() {
        val dialogBinding = DialogRotateBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftAngle = editState.rotationAngle
        var draftFlipH = editState.flipH
        var draftFlipV = editState.flipV

        fun updateRotatePreview() {
            val draft = editState.deepCopy().apply {
                rotationAngle = draftAngle
                flipH = draftFlipH
                flipV = draftFlipV
            }
            dialogBinding.imgRotatePreview.setImageBitmap(renderLivePreview(draft))
            dialogBinding.btnFlipHorizontal.text = if (draftFlipH) "Flip H (On)" else "Flip Horizontal"
            dialogBinding.btnFlipVertical.text = if (draftFlipV) "Flip V (On)" else "Flip Vertical"
            dialogBinding.tvRotateAngleLabel.text = "${draftAngle.toInt()}°"
        }

        dialogBinding.sliderRotateAngle.value = draftAngle
        updateRotatePreview()

        dialogBinding.btnFlipHorizontal.setOnClickListener {
            draftFlipH = !draftFlipH
            updateRotatePreview()
        }

        dialogBinding.btnFlipVertical.setOnClickListener {
            draftFlipV = !draftFlipV
            updateRotatePreview()
        }

        dialogBinding.sliderRotateAngle.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                draftAngle = value
                updateRotatePreview()
            }
        }

        dialogBinding.btnRotateMinus90.setOnClickListener {
            var a = draftAngle - 90f
            if (a < -180f) a += 360f
            draftAngle = a
            dialogBinding.sliderRotateAngle.value = draftAngle
            updateRotatePreview()
        }

        dialogBinding.btnRotatePlus90.setOnClickListener {
            var a = draftAngle + 90f
            if (a > 180f) a -= 360f
            draftAngle = a
            dialogBinding.sliderRotateAngle.value = draftAngle
            updateRotatePreview()
        }

        dialogBinding.btnRotateApply.setOnClickListener {
            editState.rotationAngle = draftAngle
            editState.flipH = draftFlipH
            editState.flipV = draftFlipV
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnRotateReset.setOnClickListener {
            draftAngle = 0f
            draftFlipH = false
            draftFlipV = false
            dialogBinding.sliderRotateAngle.value = 0f
            updateRotatePreview()
        }
        dialogBinding.btnRotateCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnRotateClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showTextWatermarkDialog() {
        val dialogBinding = DialogTextWatermarkBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftText = editState.watermarkText
        var draftSize = editState.watermarkSize
        var draftPosition = editState.watermarkPosition
        var draftColor = editState.watermarkColor

        fun updateWatermarkPreview() {
            val draft = editState.deepCopy().apply {
                watermarkText = draftText
                watermarkSize = draftSize
                watermarkPosition = draftPosition
                watermarkColor = draftColor
            }
            dialogBinding.imgWatermarkPreview.setImageBitmap(renderLivePreview(draft))
        }

        dialogBinding.etWatermarkText.setText(draftText)
        dialogBinding.sliderWatermarkSize.value = draftSize.toFloat().coerceIn(12f, 72f)
        dialogBinding.tvWatermarkSizeLabel.text = "$draftSize sp"
        updateWatermarkPreview()

        when (draftPosition.lowercase()) {
            "top-left" -> dialogBinding.chipPosTopLeft.isChecked = true
            "top-right" -> dialogBinding.chipPosTopRight.isChecked = true
            "bottom-left" -> dialogBinding.chipPosBottomLeft.isChecked = true
            "center" -> dialogBinding.chipPosCenter.isChecked = true
            else -> dialogBinding.chipPosBottomRight.isChecked = true
        }

        when (draftColor.uppercase()) {
            "BLACK", "#000000" -> dialogBinding.chipWmColorBlack.isChecked = true
            "RED", "#EF4444" -> dialogBinding.chipWmColorRed.isChecked = true
            "YELLOW", "#EAB308" -> dialogBinding.chipWmColorYellow.isChecked = true
            else -> dialogBinding.chipWmColorWhite.isChecked = true
        }

        dialogBinding.chipWmConfidential.setOnClickListener {
            dialogBinding.etWatermarkText.setText("CONFIDENTIAL")
            draftText = "CONFIDENTIAL"
            updateWatermarkPreview()
        }
        dialogBinding.chipWmDraft.setOnClickListener {
            dialogBinding.etWatermarkText.setText("DRAFT")
            draftText = "DRAFT"
            updateWatermarkPreview()
        }
        dialogBinding.chipWmCopy.setOnClickListener {
            dialogBinding.etWatermarkText.setText("COPY")
            draftText = "COPY"
            updateWatermarkPreview()
        }
        dialogBinding.chipWmDoNotShare.setOnClickListener {
            dialogBinding.etWatermarkText.setText("DO NOT SHARE")
            draftText = "DO NOT SHARE"
            updateWatermarkPreview()
        }

        dialogBinding.etWatermarkText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                draftText = s?.toString()?.trim() ?: ""
                updateWatermarkPreview()
            }
        })

        dialogBinding.sliderWatermarkSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                draftSize = value.toInt()
                dialogBinding.tvWatermarkSizeLabel.text = "$draftSize sp"
                updateWatermarkPreview()
            }
        }

        dialogBinding.chipGroupWatermarkColor.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupWatermarkColor.findViewById<Chip>(checkedIds[0])
                draftColor = when (chip?.id) {
                    R.id.chipWmColorBlack -> "#000000"
                    R.id.chipWmColorRed -> "#EF4444"
                    R.id.chipWmColorYellow -> "#EAB308"
                    else -> "#FFFFFF"
                }
                updateWatermarkPreview()
            }
        }

        dialogBinding.chipGroupWatermarkPosition.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupWatermarkPosition.findViewById<Chip>(checkedIds[0])
                draftPosition = when (chip?.id) {
                    R.id.chipPosTopLeft -> "top-left"
                    R.id.chipPosTopRight -> "top-right"
                    R.id.chipPosBottomLeft -> "bottom-left"
                    R.id.chipPosCenter -> "center"
                    else -> "bottom-right"
                }
                updateWatermarkPreview()
            }
        }

        dialogBinding.btnWatermarkApply.setOnClickListener {
            editState.watermarkText = draftText
            editState.watermarkSize = draftSize
            editState.watermarkPosition = draftPosition
            editState.watermarkColor = draftColor
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnWatermarkReset.setOnClickListener {
            draftText = ""
            dialogBinding.etWatermarkText.setText("")
            updateWatermarkPreview()
        }
        dialogBinding.btnWatermarkCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnWatermarkClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showColorFilterDialog() {
        val dialogBinding = DialogColorFilterBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftFilter = editState.filter
        var draftBgType = editState.bgType

        // Live preview preserves all previous edits (crop, resize, rotation, etc.)
        fun updateFilterPreview() {
            val draft = editState.deepCopy().apply {
                filter = draftFilter
                bgType = draftBgType
            }
            dialogBinding.imgFilterPreview.setImageBitmap(renderLivePreview(draft))
        }

        updateFilterPreview()

        when (draftFilter) {
            "Grayscale" -> dialogBinding.chipFilterGrayscale.isChecked = true
            "Sepia" -> dialogBinding.chipFilterSepia.isChecked = true
            "Vintage" -> dialogBinding.chipFilterVintage.isChecked = true
            "Cool" -> dialogBinding.chipFilterCool.isChecked = true
            "Warm" -> dialogBinding.chipFilterWarm.isChecked = true
            else -> dialogBinding.chipFilterDefault.isChecked = true
        }

        when (draftBgType) {
            "Black" -> dialogBinding.chipBgBlack.isChecked = true
            "White" -> dialogBinding.chipBgWhite.isChecked = true
            "Blurred Fill" -> dialogBinding.chipBgBlur.isChecked = true
            else -> dialogBinding.chipBgTrans.isChecked = true
        }

        dialogBinding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupFilters.findViewById<Chip>(checkedIds[0])
                draftFilter = when (chip?.id) {
                    R.id.chipFilterGrayscale -> "Grayscale"
                    R.id.chipFilterSepia -> "Sepia"
                    R.id.chipFilterVintage -> "Vintage"
                    R.id.chipFilterCool -> "Cool"
                    R.id.chipFilterWarm -> "Warm"
                    else -> "Default"
                }
                updateFilterPreview()
            }
        }

        dialogBinding.chipGroupBackgroundType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupBackgroundType.findViewById<Chip>(checkedIds[0])
                draftBgType = when (chip?.id) {
                    R.id.chipBgBlack -> "Black"
                    R.id.chipBgWhite -> "White"
                    R.id.chipBgBlur -> "Blurred Fill"
                    else -> "Transparent"
                }
                updateFilterPreview()
            }
        }

        dialogBinding.btnFilterApply.setOnClickListener {
            editState.filter = draftFilter
            editState.bgType = draftBgType
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnFilterReset.setOnClickListener {
            draftFilter = "Default"
            draftBgType = "Transparent"
            dialogBinding.chipFilterDefault.isChecked = true
            dialogBinding.chipBgTrans.isChecked = true
            updateFilterPreview()
        }
        dialogBinding.btnFilterCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnFilterClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showExifDialog() {
        val dialogBinding = DialogExifBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftStripExif = editState.stripExif
        var draftMake = editState.exifMake
        var draftModel = editState.exifModel
        var draftSoftware = editState.exifSoftware
        var draftDateTime = editState.exifDateTime
        var draftGps = editState.exifGps

        dialogBinding.etExifMake.setText(draftMake)
        dialogBinding.etExifModel.setText(draftModel)
        dialogBinding.etExifSoftware.setText(draftSoftware)
        dialogBinding.etExifDateTime.setText(draftDateTime)
        dialogBinding.etExifGps.setText(draftGps)

        dialogBinding.btnExifStripAll.setOnClickListener {
            dialogBinding.etExifMake.setText("")
            dialogBinding.etExifModel.setText("")
            dialogBinding.etExifSoftware.setText("")
            dialogBinding.etExifDateTime.setText("")
            dialogBinding.etExifGps.setText("")
            draftStripExif = true
            draftMake = ""
            draftModel = ""
            draftSoftware = ""
            draftDateTime = ""
            draftGps = ""
            Toast.makeText(activity, "All EXIF metadata will be scrubbed (Lossless)", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnExifRestore.setOnClickListener {
            draftStripExif = false
            Toast.makeText(activity, "Metadata preservation enabled", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnExifDone.setOnClickListener {
            editState.stripExif = draftStripExif
            editState.exifMake = dialogBinding.etExifMake.text.toString().trim()
            editState.exifModel = dialogBinding.etExifModel.text.toString().trim()
            editState.exifSoftware = dialogBinding.etExifSoftware.text.toString().trim()
            editState.exifDateTime = dialogBinding.etExifDateTime.text.toString().trim()
            editState.exifGps = dialogBinding.etExifGps.text.toString().trim()
            refreshPreview()
            dialog.dismiss()
        }

        dialogBinding.btnExifClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
