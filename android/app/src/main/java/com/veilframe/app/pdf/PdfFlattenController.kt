package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutPdfFlattenBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.FlattenConfig
import com.veilframe.app.pdf.operations.FlattenResult
import com.veilframe.app.pdf.operations.PdfFlattener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller orchestrating interactive AcroForm & annotation flattening with
 * real-time Before vs After live visual comparison and document export.
 */
/**
 * Screen-only PDF flatten preview scale. PDF points are converted to pixels via DPI / 72.
 * The maximum scale prevents tiny pages looking poor while the edge cap prevents huge previews.
 */
internal object PdfFlattenPreviewSizing {
    fun dpi(pageWidthPoints: Float, pageHeightPoints: Float, maxEdgePx: Float = 1280f): Float {
        if (pageWidthPoints <= 0f || pageHeightPoints <= 0f) return 72f
        val scale = minOf(
            1.5f,
            maxEdgePx / pageWidthPoints,
            maxEdgePx / pageHeightPoints
        )
        return scale * 72f
    }
}

class PdfFlattenController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfFlattenBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSavePdfRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    private val taskTracker = PdfTaskTracker(scope)

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var beforeBitmap: Bitmap? = null
    private var afterBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var isFlattening: Boolean = false
    private var isSaved: Boolean = false
    private var formFieldCount: Int = 0

    private val pdfFlattener by lazy { PdfFlattener() }

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = activePdfUri != null && !isSaved
    override val isProcessing: Boolean get() = isFlattening

    override fun discardAndReleaseAllResources() {
        taskTracker.cancelAll()
        previewJob?.cancel()
        beforeBitmap?.recycle()
        afterBitmap?.recycle()
        beforeBitmap = null
        afterBitmap = null
        activePdfUri = null
        activePdfName = "document.pdf"
        isFlattening = false
        isSaved = false
        binding.containerFlattenDocInfo.visibility = View.GONE
        binding.containerFlattenControls.visibility = View.GONE
        binding.containerFlattenEmptyState.visibility = View.VISIBLE
        binding.btnPdfFlattenReset.visibility = View.GONE
        binding.ivFlattenPreview.setImageDrawable(null)
    }

    fun init() {
        setupListeners()
    }

    private fun setupListeners() {
        binding.toolbarPdfFlatten.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnPdfFlattenReset.setOnClickListener {
            resetWorkspace()
        }

        binding.cardFlattenPicker.setOnClickListener {
            onPickPdfRequest()
        }

        binding.btnFlattenChangeDoc.setOnClickListener {
            onPickPdfRequest()
        }

        binding.chipGroupFlattenCompare.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipFlattenAfter -> showAfterPreview()
                else -> showBeforePreview()
            }
        }

        binding.btnFlattenExecute.setOnClickListener {
            val uri = activePdfUri ?: return@setOnClickListener
            if (isFlattening) return@setOnClickListener

            isFlattening = true
            binding.btnFlattenExecute.isEnabled = false
            binding.btnFlattenExecute.text = "Flattening Document..."

            taskTracker.launch(Dispatchers.IO) {
                var tempOutFile: File? = null
                try {
                    val cacheDir = File(activity.cacheDir, "pdf_flatten_temp")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val outputFile = File(cacheDir, "flattened_${System.currentTimeMillis()}.pdf")
                    tempOutFile = outputFile
                    val outUri = Uri.fromFile(outputFile)

                    val result = pdfFlattener.flattenPdf(
                        context = activity,
                        inputUri = uri,
                        outputUri = outUri,
                        config = FlattenConfig(
                            flattenAnnotations = true,
                            flattenForms = true,
                            removeJavaScript = true,
                            removeEmbeddedFiles = false
                        )
                    )

                    // Read the exported PDF on IO; never perform a potentially large disk read on Main.
                    val flattenedBytes = if (result.success && outputFile.isFile) {
                        outputFile.readBytes()
                    } else {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        isFlattening = false
                        binding.btnFlattenExecute.isEnabled = true
                        binding.btnFlattenExecute.text = "Flatten & Save PDF"

                        if (flattenedBytes != null) {
                            isSaved = true
                            val base = activePdfName.removeSuffix(".pdf")
                            val exportName = "${base}_flattened.pdf"
                            onSavePdfRequest(flattenedBytes, exportName)
                        } else {
                            val err = result.errorMessage ?: "Failed to flatten PDF"
                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isFlattening = false
                        binding.btnFlattenExecute.isEnabled = true
                        binding.btnFlattenExecute.text = "Flatten & Save PDF"
                        Toast.makeText(activity, "Flatten failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    tempOutFile?.delete()
                }
            }
        }
    }

    fun onPdfSelected(uri: Uri) {
        taskTracker.cancelAll()
        previewJob = null
        beforeBitmap?.recycle()
        afterBitmap?.recycle()
        beforeBitmap = null
        afterBitmap = null
        binding.ivFlattenPreview.setImageDrawable(null)
        isFlattening = false
        binding.pbFlattenLoading.visibility = View.GONE
        binding.btnFlattenExecute.isEnabled = true
        binding.btnFlattenExecute.text = "Flatten & Save PDF"
        activePdfUri = uri
        activePdfName = queryPdfDisplayName(uri) ?: "document.pdf"
        isSaved = false

        binding.containerFlattenEmptyState.visibility = View.GONE
        binding.containerFlattenDocInfo.visibility = View.VISIBLE
        binding.containerFlattenControls.visibility = View.VISIBLE
        binding.btnPdfFlattenReset.visibility = View.VISIBLE
        binding.tvFlattenDocName.text = activePdfName
        binding.tvFlattenDocMeta.text = "Inspecting form fields and annotations..."

        renderLivePreviews(uri)
    }

    private fun renderLivePreviews(uri: Uri) {
        previewJob?.cancel()
        binding.pbFlattenLoading.visibility = View.VISIBLE
        previewJob = taskTracker.launch(Dispatchers.IO) {
            var before: Bitmap? = null
            var after: Bitmap? = null
            var pageCount = 0
            var fields = 0
            try {
                val document = activity.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                    ?: throw IllegalStateException("Unable to open the selected PDF for preview")

                document.use { doc ->
                    pageCount = doc.numberOfPages
                    check(pageCount > 0) { "PDF has no pages to preview" }
                    val acroForm = doc.documentCatalog.acroForm
                    fields = acroForm?.fields?.size ?: 0
                    formFieldCount = fields

                    val renderer = PDFRenderer(doc)
                    // Preview only needs screen resolution. Bound the longest edge to 1280 px;
                    // 150-DPI rendering made large-format pages allocate very large bitmaps.
                    val pageBox = doc.getPage(0).mediaBox
                    val previewDpi = PdfFlattenPreviewSizing.dpi(pageBox.width, pageBox.height)
                    before = renderer.renderImageWithDPI(0, previewDpi)

                    // Render simulated flattened after bitmap using the same bounded resolution.
                    acroForm?.flatten()
                    after = renderer.renderImageWithDPI(0, previewDpi)
                }

                coroutineContext.ensureActive()
                withContext(Dispatchers.Main) {
                    binding.pbFlattenLoading.visibility = View.GONE
                    beforeBitmap = before
                    afterBitmap = after
                    before = null
                    after = null

                    binding.tvFlattenDocMeta.text = "$pageCount pages • $fields form fields detected"
                    binding.tvFlattenSummaryDesc.text =
                        if (fields > 0) "$fields interactive AcroForm fields detected. Flattening will bake them permanently into page streams."
                        else "No form fields detected. Annotations and markups will be permanently flattened."

                    showBeforePreview()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbFlattenLoading.visibility = View.GONE
                    binding.tvFlattenDocMeta.text = "Error rendering preview: ${e.message}"
                }
            } finally {
                before?.takeUnless { it.isRecycled }?.recycle()
                after?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private fun showBeforePreview() {
        beforeBitmap?.let {
            binding.ivFlattenPreview.setImageBitmap(it)
            binding.tvFlattenPageIndicator.text = "BEFORE (Interactive & Editable)"
        }
    }

    private fun showAfterPreview() {
        afterBitmap?.let {
            binding.ivFlattenPreview.setImageBitmap(it)
            binding.tvFlattenPageIndicator.text = "AFTER (Permanently Burned & Flattened)"
        }
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerFlattenDocInfo.visibility = View.GONE
        binding.containerFlattenControls.visibility = View.GONE
        binding.containerFlattenEmptyState.visibility = View.VISIBLE
        binding.btnPdfFlattenReset.visibility = View.GONE
        binding.ivFlattenPreview.setImageDrawable(null)
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
