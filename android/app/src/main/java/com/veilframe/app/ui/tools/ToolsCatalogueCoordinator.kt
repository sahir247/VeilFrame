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
        binding.tvToolsSubtitle.text =
            "${WorkspaceRoute.values().size} offline engines for privacy, media, documents, and forensics"

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
            val matchesCat = (selectedCategory == null || route.category == selectedCategory)
            val visible = matchesQuery && matchesCat
            card.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) visibleCount++
        }

        binding.containerEmptySearch.visibility =
            if (visibleCount == 0) View.VISIBLE else View.GONE
    }
}
