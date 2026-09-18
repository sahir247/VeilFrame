package com.veilframe.app.media.dialogs

import android.graphics.Bitmap
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
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
import com.veilframe.app.media.ImageEditState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Isolated dialog controller for Image Studio editing tools.
 * Manages modal dialogs with isolated draft states to ensure non-destructive live editing.
 * Extracted from ImageStudioController.
 */
class ImageStudioDialogController(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val editState: ImageEditState,
    private val getPreviewSourceBitmap: () -> Bitmap?,
    private val getOriginalBitmap: () -> Bitmap?,
    private val renderLivePreview: (ImageEditState) -> Bitmap?,
    private val onEditsChanged: () -> Unit
) {

    fun showCropDialog() {
        val dialogBinding = DialogCropBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        val draftState = editState.deepCopy()
        val baseBmp = getPreviewSourceBitmap() ?: getOriginalBitmap()
        dialogBinding.imgCropPreview.setImageBitmap(baseBmp)

        if (baseBmp != null) {
            val initialNorm = if (draftState.isCropped()) {
                RectF(draftState.cropLeft, draftState.cropTop, draftState.cropRight, draftState.cropBottom)
            } else null
            dialogBinding.cropOverlayView.setImageDimensions(baseBmp.width, baseBmp.height, initialNorm)
            dialogBinding.cropOverlayView.setCropAspect(draftState.cropAspect)
        }

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
            onEditsChanged()
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

    fun showResizeDialog() {
        val dialogBinding = DialogResizeBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        val origW = getOriginalBitmap()?.width ?: 1920
        val origH = getOriginalBitmap()?.height ?: 1080
        val origAspect = origW.toDouble() / origH.toDouble()

        var draftScale = editState.resizeScale
        var draftW = if (editState.resizeWidth > 0) editState.resizeWidth else origW
        var draftH = if (editState.resizeHeight > 0) editState.resizeHeight else origH
        var draftKeepAspect = editState.keepAspect
        var isUpdatingText = false
        var debounceJob: Job? = null

        fun updateResizePreview() {
            val previewDraft = editState.deepCopy().apply {
                resizeScale = draftScale
                resizeWidth = draftW
                resizeHeight = draftH
                keepAspect = draftKeepAspect
            }
            dialogBinding.imgResizePreview.setImageBitmap(renderLivePreview(previewDraft))
            dialogBinding.tvResizeLiveBadge.text = "${draftW} × ${draftH} px (${draftScale}%)"
            val scaleFactor = (draftScale.toFloat() / 100f).coerceIn(0.15f, 2.0f)
            dialogBinding.imgResizePreview.scaleX = scaleFactor
            dialogBinding.imgResizePreview.scaleY = scaleFactor
        }

        fun applyWidthUpdate() {
            if (isUpdatingText) return
            val text = dialogBinding.etResizeWidth.text?.toString()?.trim() ?: ""
            val newW = text.toIntOrNull()
            if (newW != null && newW >= 16) {
                isUpdatingText = true
                draftW = newW.coerceIn(16, 16384)
                if (draftKeepAspect) {
                    draftH = Math.round(draftW / origAspect).toInt().coerceIn(16, 16384)
                    dialogBinding.etResizeHeight.setText(draftH.toString())
                }
                val scale = Math.round((draftW.toDouble() / origW.toDouble()) * 100).toInt().coerceIn(10, 200)
                draftScale = scale
                dialogBinding.sliderResizeScale.value = scale.toFloat().coerceIn(
                    dialogBinding.sliderResizeScale.valueFrom,
                    dialogBinding.sliderResizeScale.valueTo
                )
                dialogBinding.tvResizeScaleLabel.text = "$scale%"
                updateResizePreview()
                isUpdatingText = false
            }
        }

        fun applyHeightUpdate() {
            if (isUpdatingText) return
            val text = dialogBinding.etResizeHeight.text?.toString()?.trim() ?: ""
            val newH = text.toIntOrNull()
            if (newH != null && newH >= 16) {
                isUpdatingText = true
                draftH = newH.coerceIn(16, 16384)
                if (draftKeepAspect) {
                    draftW = Math.round(draftH * origAspect).toInt().coerceIn(16, 16384)
                    dialogBinding.etResizeWidth.setText(draftW.toString())
                }
                val scale = Math.round((draftH.toDouble() / origH.toDouble()) * 100).toInt().coerceIn(10, 200)
                draftScale = scale
                dialogBinding.sliderResizeScale.value = scale.toFloat().coerceIn(
                    dialogBinding.sliderResizeScale.valueFrom,
                    dialogBinding.sliderResizeScale.valueTo
                )
                dialogBinding.tvResizeScaleLabel.text = "$scale%"
                updateResizePreview()
                isUpdatingText = false
            }
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

        dialogBinding.etResizeWidth.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingText) return
                debounceJob?.cancel()
                debounceJob = scope.launch(Dispatchers.Main) {
                    delay(400)
                    applyWidthUpdate()
                }
            }
        })

        dialogBinding.etResizeHeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingText) return
                debounceJob?.cancel()
                debounceJob = scope.launch(Dispatchers.Main) {
                    delay(400)
                    applyHeightUpdate()
                }
            }
        })

        dialogBinding.etResizeWidth.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                debounceJob?.cancel()
                applyWidthUpdate()
            }
        }

        dialogBinding.etResizeHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                debounceJob?.cancel()
                applyHeightUpdate()
            }
        }

        dialogBinding.etResizeWidth.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                debounceJob?.cancel()
                applyWidthUpdate()
                false
            } else false
        }

        dialogBinding.etResizeHeight.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                debounceJob?.cancel()
                applyHeightUpdate()
                false
            } else false
        }

        dialogBinding.sliderResizeScale.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingText) return@addOnChangeListener
            debounceJob?.cancel()
            isUpdatingText = true
            draftScale = value.toInt().coerceIn(10, 200)
            dialogBinding.tvResizeScaleLabel.text = "$draftScale%"
            draftW = ((origW * draftScale) / 100).coerceIn(16, 16384)
            draftH = ((origH * draftScale) / 100).coerceIn(16, 16384)
            dialogBinding.etResizeWidth.setText(draftW.toString())
            dialogBinding.etResizeHeight.setText(draftH.toString())
            updateResizePreview()
            isUpdatingText = false
        }

        dialogBinding.btnResizeApply.setOnClickListener {
            debounceJob?.cancel()
            editState.resizeScale = draftScale
            editState.resizeWidth = draftW
            editState.resizeHeight = draftH
            editState.keepAspect = draftKeepAspect
            onEditsChanged()
            dialog.dismiss()
        }
        dialogBinding.btnResizeReset.setOnClickListener {
            debounceJob?.cancel()
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
        dialogBinding.btnResizeCancel.setOnClickListener {
            debounceJob?.cancel()
            dialog.dismiss()
        }
        dialogBinding.btnResizeClose.setOnClickListener {
            debounceJob?.cancel()
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            debounceJob?.cancel()
        }
        dialog.show()
    }

    fun showRotateDialog() {
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
            onEditsChanged()
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

    fun showTextWatermarkDialog() {
        val dialogBinding = DialogTextWatermarkBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftText = editState.watermarkText
        var draftSize = editState.watermarkSize
        var draftPosition = editState.watermarkPosition
        var draftColor = editState.watermarkColor
        var draftOpacity = editState.watermarkOpacity
        var draftFont = editState.watermarkFont

        fun updateWatermarkPreview() {
            val draft = editState.deepCopy().apply {
                watermarkText = draftText
                watermarkSize = draftSize
                watermarkPosition = draftPosition
                watermarkColor = draftColor
                watermarkOpacity = draftOpacity
                watermarkFont = draftFont
            }
            dialogBinding.imgWatermarkPreview.setImageBitmap(renderLivePreview(draft))
        }

        dialogBinding.etWatermarkText.setText(draftText)
        dialogBinding.sliderWatermarkSize.value = draftSize.toFloat().coerceIn(12f, 72f)
        dialogBinding.tvWatermarkSizeLabel.text = "$draftSize sp"
        updateWatermarkPreview()

        when (draftPosition.lowercase().replace("_", "-")) {
            "top-left" -> dialogBinding.chipPosTopLeft.isChecked = true
            "top-center" -> dialogBinding.chipPosTopCenter.isChecked = true
            "top-right" -> dialogBinding.chipPosTopRight.isChecked = true
            "center-left" -> dialogBinding.chipPosCenterLeft.isChecked = true
            "center" -> dialogBinding.chipPosCenter.isChecked = true
            "center-right" -> dialogBinding.chipPosCenterRight.isChecked = true
            "bottom-left" -> dialogBinding.chipPosBottomLeft.isChecked = true
            "bottom-center" -> dialogBinding.chipPosBottomCenter.isChecked = true
            else -> dialogBinding.chipPosBottomRight.isChecked = true
        }

        when (draftColor.uppercase()) {
            "BLACK", "#000000" -> dialogBinding.chipWmColorBlack.isChecked = true
            "RED", "#EF4444" -> dialogBinding.chipWmColorRed.isChecked = true
            "YELLOW", "#EAB308" -> dialogBinding.chipWmColorYellow.isChecked = true
            "BLUE", "#3B82F6" -> dialogBinding.chipWmColorBlue.isChecked = true
            "GREEN", "#10B981" -> dialogBinding.chipWmColorGreen.isChecked = true
            else -> dialogBinding.chipWmColorWhite.isChecked = true
        }

        when (draftFont.lowercase()) {
            "bold" -> dialogBinding.chipFontBold.isChecked = true
            "serif" -> dialogBinding.chipFontSerif.isChecked = true
            "monospace", "mono" -> dialogBinding.chipFontMono.isChecked = true
            else -> dialogBinding.chipFontSans.isChecked = true
        }

        dialogBinding.sliderWatermarkOpacity.value = (draftOpacity * 100f).coerceIn(10f, 100f)
        dialogBinding.tvWatermarkOpacityLabel.text = "${(draftOpacity * 100f).toInt()}%"

        dialogBinding.sliderWatermarkOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                draftOpacity = value / 100f
                dialogBinding.tvWatermarkOpacityLabel.text = "${value.toInt()}%"
                updateWatermarkPreview()
            }
        }

        dialogBinding.chipGroupWatermarkFont.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupWatermarkFont.findViewById<Chip>(checkedIds[0])
                draftFont = when (chip?.id) {
                    R.id.chipFontBold -> "Bold"
                    R.id.chipFontSerif -> "Serif"
                    R.id.chipFontMono -> "Monospace"
                    else -> "Sans-Serif"
                }
                updateWatermarkPreview()
            }
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

        dialogBinding.etWatermarkText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
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
                    R.id.chipWmColorBlue -> "#3B82F6"
                    R.id.chipWmColorGreen -> "#10B981"
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
                    R.id.chipPosTopCenter -> "top-center"
                    R.id.chipPosTopRight -> "top-right"
                    R.id.chipPosCenterLeft -> "center-left"
                    R.id.chipPosCenter -> "center"
                    R.id.chipPosCenterRight -> "center-right"
                    R.id.chipPosBottomLeft -> "bottom-left"
                    R.id.chipPosBottomCenter -> "bottom-center"
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
            editState.watermarkOpacity = draftOpacity
            editState.watermarkFont = draftFont
            onEditsChanged()
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

    fun showColorFilterDialog() {
        val dialogBinding = DialogColorFilterBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftFilter = editState.filter
        var draftBgType = editState.bgType

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
            onEditsChanged()
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

    fun showExifDialog() {
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
            onEditsChanged()
            dialog.dismiss()
        }

        dialogBinding.btnExifClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
