package com.veilframe.app.pdf

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.databinding.LayoutPdfStudioBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import java.util.Locale

/**
 * Controller orchestrating the universal PDF Studio Hub, handling category filtering,
 * real-time tool search, and navigation to specialized PDF operations.
 */
class PdfStudioController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfStudioBinding,
    private val onOpenMerge: () -> Unit,
    private val onOpenSplit: () -> Unit,
    private val onOpenReorder: () -> Unit,
    private val onOpenRotate: () -> Unit,
    private val onOpenDelete: () -> Unit,
    private val onOpenExtractPages: () -> Unit,
    private val onOpenCompress: () -> Unit,
    private val onOpenPdfToImage: () -> Unit,
    private val onOpenImageToPdf: () -> Unit,
    private val onOpenExtractText: () -> Unit,
    private val onOpenHtmlToPdf: () -> Unit,
    private val onOpenDocToPdf: () -> Unit,
    private val onOpenLock: () -> Unit,
    private val onOpenUnlock: () -> Unit,
    private val onOpenWatermark: () -> Unit,
    private val onOpenSign: () -> Unit,
    private val onOpenFlatten: () -> Unit,
    private val onOpenMetadata: () -> Unit,
    private val onOpenImposition: () -> Unit,
    private val onOpenFillForms: () -> Unit,
    private val onOpenSignatureValidator: () -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    override fun hasPickedMedia(): Boolean = false
    override fun hasUnsavedWork(): Boolean = false
    override val isProcessing: Boolean get() = false
    override fun discardAndReleaseAllResources() {}

    private data class ToolCardEntry(
        val id: String,
        val card: View,
        val title: String,
        val category: String,
        val keywords: List<String>
    )

    private val toolCards by lazy {
        listOf(
            ToolCardEntry("merge", binding.cardStudioMerge, "Merge PDF", "organize", listOf("combine", "join", "multi")),
            ToolCardEntry("split", binding.cardStudioSplit, "Split PDF", "organize", listOf("separate", "pages", "extract", "cut")),
            ToolCardEntry("reorder", binding.cardStudioReorder, "Reorder Pages", "organize", listOf("arrange", "sequence", "sort")),
            ToolCardEntry("rotate", binding.cardStudioRotate, "Rotate Pages", "organize", listOf("turn", "orientation", "landscape", "portrait")),
            ToolCardEntry("delete", binding.cardStudioDelete, "Delete Pages", "organize", listOf("remove", "cut", "trash")),
            ToolCardEntry("extract_pages", binding.cardStudioExtractPages, "Extract Pages", "organize", listOf("pages", "gallery", "images", "separate")),
            ToolCardEntry("compress", binding.cardStudioCompress, "Compress PDF", "convert", listOf("shrink", "reduce", "size", "optimize")),
            ToolCardEntry("pdf_to_image", binding.cardStudioPdfToImage, "PDF to Image", "convert", listOf("webp", "png", "jpeg", "export")),
            ToolCardEntry("image_to_pdf", binding.cardStudioImageToPdf, "Image to PDF", "convert", listOf("photo", "camera", "picture", "maker")),
            ToolCardEntry("extract_text", binding.cardStudioExtractText, "Extract Text & OCR", "convert", listOf("ocr", "mlkit", "text", "txt", "copy")),
            ToolCardEntry("html_to_pdf", binding.cardStudioHtmlToPdf, "HTML to PDF", "convert", listOf("html", "code", "markup", "tags")),
            ToolCardEntry("doc_to_pdf", binding.cardStudioDocToPdf, "DOC to PDF", "convert", listOf("word", "docx", "excel", "xlsx", "office")),
            ToolCardEntry("lock", binding.cardStudioLock, "Lock PDF", "security", listOf("encrypt", "password", "protect", "aes")),
            ToolCardEntry("unlock", binding.cardStudioUnlock, "Unlock PDF", "security", listOf("decrypt", "remove password", "security")),
            ToolCardEntry("watermark", binding.cardStudioWatermark, "Watermark Studio", "security", listOf("stamp", "confidential", "mesh", "protect", "diagonal")),
            ToolCardEntry("sign", binding.cardStudioSign, "Sign PDF", "security", listOf("signature", "touch", "draw", "bezier")),
            ToolCardEntry("flatten", binding.cardStudioFlatten, "Flatten PDF", "security", listOf("acroform", "raster", "lock", "bake")),
            ToolCardEntry("metadata", binding.cardStudioMetadata, "PDF Metadata", "security", listOf("exif", "xmp", "strip", "clean", "sanitize")),
            ToolCardEntry("imposition", binding.cardStudioImposition, "Print Studio", "print", listOf("n-up", "booklet", "zine", "cut", "stack", "duplex")),
            ToolCardEntry("fill_forms", binding.cardStudioFillForms, "Fill Forms", "print", listOf("acroform", "input", "edit", "fields")),
            ToolCardEntry("signature_validator", binding.cardStudioSignatureValidator, "Verify PDF", "security", listOf("pkix", "cca", "verify", "adobe", "checkmark"))
        )
    }

    private var activeCategoryFilter: String = "all"
    private var currentSearchQuery: String = ""

    fun init() {
        setupToolbar()
        setupClickListeners()
        setupFilters()
        setupSearch()
    }

    private fun setupToolbar() {
        binding.toolbarPdfStudio.setNavigationOnClickListener {
            onNavigateBack()
        }
        binding.btnPdfStudioSearch.setOnClickListener {
            val til = binding.tilPdfStudioSearch
            if (til.visibility == View.VISIBLE) {
                til.visibility = View.GONE
                binding.etPdfStudioSearch.setText("")
            } else {
                til.visibility = View.VISIBLE
                binding.etPdfStudioSearch.requestFocus()
            }
        }
    }

    private fun setupClickListeners() {
        binding.cardStudioMerge.setOnClickListener { onOpenMerge() }
        binding.cardStudioSplit.setOnClickListener { onOpenSplit() }
        binding.cardStudioReorder.setOnClickListener { onOpenReorder() }
        binding.cardStudioRotate.setOnClickListener { onOpenRotate() }
        binding.cardStudioDelete.setOnClickListener { onOpenDelete() }
        binding.cardStudioExtractPages.setOnClickListener { onOpenExtractPages() }
        binding.cardStudioCompress.setOnClickListener { onOpenCompress() }
        binding.cardStudioPdfToImage.setOnClickListener { onOpenPdfToImage() }
        binding.cardStudioImageToPdf.setOnClickListener { onOpenImageToPdf() }
        binding.cardStudioExtractText.setOnClickListener { onOpenExtractText() }
        binding.cardStudioHtmlToPdf.setOnClickListener { onOpenHtmlToPdf() }
        binding.cardStudioDocToPdf.setOnClickListener { onOpenDocToPdf() }
        binding.cardStudioLock.setOnClickListener { onOpenLock() }
        binding.cardStudioUnlock.setOnClickListener { onOpenUnlock() }
        binding.cardStudioWatermark.setOnClickListener { onOpenWatermark() }
        binding.cardStudioSign.setOnClickListener { onOpenSign() }
        binding.cardStudioFlatten.setOnClickListener { onOpenFlatten() }
        binding.cardStudioMetadata.setOnClickListener { onOpenMetadata() }
        binding.cardStudioImposition.setOnClickListener { onOpenImposition() }
        binding.cardStudioFillForms.setOnClickListener { onOpenFillForms() }
        binding.cardStudioSignatureValidator.setOnClickListener { onOpenSignatureValidator() }
    }

    private fun setupFilters() {
        binding.chipPdfCatAll.setOnClickListener { setCategoryFilter("all") }
        binding.chipPdfCatOrganize.setOnClickListener { setCategoryFilter("organize") }
        binding.chipPdfCatConvert.setOnClickListener { setCategoryFilter("convert") }
        binding.chipPdfCatSecurity.setOnClickListener { setCategoryFilter("security") }
        binding.chipPdfCatPrint.setOnClickListener { setCategoryFilter("print") }
    }

    private fun setCategoryFilter(category: String) {
        activeCategoryFilter = category
        applyFilters()
    }

    private fun setupSearch() {
        binding.etPdfStudioSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyFilters() {
        val q = currentSearchQuery
        val cat = activeCategoryFilter

        toolCards.forEach { entry ->
            val matchesCategory = (cat == "all") || (entry.category == cat)
            val matchesQuery = q.isEmpty() ||
                    entry.title.lowercase(Locale.ROOT).contains(q) ||
                    entry.keywords.any { it.contains(q) }

            entry.card.visibility = if (matchesCategory && matchesQuery) View.VISIBLE else View.GONE
        }

        // Section header visibility
        val hasOrganize = toolCards.any { it.category == "organize" && it.card.visibility == View.VISIBLE }
        binding.tvSectionOrganize.visibility = if (hasOrganize) View.VISIBLE else View.GONE

        val hasConvert = toolCards.any { it.category == "convert" && it.card.visibility == View.VISIBLE }
        binding.tvSectionConvert.visibility = if (hasConvert) View.VISIBLE else View.GONE

        val hasSecurity = toolCards.any { it.category == "security" && it.card.visibility == View.VISIBLE }
        binding.tvSectionSecurity.visibility = if (hasSecurity) View.VISIBLE else View.GONE

        val hasPrint = toolCards.any { it.category == "print" && it.card.visibility == View.VISIBLE }
        binding.tvSectionPublishing.visibility = if (hasPrint) View.VISIBLE else View.GONE
    }
}
