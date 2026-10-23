package com.veilframe.app.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.veilframe.app.databinding.LayoutPdfExtractTextBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.OcrEngine
import com.veilframe.app.pdf.operations.TextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Controller orchestrating embedded PDF text stream extraction, inline Google ML Kit OCR prompt
 * when documents contain flat/scanned imagery, in-place optical recognition, and TXT export.
 */
class PdfExtractTextController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfExtractTextBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSaveTextRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var extractedTextContent: String = ""
    private var extractionJob: Job? = null
    private var isExtracting: Boolean = false
    private var isSaved: Boolean = false

    private val textExtractor by lazy { TextExtractor() }
    private val ocrEngine by lazy { OcrEngine(activity) }

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = extractedTextContent.isNotEmpty() && !isSaved
    override val isProcessing: Boolean get() = isExtracting

    override fun discardAndReleaseAllResources() {
        extractionJob?.cancel()
        activePdfUri = null
        extractedTextContent = ""
        isExtracting = false
    }

    fun init() {
        setupToolbar()
        setupPicker()
        setupActions()
    }

    private fun setupToolbar() {
        binding.toolbarPdfExtractText.setNavigationOnClickListener {
            onNavigateBack()
        }
        binding.btnPdfExtractTextReset.setOnClickListener {
            resetWorkspace()
        }
    }

    private fun setupPicker() {
        binding.cardExtractTextPicker.setOnClickListener {
            if (activePdfUri == null) {
                onPickPdfRequest()
            }
        }
        binding.btnExtractTextChangeDoc.setOnClickListener {
            onPickPdfRequest()
        }
    }

    fun handlePdfSelected(uri: Uri) {
        activePdfUri = uri
        isSaved = false
        val docName = queryDocumentName(uri)
        activePdfName = docName

        binding.containerExtractTextEmptyState.visibility = View.GONE
        binding.containerExtractTextDocInfo.visibility = View.VISIBLE
        binding.btnPdfExtractTextReset.visibility = View.VISIBLE
        binding.tvExtractTextDocName.text = docName
        binding.tvExtractTextDocMeta.text = "Reading stream metadata..."

        runEmbeddedTextExtraction(uri)
    }

    private fun runEmbeddedTextExtraction(uri: Uri) {
        extractionJob?.cancel()
        isExtracting = true
        binding.containerExtractTextLoading.visibility = View.VISIBLE
        binding.tvExtractTextLoadingStatus.text = "Extracting embedded text streams..."
        binding.cardOcrPromptBanner.visibility = View.GONE
        binding.containerExtractTextResults.visibility = View.GONE

        extractionJob = scope.launch(Dispatchers.IO) {
            val result = textExtractor.extractText(activity, uri)
            withContext(Dispatchers.Main) {
                isExtracting = false
                binding.containerExtractTextLoading.visibility = View.GONE

                if (result.isSuccess) {
                    val data = result.getOrNull()
                    val text = data?.text?.trim() ?: ""
                    val pageCount = data?.pageCount ?: 1
                    val charCount = data?.characterCount ?: 0
                    val wordCount = data?.wordCount ?: 0

                    binding.tvExtractTextDocMeta.text = "$pageCount pages • Ready"

                    if (text.isEmpty() || charCount == 0) {
                        // TWIST: Inline OCR Prompt Banner on the exact same page!
                        extractedTextContent = ""
                        binding.cardOcrPromptBanner.visibility = View.VISIBLE
                        binding.containerExtractTextResults.visibility = View.GONE
                    } else {
                        // Embedded text exists
                        extractedTextContent = text
                        displayResults(text, wordCount, charCount)
                    }
                } else {
                    Toast.makeText(activity, "Failed to read text: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runInPlaceMlKitOcr() {
        val uri = activePdfUri ?: return
        extractionJob?.cancel()
        isExtracting = true
        binding.containerExtractTextLoading.visibility = View.VISIBLE
        binding.tvExtractTextLoadingStatus.text = "Rendering pages & running Google ML Kit OCR..."
        binding.cardOcrPromptBanner.visibility = View.GONE

        extractionJob = scope.launch(Dispatchers.IO) {
            val fullTextBuilder = StringBuilder()
            try {
                activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                    PDDocument.load(inputStream).use { doc ->
                        val renderer = PDFRenderer(doc)
                        val total = doc.numberOfPages
                        for (i in 0 until total) {
                            withContext(Dispatchers.Main) {
                                binding.tvExtractTextLoadingStatus.text =
                                    "Google ML Kit OCR: Processing page ${i + 1} of $total..."
                            }
                            val pageBmp = renderer.renderImageWithDPI(i, 150f)
                            val pageText = ocrEngine.recognizeFullText(pageBmp)
                            pageBmp.recycle()

                            if (pageText.isNotBlank()) {
                                if (fullTextBuilder.isNotEmpty()) {
                                    fullTextBuilder.append("\n\n--- Page ${i + 1} ---\n\n")
                                }
                                fullTextBuilder.append(pageText)
                            }
                        }
                    }
                }

                val finalOcrText = fullTextBuilder.toString().trim()
                withContext(Dispatchers.Main) {
                    isExtracting = false
                    binding.containerExtractTextLoading.visibility = View.GONE

                    if (finalOcrText.isEmpty()) {
                        Toast.makeText(activity, "Google ML Kit OCR did not detect any readable text.", Toast.LENGTH_LONG).show()
                        binding.cardOcrPromptBanner.visibility = View.VISIBLE
                    } else {
                        extractedTextContent = finalOcrText
                        val words = finalOcrText.split("\\s+".toRegex()).count { it.isNotBlank() }
                        displayResults(finalOcrText, words, finalOcrText.length)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isExtracting = false
                    binding.containerExtractTextLoading.visibility = View.GONE
                    Toast.makeText(activity, "OCR Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayResults(text: String, wordCount: Int, charCount: Int) {
        binding.cardOcrPromptBanner.visibility = View.GONE
        binding.containerExtractTextResults.visibility = View.VISIBLE
        binding.tvExtractTextStats.text = "$wordCount words • $charCount chars"
        binding.tvExtractedContent.text = text
    }

    private fun setupActions() {
        binding.btnRunOcrFallback.setOnClickListener {
            runInPlaceMlKitOcr()
        }

        binding.btnForceMlKitOcr.setOnClickListener {
            runInPlaceMlKitOcr()
        }

        binding.btnExtractTextCopy.setOnClickListener {
            if (extractedTextContent.isEmpty()) {
                Toast.makeText(activity, "No text to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Extracted PDF Text", extractedTextContent)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, "Copied ${extractedTextContent.length} characters to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnExtractTextSaveTxt.setOnClickListener {
            if (extractedTextContent.isEmpty()) {
                Toast.makeText(activity, "No text to save", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isSaved = true
            val base = activePdfName.removeSuffix(".pdf")
            val exportName = "${base}_extracted.txt"
            val textBytes = extractedTextContent.toByteArray(StandardCharsets.UTF_8)
            onSaveTextRequest(textBytes, exportName)
        }
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerExtractTextDocInfo.visibility = View.GONE
        binding.containerExtractTextLoading.visibility = View.GONE
        binding.cardOcrPromptBanner.visibility = View.GONE
        binding.containerExtractTextResults.visibility = View.GONE
        binding.containerExtractTextEmptyState.visibility = View.VISIBLE
        binding.btnPdfExtractTextReset.visibility = View.GONE
        binding.tvExtractedContent.text = ""
    }

    private fun queryDocumentName(uri: Uri): String {
        var name = "document.pdf"
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
        } catch (_: Exception) {}
        return name
    }
}
