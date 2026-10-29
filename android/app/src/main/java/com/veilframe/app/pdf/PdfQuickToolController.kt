package com.veilframe.app.pdf

import android.annotation.SuppressLint
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Build
import java.io.FileInputStream
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.content.res.ColorStateList
import com.veilframe.app.qr.ui.QrColorPickerDialog
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.apache.poi.xwpf.usermodel.XWPFDocument
import com.veilframe.app.pdf.operations.OpenXmlDocumentParser
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
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
import com.veilframe.app.pdf.operations.MergeItem
import com.veilframe.app.pdf.operations.MergeItemType
import com.veilframe.app.pdf.operations.MergePageItem
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
import com.veilframe.app.pdf.ui.PdfMergePageAdapter
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
    private val onPickFileRequest: (mimeTypes: Array<String>, multiple: Boolean) -> Unit,
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
        HTML_TO_PDF("HTML to PDF", "Convert HTML code to PDF document", "Convert HTML to PDF"),
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
        activeJob?.cancel()
        selectedUris.clear()
        isExecuting = false
        thumbnailLoader.clearCache()
        binding.pdfSignCanvasView.reset()
        binding.layoutPdfSignCanvasOverlay.visibility = View.GONE
        binding.viewSignaturePad.clear()
        binding.viewSignaturePad.isDrawingEnabled = false
        binding.ivSignMiniaturePreview.setImageDrawable(null)
        binding.ivSignMiniaturePreview.visibility = View.GONE
        binding.tvSignIndicatorPlaceholder.visibility = View.VISIBLE
        binding.ivSignPagePreview.setImageDrawable(null)
        binding.containerQuickToolDocInfo.visibility = View.GONE
        binding.containerQuickToolEmptyState.visibility = View.VISIBLE
        binding.btnPdfQuickToolReset.visibility = View.GONE
        binding.panelParamDocPreview.visibility = View.GONE
        binding.cardDocVisualPreview.visibility = View.GONE
        binding.ivDocVisualPreview.setImageDrawable(null)
        binding.pbDocVisualLoading.visibility = View.GONE
        binding.tvDocPreviewSummary.text = ""
        binding.tvDocPreviewContent.text = ""
        binding.etCustomMergeOrder.setText("")
        binding.etCustomPageOrder.setText("")
        mergeFileListAdapter?.clearAll()
        mergePageAdapter?.setItems(emptyList())
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
            override fun isLongPressDragEnabled(): Boolean = (activeMode == QuickMode.REORDER)

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
                Toast.makeText(activity, "Enter valid page sequence (e.g. 3>4>2>1>5 or 1-3, 5)", Toast.LENGTH_SHORT).show()
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
                selectedUris.clear()
                selectedUris.addAll(newOrder)
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
            selectedUris.clear()
            mergeFileListAdapter?.clearAll()
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
            if (isPages) {
                refreshMergePageItems()
            }
        }

        binding.btnApplyMergeOrder.setOnClickListener {
            applyCustomMergeOrderText()
        }

        binding.btnResetMergeOrder.setOnClickListener {
            refreshMergePageItems()
        }
    }

    private fun refreshMergePageItems() {
        if (selectedUris.isEmpty()) {
            mergePageAdapter?.setItems(emptyList())
            return
        }
        scope.launch(Dispatchers.IO) {
            val mergeItems = selectedUris.mapIndexed { idx, uri ->
                val mime = activity.contentResolver.getType(uri) ?: ""
                val isImg = mime.startsWith("image/") ||
                    uri.toString().endsWith(".jpg", true) ||
                    uri.toString().endsWith(".jpeg", true) ||
                    uri.toString().endsWith(".png", true) ||
                    uri.toString().endsWith(".webp", true)
                val type = if (isImg) MergeItemType.IMAGE else MergeItemType.PDF
                var pageCount = 1
                if (!isImg) {
                    try {
                        activity.contentResolver.openInputStream(uri)?.use { stream ->
                            PDDocument.load(stream).use { doc -> pageCount = doc.numberOfPages }
                        }
                    } catch (_: Exception) {}
                }
                MergeItem(
                    id = "doc_$idx",
                    uri = uri,
                    name = queryDocumentName(uri),
                    type = type,
                    pageCount = pageCount
                )
            }
            val pageItems = pdfMerger.buildPageItems(mergeItems)
            withContext(Dispatchers.Main) {
                mergePageAdapter?.setItems(pageItems)
            }
        }
    }

    private fun applyCustomMergeOrderText() {
        val input = binding.etCustomMergeOrder.text?.toString().orEmpty()
        val currentItems = mergePageAdapter?.getItems() ?: emptyList()
        val requestedOrder = parsePageSequence(input, currentItems.size)
        if (requestedOrder.isEmpty() || currentItems.isEmpty()) {
            Toast.makeText(activity, "Enter page sequence (e.g. 1, 2, 7, 6, 3, 4, 5, 10, 8, 9)", Toast.LENGTH_SHORT).show()
            return
        }

        val itemMap = currentItems.associateBy { it.overallPageNumber }
        val reordered = mutableListOf<MergePageItem>()
        for (num in requestedOrder) {
            itemMap[num]?.let { reordered.add(it) }
        }
        for (item in currentItems) {
            if (!reordered.contains(item)) {
                reordered.add(item)
            }
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

            if (activeMode == QuickMode.DOC_TO_PDF) {
                binding.tvQuickToolDocMeta.text = "Reading document..."
                binding.panelParamDocPreview.visibility = View.VISIBLE
                binding.cardDocVisualPreview.visibility = View.VISIBLE
                binding.pbDocVisualLoading.visibility = View.VISIBLE
                binding.ivDocVisualPreview.setImageDrawable(null)
                inspectAndPreviewOfficeDoc(uri)
            } else {
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
                                    if (activeMode == QuickMode.SIGN) {
                                        binding.viewSignaturePad.isDrawingEnabled = true
                                        refreshSignPagePreview()
                                        updateSignPlacementIndicator(signPosXPercent, signPosYPercent)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            binding.tvQuickToolDocMeta.text = "Unable to read page count"
                        }
                    }
                }
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

    private fun inspectAndPreviewOfficeDoc(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val lower = primaryDocName.lowercase()
            var summary = "Office Document"
            var previewText = ""
            var metaText = "Ready to convert"
            var previewBitmap: Bitmap? = null

            try {
                when {
                    lower.endsWith(".docx") || lower.endsWith(".doc") -> {
                        metaText = "Word Document (${lower.substringAfterLast('.')})"
                        val paragraphs = mutableListOf<String>()
                        var tablesCount = 0

                        val tempDoc = File(activity.cacheDir, "preview_temp_${System.currentTimeMillis()}.docx")
                        try {
                            activity.contentResolver.openInputStream(uri)?.use { input ->
                                tempDoc.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (tempDoc.exists() && tempDoc.length() > 0) {
                                try {
                                    FileInputStream(tempDoc).use { stream ->
                                        val doc = XWPFDocument(stream)
                                        for (p in doc.paragraphs) {
                                            val t = p.text?.trim()
                                            if (!t.isNullOrBlank()) {
                                                paragraphs.add(t)
                                            }
                                        }
                                        tablesCount = doc.tables.size
                                    }
                                } catch (t: Throwable) {
                                    Log.w(TAG, "POI DOCX preview extraction failed: ${t.message}; using zip extractor", t)
                                    val text = officeConverter.extractDocxTextViaZip(tempDoc)
                                    if (text.isNotBlank()) {
                                        paragraphs.addAll(text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() })
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to copy DOCX for preview: ${t.message}", t)
                        } finally {
                            tempDoc.delete()
                        }

                        if (paragraphs.isNotEmpty()) {
                            summary = "Word Document • ${paragraphs.size} paragraphs" + (if (tablesCount > 0) ", $tablesCount tables" else "")
                            previewText = paragraphs.take(15).joinToString("\n\n")
                        } else {
                            summary = "Word Document ($primaryDocName)"
                            previewText = "Word document selected.\nConversion engine will translate document structures directly into PDF pages."
                        }
                        previewBitmap = renderOfficePreviewBitmap("DOCX", primaryDocName, paragraphs, tablesCount, null, null)
                    }
                    lower.endsWith(".xlsx") || lower.endsWith(".xls") -> {
                        metaText = "Excel Spreadsheet (${lower.substringAfterLast('.')})"
                        val sheetGrid = mutableListOf<List<String>>()
                        try {
                            activity.contentResolver.openInputStream(uri)?.use { stream ->
                                val tempFile = File(activity.cacheDir, "preview_temp_${System.currentTimeMillis()}.xlsx")
                                val wb = try {
                                    tempFile.outputStream().use { output -> stream.copyTo(output) }
                                    OpenXmlDocumentParser.parseXlsx(tempFile)
                                } finally {
                                    tempFile.delete()
                                }
                                val sheetCount = wb.sheets.size
                                val sheetNames = wb.sheets.joinToString(", ") { it.name }
                                summary = "Excel Workbook • $sheetCount sheet(s) ($sheetNames)"
                                val firstSheet = wb.sheets.firstOrNull()
                                val previewSb = StringBuilder()
                                if (firstSheet != null) {
                                    previewSb.append("Sheet: ${firstSheet.name}\n")
                                    val rowCount = minOf(firstSheet.rows.size, 15)
                                    for (r in 0 until rowCount) {
                                        val row = firstSheet.rows[r]
                                        val cells = row.take(6).filter { it.isNotBlank() }
                                        if (cells.isNotEmpty()) {
                                            sheetGrid.add(cells)
                                            previewSb.append("[ ").append(cells.joinToString(" | ")).append(" ]\n")
                                        }
                                    }
                                }
                                previewText = previewSb.toString().trim()
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "XLSX preview failed: ${t.message}", t)
                            summary = "Excel Spreadsheet ($primaryDocName)"
                            previewText = "Spreadsheet document selected.\nData cells and layout grid will be paginated into landscape PDF."
                        }
                        previewBitmap = renderOfficePreviewBitmap("XLSX", primaryDocName, emptyList(), 1, sheetGrid, null)
                    }
                    lower.endsWith(".pptx") || lower.endsWith(".ppt") -> {
                        metaText = "PowerPoint Presentation (${lower.substringAfterLast('.')})"
                        val slideTexts = mutableListOf<String>()
                        try {
                            activity.contentResolver.openInputStream(uri)?.use { stream ->
                                val ppt = XMLSlideShow(stream)
                                val slideCount = ppt.slides.size
                                summary = "PowerPoint Presentation • $slideCount slide(s)"
                                val previewSb = StringBuilder()
                                for ((idx, slide) in ppt.slides.take(4).withIndex()) {
                                    previewSb.append("Slide ${idx + 1}:\n")
                                    for (shape in slide.shapes) {
                                        if (shape is XSLFTextShape && shape.text.isNotBlank()) {
                                            val t = shape.text.trim().replace("\n", " ")
                                            slideTexts.add(t)
                                            previewSb.append("  • ").append(t).append("\n")
                                        }
                                    }
                                    previewSb.append("\n")
                                }
                                previewText = previewSb.toString().trim()
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "POI PPTX preview failed: ${t.message}", t)
                            summary = "PowerPoint Presentation ($primaryDocName)"
                            previewText = "Presentation slides will be rendered as high-fidelity PDF pages."
                        }
                        previewBitmap = renderOfficePreviewBitmap("PPTX", primaryDocName, emptyList(), 0, null, slideTexts)
                    }
                    lower.endsWith(".txt") || lower.endsWith(".rtf") -> {
                        metaText = "Text Document (${lower.substringAfterLast('.')})"
                        val lines = mutableListOf<String>()
                        activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            var count = 0
                            reader.forEachLine { line ->
                                count++
                                if (lines.size < 40 && line.isNotBlank()) {
                                    lines.add(line)
                                }
                            }
                            summary = "Text Document • $count lines"
                            previewText = lines.take(20).joinToString("\n")
                        }
                        previewBitmap = renderOfficePreviewBitmap("TXT", primaryDocName, lines, 0, null, null)
                    }
                    else -> {
                        metaText = "Office Document"
                        summary = "Document Selected • $primaryDocName"
                        previewText = "Document loaded and ready for conversion to PDF."
                        previewBitmap = renderOfficePreviewBitmap("DOC", primaryDocName, listOf("Office document loaded", primaryDocName), 0, null, null)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "inspectAndPreviewOfficeDoc failed: ${t.message}", t)
                summary = "Document Selected"
                previewText = "Ready to convert: $primaryDocName"
            }

            if (previewText.isBlank()) {
                previewText = "Preview not available for this document structure. Ready to convert."
            }

            withContext(Dispatchers.Main) {
                binding.tvQuickToolDocMeta.text = metaText
                binding.panelParamDocPreview.visibility = View.VISIBLE
                binding.tvDocPreviewSummary.text = summary
                binding.tvDocPreviewContent.text = previewText
                if (previewBitmap != null) {
                    binding.ivDocVisualPreview.setImageBitmap(previewBitmap)
                    binding.cardDocVisualPreview.visibility = View.VISIBLE
                } else {
                    binding.cardDocVisualPreview.visibility = View.GONE
                }
                binding.pbDocVisualLoading.visibility = View.GONE
            }
        }
    }

    private fun renderOfficePreviewBitmap(
        docType: String,
        docTitle: String,
        paragraphs: List<String>,
        tablesCount: Int,
        sheetGrid: List<List<String>>?,
        slideElements: List<String>?
    ): Bitmap? {
        return try {
            val width = 720
            val height = if (docType == "PPTX") 480 else 1018
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Clean white paper
            canvas.drawColor(Color.WHITE)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E5E7EB")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, borderPaint)

            val marginX = 48f
            val marginY = 48f
            val printableWidth = (width - marginX * 2).toInt()
            var currentY = marginY

            // 1. Doc Type Badge (Top Right)
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (docType) {
                    "DOCX" -> Color.parseColor("#EFF6FF")
                    "XLSX" -> Color.parseColor("#ECFDF5")
                    "PPTX" -> Color.parseColor("#FFF7ED")
                    else -> Color.parseColor("#F3F4F6")
                }
                style = Paint.Style.FILL
            }
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (docType) {
                    "DOCX" -> Color.parseColor("#1D4ED8")
                    "XLSX" -> Color.parseColor("#047857")
                    "PPTX" -> Color.parseColor("#C2410C")
                    else -> Color.parseColor("#4B5563")
                }
                textSize = 22f
                isFakeBoldText = true
            }

            val badgeRect = RectF(width - marginX - 90f, marginY - 10f, width - marginX, marginY + 26f)
            canvas.drawRoundRect(badgeRect, 8f, 8f, badgeBgPaint)
            val badgeTextWidth = badgeTextPaint.measureText(docType)
            canvas.drawText(docType, badgeRect.centerX() - badgeTextWidth / 2f, badgeRect.centerY() + 8f, badgeTextPaint)

            // 2. Document Title
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111827")
                textSize = 28f
                isFakeBoldText = true
            }
            val cleanTitle = if (docTitle.length > 34) docTitle.take(32) + "…" else docTitle
            canvas.drawText(cleanTitle, marginX, currentY + 18f, titlePaint)
            currentY += 44f

            // Divider
            val dividerPaint = Paint().apply {
                color = Color.parseColor("#F3F4F6")
                strokeWidth = 2f
            }
            canvas.drawLine(marginX, currentY, width - marginX, currentY, dividerPaint)
            currentY += 24f

            // 3. Body Content
            when (docType) {
                "XLSX" -> {
                    val grid = sheetGrid ?: emptyList()
                    val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#1F2937")
                        textSize = 18f
                    }
                    val headerCellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#374151")
                        textSize = 18f
                        isFakeBoldText = true
                    }
                    val colWidth = printableWidth / 4f
                    val rowHeight = 36f

                    val colHeaders = listOf("A", "B", "C", "D")
                    for (c in 0 until 4) {
                        val x = marginX + c * colWidth
                        val headerRect = RectF(x, currentY, x + colWidth, currentY + rowHeight)
                        canvas.drawRect(headerRect, Paint().apply { color = Color.parseColor("#F9FAFB") })
                        canvas.drawRect(headerRect, borderPaint)
                        canvas.drawText(colHeaders[c], x + colWidth / 2f - 6f, currentY + 24f, headerCellPaint)
                    }
                    currentY += rowHeight

                    for (r in 0 until minOf(grid.size, 18)) {
                        val row = grid[r]
                        for (c in 0 until 4) {
                            val x = marginX + c * colWidth
                            val cellRect = RectF(x, currentY, x + colWidth, currentY + rowHeight)
                            canvas.drawRect(cellRect, borderPaint)
                            val text = if (c < row.size) row[c] else ""
                            val truncatedText = if (text.length > 12) text.take(11) + "…" else text
                            if (truncatedText.isNotEmpty()) {
                                canvas.drawText(truncatedText, x + 8f, currentY + 24f, cellPaint)
                            }
                        }
                        currentY += rowHeight
                        if (currentY > height - marginY - 40f) break
                    }
                }
                "PPTX" -> {
                    val slideTexts = slideElements ?: emptyList()
                    val slideTitle = slideTexts.firstOrNull() ?: "Presentation Slide"
                    val slideTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#0F172A")
                        textSize = 32f
                        isFakeBoldText = true
                    }
                    canvas.drawText(slideTitle.take(30), marginX + 16f, currentY + 36f, slideTitlePaint)
                    currentY += 60f

                    val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#2563EB")
                        style = Paint.Style.FILL
                    }
                    val bulletTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#334155")
                        textSize = 20f
                    }

                    for (bullet in slideTexts.drop(1).take(5)) {
                        canvas.drawCircle(marginX + 20f, currentY + 12f, 4f, bulletPaint)
                        val text = if (bullet.length > 55) bullet.take(53) + "…" else bullet
                        canvas.drawText(text, marginX + 36f, currentY + 18f, bulletTextPaint)
                        currentY += 36f
                    }
                }
                else -> {
                    val bodyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#374151")
                        textSize = 20f
                        letterSpacing = 0.01f
                    }
                    val headingTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#111827")
                        textSize = 24f
                        isFakeBoldText = true
                    }

                    val maxBodyY = height - marginY - 50f
                    for ((idx, para) in paragraphs.withIndex()) {
                        if (currentY >= maxBodyY) break
                        val isHeading = idx == 0 || (para.length in 4..45 && !para.endsWith(".") && !para.endsWith(","))
                        val paint = if (isHeading) headingTextPaint else bodyTextPaint

                        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(para, 0, para.length, paint, printableWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(4f, 1.15f)
                                .setIncludePad(false)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(para, paint, printableWidth, Layout.Alignment.ALIGN_NORMAL, 1.15f, 4f, false)
                        }

                        if (currentY + layout.height > maxBodyY) {
                            canvas.save()
                            canvas.clipRect(marginX, currentY, marginX + printableWidth, maxBodyY)
                            canvas.translate(marginX, currentY)
                            layout.draw(canvas)
                            canvas.restore()
                            currentY = maxBodyY + 10f
                            break
                        } else {
                            canvas.save()
                            canvas.translate(marginX, currentY)
                            layout.draw(canvas)
                            canvas.restore()
                            currentY += layout.height + if (isHeading) 16f else 12f
                        }
                    }

                    if (tablesCount > 0 && currentY < maxBodyY - 60f) {
                        val tableY = currentY + 8f
                        val tableW = printableWidth.toFloat()
                        val tableH = minOf(90f, maxBodyY - tableY)
                        val tableRect = RectF(marginX, tableY, marginX + tableW, tableY + tableH)

                        val tableFill = Paint().apply { color = Color.parseColor("#F9FAFB") }
                        canvas.drawRect(tableRect, tableFill)
                        canvas.drawRect(tableRect, borderPaint)

                        canvas.drawLine(marginX, tableY + 30f, marginX + tableW, tableY + 30f, borderPaint)
                        canvas.drawLine(marginX + tableW / 2f, tableY, marginX + tableW / 2f, tableY + tableH, borderPaint)

                        val tableLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#6B7280")
                            textSize = 16f
                            isFakeBoldText = true
                        }
                        canvas.drawText("Table 1: Data Grid", marginX + 12f, tableY + 20f, tableLabelPaint)
                    }
                }
            }

            // 4. Subtle Page 1 Footer
            val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9CA3AF")
                textSize = 16f
            }
            val footerText = "— Page 1 —"
            val footerWidth = footerPaint.measureText(footerText)
            canvas.drawText(footerText, (width - footerWidth) / 2f, height - marginY / 2f, footerPaint)

            bitmap
        } catch (t: Throwable) {
            Log.e(TAG, "renderOfficePreviewBitmap failed: ${t.message}", t)
            null
        }
    }

    private fun updateMultiFileList() {
        binding.tvMultiFileCount.text = "SELECTED FILES (${selectedUris.size})"
        mergeFileListAdapter?.setUris(selectedUris)
        if (binding.chipMergeModePages.isChecked) {
            refreshMergePageItems()
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
            binding.chipSignPageFirst.isChecked -> 0
            binding.chipSignPageAll.isChecked -> 0
            binding.chipSignPageCustom.isChecked -> {
                val p = binding.etSignCustomPage.text?.toString()?.toIntOrNull() ?: 1
                (p - 1).coerceIn(0, primaryDocPages - 1)
            }
            else -> (primaryDocPages - 1).coerceAtLeast(0)
        }

        binding.pbSignPreviewLoading.visibility = View.VISIBLE
        scope.launch(Dispatchers.IO) {
            val bmp = thumbnailLoader.loadPageThumbnail(uri, targetPageIdx, 600)
            withContext(Dispatchers.Main) {
                binding.pbSignPreviewLoading.visibility = View.GONE
                if (bmp != null) {
                    binding.ivSignPagePreview.setImageBitmap(bmp)
                } else {
                    binding.ivSignPagePreview.setImageResource(R.drawable.ic_pdf_file)
                }
            }
        }
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

        val pad = SignaturePadView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * activity.resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.parseColor("#18181C"))
            setInkColor(selectedSignColor)
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
                topMargin = (8 * activity.resources.displayMetrics.density).toInt()
            }
            setOnClickListener { pad.clear() }
        }

        padContainer.addView(pad)
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
                scope.launch(Dispatchers.IO) {
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

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                val outStream = ByteArrayOutputStream()
                val base = primaryDocName.removeSuffix(".pdf")
                val result = pdfSigner.signPdfWithItems(
                    inputUri = uri,
                    outputStream = outStream,
                    items = placements
                )
                withContext(Dispatchers.Main) {
                    binding.btnSignCanvasSave.isEnabled = true
                    if (result.success) {
                        binding.layoutPdfSignCanvasOverlay.visibility = View.GONE
                        finishExecution(true, outStream.toByteArray(), "${base}_signed.pdf", origText)
                    } else {
                        isExecuting = false
                        Toast.makeText(activity, "Export failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
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
                        val isInterleaved = binding.chipMergeModePages.isChecked && (mergePageAdapter?.itemCount ?: 0) > 0
                        val result = if (isInterleaved) {
                            val pageItems = mergePageAdapter?.getItems() ?: emptyList()
                            pdfMerger.mergePageItems(activity, pageItems, outStream)
                        } else {
                            pdfMerger.mergePdfs(activity, selectedUris, outStream)
                        }
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
                        val order = rotateThumbnailAdapter?.getPageOrder() ?: (1..primaryDocPages).toList()
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
                        val canvasPlacements = withContext(Dispatchers.Main) {
                            binding.pdfSignCanvasView.getAllPlacements()
                        }

                        val result = if (canvasPlacements.isNotEmpty()) {
                            pdfSigner.signPdfWithItems(
                                inputUri = uri,
                                outputStream = outStream,
                                items = canvasPlacements
                            )
                        } else {
                            val opacity = (binding.sliderSignOpacity.value / 100f).coerceIn(0.1f, 1.0f)
                            val sigBmp = withContext(Dispatchers.Main) {
                                binding.viewSignaturePad.getSignatureBitmap(
                                    targetColor = selectedSignColor,
                                    opacity = opacity
                                )
                            }
                            if (sigBmp == null) {
                                withContext(Dispatchers.Main) {
                                    isExecuting = false
                                    binding.btnQuickToolExecute.isEnabled = true
                                    binding.btnQuickToolExecute.text = origText
                                    Toast.makeText(activity, "Please draw a signature first", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }

                            val targetPages = when {
                                binding.chipSignPageFirst.isChecked -> listOf(0)
                                binding.chipSignPageAll.isChecked -> (0 until primaryDocPages).toList()
                                binding.chipSignPageCustom.isChecked -> {
                                    val inputPage = binding.etSignCustomPage.text?.toString()?.toIntOrNull() ?: 1
                                    listOf((inputPage - 1).coerceIn(0, primaryDocPages - 1))
                                }
                                else -> listOf((primaryDocPages - 1).coerceAtLeast(0))
                            }

                            val sigWidth = 180f * signScaleFactor
                            val sigHeight = 60f * signScaleFactor
                            val xPct = (signPosXPercent / 100f).coerceIn(0f, 1f)
                            val yPct = (signPosYPercent / 100f).coerceIn(0f, 1f)
                            val posX = ((595f - sigWidth) * xPct).coerceAtLeast(0f)
                            val posY = ((842f - sigHeight) * yPct).coerceAtLeast(0f)

                            val placements = targetPages.map { pIndex ->
                                SignaturePlacement(
                                    pageIndex = pIndex,
                                    x = posX,
                                    y = posY,
                                    width = sigWidth,
                                    height = sigHeight
                                )
                            }

                            pdfSigner.addSignatureBitmaps(
                                inputUri = uri,
                                outputStream = outStream,
                                signatureBitmap = sigBmp,
                                placements = placements
                            )
                        }
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
                                Toast.makeText(activity, "Please enter HTML code", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        val htmlContent = if (!input.contains("<html", ignoreCase = true) && !input.contains("<!doctype", ignoreCase = true)) {
                            "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>body{font-family:sans-serif;padding:24px;line-height:1.6;color:#222;}</style></head><body>$input</body></html>"
                        } else {
                            input
                        }

                        val result = htmlConverter.convertHtmlToPdf(activity, htmlContent, outStream)
                        withContext(Dispatchers.Main) {
                            finishExecution(result.isSuccess, outStream.toByteArray(), "html_document.pdf", origText)
                        }
                    }
                    QuickMode.DOC_TO_PDF -> {
                        val uri = selectedUris.first()
                        val lowerName = primaryDocName.lowercase()
                        val ext = when {
                            lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") -> ".xlsx"
                            lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt") -> ".pptx"
                            lowerName.endsWith(".txt") || lowerName.endsWith(".rtf") || lowerName.endsWith(".log") -> ".txt"
                            else -> ".docx"
                        }

                        val tempInput = File(activity.cacheDir, "temp_doc_${System.currentTimeMillis()}$ext")
                        activity.contentResolver.openInputStream(uri)?.use { input ->
                            tempInput.outputStream().use { output -> input.copyTo(output) }
                        }
                        val tempPdf = File(activity.cacheDir, "temp_out_${System.currentTimeMillis()}.pdf")

                        var conversionError: String? = null
                        try {
                            when (ext) {
                                ".xlsx" -> officeConverter.convertXlsxToPdf(tempInput, tempPdf, activity)
                                ".pptx" -> officeConverter.convertPptxToPdf(tempInput, tempPdf, renderMode = "BITMAP", context = activity)
                                ".txt" -> officeConverter.convertTextToPdf(tempInput, tempPdf, activity)
                                else -> {
                                    try {
                                        officeConverter.convertDocxToPdf(tempInput, tempPdf, activity)
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "DOCX conversion failed: ${t.message}; using fallback text extractor", t)
                                        val extracted = officeConverter.extractDocxTextViaZip(tempInput)
                                        if (extracted.isNotBlank()) {
                                            val tempText = File(activity.cacheDir, "temp_docx_text_${System.currentTimeMillis()}.txt")
                                            try {
                                                tempText.writeText(extracted)
                                                officeConverter.convertTextToPdf(tempText, tempPdf, activity)
                                            } finally {
                                                tempText.delete()
                                            }
                                        } else {
                                            officeConverter.convertTextToPdf(tempInput, tempPdf, activity)
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            conversionError = t.message ?: "Failed to convert document"
                        }

                        val success = tempPdf.exists() && tempPdf.length() > 0
                        if (success) {
                            tempPdf.inputStream().use { it.copyTo(outStream) }
                        }
                        tempInput.delete()
                        tempPdf.delete()
                        withContext(Dispatchers.Main) {
                            if (success) {
                                finishExecution(true, outStream.toByteArray(), "${base}.pdf", origText)
                            } else {
                                isExecuting = false
                                binding.btnQuickToolExecute.isEnabled = true
                                binding.btnQuickToolExecute.text = origText
                                Toast.makeText(activity, conversionError ?: "Conversion failed. Please verify document format.", Toast.LENGTH_LONG).show()
                            }
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
            } catch (e: Throwable) {
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

    companion object {
        private const val TAG = "VeilFrame.PdfQuickTool"

        val DOC_MIME_TYPES = arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint",
            "text/plain",
            "application/rtf",
            "text/rtf"
        )

        /**
         * Robust page sequence parser supporting:
         * - Arbitrary delimiters: ',', ';', '>', '->', spaces, '|' (e.g. "3>4>2>1>5", "1, 2, 7, 6, 3, 4, 5, 10, 8, 9")
         * - Ranges: "1-4" or "1..4" -> [1, 2, 3, 4]
         * - Reverse ranges: "5-1" -> [5, 4, 3, 2, 1]
         * - Keywords: "reverse", "rev", "odd", "even", "reset", "all"
         */
        fun parsePageSequence(input: String, totalPages: Int = 0): List<Int> {
            if (input.isBlank()) return emptyList()
            val clean = input.trim().lowercase()

            if (totalPages > 0) {
                if (clean == "reverse" || clean == "rev") {
                    return (totalPages downTo 1).toList()
                }
                if (clean == "odd" || clean == "odds") {
                    return (1..totalPages step 2).toList()
                }
                if (clean == "even" || clean == "evens") {
                    return (2..totalPages step 2).toList()
                }
                if (clean == "reset" || clean == "all") {
                    return (1..totalPages).toList()
                }
            }

            val result = mutableListOf<Int>()
            // Split on delimiters: >, ->, commas, semicolons, or pipes
            val tokens = input.split(Regex("[,;>|]+|\\s*->\\s*"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for (token in tokens) {
                // Range check: 1-4 or 1..4 or 4-1
                val rangeMatch = Regex("""^(\d+)\s*(?:-|–|\.\.)\s*(\d+)$""").matchEntire(token)
                if (rangeMatch != null) {
                    val start = rangeMatch.groupValues[1].toIntOrNull() ?: continue
                    val end = rangeMatch.groupValues[2].toIntOrNull() ?: continue
                    if (start > 0 && end > 0) {
                        if (start <= end) {
                            for (p in start..end) {
                                if (totalPages <= 0 || p <= totalPages) result.add(p)
                            }
                        } else {
                            for (p in start downTo end) {
                                if (totalPages <= 0 || p <= totalPages) result.add(p)
                            }
                        }
                    }
                } else {
                    // Single page or space-separated tokens
                    val numbers = Regex("""\d+""").findAll(token).mapNotNull { it.value.toIntOrNull() }
                    for (num in numbers) {
                        if (num > 0 && (totalPages <= 0 || num <= totalPages)) {
                            result.add(num)
                        }
                    }
                }
            }
            return result
        }
    }

    private fun showFullScreenPagePreview(uri: Uri, pageIndex: Int) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_pdf_page_preview, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPreviewPageTitle)
        val btnClose = dialogView.findViewById<View>(R.id.btnPreviewClose)
        val ivPreview = dialogView.findViewById<ImageView>(R.id.ivPreviewFullPage)
        val pbLoading = dialogView.findViewById<ProgressBar>(R.id.pbPreviewLoading)

        tvTitle.text = "Page ${pageIndex + 1} Preview"
        btnClose.setOnClickListener { dialog.dismiss() }

        pbLoading.visibility = View.VISIBLE
        scope.launch(Dispatchers.IO) {
            val highResBitmap = thumbnailLoader.loadPageThumbnail(uri, pageIndex, 1080)
            withContext(Dispatchers.Main) {
                pbLoading.visibility = View.GONE
                if (highResBitmap != null) {
                    ivPreview.setImageBitmap(highResBitmap)
                } else {
                    ivPreview.setImageResource(R.drawable.ic_pdf_file)
                }
            }
        }

        dialog.show()
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
