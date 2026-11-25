package com.veilframe.app.pdf

import android.annotation.SuppressLint
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.content.res.ColorStateList
import com.veilframe.app.qr.ui.QrColorPickerDialog
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.pdf.ui.SignaturePadView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.veilframe.app.pdf.ui.PdfMergeFileListAdapter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.R
import com.veilframe.app.databinding.ItemPdfMergeFileBinding
import com.veilframe.app.databinding.LayoutPdfQuickToolBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.operations.HtmlToPdfConverter
import com.veilframe.app.pdf.operations.ImageConverter
import com.veilframe.app.pdf.operations.ImageFormat
import com.veilframe.app.pdf.operations.MergePageItem
import com.veilframe.app.pdf.operations.OfficeConverter
import com.veilframe.app.pdf.operations.OfficeSourceFormat
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
import com.veilframe.app.pdf.ui.PdfMergePageAdapter
import com.veilframe.app.pdf.ui.PdfPageThumbnailAdapter
import com.veilframe.app.pdf.ui.PdfThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    private val onPickFileRequest: (mimeTypes: Array<String>, multiple: Boolean) -> Unit,
    private val onSaveFileRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onSaveFileFromDiskRequest: ((File, String) -> Unit)? = null
) : ToolWorkStateContract {

    enum class QuickMode(val title: String, val subtitle: String, val buttonText: String) {
        MERGE("Merge PDF", "Combine multiple documents into one", "Merge & Export PDF"),
        SPLIT("Split PDF", "Extract pages into separate documents", "Split & Export"),
        COMPRESS("Compress PDF", "Preserve document structure, or explicitly choose image-only output", "Compress & Export PDF"),
        ROTATE("Rotate Pages", "Modulo 90°, 180°, 270° orientation fix", "Rotate & Export PDF"),
        REORDER("Reorder Pages", "Rearrange page sequence order", "Reorder & Export PDF"),
        DELETE("Delete Pages", "Purge unwanted sheets from document", "Delete Pages & Export"),
        LOCK("Lock PDF", "256-bit AES encryption & permissions", "Encrypt & Save PDF"),
        SIGN("Add Visual Signature", "Drawn signature overlay; not cryptographically signed", "Stamp Signature & Export"),
        FILL_FORMS("Fill Forms", "Interactive AcroForm values editing", "Apply Form Values & Export"),
        HTML_TO_PDF("HTML to PDF", "Convert HTML code to PDF document", "Convert HTML to PDF"),
        DOC_TO_PDF("DOC to PDF", "Offline DOCX & XLSX conversion", "Convert Office to PDF"),
        PDF_TO_IMAGE("PDF to Image", "Batch export pages to WebP/PNG/JPG", "Export Page Images"),
        IMAGE_TO_PDF("Image to PDF", "Compile gallery photos into PDF", "Compile PDF"),
        METADATA("PDF Metadata", "Inspect Info; remove explicit PDF metadata and private application tags", "Save PDF Metadata")
    }

    private var activeMode: QuickMode = QuickMode.MERGE
    private val selectedUris = mutableListOf<Uri>()
    private var primaryDocName: String = "document.pdf"
    private var primaryDocPages: Int = 1
    private var isExecuting: Boolean = false
    private var isSaved: Boolean = false
    private var isSourceReady: Boolean = false
    private var activeJob: Job? = null
    private var selectionGeneration: Long = 0L
    private var pendingSaveGeneration: Long? = null
    private var officePreviewBitmap: Bitmap? = null
    private var mergePageRequest = 0L
    private var mergePageLoadJob: Job? = null
    private var mergePageReadySources: List<Uri>? = null

    private fun clearOfficePreview() {
        binding.ivDocVisualPreview.setImageDrawable(null)
        officePreviewBitmap?.recycle()
        officePreviewBitmap = null
    }

    /** The document picker is not a save: only a completed SAF copy may mark the output saved. */
    fun onSaveResult(success: Boolean) {
        if (pendingSaveGeneration == selectionGeneration && success) isSaved = true
        pendingSaveGeneration = null
    }

    private fun markExportPending() {
        pendingSaveGeneration = selectionGeneration
        isSaved = false
    }
    private val transientJobs = Collections.synchronizedSet(mutableSetOf<Job>())

    private class ExecutionGeneration(val value: Long) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<ExecutionGeneration>
    }

    private fun launchTransient(
        owner: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val job = scope.launch(Dispatchers.IO + owner, block = block)
        transientJobs.add(job)
        job.invokeOnCompletion { transientJobs.remove(job) }
        return job
    }

    private fun cancelTransientJobs() {
        synchronized(transientJobs) {
            transientJobs.toList().forEach { it.cancel() }
            transientJobs.clear()
        }
    }

    private val pdfMerger by lazy { PdfMerger() }
    private val pdfSplitter by lazy { PdfSplitter() }
    private val pdfCompressor by lazy { PdfCompressor() }
    private val pdfRotator by lazy { PdfRotator() }
    private val pdfOrganizer by lazy { PdfOrganizer() }
    private val pdfSecurityManager by lazy { PdfSecurityManager() }
    private val pdfSigner by lazy { PdfSigner(activity) }
    private val pdfFormFiller by lazy { PdfFormFiller() }
    private val formEditor = PdfAcroFormEditor(activity)
    private val htmlConverter by lazy { HtmlToPdfConverter() }
    private val officeConverter by lazy { OfficeConverter() }
    private val imageConverter by lazy { ImageConverter() }
    private val metadataManager by lazy { PdfMetadataManager() }
    private val thumbnailLoader by lazy { PdfThumbnailLoader(activity) }
    private val pagePreviews by lazy { PdfQuickPagePreviews(activity,scope,thumbnailLoader) }

    private var splitThumbnailAdapter: PdfPageThumbnailAdapter? = null
    private var rotateThumbnailAdapter: PdfPageThumbnailAdapter? = null
    private var mergePageAdapter: PdfMergePageAdapter? = null
    private var mergeFileListAdapter: com.veilframe.app.pdf.ui.PdfMergeFileListAdapter? = null
    private var rotateItemTouchHelper: ItemTouchHelper? = null
    private var mergeItemTouchHelper: ItemTouchHelper? = null
    private var mergeFilesItemTouchHelper: ItemTouchHelper? = null
    private var selectedSignColor: Int = Color.BLACK
    private var signPosXPercent: Float = 70f
    private var signPosYPercent: Float = 10f
    private var signScaleFactor: Float = 1.0f
    private var scaleGestureDetector: ScaleGestureDetector? = null

    override fun hasPickedMedia(): Boolean = selectedUris.isNotEmpty()
    override fun hasUnsavedWork(): Boolean = selectedUris.isNotEmpty() && !isSaved
    override val isProcessing: Boolean get() = isExecuting

    override fun discardAndReleaseAllResources() {
        formEditor.dismiss()
        pagePreviews.invalidate()
        selectionGeneration++
        pendingSaveGeneration = null
        activeJob?.cancel()
        activeJob = null
        cancelTransientJobs()
        invalidateMergePagePlan()
        selectedUris.clear()
        primaryDocName = "document.pdf"
        primaryDocPages = 1
        isExecuting = false
        isSaved = false
        isSourceReady = false
        binding.btnSignCanvasSave.isEnabled = true
        splitThumbnailAdapter?.clear()
        rotateThumbnailAdapter?.clear()
        thumbnailLoader.clearCache()
        binding.pdfSignCanvasView.reset()
        binding.layoutPdfSignCanvasOverlay.visibility = View.GONE
        binding.viewSignaturePad.clear()
        binding.viewSignaturePad.isDrawingEnabled = false
        binding.ivSignMiniaturePreview.setImageDrawable(null)
        binding.ivSignMiniaturePreview.visibility = View.GONE
        binding.tvSignIndicatorPlaceholder.visibility = View.VISIBLE
        binding.ivSignPagePreview.setImageDrawable(null)
        binding.pbSignPreviewLoading.visibility = View.GONE
        binding.containerQuickToolDocInfo.visibility = View.GONE
        binding.containerQuickToolEmptyState.visibility = View.VISIBLE
        binding.btnPdfQuickToolReset.visibility = View.GONE
        binding.panelParamDocPreview.visibility = View.GONE
        binding.cardDocVisualPreview.visibility = View.GONE
        clearOfficePreview()
        binding.pbDocVisualLoading.visibility = View.GONE
        binding.tvDocPreviewSummary.text = ""
        binding.tvDocPreviewContent.text = ""
        binding.etCustomMergeOrder.setText("")
        binding.etCustomPageOrder.setText("")
        mergeFileListAdapter?.clearAll()
        mergePageAdapter?.setItems(emptyList())
        binding.tvQuickToolDocName.text = ""
        binding.tvQuickToolDocMeta.text = ""
        binding.tvMultiFileCount.text = "SELECTED FILES (0)"
        binding.btnQuickToolExecute.isEnabled = true
    }

    fun init() {
        setupToolbar()
        setupPicker()
        setupCompressControls()
        setupSplitControls()
        setupRotateControls()
        setupMergeControls()
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
        binding.panelParamDocPreview.visibility = View.GONE
        binding.cardQuickToolPicker.visibility = View.VISIBLE

        when (mode) {
            QuickMode.MERGE -> {
                binding.tvQuickToolPickPrompt.text = "Select PDFs to Merge"
                binding.tvQuickToolPickSubtitle.text = "Choose 2 or more documents to combine"
                binding.containerMultiFileList.visibility = View.VISIBLE
                binding.chipGroupMergeMode.visibility = View.VISIBLE
            }
            QuickMode.IMAGE_TO_PDF -> {
                binding.tvQuickToolPickPrompt.text = "Select Images"
                binding.tvQuickToolPickSubtitle.text = "Choose images to compile into a PDF"
                binding.containerMultiFileList.visibility = View.VISIBLE
                binding.chipGroupMergeMode.visibility = View.GONE
                binding.layoutMergePageInterleave.visibility = View.GONE
                binding.rvMergeFiles.visibility = View.VISIBLE
                binding.layoutMergeArrangeBar.visibility = View.VISIBLE
                binding.tvMergeDragTip.visibility = View.VISIBLE
                binding.btnAddMoreFiles.visibility = View.VISIBLE
            }
            QuickMode.SPLIT -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Split"
                binding.tvQuickToolPickSubtitle.text = "Choose document to split, burst, or extract"
                binding.panelParamSplit.visibility = View.VISIBLE
            }
            QuickMode.COMPRESS -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Compress"
                binding.panelParamCompress.visibility = View.VISIBLE
                binding.chipGroupCompressEngine.check(R.id.chipCompressImages)
            }
            QuickMode.LOCK -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Encrypt"
                binding.panelParamLock.visibility = View.VISIBLE
            }
            QuickMode.ROTATE -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Rotate"
                binding.panelParamRotate.visibility = View.VISIBLE
                binding.tvRotateAngleLabel.visibility = View.VISIBLE
                binding.chipGroupRotateAngle.visibility = View.VISIBLE
                binding.tilRotatePages.visibility = View.VISIBLE
                binding.layoutReorderControls.visibility = View.GONE
                binding.layoutRotateFastButtons.visibility = View.VISIBLE
                binding.layoutDeleteFastButtons.visibility = View.GONE
                binding.tvRotateHint.visibility = View.GONE
                binding.tvRotateGridTitle.text = "PAGES TO ROTATE (SELECT SPECIFIC OR ALL)"
                rotateThumbnailAdapter?.isDeleteMode = false
                rotateThumbnailAdapter?.isReorderMode = false
            }
            QuickMode.DELETE -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Prune"
                binding.panelParamRotate.visibility = View.VISIBLE
                binding.tvRotateAngleLabel.visibility = View.GONE
                binding.chipGroupRotateAngle.visibility = View.GONE
                binding.tilRotatePages.visibility = View.GONE
                binding.layoutReorderControls.visibility = View.GONE
                binding.layoutRotateFastButtons.visibility = View.GONE
                binding.layoutDeleteFastButtons.visibility = View.VISIBLE
                binding.tvRotateHint.visibility = View.GONE
                binding.tvRotateGridTitle.text = "TAP PAGES TO MARK FOR DELETION (RED CROSS)"
                rotateThumbnailAdapter?.isDeleteMode = true
                rotateThumbnailAdapter?.isReorderMode = false
            }
            QuickMode.REORDER -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Reorder"
                binding.panelParamRotate.visibility = View.VISIBLE
                binding.tvRotateAngleLabel.visibility = View.GONE
                binding.chipGroupRotateAngle.visibility = View.GONE
                binding.tilRotatePages.visibility = View.GONE
                binding.layoutReorderControls.visibility = View.VISIBLE
                binding.layoutRotateFastButtons.visibility = View.GONE
                binding.layoutDeleteFastButtons.visibility = View.GONE
                binding.tvRotateHint.visibility = View.GONE
                binding.tvRotateGridTitle.text = "PAGES SEQUENCE (DRAG TO ARRANGE)"
                rotateThumbnailAdapter?.isDeleteMode = false
                rotateThumbnailAdapter?.isReorderMode = true
                binding.chipGroupReorderMode.check(R.id.chipReorderDrag)
                binding.layoutReorderDragTip.visibility = View.VISIBLE
                binding.containerManualSequence.visibility = View.GONE
            }
            QuickMode.SIGN -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF to Sign"
                binding.panelParamSign.visibility = View.VISIBLE
                binding.viewSignaturePad.isDrawingEnabled = selectedUris.isNotEmpty()
                binding.btnQuickToolExecute.setIconResource(R.drawable.ic_sd_card)
                if (selectedUris.isNotEmpty()) {
                    refreshSignPagePreview()
                }
                updateSignPlacementIndicator(signPosXPercent, signPosYPercent)
            }
            QuickMode.METADATA -> {
                binding.tvQuickToolPickPrompt.text = "Select PDF for Metadata"
                binding.panelParamMetadata.visibility = View.VISIBLE
            }
            QuickMode.HTML_TO_PDF -> {
                binding.cardQuickToolPicker.visibility = View.GONE
                binding.panelParamSourceInput.visibility = View.VISIBLE
                binding.tilSourceInput.hint = "HTML Code (e.g. <h1>Title</h1><p>...</p>)"
                binding.etSourceInput.hint = "Type or paste raw HTML markup code here..."
            }
            QuickMode.DOC_TO_PDF -> {
                binding.tvQuickToolPickPrompt.text = "Select Office Document"
                binding.tvQuickToolPickSubtitle.text = "DOCX, XLSX, PPTX, or TXT file"
                binding.panelParamDocPreview.visibility = if (selectedUris.isNotEmpty()) View.VISIBLE else View.GONE
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
        ).apply {
            onPagePreviewRequested = { pageNum ->
                selectedUris.firstOrNull()?.let { uri ->
                    showFullScreenPagePreview(uri, pageNum - 1)
                }
            }
        }
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
        binding.btnSplitOdd.setOnClickListener {
            splitThumbnailAdapter?.selectOdd()
        }
        binding.btnSplitEven.setOnClickListener {
            splitThumbnailAdapter?.selectEven()
        }
        binding.btnSplitInvert.setOnClickListener {
            splitThumbnailAdapter?.invertSelection()
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
        ).apply {
            onPageOrderChanged = { order ->
                if (activeMode == QuickMode.REORDER) {
                    binding.etCustomPageOrder.setText(order.joinToString(" > "))
                }
            }
            onStartDragListener = { holder ->
                if (activeMode == QuickMode.REORDER) {
                    rotateItemTouchHelper?.startDrag(holder)
                }
            }
            onPagePreviewRequested = { pageNum ->
                selectedUris.firstOrNull()?.let { uri ->
                    showFullScreenPagePreview(uri, pageNum - 1)
                }
            }
        }
        binding.rvRotatePagesThumbnails.layoutManager = GridLayoutManager(activity, 3)
        binding.rvRotatePagesThumbnails.adapter = rotateThumbnailAdapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (activeMode != QuickMode.REORDER) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                rotateThumbnailAdapter?.moveItem(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    binding.rvRotatePagesThumbnails.parent?.requestDisallowInterceptTouchEvent(true)
                    viewHolder.itemView.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .alpha(0.88f)
                        .setDuration(120)
                        .start()
                    viewHolder.itemView.elevation = 20f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(120)
                    .start()
                viewHolder.itemView.elevation = 0f
            }
        }
        rotateItemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.rvRotatePagesThumbnails)
        }

        binding.chipGroupReorderMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipReorderManual)) {
                binding.layoutReorderDragTip.visibility = View.GONE
                binding.containerManualSequence.visibility = View.VISIBLE
                val current = rotateThumbnailAdapter?.getPageOrder() ?: emptyList()
                if (current.isNotEmpty() && binding.etCustomPageOrder.text.isNullOrBlank()) {
                    binding.etCustomPageOrder.setText(current.joinToString(" > "))
                }
            } else {
                binding.layoutReorderDragTip.visibility = View.VISIBLE
                binding.containerManualSequence.visibility = View.GONE
            }
        }

        binding.btnApplyPageOrder.setOnClickListener {
            val input = binding.etCustomPageOrder.text?.toString().orEmpty()
            val total = primaryDocPages
            val order = parsePageSequence(input, total)
            if (order.isNotEmpty()) {
                rotateThumbnailAdapter?.setPageOrder(order)
            } else {
                Toast.makeText(activity, "Use valid, unique page numbers or ranges (max 10,000 entries)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnReversePageOrder.setOnClickListener {
            val current = rotateThumbnailAdapter?.getPageOrder() ?: emptyList()
            if (current.isNotEmpty()) {
                rotateThumbnailAdapter?.setPageOrder(current.reversed())
            }
        }

        binding.btnResetPageOrder.setOnClickListener {
            rotateThumbnailAdapter?.resetOrder()
        }

        binding.chipPresetReset.setOnClickListener {
            rotateThumbnailAdapter?.resetOrder()
        }

        binding.chipPresetReverse.setOnClickListener {
            val current = rotateThumbnailAdapter?.getPageOrder() ?: emptyList()
            if (current.isNotEmpty()) {
                rotateThumbnailAdapter?.setPageOrder(current.reversed())
            }
        }

        binding.chipPresetOdd.setOnClickListener {
            val total = primaryDocPages
            if (total > 0) {
                val oddOrder = (1..total step 2).toList()
                rotateThumbnailAdapter?.setPageOrder(oddOrder)
            }
        }

        binding.chipPresetEven.setOnClickListener {
            val total = primaryDocPages
            if (total > 0) {
                val evenOrder = (2..total step 2).toList()
                rotateThumbnailAdapter?.setPageOrder(evenOrder)
            }
        }

        // Delete mode quick selection buttons
        binding.btnDeleteOdd.setOnClickListener {
            rotateThumbnailAdapter?.markOddForDeletion()
        }
        binding.btnDeleteEven.setOnClickListener {
            rotateThumbnailAdapter?.markEvenForDeletion()
        }
        binding.btnDeleteAll.setOnClickListener {
            rotateThumbnailAdapter?.markAllForDeletion()
        }
        binding.btnDeleteClear.setOnClickListener {
            rotateThumbnailAdapter?.clearDeletionMarks()
        }

        // Rotate mode quick selection buttons
        binding.btnRotateSelectAll.setOnClickListener {
            rotateThumbnailAdapter?.selectAll()
        }
        binding.btnRotateOdd.setOnClickListener {
            rotateThumbnailAdapter?.selectOdd()
        }
        binding.btnRotateEven.setOnClickListener {
            rotateThumbnailAdapter?.selectEven()
        }
        binding.btnRotateClear.setOnClickListener {
            rotateThumbnailAdapter?.deselectAll()
        }
    }

    private fun setupMergeControls() {
        mergeFileListAdapter = PdfMergeFileListAdapter(
            context = activity,
            scope = scope,
            thumbnailLoader = thumbnailLoader
        ).apply {
            onOrderChanged = { newOrder ->
                val changed = selectedUris != newOrder
                selectedUris.clear()
                selectedUris.addAll(newOrder)
                if (changed) {
                    invalidateMergePagePlan()
                    if (activeMode == QuickMode.MERGE && binding.chipMergeModePages.isChecked)
                        refreshMergePageItems()
                }
                binding.tvMultiFileCount.text = "SELECTED FILES (${selectedUris.size})"
                if (selectedUris.isEmpty()) {
                    binding.containerQuickToolEmptyState.visibility = View.VISIBLE
                    binding.btnPdfQuickToolReset.visibility = View.GONE
                }
            }
            onItemRemoved = { _ ->
                if (selectedUris.isEmpty()) {
                    binding.containerQuickToolEmptyState.visibility = View.VISIBLE
                    binding.btnPdfQuickToolReset.visibility = View.GONE
                }
            }
            onStartDrag = { holder ->
                mergeFilesItemTouchHelper?.startDrag(holder)
            }
        }
        binding.rvMergeFiles.layoutManager = LinearLayoutManager(activity)
        binding.rvMergeFiles.adapter = mergeFileListAdapter

        val filesCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                mergeFileListAdapter?.moveItem(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    binding.rvMergeFiles.parent?.requestDisallowInterceptTouchEvent(true)
                    viewHolder.itemView.animate()
                        .scaleX(1.02f)
                        .scaleY(1.02f)
                        .alpha(0.92f)
                        .setDuration(120)
                        .start()
                    viewHolder.itemView.elevation = 16f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(120)
                    .start()
                viewHolder.itemView.elevation = 0f
            }
        }
        mergeFilesItemTouchHelper = ItemTouchHelper(filesCallback).also {
            it.attachToRecyclerView(binding.rvMergeFiles)
        }

        binding.chipMergeReverse.setOnClickListener {
            mergeFileListAdapter?.reverseOrder()
        }
        binding.chipMergeSortName.setOnClickListener {
            mergeFileListAdapter?.sortByName()
        }
        binding.chipMergeClear.setOnClickListener {
            invalidateMergePagePlan()
            selectedUris.clear()
            isSourceReady = false
            binding.btnQuickToolExecute.isEnabled = false
            mergeFileListAdapter?.clearAll()
            mergePageAdapter?.setItems(emptyList())
            binding.containerQuickToolEmptyState.visibility = View.VISIBLE
            binding.btnPdfQuickToolReset.visibility = View.GONE
        }

        mergePageAdapter = PdfMergePageAdapter(
            context = activity,
            scope = scope,
            thumbnailLoader = thumbnailLoader
        ).apply {
            onPageOrderChanged = { items ->
                val orderStr = items.map { it.overallPageNumber }.joinToString(", ")
                binding.etCustomMergeOrder.setText(orderStr)
            }
            onPagePreviewRequested = { uri, pageIndex ->
                showFullScreenPagePreview(uri, pageIndex)
            }
        }
        binding.rvMergePageItems.layoutManager = GridLayoutManager(activity, 3)
        binding.rvMergePageItems.adapter = mergePageAdapter

        val mergeCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                mergePageAdapter?.moveItem(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        mergeItemTouchHelper = ItemTouchHelper(mergeCallback).also {
            it.attachToRecyclerView(binding.rvMergePageItems)
        }

        binding.chipGroupMergeMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val isPages = checkedIds.contains(R.id.chipMergeModePages)
            binding.rvMergeFiles.visibility = if (isPages) View.GONE else View.VISIBLE
            binding.layoutMergeArrangeBar.visibility = if (isPages) View.GONE else View.VISIBLE
            binding.tvMergeDragTip.visibility = if (isPages) View.GONE else View.VISIBLE
            binding.btnAddMoreFiles.visibility = if (isPages) View.GONE else View.VISIBLE
            binding.layoutMergePageInterleave.visibility = if (isPages) View.VISIBLE else View.GONE
            if (activeMode == QuickMode.MERGE) {
                if (isPages) {
                    refreshMergePageItems()
                } else {
                    invalidateMergePagePlan()
                    binding.btnQuickToolExecute.isEnabled = !isExecuting && isSourceReady && selectedUris.isNotEmpty()
                }
            }
        }

        binding.btnApplyMergeOrder.setOnClickListener {
            applyCustomMergeOrderText()
        }

        binding.btnResetMergeOrder.setOnClickListener {
            refreshMergePageItems()
        }
    }

    private fun invalidateMergePagePlan() {
        mergePageRequest++
        mergePageLoadJob?.cancel()
        mergePageLoadJob = null
        mergePageReadySources = null
        mergePageAdapter?.setItems(emptyList())
    }

    private fun refreshMergePageItems() {
        invalidateMergePagePlan()
        if (activeMode != QuickMode.MERGE || !binding.chipMergeModePages.isChecked) return
        binding.btnQuickToolExecute.isEnabled = false
        if (selectedUris.isEmpty()) return
        val generation = selectionGeneration
        val request = mergePageRequest
        val sourceUris = selectedUris.toList()
        fun isCurrent() = request == mergePageRequest && generation == selectionGeneration &&
            activeMode == QuickMode.MERGE && binding.chipMergeModePages.isChecked &&
            sourceUris == selectedUris.toList()
        mergePageLoadJob = launchTransient {
            try {
                val pageItems = PdfMergePagePlanner.build(activity, sourceUris, ::queryDocumentName)
                withContext(Dispatchers.Main) {
                    if (isCurrent()) {
                        mergePageAdapter?.setItems(pageItems)
                        mergePageReadySources = sourceUris
                        binding.btnQuickToolExecute.isEnabled = !isExecuting
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    if (isCurrent()) {
                        Toast.makeText(activity, "Cannot prepare complete page list: ${failure.message ?: "unreadable source"}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun applyCustomMergeOrderText() {
        if (mergePageReadySources != selectedUris.toList()) {
            Toast.makeText(activity, "Wait for the complete page list", Toast.LENGTH_SHORT).show()
            return
        }
        val input = binding.etCustomMergeOrder.text?.toString().orEmpty()
        val currentItems = mergePageAdapter?.getItems() ?: emptyList()
        val requestedOrder = parsePageSequence(input, currentItems.size)
        if (requestedOrder.isEmpty() || currentItems.isEmpty()) {
            Toast.makeText(activity, "Use valid, unique page numbers or ranges (max 10,000 entries)", Toast.LENGTH_SHORT).show()
            return
        }

        val reordered = PdfPageSequenceParser.reorderPrefix(currentItems, requestedOrder) { it.overallPageNumber }
        if (reordered.isEmpty()) {
            Toast.makeText(activity, "Invalid page order; previous order retained", Toast.LENGTH_SHORT).show()
            return
        }
        mergePageAdapter?.setItems(reordered)
    }

    private fun requestPickForActiveMode() {
        when (activeMode) {
            QuickMode.IMAGE_TO_PDF -> onPickFileRequest(arrayOf("image/*"), true)
            QuickMode.DOC_TO_PDF -> onPickFileRequest(DOC_MIME_TYPES, false)
            QuickMode.MERGE -> onPickFileRequest(arrayOf("application/pdf"), true)
            else -> onPickFileRequest(arrayOf("application/pdf"), false)
        }
    }

    fun handleFilesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        formEditor.dismiss()
        pagePreviews.invalidate()
        selectionGeneration++
        pendingSaveGeneration = null
        val generation = selectionGeneration
        activeJob?.cancel()
        activeJob = null
        cancelTransientJobs()
        isExecuting = false
        isSourceReady = false
        binding.btnQuickToolExecute.isEnabled = false
        binding.btnQuickToolExecute.text = activeMode.buttonText
        binding.btnSignCanvasSave.isEnabled = true
        binding.pdfSignCanvasView.reset()
        binding.layoutPdfSignCanvasOverlay.visibility = View.GONE
        binding.ivSignPagePreview.setImageDrawable(null)
        binding.pbSignPreviewLoading.visibility = View.GONE
        primaryDocPages = 1
        isSaved = false
        splitThumbnailAdapter?.clear()
        rotateThumbnailAdapter?.clear()
        thumbnailLoader.clearCache()

        if (activeMode == QuickMode.MERGE || activeMode == QuickMode.IMAGE_TO_PDF) {
            selectedUris.addAll(uris)
            isSourceReady = selectedUris.isNotEmpty()
            binding.btnQuickToolExecute.isEnabled = isSourceReady
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

            if (activeMode == QuickMode.DOC_TO_PDF) {
                isSourceReady = false
                binding.btnQuickToolExecute.isEnabled = false
                binding.tvQuickToolDocMeta.text = "Reading document..."
                binding.panelParamDocPreview.visibility = View.VISIBLE
                binding.cardDocVisualPreview.visibility = View.VISIBLE
                binding.pbDocVisualLoading.visibility = View.VISIBLE
                clearOfficePreview()
                inspectAndPreviewOfficeDoc(uri, generation)
            } else {
                binding.tvQuickToolDocMeta.text = "Reading document..."
                launchTransient {
                    try {
                        val pageCount = activity.contentResolver.openInputStream(uri)?.use { stream ->
                            PDDocument.load(stream).use { doc -> doc.numberOfPages }
                        } ?: throw IllegalStateException("Cannot open PDF")
                        withContext(Dispatchers.Main) {
                            if (generation != selectionGeneration || selectedUris.firstOrNull() != uri) return@withContext
                            primaryDocPages = pageCount
                            isSourceReady = pageCount > 0
                            binding.btnQuickToolExecute.isEnabled = isSourceReady
                            binding.tvQuickToolDocMeta.text = "$pageCount pages total"
                            when (activeMode) {
                                QuickMode.SPLIT -> splitThumbnailAdapter?.setDocument(uri, pageCount, (1..pageCount).toSet())
                                QuickMode.REORDER, QuickMode.ROTATE, QuickMode.DELETE ->
                                    rotateThumbnailAdapter?.setDocument(uri, pageCount, emptySet())
                                else -> Unit
                            }
                            if (activeMode == QuickMode.SIGN) {
                                binding.viewSignaturePad.isDrawingEnabled = true
                                refreshSignPagePreview()
                                updateSignPlacementIndicator(signPosXPercent, signPosYPercent)
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            if (generation == selectionGeneration && selectedUris.firstOrNull() == uri) {
                                isSourceReady = false
                                binding.btnQuickToolExecute.isEnabled = false
                                binding.tvQuickToolDocMeta.text = "Unable to read page count"
                            }
                        }
                    }
                }
            }

            // If metadata mode, inspect immediately
            if (activeMode == QuickMode.METADATA) {
                launchTransient {
                    val metaResult = metadataManager.readMetadata(activity, uri)
                    val meta = metaResult.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (generation == selectionGeneration && selectedUris.firstOrNull() == uri &&
                            activeMode == QuickMode.METADATA
                        ) {
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
    }

    private fun inspectAndPreviewOfficeDoc(uri: Uri, generation: Long) {
        val selectedName = primaryDocName
        launchTransient {
            var preview: OfficePreviewEngine.Preview? = null
            var adopted = false
            try {
                val result = OfficePreviewEngine.inspect(activity, uri, selectedName)
                preview = result
                withContext(Dispatchers.Main) {
                    if (generation != selectionGeneration || selectedUris.firstOrNull() != uri ||
                        activeMode != QuickMode.DOC_TO_PDF) return@withContext
                    isSourceReady = true
                    binding.btnQuickToolExecute.isEnabled = true
                    binding.tvQuickToolDocMeta.text = "${result.format.label} • content inspected"
                    binding.panelParamDocPreview.visibility = View.VISIBLE
                    binding.tvDocPreviewSummary.text = "${result.summary} — content sample, not PDF layout"
                    binding.tvDocPreviewContent.text = result.text.ifBlank { "No text in this sample; conversion may contain graphical content." }
                    clearOfficePreview()
                    officePreviewBitmap = result.bitmap
                    adopted = true
                    binding.ivDocVisualPreview.setImageBitmap(result.bitmap)
                    binding.cardDocVisualPreview.visibility = if (result.bitmap != null) View.VISIBLE else View.GONE
                    binding.pbDocVisualLoading.visibility = View.GONE
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure !is Exception && failure !is LinkageError) throw failure
                Log.w(TAG, "Office source inspection failed", failure)
                withContext(Dispatchers.Main) {
                    if (generation != selectionGeneration || selectedUris.firstOrNull() != uri ||
                        activeMode != QuickMode.DOC_TO_PDF) return@withContext
                    isSourceReady = false
                    binding.btnQuickToolExecute.isEnabled = false
                    binding.tvQuickToolDocMeta.text = "Document inspection failed"
                    binding.tvDocPreviewSummary.text = "Cannot convert this selection"
                    binding.tvDocPreviewContent.text = failure.message ?: "Unreadable or unsupported document."
                    clearOfficePreview()
                    binding.cardDocVisualPreview.visibility = View.GONE
                    binding.pbDocVisualLoading.visibility = View.GONE
                }
            } finally {
                if (!adopted) preview?.bitmap?.recycle()
            }
        }
    }

    private fun updateMultiFileList() {
        binding.tvMultiFileCount.text = "SELECTED FILES (${selectedUris.size})"
        mergeFileListAdapter?.setUris(selectedUris)
        if (activeMode == QuickMode.MERGE && binding.chipMergeModePages.isChecked) {
            refreshMergePageItems()
        }
    }

    private fun setupCompressControls() {
        binding.chipGroupCompressEngine.setOnCheckedStateChangeListener { _, checked ->
            binding.tvCompressionScope.text = if (R.id.chipCompressFullRender in checked) {
                "Image-only output: text, vectors, forms, links, attachments and other interactive content are lost. Confirmation required."
            } else {
                "Optimizes eligible images; retains page drawing commands and document structure. Size reduction is not guaranteed."
            }
        }
        binding.sliderCompressQuality.addOnChangeListener { _, value, _ ->
            val label = when {
                value <= 35f -> "Maximum Compression (${value.toInt()}%)"
                value <= 65f -> "Balanced (${value.toInt()}%)"
                else -> "High Quality (${value.toInt()}%)"
            }
            binding.tvCompressQualityVal.text = label
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSignControls() {
        binding.viewSignaturePad.isDrawingEnabled = selectedUris.isNotEmpty()
        binding.viewSignaturePad.onDisabledTouchListener = {
            Toast.makeText(activity, "Please select a PDF document first", Toast.LENGTH_SHORT).show()
        }

        binding.btnSignSaveToLibrary.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(activity, "Please select a PDF document first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (binding.viewSignaturePad.isEmpty()) {
                Toast.makeText(activity, "Please draw a signature first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bmp = binding.viewSignaturePad.getSignatureBitmap(
                targetColor = selectedSignColor,
                opacity = (binding.sliderSignOpacity.value / 100f).coerceIn(0.1f, 1.0f)
            )
            if (bmp != null) {
                showSaveSignatureNameDialog(bmp)
            } else {
                Toast.makeText(activity, "Signature is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSignClear.setOnClickListener {
            binding.viewSignaturePad.clear()
            updateMiniatureSignaturePreview()
        }

        binding.viewSignaturePad.onStrokeCompleted = {
            updateMiniatureSignaturePreview()
        }

        binding.chipGroupSignColor.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipSignColorBlue) -> {
                    selectedSignColor = Color.parseColor("#1D4ED8")
                    binding.viewSignaturePad.setInkColor(selectedSignColor)
                    updateMiniatureSignaturePreview()
                }
                checkedIds.contains(R.id.chipSignColorRed) -> {
                    selectedSignColor = Color.parseColor("#DC2626")
                    binding.viewSignaturePad.setInkColor(selectedSignColor)
                    updateMiniatureSignaturePreview()
                }
                checkedIds.contains(R.id.chipSignColorWhite) -> {
                    selectedSignColor = Color.WHITE
                    binding.viewSignaturePad.setInkColor(selectedSignColor)
                    updateMiniatureSignaturePreview()
                }
                checkedIds.contains(R.id.chipSignColorBlack) -> {
                    selectedSignColor = Color.BLACK
                    binding.viewSignaturePad.setInkColor(selectedSignColor)
                    updateMiniatureSignaturePreview()
                }
            }
        }

        binding.chipSignColorCustom.setOnClickListener {
            val dialog = QrColorPickerDialog(
                context = activity,
                initialColor = selectedSignColor,
                contrastAgainstColor = Color.WHITE,
                isForeground = true
            ) { newColor ->
                selectedSignColor = newColor
                binding.chipSignColorCustom.text = String.format("#%06X", (0xFFFFFF and newColor))
                binding.chipSignColorCustom.chipIconTint = ColorStateList.valueOf(newColor)
                binding.chipSignColorCustom.isChecked = true
                binding.viewSignaturePad.setInkColor(selectedSignColor)
                updateMiniatureSignaturePreview()
            }.show()
            dialog.findViewById<TextView>(R.id.picker_title)?.text = "Select Ink Color"
            dialog.findViewById<TextView>(R.id.picker_subtitle)?.text = "Choose any custom ink color for your signature"
        }

        binding.sliderSignOpacity.addOnChangeListener { _, value, _ ->
            binding.tvSignOpacityVal.text = "${value.toInt()}%"
            updateMiniatureSignaturePreview()
        }

        binding.sliderSignStrokeThickness.addOnChangeListener { _, value, _ ->
            val label = when {
                value <= 2.0f -> "Fine (${value} dp)"
                value <= 5.0f -> "Medium (${value} dp)"
                value <= 8.5f -> "Thick (${value} dp)"
                else -> "Bold (${value} dp)"
            }
            binding.tvSignStrokeThicknessVal.text = label
            binding.viewSignaturePad.setStrokeThicknessDp(value)
            updateMiniatureSignaturePreview()
        }

        binding.sliderSignScale.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                applySignScale(value / 100f, updateSlider = false)
            }
        }

        binding.chipGroupSignPage.setOnCheckedStateChangeListener { _, checkedIds ->
            val isCustom = checkedIds.contains(R.id.chipSignPageCustom)
            binding.tilSignCustomPage.visibility = if (isCustom) View.VISIBLE else View.GONE
            refreshSignPagePreview()
        }

        binding.etSignCustomPage.doAfterTextChanged {
            refreshSignPagePreview()
        }

        // Initialize ScaleGestureDetector for intuitive pinch-to-zoom resizing
        scaleGestureDetector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (signScaleFactor * detector.scaleFactor).coerceIn(0.5f, 2.5f)
                applySignScale(newScale, updateSlider = true)
                return true
            }
        })

        binding.cardSignInteractivePlacement.setOnClickListener {
            openPdfSignCanvas()
        }

        var isScaling = false
        var downX = 0f
        var downY = 0f
        var isDrag = false

        binding.frameSignInteractiveContainer.setOnTouchListener { v, event ->
            try {
                scaleGestureDetector?.onTouchEvent(event)
                if (event.pointerCount > 1) {
                    isScaling = true
                    return@setOnTouchListener true
                }
                if (event.action == MotionEvent.ACTION_DOWN) {
                    downX = event.x
                    downY = event.y
                    isDrag = false
                }
                if (event.action == MotionEvent.ACTION_MOVE) {
                    if (kotlin.math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > 10) {
                        isDrag = true
                    }
                }
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    isScaling = false
                    binding.viewSignPlacementIndicator.animate()
                        .scaleX(signScaleFactor)
                        .scaleY(signScaleFactor)
                        .setDuration(120)
                        .start()

                    if (!isDrag && event.action == MotionEvent.ACTION_UP) {
                        openPdfSignCanvas()
                        return@setOnTouchListener true
                    }
                }
                if (!isScaling && (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE)) {
                    val containerW = v.width.toFloat()
                    val containerH = v.height.toFloat()
                    if (containerW > 0 && containerH > 0) {
                        val indicatorW = binding.viewSignPlacementIndicator.width.toFloat()
                        val indicatorH = binding.viewSignPlacementIndicator.height.toFloat()

                        val left = (event.x - indicatorW / 2f).coerceIn(0f, (containerW - indicatorW).coerceAtLeast(0f))
                        val top = (event.y - indicatorH / 2f).coerceIn(0f, (containerH - indicatorH).coerceAtLeast(0f))

                        val xPct = if (containerW > indicatorW) (left / (containerW - indicatorW)) * 100f else 0f
                        val yPct = if (containerH > indicatorH) (1.0f - (top / (containerH - indicatorH))) * 100f else 0f

                        updateSignPlacementIndicator(xPct, yPct)
                    }
                }
            } catch (ignored: Exception) {
            }
            true
        }

        setupSignCanvasOverlay()
    }

    private fun applySignScale(scale: Float, updateSlider: Boolean) {
        signScaleFactor = scale.coerceIn(0.5f, 2.5f)
        binding.viewSignPlacementIndicator.scaleX = signScaleFactor
        binding.viewSignPlacementIndicator.scaleY = signScaleFactor
        binding.tvSignScaleVal.text = "${(signScaleFactor * 100).toInt()}%"
        if (updateSlider) {
            binding.sliderSignScale.value = (signScaleFactor * 100f).coerceIn(50f, 250f)
        }
        updateSignPlacementIndicator(signPosXPercent, signPosYPercent)
    }

    private fun updateSignPlacementIndicator(xPct: Float, yPct: Float) {
        signPosXPercent = xPct.coerceIn(0f, 100f)
        signPosYPercent = yPct.coerceIn(0f, 100f)
        binding.tvSignCoordBadge.text = "X: ${signPosXPercent.toInt()}% | Y: ${signPosYPercent.toInt()}% • ${(signScaleFactor * 100).toInt()}%"
        binding.frameSignInteractiveContainer.post {
            val containerW = binding.frameSignInteractiveContainer.width.toFloat()
            val containerH = binding.frameSignInteractiveContainer.height.toFloat()
            val indicatorW = binding.viewSignPlacementIndicator.width.toFloat()
            val indicatorH = binding.viewSignPlacementIndicator.height.toFloat()

            if (containerW > indicatorW && containerH > indicatorH) {
                val transX = (containerW - indicatorW) * (signPosXPercent / 100f)
                val transY = (containerH - indicatorH) * (1.0f - (signPosYPercent / 100f))
                binding.viewSignPlacementIndicator.translationX = transX
                binding.viewSignPlacementIndicator.translationY = transY
            }
        }
    }

    private fun updateMiniatureSignaturePreview() {
        val opacity = (binding.sliderSignOpacity.value / 100f).coerceIn(0.1f, 1.0f)
        val bmp = binding.viewSignaturePad.getSignatureBitmap(targetColor = selectedSignColor, opacity = opacity)
        if (bmp != null) {
            binding.ivSignMiniaturePreview.setImageBitmap(bmp)
            binding.ivSignMiniaturePreview.visibility = View.VISIBLE
            binding.tvSignIndicatorPlaceholder.visibility = View.GONE
        } else {
            binding.ivSignMiniaturePreview.setImageDrawable(null)
            binding.ivSignMiniaturePreview.visibility = View.GONE
            binding.tvSignIndicatorPlaceholder.visibility = View.VISIBLE
        }
    }

    private fun refreshSignPagePreview() {
        val uri = selectedUris.firstOrNull() ?: return
        if (primaryDocPages <= 0) return
        val targetPageIdx = when {
            binding.chipSignPageFirst.isChecked || binding.chipSignPageAll.isChecked -> 0
            binding.chipSignPageCustom.isChecked -> {
                val p = binding.etSignCustomPage.text?.toString()?.toIntOrNull()
                if (p == null || p !in 1..primaryDocPages) {
                    pagePreviews.clearSignature(binding.ivSignPagePreview,binding.pbSignPreviewLoading)
                    return
                }
                p - 1
            }
            else -> primaryDocPages - 1
        }
        pagePreviews.showSignature(uri,targetPageIdx,binding.ivSignPagePreview,binding.pbSignPreviewLoading)
    }

    private fun openPdfSignCanvas() {
        val uri = selectedUris.firstOrNull()
        if (uri == null) {
            Toast.makeText(activity, "Please select a PDF document first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.layoutPdfSignCanvasOverlay.visibility = View.VISIBLE

        val currentSigBmp = binding.viewSignaturePad.getSignatureBitmap(
            targetColor = selectedSignColor,
            opacity = (binding.sliderSignOpacity.value / 100f).coerceIn(0.1f, 1.0f)
        ) ?: pdfSigner.getSavedSignatures().firstOrNull()?.bitmapPath?.let {
            android.graphics.BitmapFactory.decodeFile(it)
        }

        binding.pdfSignCanvasView.setDocument(uri, initialSignature = currentSigBmp)
        if (currentSigBmp != null) {
            binding.pdfSignCanvasView.addSignature(currentSigBmp)
        }
    }

    private fun setupSignCanvasOverlay() {
        binding.btnSignCanvasClose.setOnClickListener {
            binding.layoutPdfSignCanvasOverlay.visibility = View.GONE
        }

        binding.btnSignCanvasPen.setOnClickListener {
            showDrawSignatureDialog()
        }

        binding.btnSignCanvasCopy.setOnClickListener {
            val copied = binding.pdfSignCanvasView.copySelectedBox()
            if (!copied) {
                Toast.makeText(activity, "Tap a signature to select and copy", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSignCanvasSavedDropdown.setOnClickListener {
            showReuseSavedSignaturesMenu()
        }

        binding.btnSignCanvasManage.setOnClickListener {
            showManageSignaturesDialog()
        }

        binding.btnSignCanvasSave.setOnClickListener {
            executeCanvasSignExport()
        }

        binding.pdfSignCanvasView.onSaveSignatureClicked = { bitmap ->
            showSaveSignatureNameDialog(bitmap)
        }
    }

    private fun showDrawSignatureDialog() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(activity, "Please select a PDF document first", Toast.LENGTH_SHORT).show()
            return
        }
        val padContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
        }

        val currentThickness = binding.sliderSignStrokeThickness.value
        val pad = SignaturePadView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * activity.resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.parseColor("#18181C"))
            setInkColor(selectedSignColor)
            setStrokeThicknessDp(currentThickness)
        }

        val thicknessHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (10 * activity.resources.displayMetrics.density).toInt(), 0, 0)
        }
        val tvThicknessTitle = TextView(activity).apply {
            text = "STROKE THICKNESS"
            setTextColor(activity.getColor(R.color.vf_secondary))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvThicknessVal = TextView(activity).apply {
            text = "${currentThickness} dp"
            setTextColor(activity.getColor(R.color.vf_text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        thicknessHeader.addView(tvThicknessTitle)
        thicknessHeader.addView(tvThicknessVal)

        val dialogThicknessSlider = com.google.android.material.slider.Slider(activity).apply {
            valueFrom = 1.0f
            valueTo = 12.0f
            value = currentThickness
            stepSize = 0.5f
            addOnChangeListener { _, value, _ ->
                tvThicknessVal.text = "${value} dp"
                pad.setStrokeThicknessDp(value)
                binding.sliderSignStrokeThickness.value = value
            }
        }

        val btnClear = com.google.android.material.button.MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Clear Ink"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * activity.resources.displayMetrics.density).toInt()
            }
            setOnClickListener { pad.clear() }
        }

        padContainer.addView(pad)
        padContainer.addView(thicknessHeader)
        padContainer.addView(dialogThicknessSlider)
        padContainer.addView(btnClear)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Draw New Signature")
            .setView(padContainer)
            .setPositiveButton("Add to PDF") { _, _ ->
                val bmp = pad.getSignatureBitmap(targetColor = selectedSignColor)
                if (bmp != null) {
                    binding.pdfSignCanvasView.addSignature(bmp)
                } else {
                    Toast.makeText(activity, "Signature is empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewOrDuplicateSignature() {
        val selected = binding.pdfSignCanvasView.selectedBox
        if (selected != null) {
            binding.pdfSignCanvasView.addSignature(selected.bitmap, selected.pageIndex)
            return
        }
        val currentSigBmp = binding.viewSignaturePad.getSignatureBitmap(
            targetColor = selectedSignColor,
            opacity = (binding.sliderSignOpacity.value / 100f).coerceIn(0.1f, 1.0f)
        )
        if (currentSigBmp != null) {
            binding.pdfSignCanvasView.addSignature(currentSigBmp)
        } else {
            showDrawSignatureDialog()
        }
    }

    private fun showSaveSignatureNameDialog(bitmap: Bitmap) {
        val existingCount = pdfSigner.getSavedSignatures().size
        val editText = com.google.android.material.textfield.TextInputEditText(activity).apply {
            setText("Signature ${existingCount + 1}")
            selectAll()
        }
        val til = com.google.android.material.textfield.TextInputLayout(activity).apply {
            hint = "Signature Name"
            setPadding(48, 16, 48, 16)
            addView(editText)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Save Signature to Library")
            .setView(til)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                launchTransient {
                    pdfSigner.saveSignatureBitmap(bitmap, name)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Signature saved to library", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReuseSavedSignaturesMenu() {
        val signatures = pdfSigner.getSavedSignatures()
        if (signatures.isEmpty()) {
            Toast.makeText(activity, "No saved signatures. Draw and save one first.", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(activity).apply {
            addView(container)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Reuse Saved Signature")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()

        for (sig in signatures) {
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (72 * activity.resources.displayMetrics.density).toInt(),
                    (40 * activity.resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (16 * activity.resources.displayMetrics.density).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#26262B"))
                val bmp = android.graphics.BitmapFactory.decodeFile(sig.bitmapPath)
                if (bmp != null) setImageBitmap(bmp)
            }

            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(activity).apply {
                text = sig.name
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val date = TextView(activity).apply {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                text = sdf.format(java.util.Date(sig.createdAt))
                textSize = 11f
                setTextColor(Color.parseColor("#9E9E9E"))
            }

            textLayout.addView(title)
            textLayout.addView(date)
            itemRow.addView(thumb)
            itemRow.addView(textLayout)

            itemRow.setOnClickListener {
                val bmp = android.graphics.BitmapFactory.decodeFile(sig.bitmapPath)
                if (bmp != null) {
                    binding.pdfSignCanvasView.addSignature(bmp)
                }
                dialog.dismiss()
            }

            container.addView(itemRow)
        }

        dialog.show()
    }

    private fun showManageSignaturesDialog() {
        fun buildDialog() {
            val signatures = pdfSigner.getSavedSignatures()
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }

            val scroll = ScrollView(activity).apply {
                addView(container)
            }

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle("Manage Saved Signatures (${signatures.size})")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .create()

            if (signatures.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = "No saved signatures yet. Sign a PDF and tap the save button beside any signature."
                    setTextColor(Color.parseColor("#9E9E9E"))
                    textSize = 13f
                    setPadding(32, 32, 32, 32)
                    gravity = android.view.Gravity.CENTER
                }
                container.addView(emptyTv)
            } else {
                for (sig in signatures) {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(12, 12, 12, 12)
                    }

                    val thumb = ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (64 * activity.resources.displayMetrics.density).toInt(),
                            (36 * activity.resources.displayMetrics.density).toInt()
                        ).apply {
                            marginEnd = (12 * activity.resources.displayMetrics.density).toInt()
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.parseColor("#26262B"))
                        val bmp = android.graphics.BitmapFactory.decodeFile(sig.bitmapPath)
                        if (bmp != null) setImageBitmap(bmp)
                    }

                    val textLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val title = TextView(activity).apply {
                        text = sig.name
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val date = TextView(activity).apply {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        text = sdf.format(java.util.Date(sig.createdAt))
                        textSize = 10f
                        setTextColor(Color.parseColor("#9E9E9E"))
                    }

                    textLayout.addView(title)
                    textLayout.addView(date)

                    val btnRename = com.google.android.material.button.MaterialButton(
                        activity,
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = "Rename"
                        textSize = 11f
                        setOnClickListener {
                            val editName = com.google.android.material.textfield.TextInputEditText(activity).apply {
                                setText(sig.name)
                                selectAll()
                            }
                            val til = com.google.android.material.textfield.TextInputLayout(activity).apply {
                                hint = "Rename Signature"
                                setPadding(48, 16, 48, 16)
                                addView(editName)
                            }
                            MaterialAlertDialogBuilder(activity)
                                .setTitle("Rename Signature")
                                .setView(til)
                                .setPositiveButton("Save") { _, _ ->
                                    val newName = editName.text?.toString()?.trim().orEmpty()
                                    if (newName.isNotBlank()) {
                                        pdfSigner.renameSignature(sig.id, newName)
                                        dialog.dismiss()
                                        buildDialog()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }

                    val btnDelete = com.google.android.material.button.MaterialButton(
                        activity,
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = "Delete"
                        textSize = 11f
                        setTextColor(Color.parseColor("#EF4444"))
                        setStrokeColorResource(android.R.color.holo_red_dark)
                        setOnClickListener {
                            pdfSigner.deleteSignatureById(sig.id)
                            dialog.dismiss()
                            buildDialog()
                        }
                    }

                    row.addView(thumb)
                    row.addView(textLayout)
                    row.addView(btnRename)
                    row.addView(btnDelete)
                    container.addView(row)
                }
            }

            dialog.show()
        }

        buildDialog()
    }

    private fun executeCanvasSignExport() {
        val uri = selectedUris.firstOrNull() ?: return
        val placements = binding.pdfSignCanvasView.getAllPlacements()
        if (placements.isEmpty()) {
            Toast.makeText(activity, "Please place at least one signature on the document", Toast.LENGTH_SHORT).show()
            return
        }

        if (isExecuting) return
        isExecuting = true
        binding.btnSignCanvasSave.isEnabled = false
        val origText = binding.btnQuickToolExecute.text.toString()

        val generation = selectionGeneration
        val base = primaryDocName.removeSuffix(".pdf")
        activeJob = launchTransient(ExecutionGeneration(generation)) {
            try {
                finishPdfOutputOnDisk("${base}_signed.pdf", origText) { output ->
                    val result = pdfSigner.signPdfWithItems(
                        inputUri = uri,
                        outputStream = output,
                        items = placements
                    )
                    withContext(Dispatchers.Main) {
                        if (generation != selectionGeneration) return@withContext
                        binding.btnSignCanvasSave.isEnabled = true
                        if (result.success) {
                            binding.layoutPdfSignCanvasOverlay.visibility = View.GONE
                        } else {
                            Toast.makeText(activity, "Export failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                    result.success
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                        if (generation != selectionGeneration) return@withContext
                    binding.btnSignCanvasSave.isEnabled = true
                    isExecuting = false
                    Toast.makeText(activity, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupExecuteButton() {
        binding.btnQuickToolExecute.setOnClickListener {
            executeActiveOperation()
        }

        binding.btnStripAllMetadata.setOnClickListener {
            val uri = selectedUris.firstOrNull() ?: return@setOnClickListener
            val generation = selectionGeneration
            val sourceName = primaryDocName
            launchTransient {
                val exportName = "${sourceName.removeSuffix(".pdf")}_sanitized.pdf"
                val file = File.createTempFile("metadata_clean_", ".pdf", activity.cacheDir)
                var handedOff = false
                try {
                    val res = file.outputStream().buffered().use { output ->
                        metadataManager.removeMetadata(activity, uri, output)
                    }
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val legacyBytes = if (res.isSuccess && file.length() > 0L &&
                        onSaveFileFromDiskRequest == null) PdfQuickExportStage.legacyBytes(file) else null
                    withContext(Dispatchers.Main) {
                        if (generation == selectionGeneration && selectedUris.firstOrNull() == uri &&
                            activeMode == QuickMode.METADATA
                        ) {
                            if (res.isSuccess && file.length() > 0L) {
                                val diskSave = onSaveFileFromDiskRequest
                                if (diskSave != null) {
                                    diskSave(file, exportName)
                                    handedOff = true
                                } else {
                                    onSaveFileRequest(requireNotNull(legacyBytes), exportName)
                                }
                                markExportPending()
                            } else {
                                Toast.makeText(activity, "Failed to remove PDF metadata", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        if (generation == selectionGeneration && selectedUris.firstOrNull() == uri &&
                            activeMode == QuickMode.METADATA) {
                            Toast.makeText(activity, "Metadata export failed: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    if (!handedOff) file.delete()
                }
            }
        }
    }

    private fun executeActiveOperation(rasterConfirmed: Boolean = false, xlsxPrintLayout: Boolean? = null) {
        if (activeMode != QuickMode.HTML_TO_PDF && !isSourceReady) {
            Toast.makeText(activity, "Wait for the selected document to finish loading", Toast.LENGTH_SHORT).show()
            return
        }
        if (activeMode == QuickMode.FILL_FORMS) {
            openFillFormEditor()
            return
        }
        if (activeMode != QuickMode.HTML_TO_PDF && selectedUris.isEmpty()) {
            Toast.makeText(activity, "Please select document(s) first", Toast.LENGTH_SHORT).show()
            return
        }

        if (isExecuting) return
        if (activeMode == QuickMode.MERGE && binding.chipMergeModePages.isChecked &&
            (mergePageReadySources != selectedUris.toList() || (mergePageAdapter?.itemCount ?: 0) == 0)) {
            Toast.makeText(activity, "Complete page list required for page-by-page merge", Toast.LENGTH_SHORT).show()
            return
        }
        if (activeMode == QuickMode.DOC_TO_PDF && OfficeSourceFormat.fromName(primaryDocName) == OfficeSourceFormat.XLSX && xlsxPrintLayout == null) {
            val generation = selectionGeneration
            val source = selectedUris.firstOrNull()
            MaterialAlertDialogBuilder(activity)
                .setTitle("Spreadsheet PDF layout")
                .setMessage("Full worksheet preserves wide columns and all stored cells. Saved print layout uses the workbook print area, paper, margins and scaling where supported; cells outside the saved print area are omitted. Excel-specific headers, hidden cells, titles, charts and other features may differ.")
                .setPositiveButton("Full worksheet") { _, _ ->
                    if (generation == selectionGeneration && activeMode == QuickMode.DOC_TO_PDF && selectedUris.firstOrNull() == source)
                        executeActiveOperation(xlsxPrintLayout = false)
                }
                .setNeutralButton("Saved print layout") { _, _ ->
                    if (generation == selectionGeneration && activeMode == QuickMode.DOC_TO_PDF && selectedUris.firstOrNull() == source)
                        executeActiveOperation(xlsxPrintLayout = true)
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val rasterSelected = activeMode == QuickMode.COMPRESS && binding.chipCompressFullRender.isChecked
        if (rasterSelected && !rasterConfirmed) {
            val generation = selectionGeneration
            val source = selectedUris.firstOrNull()
            MaterialAlertDialogBuilder(activity)
                .setTitle("Create an image-only PDF?")
                .setMessage("Every page will be rendered to a JPEG at the selected quality. Searchable and selectable text, vector graphics, editable forms, links, attachments, signatures and other PDF objects will not be preserved. Fine details can blur. The original file is unchanged. Signed or encrypted sources are refused.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create image-only PDF") { _, _ ->
                    if (generation == selectionGeneration && activeMode == QuickMode.COMPRESS &&
                        selectedUris.firstOrNull() == source && binding.chipCompressFullRender.isChecked) {
                        executeActiveOperation(rasterConfirmed = true)
                    }
                }.show()
            return
        }
        if (activeMode == QuickMode.SIGN && binding.pdfSignCanvasView.getAllPlacements().isEmpty() &&
            binding.chipSignPageCustom.isChecked &&
            (binding.etSignCustomPage.text?.toString()?.toIntOrNull() ?: 0) !in 1..primaryDocPages) {
            Toast.makeText(activity, "Enter a valid signature page number", Toast.LENGTH_SHORT).show()
            return
        }
        val quality = if (activeMode == QuickMode.COMPRESS) binding.sliderCompressQuality.value.toInt() else 65
        isExecuting = true
        binding.btnQuickToolExecute.isEnabled = false
        val origText = binding.btnQuickToolExecute.text.toString()
        binding.btnQuickToolExecute.text = "Processing..."
        // Never read split controls from a background thread.
        val splitAllPages = binding.chipSplitAllPages.isChecked
        val splitSelectedMode = binding.chipSplitSelected.isChecked
        val splitSelectedPages = splitThumbnailAdapter?.getSelectedPages()?.toList()?.sorted().orEmpty()
        val splitRangeText = binding.etSplitRanges.text?.toString().orEmpty()
        val sourcePageCount = primaryDocPages
        val operationMode = activeMode
        val operationUris = selectedUris.toList()
        val operationFileName = primaryDocName
        val operationBase = primaryDocName.removeSuffix(".pdf")
        val generation = selectionGeneration
        val interleaved = operationMode == QuickMode.MERGE && binding.chipMergeModePages.isChecked
        val mergePlan = if (interleaved) mergePageAdapter?.getItems()?.toList().orEmpty() else emptyList()
        val deletePages = if (operationMode == QuickMode.DELETE) rotateThumbnailAdapter?.getDeletedPages()?.toSet().orEmpty() else emptySet()
        val rotatePages = if (operationMode == QuickMode.ROTATE) rotateThumbnailAdapter?.getSelectedPages()?.toSet().orEmpty() else emptySet()
        val rotateAngle = when (binding.chipGroupRotateAngle.checkedChipId) {
            R.id.chipRotate180 -> RotationAngle.ROTATE_180
            R.id.chipRotate270 -> RotationAngle.ROTATE_270
            else -> RotationAngle.ROTATE_90
        }
        val reorderPages = if (operationMode == QuickMode.REORDER)
            rotateThumbnailAdapter?.getPageOrder()?.toList() ?: (1..primaryDocPages).toList() else emptyList()
        val lockOptions = if (operationMode == QuickMode.LOCK) PdfSecurityOptions(
            password = binding.etLockPassword.text?.toString().orEmpty(),
            allowPrinting = binding.cbLockAllowPrint.isChecked,
            allowCopying = binding.cbLockAllowCopy.isChecked,
            allowModifying = binding.cbLockAllowFillForms.isChecked,
            allowAnnotations = binding.cbLockAllowAnnotate.isChecked
        ) else null
        val htmlInput = if (operationMode == QuickMode.HTML_TO_PDF) binding.etSourceInput.text?.toString()?.trim().orEmpty() else ""
        val signatureItems = if (operationMode == QuickMode.SIGN) binding.pdfSignCanvasView.getAllPlacements().toList() else emptyList()

        val signX = (signPosXPercent / 100f).coerceIn(0f, 1f)
        val signY = (signPosYPercent / 100f).coerceIn(0f, 1f)
        val signScale = signScaleFactor
        val signatureInput = if (operationMode == QuickMode.SIGN && signatureItems.isEmpty()) {
            val opacity = (binding.sliderSignOpacity.value / 100f).coerceIn(0.1f, 1.0f)
            val bitmap = binding.viewSignaturePad.getSignatureBitmap(
                targetColor = selectedSignColor, opacity = opacity
            )
            val lastPage = (primaryDocPages - 1).coerceAtLeast(0)
            val pages = when {
                binding.chipSignPageFirst.isChecked -> listOf(0)
                binding.chipSignPageAll.isChecked -> (0..lastPage).toList()
                binding.chipSignPageCustom.isChecked -> {
                    val requested = requireNotNull(binding.etSignCustomPage.text?.toString()?.toIntOrNull())
                    listOf(requested - 1)
                }
                else -> listOf(lastPage)
            }
            bitmap to pages
        } else null

        activeJob = launchTransient(ExecutionGeneration(generation)) {
            try {
                val base = operationBase

                when (operationMode) {
                    QuickMode.MERGE -> {
                        finishPdfOutputOnDisk("merged_document.pdf", origText) { output ->
                            if (interleaved) {
                                pdfMerger.mergePageItems(activity, mergePlan, output).isSuccess
                            } else {
                                pdfMerger.mergePdfs(activity, operationUris, output).isSuccess
                            }
                        }
                    }
                    QuickMode.SPLIT -> {
                        val uri = operationUris.first()
                        val workJob = coroutineContext[Job]
                        if (splitAllPages) {
                            // Stream each produced PDF directly into ZIP: no O(page count)
                            // collection of page-sized ByteArrays or main-thread packaging.
                            val zipFile = File.createTempFile("split_pages_", ".zip", activity.cacheDir)
                            var handedOff = false
                            try {
                                var pagesWritten = 0
                                val result = ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                                    pdfSplitter.splitAllPages(
                                        context = activity,
                                        inputUri = uri,
                                        outputCallback = { pageNumber, pdfStream ->
                                            workJob?.ensureActive()
                                            zip.putNextEntry(ZipEntry("${base}_page_$pageNumber.pdf"))
                                            pdfStream.copyTo(zip)
                                            zip.closeEntry()
                                            pagesWritten++
                                        }
                                    )
                                }
                                workJob?.ensureActive()
                                handedOff = finishDiskExecution(
                                    result.isSuccess && pagesWritten > 0,
                                    zipFile, "${base}_split_pages.zip", origText
                                )
                            } finally {
                                if (!handedOff) zipFile.delete()
                            }
                        } else if (splitSelectedMode) {
                            if (splitSelectedPages.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    if (generation != selectionGeneration) return@withContext
                                    isExecuting = false
                                    binding.btnQuickToolExecute.isEnabled = true
                                    binding.btnQuickToolExecute.text = origText
                                    Toast.makeText(
                                        activity, "Please select at least one page to split",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return@launchTransient
                            }
                            val pdfFile = File.createTempFile("split_selected_", ".pdf", activity.cacheDir)
                            var handedOff = false
                            try {
                                val result = pdfFile.outputStream().buffered().use { output ->
                                    pdfOrganizer.extractPagesAsPdf(
                                        context = activity,
                                        inputUri = uri,
                                        outputStream = output,
                                        pagesToExtract = splitSelectedPages
                                    )
                                }
                                workJob?.ensureActive()
                                handedOff = finishDiskExecution(
                                    result.isSuccess, pdfFile, "${base}_split_selected.pdf", origText
                                )
                            } finally {
                                if (!handedOff) pdfFile.delete()
                            }
                        } else {
                            val ranges = PdfSplitRangeParser.parse(splitRangeText, sourcePageCount)
                            if (ranges.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    if (generation != selectionGeneration) return@withContext
                                    isExecuting = false
                                    binding.btnQuickToolExecute.isEnabled = true
                                    binding.btnQuickToolExecute.text = origText
                                    Toast.makeText(
                                        activity, "Please enter valid page ranges (e.g. 1-2, 3-5)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return@launchTransient
                            }
                            val singlePart = ranges.size == 1
                            val tempFile = File.createTempFile(
                                "split_ranges_", if (singlePart) ".pdf" else ".zip", activity.cacheDir
                            )
                            var handedOff = false
                            try {
                                var partsWritten = 0
                                val result = if (singlePart) {
                                    tempFile.outputStream().buffered().use { output ->
                                        pdfSplitter.splitByRanges(
                                            context = activity,
                                            inputUri = uri,
                                            ranges = ranges,
                                            outputCallback = { _, pdfStream ->
                                                workJob?.ensureActive()
                                                pdfStream.copyTo(output)
                                                partsWritten++
                                            }
                                        )
                                    }
                                } else {
                                    ZipOutputStream(tempFile.outputStream().buffered()).use { zip ->
                                        pdfSplitter.splitByRanges(
                                            context = activity,
                                            inputUri = uri,
                                            ranges = ranges,
                                            outputCallback = { partNumber, pdfStream ->
                                                workJob?.ensureActive()
                                                zip.putNextEntry(ZipEntry("${base}_part_$partNumber.pdf"))
                                                pdfStream.copyTo(zip)
                                                zip.closeEntry()
                                                partsWritten++
                                            }
                                        )
                                    }
                                }
                                workJob?.ensureActive()
                                val name = if (singlePart) {
                                    "${base}_split_part1.pdf"
                                } else {
                                    "${base}_split_ranges.zip"
                                }
                                handedOff = finishDiskExecution(
                                    result.isSuccess && partsWritten == ranges.size,
                                    tempFile, name, origText
                                )
                            } finally {
                                if (!handedOff) tempFile.delete()
                            }
                        }
                    }
                    QuickMode.DELETE -> {
                        val pagesToDelete = deletePages
                        if (pagesToDelete.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (generation != selectionGeneration) return@withContext
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, "Please tap at least one page to delete", Toast.LENGTH_SHORT).show()
                            }
                            return@launchTransient
                        }
                        finishPdfOutputOnDisk("${base}_pruned.pdf", origText) { output ->
                            pdfOrganizer.removePages(
                                context = activity,
                                inputUri = operationUris.first(),
                                outputStream = output,
                                pagesToRemove = pagesToDelete
                            ).isSuccess
                        }
                    }
                    QuickMode.ROTATE -> {
                        val angle = rotateAngle
                        val selectedPages = rotatePages
                        finishPdfOutputOnDisk("${base}_rotated.pdf", origText) { output ->
                            if (selectedPages.isNotEmpty()) {
                                val rotations = selectedPages.associateWith { angle }
                                pdfRotator.rotateSpecificPages(
                                    activity, operationUris.first(), output, rotations
                                ).isSuccess
                            } else {
                                pdfRotator.rotateAllPages(
                                    activity, operationUris.first(), output, angle
                                ).isSuccess
                            }
                        }
                    }
                    QuickMode.REORDER -> {
                        val pageOrder = reorderPages
                        finishPdfOutputOnDisk("${base}_reordered.pdf", origText) { output ->
                            pdfOrganizer.reorderPages(
                                activity, operationUris.first(), output, pageOrder
                            ).isSuccess
                        }
                    }
                    QuickMode.IMAGE_TO_PDF -> {
                        finishPdfOutputOnDisk("compiled_images.pdf", origText) { output ->
                            imageConverter.imagesToPdf(activity, operationUris, output).isSuccess
                        }
                    }
                    QuickMode.COMPRESS -> {
                        val suffix = if (rasterSelected && rasterConfirmed) "image_only" else "compressed"
                        finishPdfOutputOnDisk("${base}_$suffix.pdf", origText) { output ->
                            if (rasterSelected && rasterConfirmed) {
                                pdfCompressor.compressPdfRasterized(activity, operationUris.first(), output, quality).getOrThrow()
                            } else {
                                pdfCompressor.compressPdf(
                                    context = activity,
                                    inputUri = operationUris.first(),
                                    outputStream = output,
                                    qualityPercent = com.veilframe.app.pdf.operations.qualitySliderToCompressionPercent(quality)
                                ).getOrThrow()
                            }
                            true
                        }
                    }
                    QuickMode.LOCK -> {
                        val options = requireNotNull(lockOptions)
                        if (options.password.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (generation != selectionGeneration) return@withContext
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(
                                    activity, "Please enter an encryption password", Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@launchTransient
                        }
                        finishPdfOutputOnDisk("${base}_locked.pdf", origText) { output ->
                            pdfSecurityManager.encryptPdf(
                                activity, operationUris.first(), output, options
                            ).isSuccess
                        }
                    }
                    QuickMode.SIGN -> {
                        val uri = operationUris.first()
                        val canvasPlacements = signatureItems
                        if (canvasPlacements.isNotEmpty()) {
                            finishPdfOutputOnDisk("${base}_signed.pdf", origText) { output ->
                                pdfSigner.signPdfWithItems(
                                    inputUri = uri, outputStream = output, items = canvasPlacements
                                ).success
                            }
                        } else {
                            // Read all view controls on Main before invoking PDFBox on IO.
                            val inputs = requireNotNull(signatureInput)
                            val bitmap = inputs.first
                            if (bitmap == null) {
                                withContext(Dispatchers.Main) {
                                    if (generation != selectionGeneration) return@withContext
                                    isExecuting = false
                                    binding.btnQuickToolExecute.isEnabled = true
                                    binding.btnQuickToolExecute.text = origText
                                    Toast.makeText(activity, "Please draw a signature first", Toast.LENGTH_SHORT).show()
                                }
                                return@launchTransient
                            }
                            finishPdfOutputOnDisk("${base}_signed.pdf", origText) { output ->
                                pdfSigner.addRelativeSignatureBitmaps(
                                    inputUri = uri, outputStream = output,
                                    bitmap = bitmap, pages = inputs.second,
                                    xFraction = signX, yFractionFromBottom = signY, scale = signScale
                                ).success
                            }
                        }
                    }
                    QuickMode.HTML_TO_PDF -> {
                        val input = htmlInput
                        if (input.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (generation != selectionGeneration) return@withContext
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, "Please enter HTML code", Toast.LENGTH_SHORT).show()
                            }
                            return@launchTransient
                        }

                        val htmlContent = if (!input.contains("<html", ignoreCase = true) && !input.contains("<!doctype", ignoreCase = true)) {
                            "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>body{font-family:sans-serif;padding:24px;line-height:1.6;color:#222;}</style></head><body>$input</body></html>"
                        } else {
                            input
                        }

                        finishPdfOutputOnDisk("html_document.pdf", origText) { output ->
                            htmlConverter.convertHtmlToPdf(activity, htmlContent, output).isSuccess
                        }
                    }
                    QuickMode.DOC_TO_PDF -> {
                        val uri = operationUris.first()
                        val format = OfficeSourceFormat.fromName(operationFileName)
                        val tempInput = File.createTempFile("office_input_", format.suffix, activity.cacheDir)
                        val tempPdf = File(activity.cacheDir, "office_output_${java.util.UUID.randomUUID()}.pdf")
                        var conversionError: String? = null
                        var success = false
                        var handedOff = false
                        try {
                            val source = activity.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("Cannot open selected document")
                            source.use { input ->
                                tempInput.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                    }
                                }
                            }
                            when (format) {
                                OfficeSourceFormat.XLSX -> officeConverter.convertXlsxToPdf(tempInput, tempPdf, activity, savedPrintLayout = xlsxPrintLayout == true)
                                OfficeSourceFormat.PPTX -> officeConverter.convertPptxToPdf(tempInput, tempPdf, renderMode = "BITMAP", context = activity)
                                OfficeSourceFormat.TEXT -> officeConverter.convertTextToPdf(tempInput, tempPdf, activity)
                                OfficeSourceFormat.DOCX -> officeConverter.convertDocxToPdf(tempInput, tempPdf, activity)
                            }
                            success = tempPdf.exists() && tempPdf.length() > 0
                            if (success) {
                                handedOff = finishDiskExecution(true, tempPdf, "${base}.pdf", origText)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (t: Exception) {
                            success = false
                            conversionError = t.message ?: "Failed to convert document"
                        } finally {
                            tempInput.delete()
                            if (!handedOff) tempPdf.delete()
                        }
                        if (!success) {
                            withContext(Dispatchers.Main) {
                                if (generation != selectionGeneration) return@withContext
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, conversionError ?: "Conversion failed. Please verify document format.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    QuickMode.PDF_TO_IMAGE -> {
                        val uri = operationUris.first()
                        val workJob = coroutineContext[Job]
                        val tempZip = File.createTempFile("quick_images_", ".zip", activity.cacheDir)
                        var handedOff = false
                        try {
                            var imagesWritten = 0
                            val zipOk = ZipOutputStream(tempZip.outputStream().buffered()).use { zip ->
                                imageConverter.pdfToImages(
                                    context = activity,
                                    inputUri = uri,
                                    format = ImageFormat.WEBP,
                                    dpi = 150,
                                    outputCallback = { pageNumber, bitmap ->
                                        workJob?.ensureActive()
                                        zip.putNextEntry(ZipEntry("${base}_page_$pageNumber.webp"))
                                        check(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, zip)) {
                                            "Could not compress PDF page $pageNumber"
                                        }
                                        zip.closeEntry()
                                        imagesWritten++
                                    }
                                ).isSuccess
                            }
                            workJob?.ensureActive()
                            handedOff = finishDiskExecution(
                                zipOk && imagesWritten > 0,
                                tempZip, "${base}_images.zip", origText
                            )
                        } finally {
                            if (!handedOff) tempZip.delete()
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            if (generation != selectionGeneration) return@withContext
                            isExecuting = false
                            binding.btnQuickToolExecute.isEnabled = true
                            binding.btnQuickToolExecute.text = origText
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (generation != selectionGeneration) return@withContext
                    isExecuting = false
                    binding.btnQuickToolExecute.isEnabled = true
                    binding.btnQuickToolExecute.text = origText
                    Toast.makeText(activity, "Operation failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { job -> job.invokeOnCompletion { signatureInput?.first?.recycle() } }
    }

    /**
     * Analyze the selected PDF and present its actual AcroForm fields for editing.
     * PDFs without editable AcroForm fields are reported explicitly instead of showing
     * an empty form-filling screen.
     */
    private fun openFillFormEditor() {
        val uri = selectedUris.firstOrNull()
        if (uri == null) {
            Toast.makeText(activity, "Please select a PDF document first", Toast.LENGTH_SHORT).show()
            return
        }
        if (isExecuting) return
        val generation = selectionGeneration
        isExecuting = true
        binding.btnQuickToolExecute.isEnabled = false
        binding.btnQuickToolExecute.text = "Inspecting form..."
        activeJob = launchTransient {
            val analysis = pdfFormFiller.analyzeForm(activity, uri)
            withContext(Dispatchers.Main) {
                if (generation != selectionGeneration || selectedUris.firstOrNull() != uri) return@withContext
                isExecuting = false
                binding.btnQuickToolExecute.isEnabled = true
                binding.btnQuickToolExecute.text = QuickMode.FILL_FORMS.buttonText
                if (analysis.errorMessage != null) {
                    Toast.makeText(activity, analysis.errorMessage, Toast.LENGTH_LONG).show()
                    return@withContext
                }
                if (!analysis.hasForm || analysis.fields.isEmpty()) {
                    Toast.makeText(
                        activity,
                        "This PDF has no editable AcroForm fields. Scanned or XFA-only forms are not editable here.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                if (analysis.fillableFields == 0) {
                    Toast.makeText(activity, "This PDF contains no editable form fields.", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                showAcroFormEditor(uri, generation, analysis.fields)
            }
        }
    }

    private fun showAcroFormEditor(
        uri: Uri, generation: Long,
        fields: List<com.veilframe.app.pdf.operations.FormField>
    ) {
        formEditor.show(fields,
            isCurrent = { generation == selectionGeneration && selectedUris.firstOrNull() == uri }
        ) { values, shouldFlatten, closeEditor ->
            if (isExecuting) return@show
                isExecuting = true
                binding.btnQuickToolExecute.isEnabled = false
                binding.btnQuickToolExecute.text = "Applying form values..."
                val outputName = "${primaryDocName.substringBeforeLast('.', primaryDocName)}_filled.pdf"
                activeJob = launchTransient {
                    val file = File.createTempFile("form_filled_", ".pdf", activity.cacheDir)
                    var handedOff = false
                    try {
                        val result = file.outputStream().buffered().use { output ->
                            pdfFormFiller.fillFormToStream(
                                context = activity,
                                inputUri = uri,
                                outputStream = output,
                                fieldValues = values,
                                flatten = shouldFlatten
                            )
                        }
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val legacyBytes = if (result.success && file.length() > 0L &&
                            onSaveFileFromDiskRequest == null) PdfQuickExportStage.legacyBytes(file) else null
                        withContext(Dispatchers.Main) {
                            if (generation != selectionGeneration || selectedUris.firstOrNull() != uri) return@withContext
                            isExecuting = false
                            binding.btnQuickToolExecute.isEnabled = true
                            binding.btnQuickToolExecute.text = QuickMode.FILL_FORMS.buttonText
                            if (generation == selectionGeneration && selectedUris.firstOrNull() == uri) {
                                if (result.success && file.length() > 0L) {
                                    val diskSave = onSaveFileFromDiskRequest
                                    if (diskSave != null) {
                                        diskSave(file, outputName)
                                        handedOff = true
                                    } else {
                                        onSaveFileRequest(requireNotNull(legacyBytes), outputName)
                                    }
                                    markExportPending()
                                    closeEditor()
                                    Toast.makeText(activity, "${result.fieldsUpdated} field(s) filled", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, result.errorMessage ?: "Unable to fill this PDF form", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        withContext(Dispatchers.Main) {
                            if (generation == selectionGeneration && selectedUris.firstOrNull() == uri) {
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = QuickMode.FILL_FORMS.buttonText
                                Toast.makeText(activity, "Form export failed: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        if (!handedOff) file.delete()
                    }
                }
        }
    }

    /**
     * Deliver a generated export to the SAF picker without reading large PDFs or ZIPs
     * back into heap. Returns true only when picker ownership is transferred.
     */
    /**
     * Run the existing PDF engine against a buffered, disk-backed sink.
     * The SAF picker takes ownership only after a complete successful export;
     * otherwise the temporary PDF is removed, including on cancellation.
     */
    private suspend fun finishPdfOutputOnDisk(
        fileName: String,
        originalButtonText: String,
        generate: suspend (java.io.OutputStream) -> Boolean
    ) {
        PdfQuickExportStage.generate(activity.cacheDir,generate) { success,file ->
            finishDiskExecution(success,file,fileName,originalButtonText)
        }
    }

    private suspend fun finishDiskExecution(
        success: Boolean, file: File, fileName: String, origBtnText: String
    ): Boolean {
        val owner = requireNotNull(kotlin.coroutines.coroutineContext[ExecutionGeneration])
        val complete = success && file.isFile && file.length() > 0L
        val legacyBytes = if (complete && onSaveFileFromDiskRequest == null) PdfQuickExportStage.legacyBytes(file) else null
        return withContext(Dispatchers.Main) {
            if (owner.value != selectionGeneration) return@withContext false
            isExecuting = false
            binding.btnQuickToolExecute.isEnabled = true
            binding.btnQuickToolExecute.text = origBtnText
            if (!complete) {
                Toast.makeText(activity, "Operation completed with errors or empty output", Toast.LENGTH_LONG).show()
                false
            } else {
                val diskSave = onSaveFileFromDiskRequest
                if (diskSave != null) {
                    diskSave(file, fileName)
                    markExportPending()
                    true
                } else {
                    onSaveFileRequest(requireNotNull(legacyBytes), fileName)
                    markExportPending()
                    false
                }
            }
        }
    }

    private fun finishExecution(success: Boolean, data: ByteArray, fileName: String, origBtnText: String) {
        isExecuting = false
        binding.btnQuickToolExecute.isEnabled = true
        binding.btnQuickToolExecute.text = origBtnText

        if (success && data.isNotEmpty()) {
            onSaveFileRequest(data, fileName)
            markExportPending()
        } else {
            Toast.makeText(activity, "Operation completed with errors or empty output", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "VeilFrame.PdfQuickTool"

        val DOC_MIME_TYPES = arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain"
        )

        /** Parse a bounded manual page-order prefix without silently ignoring errors. */
        fun parsePageSequence(input: String, totalPages: Int = 0): List<Int> =
            PdfPageSequenceParser.parse(input, totalPages)

    }

    private fun showFullScreenPagePreview(uri: Uri, pageIndex: Int) {
        pagePreviews.showDialog(uri,pageIndex)
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerQuickToolDocInfo.visibility = View.GONE
        binding.containerQuickToolEmptyState.visibility = View.VISIBLE
        binding.btnPdfQuickToolReset.visibility = View.GONE
        mergeFileListAdapter?.clearAll()
        binding.viewSignaturePad.clear()
        binding.ivSignMiniaturePreview.setImageDrawable(null)
        binding.ivSignMiniaturePreview.visibility = View.GONE
        binding.tvSignIndicatorPlaceholder.visibility = View.VISIBLE
        binding.ivSignPagePreview.setImageDrawable(null)
        signPosXPercent = 70.0f
        signPosYPercent = 10.0f
        updateSignPlacementIndicator(70.0f, 10.0f)
        mergePageAdapter?.setItems(emptyList())
        binding.etCustomMergeOrder.setText("")
        binding.etCustomPageOrder.setText("")
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
