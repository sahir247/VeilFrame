package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutPdfWatermarkBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.PdfWatermarker
import com.veilframe.app.pdf.operations.WatermarkConfig
import com.veilframe.app.pdf.operations.WatermarkFillPattern
import com.veilframe.app.pdf.operations.WatermarkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller orchestrating live interactive PDF watermarking, multi-angle security mesh,
 * real-time opacity/angle controls, and document export.
 */
class PdfWatermarkController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfWatermarkBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSavePdfRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var basePageBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var isApplying: Boolean = false
    private var isSaved: Boolean = false
    private var totalPages: Int = 1

    private val pdfWatermarker by lazy { PdfWatermarker() }

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = activePdfUri != null && !isSaved
    override val isProcessing: Boolean get() = isApplying

    override fun discardAndReleaseAllResources() {
        previewJob?.cancel()
        basePageBitmap?.recycle()
        basePageBitmap = null
        activePdfUri = null
        activePdfName = "document.pdf"
        isApplying = false
        isSaved = false
        binding.containerWatermarkDocInfo.visibility = View.GONE
        binding.containerWatermarkControls.visibility = View.GONE
        binding.containerWatermarkEmptyState.visibility = View.VISIBLE
        binding.btnPdfWatermarkReset.visibility = View.GONE
        binding.ivWatermarkPreview.setImageDrawable(null)
    }

    fun init() {
        setupToolbar()
        setupPicker()
        setupLiveControls()
        setupExportAction()
    }

    private fun setupToolbar() {
        binding.toolbarPdfWatermark.setNavigationOnClickListener {
            onNavigateBack()
        }
        binding.btnPdfWatermarkReset.setOnClickListener {
            resetWorkspace()
        }
    }

    private fun setupPicker() {
        binding.cardWatermarkPicker.setOnClickListener {
            onPickPdfRequest()
        }
        binding.btnWatermarkChangeDoc.setOnClickListener {
            onPickPdfRequest()
        }
    }

    fun onPdfSelected(uri: Uri) {
        activePdfUri = uri
        activePdfName = queryPdfDisplayName(uri) ?: "document.pdf"
        isSaved = false

        binding.containerWatermarkEmptyState.visibility = View.GONE
        binding.containerWatermarkDocInfo.visibility = View.VISIBLE
        binding.containerWatermarkControls.visibility = View.VISIBLE
        binding.btnPdfWatermarkReset.visibility = View.VISIBLE
        binding.tvWatermarkDocName.text = activePdfName
        binding.tvWatermarkDocMeta.text = "Rendering preview..."

        renderBasePagePreview(uri)
    }

    private fun renderBasePagePreview(uri: Uri) {
        binding.pbWatermarkPreviewLoading.visibility = View.VISIBLE
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = activity.contentResolver.openInputStream(uri) ?: return@launch
                val document = PDDocument.load(inputStream)
                inputStream.close()

                totalPages = document.numberOfPages
                val renderer = PDFRenderer(document)
                val bitmap = renderer.renderImageWithDPI(0, 150f)
                document.close()

                withContext(Dispatchers.Main) {
                    binding.pbWatermarkPreviewLoading.visibility = View.GONE
                    basePageBitmap = bitmap
                    binding.tvWatermarkDocMeta.text = "$totalPages pages • Page 1 preview ready"
                    binding.tvWatermarkPageIndicator.text = "PAGE 1 OF $totalPages"
                    triggerLivePreviewUpdate()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbWatermarkPreviewLoading.visibility = View.GONE
                    binding.tvWatermarkDocMeta.text = "Failed to render preview: ${e.message}"
                }
            }
        }
    }

    private fun setupLiveControls() {
        binding.etWatermarkText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                triggerLivePreviewUpdate()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.sliderWatermarkOpacity.addOnChangeListener { _, value, _ ->
            val pct = value.toInt()
            binding.tvWatermarkOpacityVal.text = "$pct%"
            triggerLivePreviewUpdate()
        }

        binding.sliderWatermarkAngle.addOnChangeListener { _, value, _ ->
            val deg = value.toInt()
            binding.tvWatermarkAngleVal.text = "$deg°"
            triggerLivePreviewUpdate()
        }

        binding.sliderWatermarkFontSize.addOnChangeListener { _, value, _ ->
            val pt = value.toInt()
            binding.tvWatermarkFontSizeVal.text = "${pt}pt"
            triggerLivePreviewUpdate()
        }

        binding.chipGroupWatermarkPattern.setOnCheckedStateChangeListener { _, _ ->
            triggerLivePreviewUpdate()
        }
    }

    private fun getCurrentConfig(): WatermarkConfig {
        val rawText = binding.etWatermarkText.text?.toString()?.trim()
        val content = if (rawText.isNullOrEmpty()) "CONFIDENTIAL" else rawText
        val opacity = (binding.sliderWatermarkOpacity.value / 100f).coerceIn(0.05f, 1.0f)
        val rotation = binding.sliderWatermarkAngle.value
        val fontSize = binding.sliderWatermarkFontSize.value
        val color = Color.DKGRAY

        val pattern = when (binding.chipGroupWatermarkPattern.checkedChipId) {
            R.id.chipPatternCenter -> WatermarkFillPattern.SINGLE_CENTER
            R.id.chipPatternHorizontal -> WatermarkFillPattern.FILL_HORIZONTAL
            R.id.chipPatternVertical -> WatermarkFillPattern.FILL_VERTICAL
            R.id.chipPatternDiagonalDesc -> WatermarkFillPattern.FILL_DIAGONAL_RTL
            R.id.chipPatternMesh -> WatermarkFillPattern.FILL_CROSS_HATCH
            else -> WatermarkFillPattern.FILL_DIAGONAL_LTR
        }

        return WatermarkConfig(
            type = WatermarkType.Text(
                content = content,
                fontSize = fontSize,
                rotation = rotation,
                colorHex = color
            ),
            pattern = pattern,
            opacity = opacity,
            applyToAllPages = true
        )
    }

    private fun triggerLivePreviewUpdate() {
        val bmp = basePageBitmap ?: return
        previewJob?.cancel()
        previewJob = scope.launch(Dispatchers.Default) {
            val config = getCurrentConfig()
            val watermarked = pdfWatermarker.renderLivePreview(bmp, config)
            withContext(Dispatchers.Main) {
                binding.ivWatermarkPreview.setImageBitmap(watermarked)
            }
        }
    }

    private fun setupExportAction() {
        binding.btnWatermarkApplyExport.setOnClickListener {
            val uri = activePdfUri ?: return@setOnClickListener
            if (isApplying) return@setOnClickListener

            isApplying = true
            binding.btnWatermarkApplyExport.isEnabled = false
            binding.btnWatermarkApplyExport.text = "Applying Watermark..."

            scope.launch(Dispatchers.IO) {
                try {
                    val config = getCurrentConfig()
                    val tempOut = File(activity.cacheDir, "watermarked_${System.currentTimeMillis()}.pdf")
                    val outUri = Uri.fromFile(tempOut)
                    val result = pdfWatermarker.addWatermark(
                        context = activity,
                        inputUri = uri,
                        outputUri = outUri,
                        config = config
                    )

                    withContext(Dispatchers.Main) {
                        isApplying = false
                        binding.btnWatermarkApplyExport.isEnabled = true
                        binding.btnWatermarkApplyExport.text = "Apply Watermark & Export"

                        if (result.success && tempOut.exists()) {
                            isSaved = true
                            val bytes = tempOut.readBytes()
                            tempOut.delete()
                            val base = activePdfName.removeSuffix(".pdf")
                            val exportName = "${base}_watermarked.pdf"
                            onSavePdfRequest(bytes, exportName)
                        } else {
                            tempOut.delete()
                            val err = result.errorMessage ?: "Failed to watermark document"
                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isApplying = false
                        binding.btnWatermarkApplyExport.isEnabled = true
                        binding.btnWatermarkApplyExport.text = "Apply Watermark & Export"
                        Toast.makeText(activity, "Watermarking failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerWatermarkDocInfo.visibility = View.GONE
        binding.containerWatermarkControls.visibility = View.GONE
        binding.containerWatermarkEmptyState.visibility = View.VISIBLE
        binding.btnPdfWatermarkReset.visibility = View.GONE
        binding.ivWatermarkPreview.setImageDrawable(null)
    }

    private fun queryPdfDisplayName(uri: Uri): String? {
        val cursor = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) return it.getString(index)
            }
        }
        return uri.lastPathSegment
    }
}
