package com.veilframe.app.ui.tools

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutToolsCatalogueBinding
import com.veilframe.app.navigation.WorkspaceCategory
import com.veilframe.app.navigation.WorkspaceRoute

/**
 * Coordinates search filtering, category filtering, accessibility labeling,
 * and workspace navigation for the Tools & Workspaces catalogue screen.
 */
class ToolsCatalogueCoordinator(
    private val binding: LayoutToolsCatalogueBinding,
    private val onOpenRoute: (route: WorkspaceRoute, originView: View) -> Unit,
    private val onOpenMarkdownStudio: (originView: View) -> Unit
) {

    private val cards by lazy {
        listOf(
            WorkspaceRoute.IMAGE_STUDIO to binding.cardToolImageStudio,
            WorkspaceRoute.VIDEO_STUDIO to binding.cardToolVideoStudio,
            WorkspaceRoute.QR_STUDIO to binding.cardToolQrStudio,
            WorkspaceRoute.DOCUMENT_SCANNER to binding.cardToolDocScanner,
            WorkspaceRoute.BACKGROUND_REMOVER to binding.cardToolBgRemover,
            WorkspaceRoute.MOTION_LAB to binding.cardToolMotionLab,
            WorkspaceRoute.IMAGE_UPSCALER to binding.cardToolAiUpscaler,
            WorkspaceRoute.IMAGE_CLEANER to binding.cardToolPrivacyScrubber,
            WorkspaceRoute.VIDEO_CLEANER to binding.cardToolVideoCleaner,
            WorkspaceRoute.IMAGE_QUALITY to binding.cardToolImageQuality,
            WorkspaceRoute.FOLDER_SCANNER to binding.cardToolFolderAnalyzer,
            WorkspaceRoute.AI_BUNDLE to binding.cardToolAiBundler,
            WorkspaceRoute.MARKDOWN_STUDIO to binding.cardToolMarkdownStudio,
            WorkspaceRoute.PROVENANCE to binding.cardToolProvenance,
            WorkspaceRoute.PDF_STUDIO to binding.cardToolPdfStudio,
            WorkspaceRoute.PDF_SIGNATURE to binding.cardToolPdfSignature,
            WorkspaceRoute.PDF_UNLOCK to binding.cardToolPdfUnlock
        )
    }

    fun init() {
        binding.tvToolsSubtitle.text = "Offline privacy, media, and document utilities"

        // Ensure accessibility: assign authoritative TalkBack label to each card from WorkspaceRoute
        cards.forEach { (route, card) ->
            card.contentDescription = "${route.title}, ${route.status.label}. ${route.description}"
        }

        setupClickListeners()
        setupFilters()
    }

    private fun setupClickListeners() {
        cards.forEach { (route, card) ->
            card.setOnClickListener {
                if (route == WorkspaceRoute.MARKDOWN_STUDIO) {
                    onOpenMarkdownStudio(card)
                } else {
                    onOpenRoute(route, card)
                }
            }
        }
    }

    private fun setupFilters() {
        binding.etToolSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupToolCategories.setOnCheckedStateChangeListener { _, _ ->
            applyFilter()
        }
    }

    fun applyFilter() {
        val query = binding.etToolSearch.text?.toString().orEmpty().trim()
        val checkedChipId = binding.chipGroupToolCategories.checkedChipId

        val matchingRoutes = if (query.isEmpty()) {
            WorkspaceRoute.values().toList()
        } else {
            WorkspaceRoute.search(query)
        }.toSet()

        val selectedCategory = when (checkedChipId) {
            R.id.chipCategoryCreate -> WorkspaceCategory.CREATE_EDIT
            R.id.chipCategoryPrivacy -> WorkspaceCategory.PRIVACY
            R.id.chipCategoryAnalyze -> WorkspaceCategory.ANALYZE
            R.id.chipCategoryDev -> WorkspaceCategory.DEVELOPER
            R.id.chipCategoryPdf -> WorkspaceCategory.PDF_OPERATIONS
            else -> null
        }

        var visibleCount = 0
        cards.forEach { (route, card) ->
            val matchesQuery = route in matchingRoutes
            val matchesCat = (query.isNotEmpty() || selectedCategory == null || route.category == selectedCategory)
            val visible = matchesQuery && matchesCat
            card.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) visibleCount++
        }

        // Dynamically collapse empty rows to prevent whitespace
        val allRows = listOf(
            binding.rowCreate1 to listOf(binding.cardToolImageStudio, binding.cardToolVideoStudio),
            binding.rowCreate2 to listOf(binding.cardToolQrStudio, binding.cardToolDocScanner),
            binding.rowCreate3 to listOf(binding.cardToolBgRemover, binding.cardToolAiUpscaler),
            binding.rowPrivacy1 to listOf(binding.cardToolPrivacyScrubber, binding.cardToolVideoCleaner),
            binding.rowAnalyze1 to listOf(binding.cardToolImageQuality, binding.cardToolFolderAnalyzer),
            binding.rowDev1 to listOf(binding.cardToolAiBundler, binding.cardToolMarkdownStudio),
            binding.rowDev2 to listOf(binding.cardToolProvenance, binding.cardToolMotionLab),
            binding.rowPdf1 to listOf(binding.cardToolPdfSignature, binding.cardToolPdfUnlock)
        )

        allRows.forEach { (row, rowCards) ->
            row.visibility = if (rowCards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        }

        // Collapse category headers when category is filtered out or empty
        val createCards = listOf(binding.cardToolImageStudio, binding.cardToolVideoStudio, binding.cardToolQrStudio, binding.cardToolDocScanner, binding.cardToolBgRemover, binding.cardToolAiUpscaler)
        val privacyCards = listOf(binding.cardToolPrivacyScrubber, binding.cardToolVideoCleaner)
        val analyzeCards = listOf(binding.cardToolImageQuality, binding.cardToolFolderAnalyzer)
        val devCards = listOf(binding.cardToolAiBundler, binding.cardToolMarkdownStudio, binding.cardToolProvenance, binding.cardToolMotionLab)
        val pdfCards = listOf(binding.cardToolPdfStudio, binding.cardToolPdfSignature, binding.cardToolPdfUnlock)

        binding.headerCategoryCreateEdit.visibility =
            if ((selectedCategory == null || selectedCategory == WorkspaceCategory.CREATE_EDIT) && createCards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

        binding.headerCategoryPrivacy.visibility =
            if ((selectedCategory == null || selectedCategory == WorkspaceCategory.PRIVACY) && privacyCards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

        binding.headerCategoryAnalyze.visibility =
            if ((selectedCategory == null || selectedCategory == WorkspaceCategory.ANALYZE) && analyzeCards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

        binding.headerCategoryDeveloper.visibility =
            if ((selectedCategory == null || selectedCategory == WorkspaceCategory.DEVELOPER) && devCards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

        binding.headerCategoryPdfOperations.visibility =
            if ((selectedCategory == null || selectedCategory == WorkspaceCategory.PDF_OPERATIONS) && pdfCards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

        binding.containerEmptySearch.visibility =
            if (visibleCount == 0) View.VISIBLE else View.GONE
    }
}
