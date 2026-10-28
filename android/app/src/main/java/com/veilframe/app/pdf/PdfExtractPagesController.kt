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
import java.io.ByteArrayOutputStream
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
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

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
        activePdfUri = uri
        isSaved = false
        val docName = queryDocumentName(uri)
        activePdfName = docName

        binding.containerExtractPagesEmptyState.visibility = View.GONE
        binding.containerExtractPagesDocInfo.visibility = View.VISIBLE
        binding.btnPdfExtractPagesReset.visibility = View.VISIBLE
        binding.tvExtractPagesDocName.text = docName
        binding.tvExtractPagesDocMeta.text = "Reading document pages..."

        scope.launch(Dispatchers.IO) {
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

            val isImageMode = binding.chipFormatImages.isChecked

            activeJob = scope.launch(Dispatchers.IO) {
                try {
                    val base = activePdfName.removeSuffix(".pdf")
                    if (!isImageMode) {
                        val outStream = ByteArrayOutputStream()
                        val result = pdfOrganizer.extractPagesAsPdf(
                            context = activity,
                            inputUri = uri,
                            outputStream = outStream,
                            pagesToExtract = selectedPages
                        )

                        withContext(Dispatchers.Main) {
                            isExtracting = false
                            binding.btnExtractPagesExecute.isEnabled = true
                            binding.btnExtractPagesExecute.text = "Extract as PDF & Export"

                            if (result.isSuccess) {
                                isSaved = true
                                val exportName = "${base}_extracted_pages.pdf"
                                onSaveFileRequest(outStream.toByteArray(), exportName)
                            } else {
                                Toast.makeText(activity, "Failed to extract PDF: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
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

                        val compressedImages = mutableListOf<Pair<Int, ByteArray>>()
                        val result = pdfOrganizer.extractPagesAsImages(
                            context = activity,
                            inputUri = uri,
                            pagesToExtract = selectedPages,
                            format = format,
                            dpi = dpi,
                            outputCallback = { pageNum, bmp ->
                                val out = ByteArrayOutputStream()
                                val compressFormat = when (format) {
                                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                                    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                                    ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
                                }
                                bmp.compress(compressFormat, 90, out)
                                compressedImages.add(pageNum to out.toByteArray())
                            }
                        )

                        withContext(Dispatchers.Main) {
                            isExtracting = false
                            binding.btnExtractPagesExecute.isEnabled = true
                            binding.btnExtractPagesExecute.text = "Extract as Images & Export"

                            if (result.isSuccess && compressedImages.isNotEmpty()) {
                                isSaved = true
                                val ext = when (format) {
                                    ImageFormat.PNG -> "png"
                                    ImageFormat.JPEG -> "jpg"
                                    ImageFormat.WEBP -> "webp"
                                }

                                if (compressedImages.size == 1) {
                                    val (num, bytes) = compressedImages.first()
                                    val exportName = "${base}_page_${num}.$ext"
                                    onSaveFileRequest(bytes, exportName)
                                } else {
                                    val zipOut = ByteArrayOutputStream()
                                    ZipOutputStream(zipOut).use { zos ->
                                        for ((num, bytes) in compressedImages) {
                                            val entry = ZipEntry("${base}_page_${num}.$ext")
                                            zos.putNextEntry(entry)
                                            zos.write(bytes)
                                            zos.closeEntry()
                                        }
                                    }
                                    val exportName = "${base}_extracted_images.zip"
                                    onSaveFileRequest(zipOut.toByteArray(), exportName)
                                }
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Unknown rendering error"
                                Toast.makeText(activity, "Failed to render images: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isExtracting = false
                        binding.btnExtractPagesExecute.isEnabled = true
                        binding.btnExtractPagesExecute.text = "Extract & Export"
                        Toast.makeText(activity, "Extraction failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
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
