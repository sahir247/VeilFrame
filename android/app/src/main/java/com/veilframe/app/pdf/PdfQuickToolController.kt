package com.veilframe.app.pdf

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.R
import com.veilframe.app.databinding.ItemPdfMergeFileBinding
import com.veilframe.app.databinding.LayoutPdfQuickToolBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.HtmlToPdfConverter
import com.veilframe.app.pdf.operations.ImageConverter
import com.veilframe.app.pdf.operations.ImageFormat
import com.veilframe.app.pdf.operations.OfficeConverter
import com.veilframe.app.pdf.operations.PageRange
import com.veilframe.app.pdf.operations.PdfCompressor
import com.veilframe.app.pdf.operations.PdfFormFiller
import com.veilframe.app.pdf.operations.PdfMerger
import com.veilframe.app.pdf.operations.PdfMetadataManager
import com.veilframe.app.pdf.operations.PdfOrganizer
import com.veilframe.app.pdf.operations.PdfRotator
import com.veilframe.app.pdf.operations.PdfSecurityManager
import com.veilframe.app.pdf.operations.PdfSecurityOptions
import com.veilframe.app.pdf.operations.PdfSigner
import com.veilframe.app.pdf.operations.PdfSplitter
import com.veilframe.app.pdf.operations.RotationAngle
import com.veilframe.app.pdf.operations.SignaturePlacement
import com.veilframe.app.pdf.ui.PdfPageThumbnailAdapter
import com.veilframe.app.pdf.ui.PdfThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Controller managing specialized single-action quick tools in the PDF Suite:
 * Merge, Split, Compress (dual engine), Rotate, Reorder, Delete, Lock, Sign (touch Bézier),
 * Form Filling, HTML/Office to PDF, and Metadata Sanitization.
 * Enhanced with interactive page previews, merge reordering, and visual split/delete controls.
 */
