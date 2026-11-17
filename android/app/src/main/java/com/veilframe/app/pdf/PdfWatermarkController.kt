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
import kotlinx.coroutines.delay
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
    private val onNavigateBack: () -> Unit,
    private val onSaveFileFromDiskRequest: ((File, String) -> Unit)? = null
) : ToolWorkStateContract {

    private val taskTracker = PdfTaskTracker(scope)

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var basePageBitmap: Bitmap? = null
    private var livePreviewBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var isApplying: Boolean = false
    private var isSaved: Boolean = false
    private var totalPages: Int = 1

    private val pdfWatermarker by lazy { PdfWatermarker() }

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = activePdfUri != null && !isSaved
    override val isProcessing: Boolean get() = isApplying

    override fun discardAndReleaseAllResources() {
        taskTracker.cancelAll()
        previewJob?.cancel()
        binding.ivWatermarkPreview.setImageDrawable(null)
        livePreviewBitmap?.recycle()
        livePreviewBitmap = null
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
        taskTracker.cancelAll()
        previewJob = null
        binding.ivWatermarkPreview.setImageDrawable(null)
        livePreviewBitmap?.recycle()
        livePreviewBitmap = null
        basePageBitmap?.recycle()
        basePageBitmap = null
        isApplying = false
        binding.pbWatermarkPreviewLoading.visibility = View.GONE
        binding.btnWatermarkApplyExport.isEnabled = true
        binding.btnWatermarkApplyExport.text = "Apply Watermark & Export"
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
        taskTracker.launch(Dispatchers.IO) {
            try {
                val preview = activity.contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { document ->
                        val pageCount = document.numberOfPages
                        require(pageCount > 0) { "PDF contains no pages" }
                        val box = document.getPage(0).mediaBox
                        // Previews are display-only: cap the largest raster edge to 1280px.
                        val dpi = PdfFlattenPreviewSizing.dpi(box.width, box.height)
                        pageCount to PDFRenderer(document).renderImageWithDPI(0, dpi)
                    }
                } ?: throw java.io.IOException("Unable to open PDF for preview")

                val (pageCount, bitmap) = preview
                var displayed = false
                try {
                    withContext(Dispatchers.Main) {
                        binding.pbWatermarkPreviewLoading.visibility = View.GONE
                        totalPages = pageCount
                        basePageBitmap = bitmap
                        displayed = true
                        binding.tvWatermarkDocMeta.text = "$pageCount pages • Page 1 preview ready"
                        binding.tvWatermarkPageIndicator.text = "PAGE 1 OF $pageCount"
                        triggerLivePreviewUpdate()
                    }
                } finally {
                    if (!displayed) bitmap.recycle()
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
        // Capture view values on Main and coalesce rapid slider/text updates.
        val config = getCurrentConfig()
        previewJob?.cancel()
        previewJob = taskTracker.launch(Dispatchers.Default) {
            var rendered: Bitmap? = null
            try {
                delay(75)
                if (bmp.isRecycled) return@launch
                rendered = pdfWatermarker.renderLivePreview(bmp, config)
                withContext(Dispatchers.Main) {
                    val previous = livePreviewBitmap
                    binding.ivWatermarkPreview.setImageBitmap(rendered)
                    livePreviewBitmap = rendered
                    rendered = null
                    previous?.recycle()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                // A workspace switch may recycle the source before a late preview starts.
                android.util.Log.w("VeilFrame.PdfWatermark", "Preview rendering failed", error)
            } finally {
                // Cancellation after rendering but before the Main-thread swap must not leak a bitmap.
                rendered?.recycle()
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

            // Read view configuration on the UI thread, before background processing.
            val config = getCurrentConfig()
            taskTracker.launch(Dispatchers.IO) {
                var tempOut: File? = null
                try {
                    val outputFile = File.createTempFile("watermarked_", ".pdf", activity.cacheDir)
                    tempOut = outputFile
                    val outUri = Uri.fromFile(outputFile)
                    val result = pdfWatermarker.addWatermark(
                        context = activity,
                        inputUri = uri,
                        outputUri = outUri,
                        config = config
                    )

                    val complete = result.success && outputFile.isFile && outputFile.length() > 0L
                    // Legacy consumers still receive bytes; the app itself uses a disk-backed save.
                    val outputBytes = if (complete && onSaveFileFromDiskRequest == null) outputFile.readBytes() else null

                    withContext(Dispatchers.Main) {
                        isApplying = false
                        binding.btnWatermarkApplyExport.isEnabled = true
                        binding.btnWatermarkApplyExport.text = "Apply Watermark & Export"

                        if (complete) {
                            val base = activePdfName.removeSuffix(".pdf")
                            val exportName = "${base}_watermarked.pdf"
                            val diskSave = onSaveFileFromDiskRequest
                            if (diskSave != null) {
                                diskSave(outputFile, exportName)
                                tempOut = null // The SAF picker now owns the temporary file.
                            } else {
                                onSavePdfRequest(requireNotNull(outputBytes), exportName)
                            }
                            isSaved = true
                        } else {
                            val err = result.errorMessage ?: "Failed to watermark document"
                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isApplying = false
                        binding.btnWatermarkApplyExport.isEnabled = true
                        binding.btnWatermarkApplyExport.text = "Apply Watermark & Export"
                        Toast.makeText(activity, "Watermarking failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    tempOut?.delete()
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
