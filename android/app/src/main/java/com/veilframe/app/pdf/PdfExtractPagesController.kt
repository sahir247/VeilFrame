package com.veilframe.app.pdf

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutPdfExtractPagesBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.ImageFormat
import com.veilframe.app.pdf.operations.PdfOrganizer
import com.veilframe.app.pdf.ui.PdfPageThumbnailAdapter
import com.veilframe.app.pdf.ui.PdfThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Controller orchestrating visual PDF Page Extraction (to new PDF or images).
 * Features real-time page thumbnail rendering, visual multi-selection, preset ranges,
 * and lossless image compression export.
 */
class PdfExtractPagesController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfExtractPagesBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSaveFileRequest: (data: ByteArray, fileName: String) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onSaveFileFromDiskRequest: ((File, String) -> Unit)? = null
) : ToolWorkStateContract {

    private val taskTracker = PdfTaskTracker(scope)

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var totalPages: Int = 1
    private var isExtracting: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null
    private var isInternalRangeUpdate = false

    private val pdfOrganizer by lazy { PdfOrganizer() }
    private val thumbnailLoader by lazy { PdfThumbnailLoader(activity) }
    private var thumbnailAdapter: PdfPageThumbnailAdapter? = null

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = activePdfUri != null && !isSaved
    override val isProcessing: Boolean get() = isExtracting

    override fun discardAndReleaseAllResources() {
        taskTracker.cancelAll()
        activeJob?.cancel()
        activePdfUri = null
        activePdfName = "document.pdf"
        isExtracting = false
        isSaved = false
        thumbnailLoader.clearCache()
        thumbnailAdapter?.clear()
        binding.containerExtractPagesDocInfo.visibility = View.GONE
        binding.containerExtractPagesConfig.visibility = View.GONE
        binding.containerExtractPagesEmptyState.visibility = View.VISIBLE
        binding.btnPdfExtractPagesReset.visibility = View.GONE
    }

    fun init() {
        setupToolbar()
        setupPicker()
        setupThumbnailGrid()
        setupFormatToggle()
        setupPresetChips()
        setupRangeTextWatcher()
        setupExecuteButton()
    }

    private fun setupToolbar() {
        binding.toolbarPdfExtractPages.setNavigationOnClickListener {
            onNavigateBack()
        }
        binding.btnPdfExtractPagesReset.setOnClickListener {
            resetWorkspace()
        }
    }

    private fun setupPicker() {
        binding.cardExtractPagesPicker.setOnClickListener {
            if (activePdfUri == null) {
                onPickPdfRequest()
            }
        }
        binding.btnExtractPagesChangeDoc.setOnClickListener {
            onPickPdfRequest()
        }
    }

    private fun setupThumbnailGrid() {
        thumbnailAdapter = PdfPageThumbnailAdapter(
            context = activity,
            scope = scope,
            thumbnailLoader = thumbnailLoader,
            onPageSelectionChanged = { selectedPages ->
                onAdapterSelectionChanged(selectedPages)
            }
        )
        binding.rvExtractPagesThumbnails.layoutManager = GridLayoutManager(activity, 3)
        binding.rvExtractPagesThumbnails.adapter = thumbnailAdapter

        binding.btnExtractSelectAll.setOnClickListener {
            thumbnailAdapter?.selectAll()
        }
        binding.btnExtractDeselectAll.setOnClickListener {
            thumbnailAdapter?.deselectAll()
        }
    }

    private fun onAdapterSelectionChanged(selectedPages: Set<Int>) {
        binding.tvExtractSelectionCount.text = "${selectedPages.size} of $totalPages selected"
        if (!isInternalRangeUpdate) {
            isInternalRangeUpdate = true
            val formattedRange = formatPagesToRangeString(selectedPages)
            binding.etExtractPagesRange.setText(formattedRange)
            isInternalRangeUpdate = false
        }
    }

    private fun setupRangeTextWatcher() {
        binding.etExtractPagesRange.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInternalRangeUpdate) {
                    val text = s?.toString().orEmpty()
                    val pages = parseSelectedPages(text).toSet()
                    isInternalRangeUpdate = true
                    thumbnailAdapter?.selectPages(pages)
                    binding.tvExtractSelectionCount.text = "${pages.size} of $totalPages selected"
                    isInternalRangeUpdate = false
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun handlePdfSelected(uri: Uri) {
        taskTracker.cancelAll()
        activeJob = null
        isExtracting = false
        totalPages = 1
        binding.btnExtractPagesExecute.isEnabled = true
        binding.btnExtractPagesExecute.text = "Extract & Export"
        activePdfUri = uri
        isSaved = false
        val docName = queryDocumentName(uri)
        activePdfName = docName

        binding.containerExtractPagesEmptyState.visibility = View.GONE
        binding.containerExtractPagesDocInfo.visibility = View.VISIBLE
        binding.btnPdfExtractPagesReset.visibility = View.VISIBLE
        binding.tvExtractPagesDocName.text = docName
        binding.tvExtractPagesDocMeta.text = "Reading document pages..."

        taskTracker.launch(Dispatchers.IO) {
            try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        totalPages = doc.numberOfPages
                        withContext(Dispatchers.Main) {
                            binding.tvExtractPagesDocMeta.text = "$totalPages pages total"
                            binding.containerExtractPagesConfig.visibility = View.VISIBLE
                            val initialPages = if (totalPages > 1) (1..minOf(3, totalPages)).toSet() else setOf(1)
                            thumbnailAdapter?.setDocument(uri, totalPages, initialPages)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Failed to inspect PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    resetWorkspace()
                }
            }
        }
    }

    private fun setupFormatToggle() {
        binding.chipGroupExtractFormat.setOnCheckedStateChangeListener { _, checkedIds ->
            val isImages = checkedIds.contains(R.id.chipFormatImages)
            binding.containerExtractImageOptions.visibility = if (isImages) View.VISIBLE else View.GONE
            binding.btnExtractPagesExecute.text = if (isImages) "Extract as Images & Export" else "Extract as PDF & Export"
        }
    }

    private fun setupPresetChips() {
        binding.chipPresetAll.setOnClickListener {
            thumbnailAdapter?.selectAll()
        }
        binding.chipPresetFirst.setOnClickListener {
            thumbnailAdapter?.selectPages(setOf(1))
        }
        binding.chipPresetOdd.setOnClickListener {
            val odd = (1..totalPages step 2).toSet()
            thumbnailAdapter?.selectPages(odd)
        }
        binding.chipPresetEven.setOnClickListener {
            val even = (2..totalPages step 2).toSet()
            thumbnailAdapter?.selectPages(even)
        }
    }

    private fun formatPagesToRangeString(pages: Set<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.sorted()
        val ranges = mutableListOf<String>()
        var start = sorted[0]
        var end = start

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            if (current == end + 1) {
                end = current
            } else {
                ranges.add(if (start == end) "$start" else "$start-$end")
                start = current
                end = current
            }
        }
        ranges.add(if (start == end) "$start" else "$start-$end")
        return ranges.joinToString(", ")
    }

    private fun parseSelectedPages(rangeInput: String): List<Int> {
        val result = mutableSetOf<Int>()
        if (rangeInput.isBlank()) return emptyList()

        val parts = rangeInput.split(",", ";", " ")
        for (part in parts) {
            val clean = part.trim()
            if (clean.isEmpty()) continue
            if (clean.contains("-")) {
                val rangeParts = clean.split("-")
                if (rangeParts.size == 2) {
                    val start = rangeParts[0].toIntOrNull() ?: continue
                    val end = rangeParts[1].toIntOrNull() ?: continue
                    for (p in minOf(start, end)..maxOf(start, end)) {
                        if (p in 1..totalPages) result.add(p)
                    }
                }
            } else {
                val p = clean.toIntOrNull()
                if (p != null && p in 1..totalPages) {
                    result.add(p)
                }
            }
        }
        return result.sorted()
    }

    private fun setupExecuteButton() {
        binding.btnExtractPagesExecute.setOnClickListener {
            val uri = activePdfUri ?: return@setOnClickListener
            val selectedPages = thumbnailAdapter?.getSelectedPages()?.toList()?.sorted()
                ?: parseSelectedPages(binding.etExtractPagesRange.text?.toString() ?: "")

            if (selectedPages.isEmpty()) {
                Toast.makeText(activity, "Please select at least one page to extract", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isExtracting) return@setOnClickListener
            isExtracting = true
            binding.btnExtractPagesExecute.isEnabled = false
            binding.btnExtractPagesExecute.text = "Extracting..."

            // Snapshot UI state before entering Dispatchers.IO.
            val isImageMode = binding.chipFormatImages.isChecked
            val format = when (binding.chipGroupImageCodec.checkedChipId) {
                R.id.chipCodecPng -> ImageFormat.PNG
                R.id.chipCodecJpeg -> ImageFormat.JPEG
                else -> ImageFormat.WEBP
            }
            val dpi = when (binding.chipGroupImageDpi.checkedChipId) {
                R.id.chipDpi72 -> 72
                R.id.chipDpi300 -> 300
                else -> 150
            }
            val base = activePdfName.removeSuffix(".pdf")

            activeJob = taskTracker.launch(Dispatchers.IO) {
                var outputFile: File? = null
                var handedOff = false
                try {
                    val extension = when (format) {
                        ImageFormat.PNG -> "png"
                        ImageFormat.JPEG -> "jpg"
                        ImageFormat.WEBP -> "webp"
                    }
                    val compressFormat = when (format) {
                        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                        ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
                    }
                    val exportName: String
                    val success: Boolean
                    val generated: File
                    if (!isImageMode) {
                        generated = File.createTempFile("extracted_pages_", ".pdf", activity.cacheDir)
                        outputFile = generated
                        success = generated.outputStream().buffered().use { stream ->
                            pdfOrganizer.extractPagesAsPdf(
                                context = activity,
                                inputUri = uri,
                                outputStream = stream,
                                pagesToExtract = selectedPages
                            ).isSuccess
                        }
                        exportName = "${base}_extracted_pages.pdf"
                    } else if (selectedPages.size == 1) {
                        generated = File.createTempFile("extracted_page_", ".$extension", activity.cacheDir)
                        outputFile = generated
                        success = generated.outputStream().buffered().use { stream ->
                            pdfOrganizer.extractPagesAsImages(
                                context = activity,
                                inputUri = uri,
                                pagesToExtract = selectedPages,
                                format = format,
                                dpi = dpi,
                                outputCallback = { pageNumber, bitmap ->
                                    check(bitmap.compress(compressFormat, 90, stream)) {
                                        "Unable to compress extracted page $pageNumber"
                                    }
                                }
                            ).isSuccess
                        }
                        exportName = "${base}_page_${selectedPages.first()}.$extension"
                    } else {
                        generated = File.createTempFile("extracted_images_", ".zip", activity.cacheDir)
                        outputFile = generated
                        success = ZipOutputStream(generated.outputStream().buffered()).use { zip ->
                            pdfOrganizer.extractPagesAsImages(
                                context = activity,
                                inputUri = uri,
                                pagesToExtract = selectedPages,
                                format = format,
                                dpi = dpi,
                                outputCallback = { pageNumber, bitmap ->
                                    zip.putNextEntry(ZipEntry("${base}_page_$pageNumber.$extension"))
                                    check(bitmap.compress(compressFormat, 90, zip)) {
                                        "Unable to compress extracted page $pageNumber"
                                    }
                                    zip.closeEntry()
                                }
                            ).isSuccess
                        }
                        exportName = "${base}_extracted_images.zip"
                    }

                    val complete = success && generated.isFile && generated.length() > 0L
                    // Old consumers retain the byte-array API; the app never materializes
                    // these files on the heap because it supplies the disk-save callback.
                    val legacyBytes = if (complete && onSaveFileFromDiskRequest == null) {
                        generated.readBytes()
                    } else null

                    withContext(Dispatchers.Main) {
                        isExtracting = false
                        binding.btnExtractPagesExecute.isEnabled = true
                        binding.btnExtractPagesExecute.text = if (isImageMode) {
                            "Extract as Images & Export"
                        } else {
                            "Extract as PDF & Export"
                        }
                        if (complete) {
                            val diskSave = onSaveFileFromDiskRequest
                            if (diskSave != null) {
                                diskSave(generated, exportName)
                                handedOff = true
                            } else {
                                onSaveFileRequest(requireNotNull(legacyBytes), exportName)
                            }
                            isSaved = true
                        } else {
                            Toast.makeText(activity, "Unable to extract pages", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        isExtracting = false
                        binding.btnExtractPagesExecute.isEnabled = true
                        binding.btnExtractPagesExecute.text = "Extract & Export"
                        Toast.makeText(activity, "Extraction failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (!handedOff) outputFile?.delete()
                }
            }
        }
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerExtractPagesDocInfo.visibility = View.GONE
        binding.containerExtractPagesConfig.visibility = View.GONE
        binding.containerExtractPagesEmptyState.visibility = View.VISIBLE
        binding.btnPdfExtractPagesReset.visibility = View.GONE
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
