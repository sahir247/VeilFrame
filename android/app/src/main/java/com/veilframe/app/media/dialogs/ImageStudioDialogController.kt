package com.veilframe.app.media.dialogs

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.veilframe.app.ui.motion.MorphDialogController
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogColorFilterBinding
import com.veilframe.app.databinding.DialogCropBinding
import com.veilframe.app.databinding.DialogExifBinding
import com.veilframe.app.databinding.DialogImageBlurBinding
import com.veilframe.app.databinding.DialogBlurBrushCanvasBinding
import com.veilframe.app.databinding.DialogResizeBinding
import com.veilframe.app.databinding.DialogRotateBinding
import com.veilframe.app.databinding.DialogTextWatermarkBinding
import com.veilframe.app.media.ImageBlurScope
import com.veilframe.app.media.ImageBlurType
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.transform.ImageTransformEngine
import com.veilframe.app.media.blur.ImageBlurEngine
import com.veilframe.app.ui.views.BlurBrushCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val getOriginalDimensions: () -> Pair<Int, Int>,
    private val renderLivePreview: (ImageEditState) -> Bitmap?,
    private val onEditsChanged: () -> Unit
) {

    fun showCropDialog(originView: View? = null) {
        val dialogBinding = DialogCropBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

        val draftState = editState.deepCopy()
        val baseBmp = getPreviewSourceBitmap()
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
            "Passport (600×600)" -> dialogBinding.chipAspectPassport.isChecked = true
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
                    R.id.chipAspectPassport -> "Passport (600×600)"
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
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                val normRect = dialogBinding.cropOverlayView.getCropNormalized()
                editState.cropLeft = normRect.left
                editState.cropTop = normRect.top
                editState.cropRight = normRect.right
                editState.cropBottom = normRect.bottom
                editState.cropAspect = draftState.cropAspect
                onEditsChanged()
            })
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
        dialogBinding.btnCropCancel.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialogBinding.btnCropClose.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showResizeDialog(originView: View? = null) {
        val dialogBinding = DialogResizeBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

        val dims = getOriginalDimensions()
        val fullW = dims.first.takeIf { it > 0 } ?: (getPreviewSourceBitmap()?.width ?: 1920)
        val fullH = dims.second.takeIf { it > 0 } ?: (getPreviewSourceBitmap()?.height ?: 1080)
        val origW = if (editState.isCropped()) {
            ((editState.cropRight - editState.cropLeft) * fullW).toInt().coerceAtLeast(16)
        } else fullW
        val origH = if (editState.isCropped()) {
            ((editState.cropBottom - editState.cropTop) * fullH).toInt().coerceAtLeast(16)
        } else fullH
        val origAspect = origW.toDouble() / origH.toDouble()

        var draftScale = editState.resizeScale
        var draftW = if (editState.resizeWidth > 0) editState.resizeWidth else origW
        var draftH = if (editState.resizeHeight > 0) editState.resizeHeight else origH
        var draftKeepAspect = editState.keepAspect
        var isUpdatingText = false
        var debounceJob: Job? = null
        val resizeRenderer = getPreviewSourceBitmap()?.let { source ->
            CoalescingImagePreviewRenderer(
                ownerScope = scope,
                input = source,
                originalWidth = origW,
                originalHeight = origH,
                onRendered = { bitmap -> dialogBinding.imgResizePreview.setImageBitmap(bitmap) }
            )
        }

        fun updateResizePreview() {
            val previewDraft = editState.copy(
                resizeScale = draftScale,
                resizeWidth = draftW,
                resizeHeight = draftH,
                keepAspect = draftKeepAspect
            )
            // One worker per dialog. Repeated slider changes replace pending work,
            // and no expensive image transformation executes in the UI callback.
            resizeRenderer?.request(previewDraft)
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
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.resizeScale = draftScale
                editState.resizeWidth = draftW
                editState.resizeHeight = draftH
                editState.keepAspect = draftKeepAspect
                onEditsChanged()
            })
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
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialogBinding.btnResizeClose.setOnClickListener {
            debounceJob?.cancel()
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialog.setOnDismissListener {
            debounceJob?.cancel()
            resizeRenderer?.close()
            dialogBinding.imgResizePreview.setImageDrawable(null)
        }
        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showRotateDialog(originView: View? = null) {
        val dialogBinding = DialogRotateBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

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
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.rotationAngle = draftAngle
                editState.flipH = draftFlipH
                editState.flipV = draftFlipV
                onEditsChanged()
            })
        }
        dialogBinding.btnRotateReset.setOnClickListener {
            draftAngle = 0f
            draftFlipH = false
            draftFlipV = false
            dialogBinding.sliderRotateAngle.value = 0f
            updateRotatePreview()
        }
        dialogBinding.btnRotateCancel.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialogBinding.btnRotateClose.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showTextWatermarkDialog(originView: View? = null) {
        val dialogBinding = DialogTextWatermarkBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

        var draftText = editState.watermarkText
        var draftSize = editState.watermarkSize
        var draftPosition = editState.watermarkPosition
        var draftColor = editState.watermarkColor
        var draftOpacity = if (editState.watermarkOpacity > 1.0f) (editState.watermarkOpacity / 100f).coerceIn(0.1f, 1.0f) else editState.watermarkOpacity.coerceIn(0.1f, 1.0f)
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

        fun updateSwatch(colorStr: String) {
            try {
                val c = when (colorStr.trim().lowercase()) {
                    "white" -> android.graphics.Color.WHITE
                    "black" -> android.graphics.Color.BLACK
                    "red" -> android.graphics.Color.parseColor("#EF4444")
                    "yellow" -> android.graphics.Color.parseColor("#EAB308")
                    "blue" -> android.graphics.Color.parseColor("#3B82F6")
                    "green" -> android.graphics.Color.parseColor("#10B981")
                    "cyan" -> android.graphics.Color.parseColor("#06B6D4")
                    "magenta" -> android.graphics.Color.parseColor("#D946EF")
                    "orange" -> android.graphics.Color.parseColor("#F97316")
                    "gold" -> android.graphics.Color.parseColor("#F59E0B")
                    "emerald" -> android.graphics.Color.parseColor("#059669")
                    "teal" -> android.graphics.Color.parseColor("#14B8A6")
                    "violet" -> android.graphics.Color.parseColor("#8B5CF6")
                    "pink" -> android.graphics.Color.parseColor("#EC4899")
                    "coral" -> android.graphics.Color.parseColor("#F43F5E")
                    "slate" -> android.graphics.Color.parseColor("#64748B")
                    else -> {
                        val hex = if (colorStr.startsWith("#")) colorStr else "#$colorStr"
                        android.graphics.Color.parseColor(hex)
                    }
                }
                dialogBinding.viewWatermarkColorSwatch.setBackgroundColor(c)
            } catch (_: Exception) {
                dialogBinding.viewWatermarkColorSwatch.setBackgroundColor(android.graphics.Color.WHITE)
            }
        }

        when (draftColor.uppercase()) {
            "BLACK", "#000000" -> dialogBinding.chipWmColorBlack.isChecked = true
            "RED", "#EF4444" -> dialogBinding.chipWmColorRed.isChecked = true
            "YELLOW", "#EAB308" -> dialogBinding.chipWmColorYellow.isChecked = true
            "BLUE", "#3B82F6" -> dialogBinding.chipWmColorBlue.isChecked = true
            "GREEN", "#10B981" -> dialogBinding.chipWmColorGreen.isChecked = true
            "CYAN", "#06B6D4" -> dialogBinding.chipWmColorCyan.isChecked = true
            "MAGENTA", "#D946EF" -> dialogBinding.chipWmColorMagenta.isChecked = true
            "ORANGE", "#F97316" -> dialogBinding.chipWmColorOrange.isChecked = true
            "GOLD", "#F59E0B" -> dialogBinding.chipWmColorGold.isChecked = true
            "EMERALD", "#059669" -> dialogBinding.chipWmColorEmerald.isChecked = true
            "TEAL", "#14B8A6" -> dialogBinding.chipWmColorTeal.isChecked = true
            "VIOLET", "#8B5CF6" -> dialogBinding.chipWmColorViolet.isChecked = true
            "PINK", "#EC4899" -> dialogBinding.chipWmColorPink.isChecked = true
            "CORAL", "#F43F5E" -> dialogBinding.chipWmColorCoral.isChecked = true
            "SLATE", "#64748B" -> dialogBinding.chipWmColorSlate.isChecked = true
            "WHITE", "#FFFFFF" -> dialogBinding.chipWmColorWhite.isChecked = true
            else -> {
                dialogBinding.chipGroupWatermarkColor.clearCheck()
                dialogBinding.etWatermarkHex.setText(draftColor)
            }
        }
        updateSwatch(draftColor)

        when (draftFont.lowercase()) {
            "bold", "heavy" -> dialogBinding.chipFontBold.isChecked = true
            "light" -> dialogBinding.chipFontLight.isChecked = true
            "condensed" -> dialogBinding.chipFontCondensed.isChecked = true
            "serif" -> dialogBinding.chipFontSerif.isChecked = true
            "serif bold" -> dialogBinding.chipFontSerifBold.isChecked = true
            "serif italic" -> dialogBinding.chipFontSerifItalic.isChecked = true
            "monospace", "mono" -> dialogBinding.chipFontMono.isChecked = true
            "mono bold", "monospace bold" -> dialogBinding.chipFontMonoBold.isChecked = true
            "cursive", "script" -> dialogBinding.chipFontCursive.isChecked = true
            "casual" -> dialogBinding.chipFontCasual.isChecked = true
            "black", "heavy black" -> dialogBinding.chipFontBlack.isChecked = true
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
                    R.id.chipFontLight -> "Light"
                    R.id.chipFontCondensed -> "Condensed"
                    R.id.chipFontSerif -> "Serif"
                    R.id.chipFontSerifBold -> "Serif Bold"
                    R.id.chipFontSerifItalic -> "Serif Italic"
                    R.id.chipFontMono -> "Monospace"
                    R.id.chipFontMonoBold -> "Mono Bold"
                    R.id.chipFontCursive -> "Cursive"
                    R.id.chipFontCasual -> "Casual"
                    R.id.chipFontBlack -> "Heavy Black"
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
                    R.id.chipWmColorCyan -> "#06B6D4"
                    R.id.chipWmColorMagenta -> "#D946EF"
                    R.id.chipWmColorOrange -> "#F97316"
                    R.id.chipWmColorGold -> "#F59E0B"
                    R.id.chipWmColorEmerald -> "#059669"
                    R.id.chipWmColorTeal -> "#14B8A6"
                    R.id.chipWmColorViolet -> "#8B5CF6"
                    R.id.chipWmColorPink -> "#EC4899"
                    R.id.chipWmColorCoral -> "#F43F5E"
                    R.id.chipWmColorSlate -> "#64748B"
                    else -> "#FFFFFF"
                }
                updateSwatch(draftColor)
                updateWatermarkPreview()
            }
        }

        dialogBinding.etWatermarkHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hex = s?.toString()?.trim() ?: ""
                if (hex.isNotEmpty() && (hex.length == 7 || hex.length == 9 || (hex.length == 6 && !hex.startsWith("#")))) {
                    val fullHex = if (hex.startsWith("#")) hex else "#$hex"
                    try {
                        android.graphics.Color.parseColor(fullHex)
                        draftColor = fullHex
                        dialogBinding.chipGroupWatermarkColor.clearCheck()
                        updateSwatch(fullHex)
                        updateWatermarkPreview()
                    } catch (_: Exception) {}
                }
            }
        })

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
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.watermarkText = draftText
                editState.watermarkSize = draftSize
                editState.watermarkPosition = draftPosition
                editState.watermarkColor = draftColor
                editState.watermarkOpacity = draftOpacity
                editState.watermarkFont = draftFont
                onEditsChanged()
            })
        }
        dialogBinding.btnWatermarkReset.setOnClickListener {
            draftText = ""
            dialogBinding.etWatermarkText.setText("")
            updateWatermarkPreview()
        }
        dialogBinding.btnWatermarkCancel.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialogBinding.btnWatermarkClose.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showColorFilterDialog(originView: View? = null) {
        val dialogBinding = DialogColorFilterBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

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

        // Dynamically populate all filters from VideoColorFilterHelper
        dialogBinding.chipGroupFilters.removeAllViews()
        for (profile in com.veilframe.app.media.preview.VideoColorFilterHelper.PROFILES) {
            val chip = com.google.android.material.chip.Chip(activity).apply {
                text = profile.name
                isCheckable = true
                isChecked = (draftFilter.equals(profile.id, ignoreCase = true) ||
                        draftFilter.equals(profile.name, ignoreCase = true) ||
                        (draftFilter == "Default" && profile.id == "Original"))
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        draftFilter = if (profile.id == "Original") "Default" else profile.id
                        updateFilterPreview()
                    }
                }
            }
            dialogBinding.chipGroupFilters.addView(chip)
        }

        when (draftBgType) {
            "Black" -> dialogBinding.chipBgBlack.isChecked = true
            "White" -> dialogBinding.chipBgWhite.isChecked = true
            "Blurred Fill" -> dialogBinding.chipBgBlur.isChecked = true
            else -> dialogBinding.chipBgTrans.isChecked = true
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
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.filter = draftFilter
                editState.bgType = draftBgType
                onEditsChanged()
            })
        }
        dialogBinding.btnFilterReset.setOnClickListener {
            draftFilter = "Default"
            draftBgType = "Transparent"
            dialogBinding.chipFilterDefault.isChecked = true
            dialogBinding.chipBgTrans.isChecked = true
            updateFilterPreview()
        }
        dialogBinding.btnFilterCancel.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialogBinding.btnFilterClose.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showExifDialog(originView: View? = null) {
        val dialogBinding = DialogExifBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

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
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.stripExif = draftStripExif
                editState.exifMake = dialogBinding.etExifMake.text.toString().trim()
                editState.exifModel = dialogBinding.etExifModel.text.toString().trim()
                editState.exifSoftware = dialogBinding.etExifSoftware.text.toString().trim()
                editState.exifDateTime = dialogBinding.etExifDateTime.text.toString().trim()
                editState.exifGps = dialogBinding.etExifGps.text.toString().trim()
                onEditsChanged()
            })
        }

        dialogBinding.btnExifClose.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showBlurDialog(originView: View? = null) {
        val dialogBinding = DialogImageBlurBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        val morph = MorphDialogController()

        var draftScope = editState.blurScope.takeIf { it != ImageBlurScope.NONE } ?: ImageBlurScope.BACKGROUND
        var draftType = editState.blurType
        var draftRadius = editState.blurRadius
        var draftMask = editState.blurBrushMask?.let { Bitmap.createBitmap(it) }
        var draftSubjectCutout = editState.blurBgSubjectCutout?.let { Bitmap.createBitmap(it) }
        var isExtractingSubject = false

        fun ensureSubjectCutoutLoaded(onReady: () -> Unit) {
            if (draftSubjectCutout != null) {
                onReady()
                return
            }
            if (isExtractingSubject) return
            isExtractingSubject = true
            val baseBmp = renderLivePreview(editState.deepCopy().apply { blurScope = ImageBlurScope.NONE })
            if (baseBmp == null) {
                isExtractingSubject = false
                onReady()
                return
            }
            scope.launch(Dispatchers.Default) {
                val cutout = ImageBlurEngine.extractSubjectMask(activity, baseBmp)
                withContext(Dispatchers.Main) {
                    isExtractingSubject = false
                    draftSubjectCutout = cutout
                    onReady()
                }
            }
        }

        val originalBaseBmp = renderLivePreview(editState.deepCopy().apply { blurScope = ImageBlurScope.NONE })
        var currentPreviewBitmap: Bitmap? = null
        val (blurOrigW, blurOrigH) = getOriginalDimensions()
        val blurRenderer = getPreviewSourceBitmap()?.let { source ->
            CoalescingImagePreviewRenderer(
                ownerScope = scope,
                input = source,
                originalWidth = blurOrigW,
                originalHeight = blurOrigH,
                onRendered = { bitmap ->
                    currentPreviewBitmap = bitmap
                    if (dialogBinding.tvBlurBeforeBadge.visibility != View.VISIBLE) {
                        dialogBinding.imgBlurPreview.setImageBitmap(bitmap)
                    }
                }
            )
        }

        fun updateBlurPreview() {
            if (draftScope == ImageBlurScope.BACKGROUND && draftSubjectCutout == null) {
                ensureSubjectCutoutLoaded {
                    updateBlurPreview()
                }
            }
            val draft = editState.copy(
                blurScope = draftScope,
                blurType = draftType,
                blurRadius = draftRadius,
                blurBrushMask = draftMask,
                blurBgSubjectCutout = draftSubjectCutout
            )
            blurRenderer?.request(draft)
        }

        dialogBinding.imgBlurPreview.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (originalBaseBmp != null) {
                        dialogBinding.imgBlurPreview.setImageBitmap(originalBaseBmp)
                        dialogBinding.tvBlurBeforeBadge.visibility = View.VISIBLE
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    dialogBinding.tvBlurBeforeBadge.visibility = View.GONE
                    if (currentPreviewBitmap != null) {
                        dialogBinding.imgBlurPreview.setImageBitmap(currentPreviewBitmap)
                    }
                    true
                }
                else -> false
            }
        }

        updateBlurPreview()

        when (draftScope) {
            ImageBlurScope.FULL -> dialogBinding.chipBlurFull.isChecked = true
            else -> dialogBinding.chipBlurBg.isChecked = true
        }

        if (draftMask != null) {
            dialogBinding.tvBlurBrushStatus.visibility = View.VISIBLE
        } else {
            dialogBinding.tvBlurBrushStatus.visibility = View.GONE
        }

        when (draftType) {
            ImageBlurType.BOX -> dialogBinding.chipBlurBox.isChecked = true
            ImageBlurType.MOSAIC -> dialogBinding.chipBlurMosaic.isChecked = true
            ImageBlurType.GLASS -> dialogBinding.chipBlurGlass.isChecked = true
            ImageBlurType.MOTION -> dialogBinding.chipBlurMotion.isChecked = true
            ImageBlurType.BOKEH -> dialogBinding.chipBlurBokeh.isChecked = true
            ImageBlurType.SOLID -> dialogBinding.chipBlurSolid.isChecked = true
            ImageBlurType.PAINT -> dialogBinding.chipBlurPaint.isChecked = true
            ImageBlurType.WATER -> dialogBinding.chipBlurWater.isChecked = true
            ImageBlurType.ZOOM -> dialogBinding.chipBlurZoom.isChecked = true
            else -> dialogBinding.chipBlurGaussian.isChecked = true
        }

        dialogBinding.sliderBlurRadius.value = draftRadius.toFloat().coerceIn(5f, 80f)
        dialogBinding.tvBlurRadiusValue.text = "$draftRadius px"

        dialogBinding.chipGroupBlurScope.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupBlurScope.findViewById<Chip>(checkedIds[0])
                draftScope = when (chip?.id) {
                    R.id.chipBlurFull -> ImageBlurScope.FULL
                    else -> ImageBlurScope.BACKGROUND
                }
                updateBlurPreview()
            }
        }

        dialogBinding.chipGroupBlurType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupBlurType.findViewById<Chip>(checkedIds[0])
                draftType = when (chip?.id) {
                    R.id.chipBlurBox -> ImageBlurType.BOX
                    R.id.chipBlurMosaic -> ImageBlurType.MOSAIC
                    R.id.chipBlurGlass -> ImageBlurType.GLASS
                    R.id.chipBlurMotion -> ImageBlurType.MOTION
                    R.id.chipBlurBokeh -> ImageBlurType.BOKEH
                    R.id.chipBlurSolid -> ImageBlurType.SOLID
                    R.id.chipBlurPaint -> ImageBlurType.PAINT
                    R.id.chipBlurWater -> ImageBlurType.WATER
                    R.id.chipBlurZoom -> ImageBlurType.ZOOM
                    else -> ImageBlurType.GAUSSIAN
                }
                updateBlurPreview()
            }
        }

        dialogBinding.sliderBlurRadius.addOnChangeListener { _, value, fromUser ->
            draftRadius = value.toInt()
            dialogBinding.tvBlurRadiusValue.text = "$draftRadius px"
            if (fromUser) updateBlurPreview()
        }

        dialogBinding.btnOpenBlurBrushCanvas.setOnClickListener {
            // Pick up active blur work so the interactive canvas opens with background/full blur already applied
            val draftForCanvas = editState.deepCopy().apply {
                blurScope = draftScope
                blurType = draftType
                blurRadius = draftRadius
                blurBgSubjectCutout = draftSubjectCutout
            }
            val previewBmp = renderLivePreview(draftForCanvas) ?: originalBaseBmp
            if (previewBmp != null) {
                showBlurBrushCanvasDialog(previewBmp, draftType, draftRadius, draftMask) { newMask ->
                    draftMask = newMask
                    if (newMask != null) {
                        dialogBinding.tvBlurBrushStatus.visibility = View.VISIBLE
                    } else {
                        dialogBinding.tvBlurBrushStatus.visibility = View.GONE
                    }
                    updateBlurPreview()
                }
            } else {
                Toast.makeText(activity, "Preview bitmap unavailable for canvas", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnBlurApply.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.blurScope = draftScope
                editState.blurType = draftType
                editState.blurRadius = draftRadius
                editState.blurBrushMask = draftMask
                editState.blurBgSubjectCutout = draftSubjectCutout
                onEditsChanged()
            })
        }

        dialogBinding.btnBlurReset.setOnClickListener {
            draftScope = ImageBlurScope.NONE
            draftMask = null
            draftSubjectCutout = null
            morph.requestDismiss(dialog, originView, dialogBinding.root, commitAction = {
                editState.blurScope = ImageBlurScope.NONE
                editState.blurBrushMask = null
                editState.blurBgSubjectCutout = null
                onEditsChanged()
            })
        }

        dialogBinding.btnBlurCancel.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialogBinding.btnBlurClose.setOnClickListener {
            morph.requestDismiss(dialog, originView, dialogBinding.root)
        }
        dialog.setOnDismissListener {
            blurRenderer?.close()
            dialogBinding.imgBlurPreview.setImageDrawable(null)
        }

        morph.showMorphDialog(activity, originView, dialog, dialogBinding.root)
    }

    fun showBlurBrushCanvasDialog(
        sourceBitmap: Bitmap,
        blurType: ImageBlurType,
        radius: Int,
        initialMask: Bitmap?,
        onDone: (Bitmap?) -> Unit
    ) {
        val dialogBinding = DialogBlurBrushCanvasBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(dialogBinding.root)
        }

        val blurred = ImageBlurEngine.applyBlur(sourceBitmap, blurType, radius)
        dialogBinding.blurCanvasView.setBitmaps(sourceBitmap, blurred, initialMask)

        dialogBinding.btnModeBlurBrush.isChecked = true
        dialogBinding.toggleBrushMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnModeBlurBrush -> dialogBinding.blurCanvasView.mode = BlurBrushCanvasView.Mode.BLUR
                    R.id.btnModeBlurErase -> dialogBinding.blurCanvasView.mode = BlurBrushCanvasView.Mode.ERASE
                }
            }
        }

        dialogBinding.sliderBrushSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                dialogBinding.blurCanvasView.brushRadiusPx = value
            }
        }

        dialogBinding.btnBrushUndo.setOnClickListener { dialogBinding.blurCanvasView.undo() }
        dialogBinding.btnBrushRedo.setOnClickListener { dialogBinding.blurCanvasView.redo() }
        dialogBinding.btnBrushClear.setOnClickListener { dialogBinding.blurCanvasView.clearMask() }
        dialogBinding.btnBrushResetZoom.setOnClickListener { dialogBinding.blurCanvasView.resetZoom() }

        dialogBinding.btnBrushClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnBrushApply.setOnClickListener {
            val mask = dialogBinding.blurCanvasView.getMaskBitmap()
            onDone(mask)
            dialog.dismiss()
        }

        dialog.show()
    }
}
