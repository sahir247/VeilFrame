package com.veilframe.app.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutPdfImpositionBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.pdf.imposition.BindingDirection
import com.veilframe.app.pdf.imposition.ImpositionConfig
import com.veilframe.app.pdf.imposition.ImpositionEngine
import com.veilframe.app.pdf.imposition.ImpositionPdfExporter
import com.veilframe.app.pdf.imposition.ImpositionToolMode
import com.veilframe.app.pdf.imposition.NUpLayoutMode
import com.veilframe.app.pdf.imposition.PaperPreset
import com.veilframe.app.pdf.imposition.PaperSize
import com.veilframe.app.pdf.imposition.SheetLayout
import com.veilframe.app.pdf.imposition.ZineType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller orchestrating Print Imposition Studio: Booklets, N-Up (Z-order, Cut & Stack),
 * 8-Page Mini-Zines, real-time live sheet preview, layout calculation, and print PDF export.
 */
class PdfImpositionController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfImpositionBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSavePdfRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    private var activePdfUri: Uri? = null
    private var activePdfName: String = "document.pdf"
    private var totalPages: Int = 1
    private var sourceWidthPt: Float = 595.28f
    private var sourceHeightPt: Float = 841.89f
    private var isGenerating: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null
    private var previewJob: Job? = null
    private var activeSheetLayouts: List<SheetLayout> = emptyList()
    private var currentSheetIndex: Int = 0

    override fun hasPickedMedia(): Boolean = activePdfUri != null
    override fun hasUnsavedWork(): Boolean = activePdfUri != null && !isSaved
    override val isProcessing: Boolean get() = isGenerating

    override fun discardAndReleaseAllResources() {
        activeJob?.cancel()
        previewJob?.cancel()
        activePdfUri = null
        activePdfName = "document.pdf"
        activeSheetLayouts = emptyList()
        currentSheetIndex = 0
        isGenerating = false
        isSaved = false
        binding.containerImpositionDocInfo.visibility = View.GONE
        binding.containerImpositionControls.visibility = View.GONE
        binding.containerImpositionEmptyState.visibility = View.VISIBLE
        binding.btnPdfImpositionReset.visibility = View.GONE
        binding.ivImpositionPreview.setImageBitmap(null)
    }

    fun init() {
        setupListeners()
    }

    private fun setupListeners() {
        binding.toolbarPdfImposition.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnPdfImpositionReset.setOnClickListener {
            resetWorkspace()
        }

        binding.cardImpositionPicker.setOnClickListener {
            onPickPdfRequest()
        }

        binding.btnImpositionChangeDoc.setOnClickListener {
            onPickPdfRequest()
        }

        binding.chipGroupImpositionScheme.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipSchemeBooklet
            updateImpositionPlanSummary(checkedId)
            calculateAndRenderLivePreview(resetIndex = true)
        }

        binding.chipGroupImpositionPaperSize.setOnCheckedStateChangeListener { _, _ ->
            calculateAndRenderLivePreview(resetIndex = false)
        }

        binding.switchDrawGuides.setOnCheckedChangeListener { _, _ ->
            calculateAndRenderLivePreview(resetIndex = false)
        }

        binding.btnImpositionPrevSheet.setOnClickListener {
            if (currentSheetIndex > 0) {
                currentSheetIndex--
                updateSheetPaginationUI()
                renderCurrentSheetPreview()
            }
        }

        binding.btnImpositionNextSheet.setOnClickListener {
            if (currentSheetIndex < activeSheetLayouts.size - 1) {
                currentSheetIndex++
                updateSheetPaginationUI()
                renderCurrentSheetPreview()
            }
        }

        setupExecuteAction()
    }

    fun onPdfSelected(uri: Uri) {
        activePdfUri = uri
        activePdfName = queryPdfDisplayName(uri) ?: "document.pdf"
        isSaved = false

        binding.containerImpositionEmptyState.visibility = View.GONE
        binding.containerImpositionDocInfo.visibility = View.VISIBLE
        binding.containerImpositionControls.visibility = View.VISIBLE
        binding.btnPdfImpositionReset.visibility = View.VISIBLE
        binding.tvImpositionDocName.text = activePdfName
        binding.tvImpositionDocMeta.text = "Reading document geometry..."

        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = activity.contentResolver.openInputStream(uri) ?: return@launch
                val document = PDDocument.load(inputStream)
                inputStream.close()

                totalPages = document.numberOfPages
                if (totalPages > 0) {
                    val firstPage = document.getPage(0)
                    sourceWidthPt = firstPage.mediaBox.width
                    sourceHeightPt = firstPage.mediaBox.height
                }
                document.close()

                withContext(Dispatchers.Main) {
                    val wMm = (sourceWidthPt * 25.4f / 72f).toInt()
                    val hMm = (sourceHeightPt * 25.4f / 72f).toInt()
                    binding.tvImpositionDocMeta.text = "$totalPages pages • ${wMm}x${hMm} mm"
                    val checked = binding.chipGroupImpositionScheme.checkedChipId
                    updateImpositionPlanSummary(if (checked != View.NO_ID) checked else R.id.chipSchemeBooklet)
                    calculateAndRenderLivePreview(resetIndex = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvImpositionDocMeta.text = "Failed to inspect geometry: ${e.message}"
                }
            }
        }
    }

    private fun updateImpositionPlanSummary(checkedId: Int) {
        if (activePdfUri == null) return

        when (checkedId) {
            R.id.chipSchemeBooklet -> {
                binding.tvImpositionPlanTitle.text = "Saddle-Stitch Booklet (4 Pages / Sheet)"
                val sheets = (totalPages + 3) / 4
                val totalImposedPages = sheets * 4
                val blankNeeded = totalImposedPages - totalPages
                binding.tvImpositionPlanDetails.text =
                    "Requires $sheets sheets ($totalImposedPages booklet positions). $blankNeeded blank pad pages will be automatically inserted."
            }
            R.id.chipScheme2Up -> {
                binding.tvImpositionPlanTitle.text = "2-Up Side-by-Side (2 Pages / Sheet)"
                val sheets = (totalPages + 1) / 2
                binding.tvImpositionPlanDetails.text =
                    "Will produce $sheets target sheets with 2 pages placed side-by-side in Z-reading order."
            }
            R.id.chipScheme4Up -> {
                binding.tvImpositionPlanTitle.text = "4-Up Grid (2x2 Pages / Sheet)"
                val sheets = (totalPages + 3) / 4
                binding.tvImpositionPlanDetails.text =
                    "Will produce $sheets target sheets with 4 pages per sheet in a 2x2 grid."
            }
            R.id.chipSchemeCutStack -> {
                binding.tvImpositionPlanTitle.text = "Cut & Stack Pocket Booklet"
                binding.tvImpositionPlanDetails.text =
                    "$totalPages pages permuted so that cutting sheets into quarters and stacking preserves exact sequential order."
            }
            R.id.chipSchemeMiniZine -> {
                binding.tvImpositionPlanTitle.text = "8-Page Mini-Zine (1 Sheet)"
                binding.tvImpositionPlanDetails.text =
                    "First 8 pages imposed onto a single sheet with center slit guideline. Fold into a pocket zine without glue or staples."
            }
        }
    }

    private fun getCurrentConfig(): ImpositionConfig {
        val schemeId = binding.chipGroupImpositionScheme.checkedChipId
        val paperPreset = when (binding.chipGroupImpositionPaperSize.checkedChipId) {
            R.id.chipPaperLetter -> PaperPreset.LETTER
            R.id.chipPaperLegal -> PaperPreset.LEGAL
            else -> PaperPreset.A4
        }

        val mode = when (schemeId) {
            R.id.chipSchemeBooklet -> ImpositionToolMode.BOOKLET
            R.id.chipSchemeMiniZine -> ImpositionToolMode.ZINE
            else -> ImpositionToolMode.N_UP
        }

        val (nUpCols, nUpRows) = when (schemeId) {
            R.id.chipScheme4Up, R.id.chipSchemeCutStack -> 2 to 2
            R.id.chipScheme2Up -> 2 to 1
            else -> 2 to 2
        }

        val nUpMode = when (schemeId) {
            R.id.chipSchemeCutStack -> NUpLayoutMode.CUT_STACK_LTR
            else -> NUpLayoutMode.STANDARD
        }

        return ImpositionConfig(
            mode = mode,
            targetPaperSize = PaperSize(preset = paperPreset),
            isLandscape = mode == ImpositionToolMode.BOOKLET || (nUpCols > nUpRows),
            gridCols = nUpCols,
            gridRows = nUpRows,
            nUpLayoutMode = nUpMode,
            bindingDirection = BindingDirection.LEFT_WESTERN,
            zineType = ZineType.ONE_SHEET_8PAGE,
            showCropMarks = binding.switchDrawGuides.isChecked
        )
    }

    private fun calculateAndRenderLivePreview(resetIndex: Boolean) {
        if (activePdfUri == null || totalPages <= 0) return

        try {
            val config = getCurrentConfig()
            activeSheetLayouts = ImpositionEngine.calculateLayout(
                pageCount = totalPages,
                sourceWidthPt = sourceWidthPt,
                sourceHeightPt = sourceHeightPt,
                config = config
            )
        } catch (_: Exception) {
            activeSheetLayouts = emptyList()
        }

        if (resetIndex || currentSheetIndex !in activeSheetLayouts.indices) {
            currentSheetIndex = 0
        }

        updateSheetPaginationUI()
        renderCurrentSheetPreview()
    }

    private fun updateSheetPaginationUI() {
        val totalSheets = activeSheetLayouts.size
        if (totalSheets <= 0) {
            binding.containerImpositionSheetNav.visibility = View.GONE
            binding.tvImpositionSheetIndicator.text = ""
            return
        }

        binding.containerImpositionSheetNav.visibility = View.VISIBLE
        binding.btnImpositionPrevSheet.isEnabled = currentSheetIndex > 0
        binding.btnImpositionNextSheet.isEnabled = currentSheetIndex < totalSheets - 1

        val sheetNum = currentSheetIndex + 1
        val isBooklet = binding.chipGroupImpositionScheme.checkedChipId == R.id.chipSchemeBooklet
        val side = if (isBooklet) {
            if (sheetNum % 2 == 1) "Front" else "Back"
        } else {
            "Sheet"
        }

        binding.tvImpositionSheetCount.text = "Sheet $sheetNum / $totalSheets"
        binding.tvImpositionSheetIndicator.text = if (isBooklet) {
            "Sheet $sheetNum of $totalSheets ($side)"
        } else {
            "Sheet $sheetNum of $totalSheets"
        }
    }

    private fun renderCurrentSheetPreview() {
        val uri = activePdfUri ?: return
        if (currentSheetIndex !in activeSheetLayouts.indices) return

        val sheet = activeSheetLayouts[currentSheetIndex]
        val drawGuides = binding.switchDrawGuides.isChecked

        previewJob?.cancel()
        binding.pbImpositionLoading.visibility = View.VISIBLE

        previewJob = scope.launch(Dispatchers.IO) {
            val bitmap = ImpositionPdfExporter.renderSheetPreview(
                context = activity,
                inputUri = uri,
                sheet = sheet,
                drawGuides = drawGuides,
                maxDimension = 900
            )

            withContext(Dispatchers.Main) {
                binding.pbImpositionLoading.visibility = View.GONE
                if (bitmap != null) {
                    binding.ivImpositionPreview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun setupExecuteAction() {
        binding.btnImpositionExecute.setOnClickListener {
            val uri = activePdfUri ?: return@setOnClickListener
            if (isGenerating) return@setOnClickListener

            isGenerating = true
            binding.btnImpositionExecute.isEnabled = false
            binding.btnImpositionExecute.text = "Calculating & Imposing..."

            activeJob = scope.launch(Dispatchers.IO) {
                try {
                    val config = getCurrentConfig()
                    val layouts = ImpositionEngine.calculateLayout(
                        pageCount = totalPages,
                        sourceWidthPt = sourceWidthPt,
                        sourceHeightPt = sourceHeightPt,
                        config = config
                    )

                    val tempOut = File(activity.cacheDir, "imposed_${System.currentTimeMillis()}.pdf")
                    ImpositionPdfExporter.exportImposedPdf(
                        context = activity,
                        inputUri = uri,
                        sheetLayouts = layouts,
                        outputFile = tempOut
                    )

                    val bytes = tempOut.readBytes()
                    tempOut.delete()

                    withContext(Dispatchers.Main) {
                        isGenerating = false
                        binding.btnImpositionExecute.isEnabled = true
                        binding.btnImpositionExecute.text = "Generate Imposed Print PDF"

                        isSaved = true
                        val base = activePdfName.removeSuffix(".pdf")
                        val exportName = "${base}_imposed.pdf"
                        onSavePdfRequest(bytes, exportName)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isGenerating = false
                        binding.btnImpositionExecute.isEnabled = true
                        binding.btnImpositionExecute.text = "Generate Imposed Print PDF"
                        Toast.makeText(activity, "Imposition failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resetWorkspace() {
        discardAndReleaseAllResources()
        binding.containerImpositionDocInfo.visibility = View.GONE
        binding.containerImpositionControls.visibility = View.GONE
        binding.containerImpositionEmptyState.visibility = View.VISIBLE
        binding.btnPdfImpositionReset.visibility = View.GONE
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
