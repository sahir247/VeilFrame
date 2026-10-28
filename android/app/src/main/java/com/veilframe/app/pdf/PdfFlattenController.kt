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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller orchestrating interactive AcroForm & annotation flattening with
 * real-time Before vs After live visual comparison and document export.
 */
class PdfFlattenController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfFlattenBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSavePdfRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

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

            scope.launch(Dispatchers.IO) {
                try {
                    val cacheDir = File(activity.cacheDir, "pdf_flatten_temp")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val tempOutFile = File(cacheDir, "flattened_${System.currentTimeMillis()}.pdf")
                    val outUri = Uri.fromFile(tempOutFile)

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

                    withContext(Dispatchers.Main) {
                        isFlattening = false
                        binding.btnFlattenExecute.isEnabled = true
                        binding.btnFlattenExecute.text = "Flatten & Save PDF"

                        if (result.success && tempOutFile.exists()) {
                            isSaved = true
                            val bytes = tempOutFile.readBytes()
                            tempOutFile.delete()
                            val base = activePdfName.removeSuffix(".pdf")
                            val exportName = "${base}_flattened.pdf"
                            onSavePdfRequest(bytes, exportName)
                        } else {
                            tempOutFile.delete()
                            val err = result.errorMessage ?: "Failed to flatten PDF"
                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isFlattening = false
                        binding.btnFlattenExecute.isEnabled = true
                        binding.btnFlattenExecute.text = "Flatten & Save PDF"
                        Toast.makeText(activity, "Flatten failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun onPdfSelected(uri: Uri) {
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
        previewJob = scope.launch(Dispatchers.IO) {
            try {
                val inputStream = activity.contentResolver.openInputStream(uri) ?: return@launch
                val document = PDDocument.load(inputStream)
                inputStream.close()

                val pageCount = document.numberOfPages
                val acroForm = document.documentCatalog.acroForm
                val fields = acroForm?.fields?.size ?: 0
                formFieldCount = fields

                val renderer = PDFRenderer(document)
                // Render before bitmap
                val before = renderer.renderImageWithDPI(0, 150f)

                // Render simulated flattened after bitmap
                acroForm?.flatten()
                val after = renderer.renderImageWithDPI(0, 150f)

                document.close()

                withContext(Dispatchers.Main) {
                    binding.pbFlattenLoading.visibility = View.GONE
                    beforeBitmap = before
                    afterBitmap = after

                    binding.tvFlattenDocMeta.text = "$pageCount pages • $fields form fields detected"
                    binding.tvFlattenSummaryDesc.text =
                        if (fields > 0) "$fields interactive AcroForm fields detected. Flattening will bake them permanently into page streams."
                        else "No form fields detected. Annotations and markups will be permanently flattened."

                    // Default to showing Before
                    showBeforePreview()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbFlattenLoading.visibility = View.GONE
                    binding.tvFlattenDocMeta.text = "Error rendering preview: ${e.message}"
                }
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
