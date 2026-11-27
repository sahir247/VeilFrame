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
import com.veilframe.app.pdf.operations.OcrLanguage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.pdf.operations.TextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private var generation = 0L
    private var scriptDialog: androidx.appcompat.app.AlertDialog? = null

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = extractedTextContent.isNotEmpty() && !isSaved
    override val isProcessing: Boolean get() = isExtracting

    override fun discardAndReleaseAllResources() {
        generation++
        scriptDialog?.dismiss()
        scriptDialog = null
        extractionJob?.cancel()
        activePdfUri = null
        activePdfName = "document.pdf"
        extractedTextContent = ""
        isExtracting = false
        isSaved = false
        binding.containerExtractTextDocInfo.visibility = View.GONE
        binding.containerExtractTextLoading.visibility = View.GONE
        binding.cardOcrPromptBanner.visibility = View.GONE
        binding.containerExtractTextResults.visibility = View.GONE
        binding.containerExtractTextEmptyState.visibility = View.VISIBLE
        binding.btnPdfExtractTextReset.visibility = View.GONE
        binding.tvExtractedContent.text = ""
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
        scriptDialog?.dismiss()
        scriptDialog = null
        activePdfUri = uri
        extractedTextContent = ""
        binding.tvExtractedContent.text = ""
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
        val owner = ++generation
        extractionJob?.cancel()
        isExtracting = true
        binding.containerExtractTextLoading.visibility = View.VISIBLE
        binding.tvExtractTextLoadingStatus.text = "Extracting embedded text streams..."
        binding.cardOcrPromptBanner.visibility = View.GONE
        binding.containerExtractTextResults.visibility = View.GONE

        extractionJob = scope.launch(Dispatchers.IO) {
            val result = textExtractor.extractText(activity, uri)
            withContext(Dispatchers.Main) {
                if (owner != generation || activePdfUri != uri) return@withContext
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

    private fun chooseOcrScript() {
        val uri = activePdfUri ?: return
        if (isExtracting) return
        val owner = generation
        val scripts = OcrLanguage.entries
        scriptDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Choose OCR script")
            .setItems(scripts.map { it.displayName }.toTypedArray()) { _, index ->
                if (owner == generation && activePdfUri == uri) runInPlaceMlKitOcr(scripts[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
        scriptDialog = dialog
        dialog.setOnDismissListener { if (scriptDialog === dialog) scriptDialog = null }
    }

    private fun runInPlaceMlKitOcr(language: OcrLanguage) {
        val uri = activePdfUri ?: return
        val owner = ++generation
        extractionJob?.cancel()
        extractedTextContent = ""
        isSaved = false
        binding.tvExtractedContent.text = ""
        binding.containerExtractTextResults.visibility = View.GONE
        isExtracting = true
        binding.containerExtractTextLoading.visibility = View.VISIBLE
        binding.tvExtractTextLoadingStatus.text = "Preparing ${language.displayName} OCR..."
        binding.cardOcrPromptBanner.visibility = View.GONE

        extractionJob = scope.launch(Dispatchers.IO) {
            val fullTextBuilder = StringBuilder()
            // Each job owns its selected recognizer until native recognition finishes.
            val ocrEngine = OcrEngine(activity, language)
            try {
                val input = activity.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot open PDF input stream")
                input.use { inputStream ->
                    PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        val renderer = PDFRenderer(doc)
                        val total = doc.numberOfPages
                        for (i in 0 until total) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            withContext(Dispatchers.Main) {
                                if (owner != generation || activePdfUri != uri) return@withContext
                                binding.tvExtractTextLoadingStatus.text =
                                    "${language.displayName}: page ${i + 1} of $total..."
                            }
                            val box = doc.getPage(i).cropBox
                            val dpi = com.veilframe.app.pdf.operations.OcrDpiUtil.getSafeOcrDpi(box.width, box.height)
                            val pageBmp = renderer.renderImageWithDPI(i, dpi)
                            val pageText = try {
                                ocrEngine.recognizeFullText(pageBmp)
                            } finally {
                                pageBmp.recycle()
                            }

                            if (pageText.isNotBlank()) {
                                check(pageText.length.toLong() + fullTextBuilder.length + 64 <= 8L * 1024 * 1024) {
                                    "OCR text exceeds 8 million character budget; extract a smaller PDF"
                                }
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
                    if (owner != generation || activePdfUri != uri) return@withContext
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (owner != generation || activePdfUri != uri) return@withContext
                    isExtracting = false
                    binding.containerExtractTextLoading.visibility = View.GONE
                    binding.cardOcrPromptBanner.visibility = View.VISIBLE
                    Toast.makeText(activity, "OCR Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                ocrEngine.close()
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
            chooseOcrScript()
        }

        binding.btnForceMlKitOcr.setOnClickListener {
            chooseOcrScript()
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
