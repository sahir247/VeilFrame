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
import com.chaquo.python.Python
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
    private val getPython: () -> Python?,
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
            originalBitmap = bmp // Set immutable source

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
                binding.imgFileThumb.setImageBitmap(bmp)
                binding.imgBeforePreview.setImageBitmap(bmp)
                binding.tvImgBeforeSize.text = "${formatBytes(originalBytes)} (${bmp.width} × ${bmp.height} px)"
                binding.btnImgClearAll.isEnabled = true

                // Enable all tools
                binding.toolCrop.isEnabled = true
                binding.toolResize.isEnabled = true
                binding.toolRotate.isEnabled = true
                binding.toolColorFilter.isEnabled = true
                binding.toolExif.isEnabled = true

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
     * Non-destructive live preview renderer.
     * Always starts from the immutable originalBitmap to eliminate cumulative drift.
     */
    fun renderLivePreview(): Bitmap? {
        val src = originalBitmap ?: return null

        var result = src

        // 1. Crop
        if (editState.cropAspect != "Free" && editState.cropAspect != "Original") {
            val ratio = when (editState.cropAspect) {
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
              // 2. Rotate & Flip
        if (editState.rotationAngle != 0f || editState.flipH || editState.flipV) {
            try {
                val matrix = Matrix()
                if (editState.rotationAngle != 0f) {
                    matrix.postRotate(editState.rotationAngle)
                }
                if (editState.flipH || editState.flipV) {
                    val sx = if (editState.flipH) -1f else 1f
                    val sy = if (editState.flipV) -1f else 1f
                    matrix.postScale(sx, sy)
                }
                result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
            } catch (_: Exception) {}
        }

        // 3. Resize
        val targetWidth = when {
            editState.resizeWidth > 0 -> editState.resizeWidth
            editState.resizeScale != 100 -> ((result.width * editState.resizeScale) / 100).coerceAtLeast(1)
            else -> result.width
        }
        val targetHeight = when {
            editState.resizeHeight > 0 -> editState.resizeHeight
            editState.resizeScale != 100 -> ((result.height * editState.resizeScale) / 100).coerceAtLeast(1)
            else -> result.height
        }
        if (targetWidth != result.width || targetHeight != result.height) {
            try {
                result = Bitmap.createScaledBitmap(result, targetWidth, targetHeight, true)
            } catch (_: Exception) {}
        }

        // 4. Background Fill (for alpha transparency)
        if (editState.bgType != "Transparent") {
            try {
                val bgBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bgBmp)
                val bgPaint = Paint().apply {
                    color = if (editState.bgType.contains("White", ignoreCase = true)) {
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
        if (editState.filter != "Default" && editState.filter != "None") {
            try {
                val filteredBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(filteredBmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                val colorMatrix = ColorMatrix()
                when (editState.filter) {
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
        if (editState.watermarkText.isNotEmpty()) {
            try {
                val wmBmp = result.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(wmBmp)
                val scale = (result.width.toFloat() / 1080f).coerceIn(0.5f, 3.0f)
                val textSizePx = (editState.watermarkSize.toFloat() * scale).coerceAtLeast(16f)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = when (editState.watermarkColor.uppercase()) {
                        "BLACK", "#000000" -> android.graphics.Color.BLACK
                        "RED", "#EF4444" -> android.graphics.Color.RED
                        "YELLOW", "#EAB308" -> android.graphics.Color.YELLOW
                        else -> android.graphics.Color.WHITE
                    }
                    textSize = textSizePx
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setShadowLayer(4f * scale, 2f * scale, 2f * scale, android.graphics.Color.argb(160, 0, 0, 0))
                }

                val text = editState.watermarkText
                val textWidth = textPaint.measureText(text)
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                val textHeight = textBounds.height().toFloat()

                val margin = 32f * scale
                val (x, y) = when (editState.watermarkPosition.lowercase()) {
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
        val previewBmp = renderLivePreview() ?: return
        binding.imgAfterPreview.setImageBitmap(previewBmp)

        val isPng = outputConfig.format.equals("PNG", ignoreCase = true)
        if (outputConfig.compressionMode == "target_size") {
            val targetKb = outputConfig.targetSizeKb ?: 250
            val targetBytes = targetKb.toLong() * 1024L
            binding.tvImgQualityLabel.text = "Target Ceiling: $targetKb KB"
            binding.tvImgAfterSize.text = "~${formatBytes(targetBytes)} (${previewBmp.width} × ${previewBmp.height} px • ${outputConfig.format})"
            val ratio = if (originalBytes > 0) {
                (100.0 - (targetBytes.toDouble() / originalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
            } else 0
            binding.tvImgComparisonRatio.text = "Total: ${formatBytes(originalBytes)} → ~${formatBytes(targetBytes)} (-$ratio%)"
        } else if (isPng) {
            binding.tvImgQualityLabel.text = "Compression Level: (Deflate 1-9)"
            binding.tvImgAfterSize.text = "${previewBmp.width} × ${previewBmp.height} px • Deflate"
            binding.tvImgComparisonRatio.text = "PNG is lossless • Size determined by image complexity"
        } else {
            binding.tvImgQualityLabel.text = "Quality: ${outputConfig.quality}%"
            val scaleFactor = (previewBmp.width.toDouble() * previewBmp.height.toDouble()) /
                    ((originalBitmap?.width ?: 1920).toDouble() * (originalBitmap?.height ?: 1080).toDouble())
            val qualityFactor = outputConfig.quality.toDouble() / 100.0
            val estBytes = (originalBytes.toDouble() * scaleFactor * qualityFactor * 0.4).toLong().coerceAtLeast(1024L)

            binding.tvImgAfterSize.text = "~${formatBytes(estBytes)} (${previewBmp.width} × ${previewBmp.height} px • ${outputConfig.format})"
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

            if (editState.cropAspect != "Free" && editState.cropAspect != "Original") {
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
                val curCrop = if (editState.cropAspect != "Free" && editState.cropAspect != "Original") "Crop ${editState.cropAspect} • " else ""
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
        val ext = outputConfig.format.lowercase()
        val outFilename = binding.etImgOutputFilename.text.toString().trim().ifEmpty { "compressed_image.$ext" }
        val outFile = File(outDir, outFilename)

        binding.layoutImgProgress.visibility = View.VISIBLE
        binding.btnImgExecute.text = "Cancel"

        compressionJob = scope.launch(Dispatchers.IO) {
            try {
                val previewBmp = renderLivePreview()
                val result = MediaProcessor.image.process(
                    srcFile = srcFile,
                    outFile = outFile,
                    editState = editState,
                    outputConfig = outputConfig,
                    previewBitmap = previewBmp,
                    py = getPython()
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
                        val finalBmp = BitmapFactory.decodeFile(outFile.absolutePath) ?: previewBmp
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

    // Tool Dialogs
    private fun showCropDialog() {
        val dialogBinding = DialogCropBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        dialogBinding.imgCropPreview.setImageBitmap(originalBitmap)

        when (editState.cropAspect) {
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
                editState.cropAspect = when (chip?.id) {
                    R.id.chipAspect11 -> "1:1"
                    R.id.chipAspect43 -> "4:3"
                    R.id.chipAspect34 -> "3:4"
                    R.id.chipAspect169 -> "16:9"
                    R.id.chipAspect916 -> "9:16"
                    else -> "Free"
                }
            }
        }

        dialogBinding.btnCropApply.setOnClickListener {
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnCropReset.setOnClickListener {
            editState.cropAspect = "Free"
            refreshPreview()
            dialog.dismiss()
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
        dialogBinding.imgResizePreview.setImageBitmap(originalBitmap)
        dialogBinding.sliderResizeScale.value = editState.resizeScale.toFloat()
        dialogBinding.tvResizeScaleLabel.text = "${editState.resizeScale}%"
        dialogBinding.etResizeWidth.setText(if (editState.resizeWidth > 0) editState.resizeWidth.toString() else origW.toString())
        dialogBinding.etResizeHeight.setText(if (editState.resizeHeight > 0) editState.resizeHeight.toString() else origH.toString())

        dialogBinding.sliderResizeScale.addOnChangeListener { _, value, _ ->
            editState.resizeScale = value.toInt()
            dialogBinding.tvResizeScaleLabel.text = "${editState.resizeScale}%"
            val newW = (origW * editState.resizeScale) / 100
            val newH = (origH * editState.resizeScale) / 100
            dialogBinding.etResizeWidth.setText(newW.toString())
            dialogBinding.etResizeHeight.setText(newH.toString())
        }

        dialogBinding.btnResizeApply.setOnClickListener {
            val customW = dialogBinding.etResizeWidth.text.toString().toIntOrNull()
            val customH = dialogBinding.etResizeHeight.text.toString().toIntOrNull()
            if (customW != null && customH != null && customW > 0 && customH > 0) {
                editState.resizeWidth = customW
                editState.resizeHeight = customH
            }
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnResizeReset.setOnClickListener {
            editState.resizeScale = 100
            editState.resizeWidth = 0
            editState.resizeHeight = 0
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnResizeCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnResizeClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRotateDialog() {
        val dialogBinding = DialogRotateBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        val baseBmp = originalBitmap
        dialogBinding.imgRotatePreview.setImageBitmap(baseBmp)
        dialogBinding.sliderRotateAngle.value = editState.rotationAngle
        dialogBinding.tvRotateAngleLabel.text = "${editState.rotationAngle.toInt()}°"

        var tempFlipH = editState.flipH
        var tempFlipV = editState.flipV

        fun updateFlipButtons() {
            dialogBinding.btnFlipHorizontal.text = if (tempFlipH) "Flip H (On)" else "Flip Horizontal"
            dialogBinding.btnFlipVertical.text = if (tempFlipV) "Flip V (On)" else "Flip Vertical"
            dialogBinding.imgRotatePreview.scaleX = if (tempFlipH) -1f else 1f
            dialogBinding.imgRotatePreview.scaleY = if (tempFlipV) -1f else 1f
        }
        updateFlipButtons()

        dialogBinding.btnFlipHorizontal.setOnClickListener {
            tempFlipH = !tempFlipH
            updateFlipButtons()
        }

        dialogBinding.btnFlipVertical.setOnClickListener {
            tempFlipV = !tempFlipV
            updateFlipButtons()
        }

        dialogBinding.sliderRotateAngle.addOnChangeListener { _, value, _ ->
            dialogBinding.tvRotateAngleLabel.text = "${value.toInt()}°"
            dialogBinding.imgRotatePreview.rotation = value
        }

        dialogBinding.btnRotateMinus90.setOnClickListener {
            var a = dialogBinding.sliderRotateAngle.value - 90f
            if (a < -180f) a += 360f
            dialogBinding.sliderRotateAngle.value = a
        }

        dialogBinding.btnRotatePlus90.setOnClickListener {
            var a = dialogBinding.sliderRotateAngle.value + 90f
            if (a > 180f) a -= 360f
            dialogBinding.sliderRotateAngle.value = a
        }

        dialogBinding.btnRotateApply.setOnClickListener {
            editState.rotationAngle = dialogBinding.sliderRotateAngle.value
            editState.flipH = tempFlipH
            editState.flipV = tempFlipV
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnRotateReset.setOnClickListener {
            editState.rotationAngle = 0f
            editState.flipH = false
            editState.flipV = false
            tempFlipH = false
            tempFlipV = false
            dialogBinding.sliderRotateAngle.value = 0f
            updateFlipButtons()
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnRotateCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnRotateClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showTextWatermarkDialog() {
        val dialogBinding = DialogTextWatermarkBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        dialogBinding.imgWatermarkPreview.setImageBitmap(renderLivePreview())
        dialogBinding.etWatermarkText.setText(editState.watermarkText)
        dialogBinding.sliderWatermarkSize.value = editState.watermarkSize.toFloat()
        dialogBinding.tvWatermarkSizeLabel.text = "${editState.watermarkSize} sp"

        when (editState.watermarkPosition.lowercase()) {
            "top-left" -> dialogBinding.chipPosTopLeft.isChecked = true
            "top-right" -> dialogBinding.chipPosTopRight.isChecked = true
            "bottom-left" -> dialogBinding.chipPosBottomLeft.isChecked = true
            "center" -> dialogBinding.chipPosCenter.isChecked = true
            else -> dialogBinding.chipPosBottomRight.isChecked = true
        }

        when (editState.watermarkColor.uppercase()) {
            "BLACK", "#000000" -> dialogBinding.chipWmColorBlack.isChecked = true
            "RED", "#EF4444" -> dialogBinding.chipWmColorRed.isChecked = true
            "YELLOW", "#EAB308" -> dialogBinding.chipWmColorYellow.isChecked = true
            else -> dialogBinding.chipWmColorWhite.isChecked = true
        }

        // Presets
        dialogBinding.chipWmConfidential.setOnClickListener { dialogBinding.etWatermarkText.setText("CONFIDENTIAL") }
        dialogBinding.chipWmDraft.setOnClickListener { dialogBinding.etWatermarkText.setText("DRAFT") }
        dialogBinding.chipWmCopy.setOnClickListener { dialogBinding.etWatermarkText.setText("COPY") }
        dialogBinding.chipWmDoNotShare.setOnClickListener { dialogBinding.etWatermarkText.setText("DO NOT SHARE") }

        dialogBinding.sliderWatermarkSize.addOnChangeListener { _, value, _ ->
            dialogBinding.tvWatermarkSizeLabel.text = "${value.toInt()} sp"
        }

        dialogBinding.btnWatermarkApply.setOnClickListener {
            editState.watermarkText = dialogBinding.etWatermarkText.text?.toString()?.trim() ?: ""
            editState.watermarkSize = dialogBinding.sliderWatermarkSize.value.toInt()
            editState.watermarkPosition = when {
                dialogBinding.chipPosTopLeft.isChecked -> "top-left"
                dialogBinding.chipPosTopRight.isChecked -> "top-right"
                dialogBinding.chipPosBottomLeft.isChecked -> "bottom-left"
                dialogBinding.chipPosCenter.isChecked -> "center"
                else -> "bottom-right"
            }
            editState.watermarkColor = when {
                dialogBinding.chipWmColorBlack.isChecked -> "#000000"
                dialogBinding.chipWmColorRed.isChecked -> "#EF4444"
                dialogBinding.chipWmColorYellow.isChecked -> "#EAB308"
                else -> "#FFFFFF"
            }
            refreshPreview()
            dialog.dismiss()
        }

        dialogBinding.btnWatermarkReset.setOnClickListener {
            editState.watermarkText = ""
            refreshPreview()
            dialog.dismiss()
        }

        dialogBinding.btnWatermarkCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnWatermarkClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showColorFilterDialog() {
        val dialogBinding = DialogColorFilterBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        dialogBinding.imgFilterPreview.setImageBitmap(originalBitmap)

        when (editState.filter) {
            "Grayscale" -> dialogBinding.chipFilterGrayscale.isChecked = true
            "Sepia" -> dialogBinding.chipFilterSepia.isChecked = true
            "Vintage" -> dialogBinding.chipFilterVintage.isChecked = true
            "Cool" -> dialogBinding.chipFilterCool.isChecked = true
            "Warm" -> dialogBinding.chipFilterWarm.isChecked = true
            else -> dialogBinding.chipFilterDefault.isChecked = true
        }

        dialogBinding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupFilters.findViewById<Chip>(checkedIds[0])
                editState.filter = when (chip?.id) {
                    R.id.chipFilterGrayscale -> "Grayscale"
                    R.id.chipFilterSepia -> "Sepia"
                    R.id.chipFilterVintage -> "Vintage"
                    R.id.chipFilterCool -> "Cool"
                    R.id.chipFilterWarm -> "Warm"
                    else -> "Default"
                }
            }
        }

        dialogBinding.btnFilterApply.setOnClickListener {
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnFilterReset.setOnClickListener {
            editState.filter = "Default"
            refreshPreview()
            dialog.dismiss()
        }
        dialogBinding.btnFilterCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnFilterClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showExifDialog() {
        val dialogBinding = DialogExifBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        dialogBinding.etExifMake.setText(editState.exifMake)
        dialogBinding.etExifModel.setText(editState.exifModel)
        dialogBinding.etExifSoftware.setText(editState.exifSoftware)
        dialogBinding.etExifDateTime.setText(editState.exifDateTime)
        dialogBinding.etExifGps.setText(editState.exifGps)

        dialogBinding.btnExifStripAll.setOnClickListener {
            dialogBinding.etExifMake.setText("")
            dialogBinding.etExifModel.setText("")
            dialogBinding.etExifSoftware.setText("")
            dialogBinding.etExifDateTime.setText("")
            dialogBinding.etExifGps.setText("")
            editState.stripExif = true
            Toast.makeText(activity, "All EXIF metadata will be completely scrubbed", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnExifRestore.setOnClickListener {
            editState.stripExif = false
            Toast.makeText(activity, "Metadata preservation enabled", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnExifDone.setOnClickListener {
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