class PdfQuickToolController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfQuickToolBinding,
    private val scope: CoroutineScope,
    private val onPickFileRequest: (mimeType: String, multiple: Boolean) -> Unit,
    private val onSaveFileRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    enum class QuickMode(val title: String, val subtitle: String, val buttonText: String) {
        MERGE("Merge PDF", "Combine multiple documents into one", "Merge & Export PDF"),
        SPLIT("Split PDF", "Extract pages into separate documents", "Split & Export"),
        COMPRESS("Compress PDF", "Dual-engine smart size reduction", "Compress & Export PDF"),
        ROTATE("Rotate Pages", "Modulo 90°, 180°, 270° orientation fix", "Rotate & Export PDF"),
        REORDER("Reorder Pages", "Rearrange page sequence order", "Reorder & Export PDF"),
        DELETE("Delete Pages", "Purge unwanted sheets from document", "Delete Pages & Export"),
        LOCK("Lock PDF", "256-bit AES encryption & permissions", "Encrypt & Save PDF"),
        SIGN("Sign PDF", "Touch Bézier canvas signature stamping", "Stamp Signature & Export"),
        FILL_FORMS("Fill Forms", "Interactive AcroForm values editing", "Apply Form Values & Export"),
        HTML_TO_PDF("HTML to PDF", "Convert web page or HTML to PDF", "Render HTML to PDF"),
        DOC_TO_PDF("DOC to PDF", "Offline DOCX & XLSX conversion", "Convert Office to PDF"),
        PDF_TO_IMAGE("PDF to Image", "Batch export pages to WebP/PNG/JPG", "Export Page Images"),
        IMAGE_TO_PDF("Image to PDF", "Compile gallery photos into PDF", "Compile PDF"),
        METADATA("PDF Metadata", "Audit & scrub hidden XMP/Info tags", "Sanitize & Save PDF")
    }

    private var activeMode: QuickMode = QuickMode.MERGE
    private val selectedUris = mutableListOf<Uri>()
    private var primaryDocName: String = "document.pdf"
    private var primaryDocPages: Int = 1
    private var isExecuting: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null

    private val pdfMerger by lazy { PdfMerger() }
    private val pdfSplitter by lazy { PdfSplitter() }
    private val pdfCompressor by lazy { PdfCompressor() }
    private val pdfRotator by lazy { PdfRotator() }
    private val pdfOrganizer by lazy { PdfOrganizer() }
    private val pdfSecurityManager by lazy { PdfSecurityManager() }
    private val pdfSigner by lazy { PdfSigner(activity) }
    private val pdfFormFiller by lazy { PdfFormFiller() }
    private val htmlConverter by lazy { HtmlToPdfConverter() }
    private val officeConverter by lazy { OfficeConverter() }
    private val imageConverter by lazy { ImageConverter() }
    private val metadataManager by lazy { PdfMetadataManager() }
    private val thumbnailLoader by lazy { PdfThumbnailLoader(activity) }

    private var splitThumbnailAdapter: PdfPageThumbnailAdapter? = null
    private var rotateThumbnailAdapter: PdfPageThumbnailAdapter? = null

    override fun hasPickedMedia(): Boolean = selectedUris.isNotEmpty()
    override fun hasUnsavedWork(): Boolean = selectedUris.isNotEmpty() && !isSaved
    override val isProcessing: Boolean get() = isExecuting

    override fun discardAndReleaseAllResources() {
        activeJob?.cancel()
        selectedUris.clear()
        isExecuting = false
        thumbnailLoader.clearCache()
    }

    fun init() {
        setupToolbar()
        setupPicker()
        setupCompressControls()
        setupSplitControls()
        setupRotateControls()
        setupSignControls()
        setupExecuteButton()
    }

    fun setMode(mode: QuickMode) {
        activeMode = mode
        resetWorkspace()

        binding.toolbarPdfQuickTool.title = mode.title
        binding.toolbarPdfQuickTool.subtitle = mode.subtitle
        binding.btnQuickToolExecute.text = mode.buttonText

        // Hide all parameter panels first
        binding.containerMultiFileList.visibility = View.GONE
        binding.panelParamCompress.visibility = View.GONE
        binding.panelParamLock.visibility = View.GONE
        binding.panelParamRotate.visibility = View.GONE
        binding.panelParamSplit.visibility = View.GONE
        binding.panelParamSign.visibility = View.GONE
        binding.panelParamMetadata.visibility = View.GONE
        binding.panelParamSourceInput.visibility = View.GONE
        binding.cardQuickToolPicker.visibility = View.VISIBLE

        when (mode) {
            QuickMode.MERGE -> {
                binding.tvQuickToolPickPrompt.text = "Select PDFs to Merge"
                binding.tvQuickToolPickSubtitle.text = "Choose 2 or more documents to combine"
                binding.containerMultiFileList.visibility = View.VISIBLE
            }
            QuickMode.IMAGE_TO_PDF -> {
                binding.tvQuickToolPickPrompt.text = "Select Images"
                binding.tvQuickToolPickSubtitle.text = "Choose images to compile into a PDF"
                binding.containerMultiFileList.visibility = View.VISIBLE
            }
            QuickMode.SPLIT -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Split"
                binding.tvQuickToolPickSubtitle.text = "Choose document to split, burst, or extract"
                binding.panelParamSplit.visibility = View.VISIBLE
            }
            QuickMode.COMPRESS -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Compress"
                binding.panelParamCompress.visibility = View.VISIBLE
            }
            QuickMode.LOCK -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Encrypt"
                binding.panelParamLock.visibility = View.VISIBLE
            }
            QuickMode.ROTATE -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Rotate"
                binding.panelParamRotate.visibility = View.VISIBLE
                binding.chipGroupRotateAngle.visibility = View.VISIBLE
                binding.tvRotateGridTitle.text = "PAGES TO ROTATE (SELECT SPECIFIC OR ALL)"
                rotateThumbnailAdapter?.isDeleteMode = false
            }
            QuickMode.DELETE -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Prune"
                binding.panelParamRotate.visibility = View.VISIBLE
                binding.chipGroupRotateAngle.visibility = View.GONE
                binding.tilRotatePages.visibility = View.GONE
                binding.tvRotateGridTitle.text = "TAP PAGES TO MARK FOR DELETION (RED CROSS)"
                rotateThumbnailAdapter?.isDeleteMode = true
            }
            QuickMode.REORDER -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Reorder"
                binding.panelParamRotate.visibility = View.VISIBLE
                binding.chipGroupRotateAngle.visibility = View.GONE
                binding.tilRotatePages.visibility = View.GONE
                binding.tvRotateGridTitle.text = "DOCUMENT PAGES OVERVIEW"
                rotateThumbnailAdapter?.isDeleteMode = false
            }
            QuickMode.SIGN -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Sign"
                binding.panelParamSign.visibility = View.VISIBLE
            }
            QuickMode.METADATA -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF for Metadata"
                binding.panelParamMetadata.visibility = View.VISIBLE
            }
            QuickMode.HTML_TO_PDF -> {
                binding.cardQuickToolPicker.visibility = View.GONE
                binding.panelParamSourceInput.visibility = View.VISIBLE
                binding.tilSourceInput.hint = "Web URL or HTML string (e.g. https://...)"
            }
            QuickMode.DOC_TO_PDF -> {
                binding.tvQuickToolPickPrompt.text = "Select Office Document"
                binding.tvQuickToolPickSubtitle.text = "DOCX, DOC, or XLSX file"
            }
            else -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF Document"
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbarPdfQuickTool.setNavigationOnClickListener {
            onNavigateBack()
        }
        binding.btnPdfQuickToolReset.setOnClickListener {
            resetWorkspace()
        }
    }

    private fun setupPicker() {
        binding.cardQuickToolPicker.setOnClickListener {
            requestPickForActiveMode()
        }
        binding.btnQuickToolChangeDoc.setOnClickListener {
            requestPickForActiveMode()
        }
        binding.btnAddMoreFiles.setOnClickListener {
            requestPickForActiveMode()
        }
    }

    private fun setupSplitControls() {
        splitThumbnailAdapter = PdfPageThumbnailAdapter(
            context = activity,
            scope = scope,
            thumbnailLoader = thumbnailLoader,
            onPageSelectionChanged = { selectedPages ->
                binding.tvSplitSelectionCount.text = "${selectedPages.size} of $primaryDocPages selected"
            }
        )
        binding.rvSplitPagesThumbnails.layoutManager = GridLayoutManager(activity, 3)
        binding.rvSplitPagesThumbnails.adapter = splitThumbnailAdapter

        binding.chipGroupSplitMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val isCustomRanges = checkedIds.contains(R.id.chipSplitFixedRanges)
            val isSelected = checkedIds.contains(R.id.chipSplitSelected)

            binding.tilSplitRanges.visibility = if (isCustomRanges) View.VISIBLE else View.GONE
            binding.layoutSplitFastButtons.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (checkedIds.contains(R.id.chipSplitAllPages)) {
                binding.tvSplitSelectionCount.text = "All pages will be split to separate PDFs"
                splitThumbnailAdapter?.selectAll()
            } else if (isSelected) {
                binding.tvSplitSelectionCount.text = "${splitThumbnailAdapter?.getSelectedPages()?.size ?: 0} of $primaryDocPages selected"
            } else {
                binding.tvSplitSelectionCount.text = "Split by ranges entered above"
            }
        }

        binding.btnSplitSelectAll.setOnClickListener {
            splitThumbnailAdapter?.selectAll()
        }
        binding.btnSplitDeselectAll.setOnClickListener {
            splitThumbnailAdapter?.deselectAll()
        }
    }

    private fun setupRotateControls() {
        rotateThumbnailAdapter = PdfPageThumbnailAdapter(
            context = activity,
            scope = scope,
            thumbnailLoader = thumbnailLoader,
            onPageSelectionChanged = { _ -> }
        )
        binding.rvRotatePagesThumbnails.layoutManager = GridLayoutManager(activity, 3)
        binding.rvRotatePagesThumbnails.adapter = rotateThumbnailAdapter
    }

    private fun requestPickForActiveMode() {
        when (activeMode) {
            QuickMode.IMAGE_TO_PDF -> onPickFileRequest("image/*", true)
            QuickMode.DOC_TO_PDF -> onPickFileRequest("*/*", false)
            QuickMode.MERGE -> onPickFileRequest("application/pdf", true)
            else -> onPickFileRequest("application/pdf", false)
        }
    }

    fun handleFilesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isSaved = false

        if (activeMode == QuickMode.MERGE || activeMode == QuickMode.IMAGE_TO_PDF) {
            selectedUris.addAll(uris)
            updateMultiFileList()
            binding.containerQuickToolEmptyState.visibility = View.GONE
            binding.btnPdfQuickToolReset.visibility = View.VISIBLE
        } else {
            selectedUris.clear()
            selectedUris.add(uris.first())
            val uri = uris.first()
            primaryDocName = queryDocumentName(uri)

            binding.containerQuickToolEmptyState.visibility = View.GONE
            binding.containerQuickToolDocInfo.visibility = View.VISIBLE
            binding.btnPdfQuickToolReset.visibility = View.VISIBLE
            binding.tvQuickToolDocName.text = primaryDocName
            binding.tvQuickToolDocMeta.text = "Reading document..."

            scope.launch(Dispatchers.IO) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        PDDocument.load(stream).use { doc ->
                            primaryDocPages = doc.numberOfPages
                            withContext(Dispatchers.Main) {
                                binding.tvQuickToolDocMeta.text = "$primaryDocPages pages total"
                                splitThumbnailAdapter?.setDocument(uri, primaryDocPages, (1..primaryDocPages).toSet())
                                rotateThumbnailAdapter?.setDocument(uri, primaryDocPages, emptySet())
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // If metadata mode, inspect immediately
            if (activeMode == QuickMode.METADATA) {
                scope.launch(Dispatchers.IO) {
                    val metaResult = metadataManager.readMetadata(activity, uri)
                    val meta = metaResult.getOrNull()
                    withContext(Dispatchers.Main) {
                        meta?.let {
                            binding.etMetaTitle.setText(it.title ?: "")
                            binding.etMetaAuthor.setText(it.author ?: "")
                            binding.etMetaKeywords.setText(it.keywords ?: "")
                        }
                    }
                }
            }
        }
    }

    private fun updateMultiFileList() {
        binding.layoutFilesItems.removeAllViews()
        binding.tvMultiFileCount.text = "SELECTED FILES (${selectedUris.size})"

        val inflater = LayoutInflater.from(activity)

        for (idx in selectedUris.indices) {
            val uri = selectedUris[idx]
            val name = queryDocumentName(uri)

            val itemBinding = ItemPdfMergeFileBinding.inflate(inflater, binding.layoutFilesItems, false)

            itemBinding.tvMergeSequence.text = "#${idx + 1}"
            itemBinding.tvMergeFileName.text = name
            itemBinding.tvMergeFileMeta.text = "Loading preview..."

            // Load first page thumbnail
            itemBinding.pbMergeThumb.visibility = View.VISIBLE
            scope.launch(Dispatchers.IO) {
                val bmp = thumbnailLoader.loadPageThumbnail(uri, 0, 160)
                var pageCount = 1
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        PDDocument.load(stream).use { pageCount = it.numberOfPages }
                    }
                } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    itemBinding.pbMergeThumb.visibility = View.GONE
                    if (bmp != null) {
                        itemBinding.ivMergeThumb.setImageBitmap(bmp)
                    } else {
                        itemBinding.ivMergeThumb.setImageResource(R.drawable.ic_pdf_file)
                    }
                    itemBinding.tvMergeFileMeta.text = "$pageCount ${if (pageCount == 1) "page" else "pages"}"
                }
            }

            // Move Up button
            itemBinding.btnMergeMoveUp.isEnabled = (idx > 0)
            itemBinding.btnMergeMoveUp.alpha = if (idx > 0) 1.0f else 0.35f
            itemBinding.btnMergeMoveUp.setOnClickListener {
                if (idx > 0) {
                    Collections.swap(selectedUris, idx, idx - 1)
                    updateMultiFileList()
                }
            }

            // Move Down button
            itemBinding.btnMergeMoveDown.isEnabled = (idx < selectedUris.size - 1)
            itemBinding.btnMergeMoveDown.alpha = if (idx < selectedUris.size - 1) 1.0f else 0.35f
            itemBinding.btnMergeMoveDown.setOnClickListener {
                if (idx < selectedUris.size - 1) {
                    Collections.swap(selectedUris, idx, idx + 1)
                    updateMultiFileList()
                }
            }

            // Remove button
            itemBinding.btnMergeRemove.setOnClickListener {
                selectedUris.removeAt(idx)
                updateMultiFileList()
                if (selectedUris.isEmpty()) {
                    binding.containerQuickToolEmptyState.visibility = View.VISIBLE
                    binding.btnPdfQuickToolReset.visibility = View.GONE
                }
            }

            binding.layoutFilesItems.addView(itemBinding.root)
        }
    }

    private fun setupCompressControls() {
        binding.sliderCompressQuality.addOnChangeListener { _, value, _ ->
            val label = when {
                value <= 35f -> "Maximum Compression (${value.toInt()}%)"
                value <= 65f -> "Balanced (${value.toInt()}%)"
                else -> "High Quality (${value.toInt()}%)"
            }
            binding.tvCompressQualityVal.text = label
        }
    }

    private fun setupSignControls() {
        binding.btnSignClear.setOnClickListener {
            binding.viewSignaturePad.clear()
        }
    }

    private fun setupExecuteButton() {
        binding.btnQuickToolExecute.setOnClickListener {
            executeActiveOperation()
        }

        binding.btnStripAllMetadata.setOnClickListener {
            val uri = selectedUris.firstOrNull() ?: return@setOnClickListener
            scope.launch(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                val res = metadataManager.removeMetadata(activity, uri, out)
                withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        isSaved = true
                        val exportName = "${primaryDocName.removeSuffix(".pdf")}_sanitized.pdf"
                        onSaveFileRequest(out.toByteArray(), exportName)
                    } else {
                        Toast.makeText(activity, "Failed to sanitize metadata", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun executeActiveOperation() {
        if (activeMode != QuickMode.HTML_TO_PDF && selectedUris.isEmpty()) {
            Toast.makeText(activity, "Please select document(s) first", Toast.LENGTH_SHORT).show()
            return
        }

        if (isExecuting) return
        isExecuting = true
        binding.btnQuickToolExecute.isEnabled = false
        val origText = binding.btnQuickToolExecute.text.toString()
        binding.btnQuickToolExecute.text = "Processing..."

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                val outStream = ByteArrayOutputStream()
                val base = primaryDocName.removeSuffix(".pdf")

                when (activeMode) {
                    QuickMode.MERGE -> {
                        val result = pdfMerger.mergePdfs(activity, selectedUris, outStream)
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "merged_document.pdf", origText)
                        }
                    }
                    QuickMode.SPLIT -> {
                        val uri = selectedUris.first()
                        if (binding.chipSplitAllPages.isChecked) {
                            // Burst each page into individual PDF in a zip archive
                            val singlePdfs = mutableListOf<Pair<Int, ByteArray>>()
                            val result = pdfSplitter.splitAllPages(
                                context = activity,
                                inputUri = uri,
                                outputCallback = { pageNum, stream ->
                                    val pageOut = ByteArrayOutputStream()
                                    stream.copyTo(pageOut)
                                    singlePdfs.add(pageNum to pageOut.toByteArray())
                                }
                            )

                            withContext(Dispatchers.Main) {
                                if (result.isSuccess && singlePdfs.isNotEmpty()) {
                                    val zipOut = ByteArrayOutputStream()
                                    ZipOutputStream(zipOut).use { zos ->
                                        for ((num, bytes) in singlePdfs) {
                                            val entry = ZipEntry("${base}_page_${num}.pdf")
                                            zos.putNextEntry(entry)
                                            zos.write(bytes)
                                            zos.closeEntry()
                                        }
                                    }
                                    finishExecution(true, zipOut.toByteArray(), "${base}_split_pages.zip", origText)
                                } else {
                                    finishExecution(false, ByteArray(0), "", origText)
                                }
                            }
                        } else if (binding.chipSplitSelected.isChecked) {
                            // Extract selected pages as PDF
                            val selected = splitThumbnailAdapter?.getSelectedPages()?.toList()?.sorted() ?: emptyList()
                            if (selected.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    isExecuting = false
                                    binding.btnQuickToolExecute.isEnabled = true
                                    binding.btnQuickToolExecute.text = origText
                                    Toast.makeText(activity, "Please select at least one page to split", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }

                            val result = pdfOrganizer.extractPagesAsPdf(
                                context = activity,
                                inputUri = uri,
                                outputStream = outStream,
                                pagesToExtract = selected
                            )
                            withContext(Dispatchers.Main) {
                                finishExecution(result.isSuccess, outStream.toByteArray(), "${base}_split_selected.pdf", origText)
                            }
                        } else {
                            // Split by custom ranges
                            val rangeStr = binding.etSplitRanges.text?.toString().orEmpty()
                            val ranges = parsePageRanges(rangeStr)
                            if (ranges.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    isExecuting = false
                                    binding.btnQuickToolExecute.isEnabled = true
                                    binding.btnQuickToolExecute.text = origText
                                    Toast.makeText(activity, "Please enter valid page ranges (e.g. 1-2, 3-5)", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }

                            val partPdfs = mutableListOf<Pair<Int, ByteArray>>()
                            val result = pdfSplitter.splitByRanges(
                                context = activity,
                                inputUri = uri,
                                ranges = ranges,
                                outputCallback = { rangeIndex, stream ->
                                    val partOut = ByteArrayOutputStream()
                                    stream.copyTo(partOut)
                                    partPdfs.add(rangeIndex to partOut.toByteArray())
                                }
                            )

                            withContext(Dispatchers.Main) {
                                if (result.isSuccess && partPdfs.isNotEmpty()) {
                                    if (partPdfs.size == 1) {
                                        finishExecution(true, partPdfs.first().second, "${base}_split_part1.pdf", origText)
                                    } else {
                                        val zipOut = ByteArrayOutputStream()
                                        ZipOutputStream(zipOut).use { zos ->
                                            for ((num, bytes) in partPdfs) {
                                                val entry = ZipEntry("${base}_part_${num}.pdf")
                                                zos.putNextEntry(entry)
                                                zos.write(bytes)
                                                zos.closeEntry()
                                            }
                                        }
                                        finishExecution(true, zipOut.toByteArray(), "${base}_split_ranges.zip", origText)
                                    }
                                } else {
                                    finishExecution(false, ByteArray(0), "", origText)
                                }
                            }
                        }
                    }
                    QuickMode.DELETE -> {
                        val uri = selectedUris.first()
                        val pagesToDelete = rotateThumbnailAdapter?.getDeletedPages() ?: emptySet()
                        if (pagesToDelete.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, "Please tap at least one page to delete", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        val result = pdfOrganizer.removePages(
                            context = activity,
                            inputUri = uri,
                            outputStream = outStream,
                            pagesToRemove = pagesToDelete
                        )
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "${base}_pruned.pdf", origText)
                        }
                    }
                    QuickMode.ROTATE -> {
                        val uri = selectedUris.first()
                        val angle = when (binding.chipGroupRotateAngle.checkedChipId) {
                            R.id.chipRotate180 -> RotationAngle.ROTATE_180
                            R.id.chipRotate270 -> RotationAngle.ROTATE_270
                            else -> RotationAngle.ROTATE_90
                        }

                        val selectedPages = rotateThumbnailAdapter?.getSelectedPages() ?: emptySet()
                        val result = if (selectedPages.isNotEmpty()) {
                            val rotations = selectedPages.associateWith { angle }
                            pdfRotator.rotateSpecificPages(activity, uri, outStream, rotations)
                        } else {
                            pdfRotator.rotateAllPages(activity, uri, outStream, angle)
                        }

                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "${base}_rotated.pdf", origText)
                        }
                    }
                    QuickMode.REORDER -> {
                        val uri = selectedUris.first()
                        val order = (1..primaryDocPages).reversed().toList() // Simple reverse or custom
                        val result = pdfOrganizer.reorderPages(activity, uri, outStream, order)
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "${base}_reordered.pdf", origText)
                        }
                    }
                    QuickMode.IMAGE_TO_PDF -> {
                        val result = imageConverter.imagesToPdf(activity, selectedUris, outStream)
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "compiled_images.pdf", origText)
                        }
                    }
                    QuickMode.COMPRESS -> {
                        val uri = selectedUris.first()
                        val quality = binding.sliderCompressQuality.value.toInt()

                        val result = pdfCompressor.compressPdf(
                            context = activity,
                            inputUri = uri,
                            outputStream = outStream,
                            qualityPercent = quality
                        )
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "${base}_compressed.pdf", origText)
                        }
                    }
                    QuickMode.LOCK -> {
                        val uri = selectedUris.first()
                        val password = binding.etLockPassword.text?.toString() ?: ""
                        if (password.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, "Please enter an encryption password", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        val options = PdfSecurityOptions(
                            password = password,
                            allowPrinting = binding.cbLockAllowPrint.isChecked,
                            allowCopying = binding.cbLockAllowCopy.isChecked,
                            allowModifying = binding.cbLockAllowFillForms.isChecked,
                            allowAnnotations = binding.cbLockAllowAnnotate.isChecked
                        )

                        val result = pdfSecurityManager.encryptPdf(activity, uri, outStream, options)
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "${base}_locked.pdf", origText)
                        }
                    }
                    QuickMode.SIGN -> {
                        val uri = selectedUris.first()
                        val sigBmp = withContext(Dispatchers.Main) { binding.viewSignaturePad.getSignatureBitmap() }
                        if (sigBmp == null) {
                            withContext(Dispatchers.Main) {
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, "Please draw a signature first", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        val placement = SignaturePlacement(
                            pageIndex = 0,
                            x = 350f,
                            y = 80f,
                            width = 180f,
                            height = 60f
                        )

                        val result = pdfSigner.addSignatureBitmap(
                            inputUri = uri,
                            outputStream = outStream,
                            signatureBitmap = sigBmp,
                            placement = placement
                        )
                        withContext(Dispatchers.Main) {
                            finishExecution(result.success, outStream.toByteArray(), "${base}_signed.pdf", origText)
                        }
                    }
                    QuickMode.HTML_TO_PDF -> {
                        val input = binding.etSourceInput.text?.toString()?.trim() ?: ""
                        if (input.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, "Please enter URL or HTML content", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        val result = if (input.startsWith("http://") || input.startsWith("https://")) {
                            htmlConverter.convertUrlToPdf(activity, input, outStream)
                        } else {
                            htmlConverter.convertHtmlToPdf(activity, input, outStream)
                        }
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "rendered_web.pdf", origText)
                        }
                    }
                    QuickMode.DOC_TO_PDF -> {
                        val uri = selectedUris.first()
                        val tempDocx = File(activity.cacheDir, "temp_${System.currentTimeMillis()}.docx")
                        activity.contentResolver.openInputStream(uri)?.use { input ->
                            tempDocx.outputStream().use { output -> input.copyTo(output) }
                        }
                        val tempPdf = File(activity.cacheDir, "temp_${System.currentTimeMillis()}.pdf")
                        officeConverter.convertDocxToPdf(tempDocx, tempPdf, activity)
                        val success = tempPdf.exists() && tempPdf.length() > 0
                        if (success) {
                            tempPdf.inputStream().use { it.copyTo(outStream) }
                        }
                        tempDocx.delete()
                        tempPdf.delete()
                        withContext(Dispatchers.Main) {
                            finishExecution(success, outStream.toByteArray(), "${base}.pdf", origText)
                        }
                    }
                    QuickMode.PDF_TO_IMAGE -> {
                        val uri = selectedUris.first()
                        val bitmaps = mutableListOf<Pair<Int, Bitmap>>()
                        val result = imageConverter.pdfToImages(
                            context = activity,
                            inputUri = uri,
                            format = ImageFormat.WEBP,
                            dpi = 150,
                            outputCallback = { pageNum, bmp ->
                                bitmaps.add(pageNum to bmp)
                            }
                        )

                        withContext(Dispatchers.Main) {
                            if (result.isSuccess && bitmaps.isNotEmpty()) {
                                val zipOut = ByteArrayOutputStream()
                                ZipOutputStream(zipOut).use { zos ->
                                    for ((num, bmp) in bitmaps) {
                                        val entry = ZipEntry("${base}_page_${num}.webp")
                                        zos.putNextEntry(entry)
                                        bmp.compress(Bitmap.CompressFormat.WEBP, 90, zos)
                                        zos.closeEntry()
                                    }
                                }
                                finishExecution(true, zipOut.toByteArray(), "${base}_images.zip", origText)
                            } else {
                                finishExecution(false, ByteArray(0), "", origText)
                            }
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            isExecuting = false
                            binding.btnQuickToolExecute.isEnabled = true
                            binding.btnQuickToolExecute.text = origText
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isExecuting = false
                    binding.btnQuickToolExecute.isEnabled = true
                    binding.btnQuickToolExecute.text = origText
                    Toast.makeText(activity, "Operation failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parsePageRanges(rangeInput: String): List<PageRange> {
        val result = mutableListOf<PageRange>()
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
                    if (start in 1..primaryDocPages && end in 1..primaryDocPages) {
                        result.add(PageRange(minOf(start, end), maxOf(start, end)))
                    }
                }
            } else {
                val p = clean.toIntOrNull()
                if (p != null && p in 1..primaryDocPages) {
                    result.add(PageRange(p, p))
                }
            }
        }
        return result
    }

    private fun finishExecution(success: Boolean, data: ByteArray, fileName: String, origBtnText: String) {
        isExecuting = false
        binding.btnQuickToolExecute.isEnabled = true
        binding.btnQuickToolExecute.text = origBtnText

        if (success && data.isNotEmpty()) {
            isSaved = true
            onSaveFileRequest(data, fileName)
        } else {
            Toast.makeText(activity, "Operation completed with errors or empty output", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerQuickToolDocInfo.visibility = View.GONE
        binding.containerQuickToolEmptyState.visibility = View.VISIBLE
        binding.btnPdfQuickToolReset.visibility = View.GONE
        binding.layoutFilesItems.removeAllViews()
        binding.viewSignaturePad.clear()
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
