package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutPdfExtractPagesBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.ImageConverter
import com.veilframe.app.pdf.operations.ImageFormat
import com.veilframe.app.pdf.operations.PdfOrganizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Controller orchestrating PDF page extraction with the dual twist:
 * 1. Extract selected pages as a brand new standalone PDF document.
 * 2. Extract selected pages directly into high-res images (WebP, PNG, JPEG) with DPI scaling.
 */
class PdfExtractPagesController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfExtractPagesBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSaveFileRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var totalPages: Int = 1
    private var isExtracting: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null

    private val pdfOrganizer by lazy { PdfOrganizer() }

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = activePdfUri != null && !isSaved
    override val isProcessing: Boolean get() = isExtracting

    override fun discardAndReleaseAllResources() {
        activeJob?.cancel()
        activePdfUri = null
        isExtracting = false
    }

    fun init() {
        setupToolbar()
        setupPicker()
        setupFormatToggle()
        setupPresetChips()
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
                            val defaultRange = if (totalPages > 1) "1-${minOf(3, totalPages)}" else "1"
                            binding.etExtractPagesRange.setText(defaultRange)
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
            binding.etExtractPagesRange.setText("1-$totalPages")
        }
        binding.chipPresetFirst.setOnClickListener {
            binding.etExtractPagesRange.setText("1")
        }
        binding.chipPresetOdd.setOnClickListener {
            val odd = (1..totalPages step 2).joinToString(", ")
            binding.etExtractPagesRange.setText(odd)
        }
        binding.chipPresetEven.setOnClickListener {
            val even = (2..totalPages step 2).joinToString(", ")
            binding.etExtractPagesRange.setText(if (even.isEmpty()) "1" else even)
        }
    }

    private fun parseSelectedPages(input: String): List<Int> {
        val result = mutableSetOf<Int>()
        val parts = input.split(",").map { it.trim() }

        for (part in parts) {
            if (part.contains("-")) {
                val rangeParts = part.split("-").map { it.trim() }
                if (rangeParts.size == 2) {
                    val start = rangeParts[0].toIntOrNull() ?: continue
                    val end = rangeParts[1].toIntOrNull() ?: continue
                    for (p in minOf(start, end)..maxOf(start, end)) {
                        if (p in 1..totalPages) result.add(p)
                    }
                }
            } else {
                val p = part.toIntOrNull()
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
            val rangeText = binding.etExtractPagesRange.text?.toString()?.trim() ?: ""
            val selectedPages = parseSelectedPages(rangeText)

            if (selectedPages.isEmpty()) {
                Toast.makeText(activity, "Please enter a valid page range (1 to $totalPages)", Toast.LENGTH_SHORT).show()
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
                        // Extract as PDF
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
                        // Extract as Images
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
                                    // Single image export
                                    val (num, bytes) = compressedImages.first()
                                    val exportName = "${base}_page_${num}.$ext"
                                    onSaveFileRequest(bytes, exportName)
                                } else {
                                    // Multiple images: zip archive
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
