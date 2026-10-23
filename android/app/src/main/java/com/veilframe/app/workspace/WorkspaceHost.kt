package com.veilframe.app.workspace

import android.view.View
import androidx.annotation.MainThread
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import com.veilframe.app.databinding.LayoutDocumentScannerBinding
import com.veilframe.app.databinding.LayoutImageUpscalerBinding
import com.veilframe.app.databinding.LayoutMotionLabBinding
import com.veilframe.app.databinding.LayoutPdfExtractPagesBinding
import com.veilframe.app.databinding.LayoutPdfExtractTextBinding
import com.veilframe.app.databinding.LayoutPdfFlattenBinding
import com.veilframe.app.databinding.LayoutPdfImpositionBinding
import com.veilframe.app.databinding.LayoutPdfQuickToolBinding
import com.veilframe.app.databinding.LayoutPdfSignatureBinding
import com.veilframe.app.databinding.LayoutPdfStudioBinding
import com.veilframe.app.databinding.LayoutPdfUnlockBinding
import com.veilframe.app.databinding.LayoutPdfWatermarkBinding
import com.veilframe.app.databinding.LayoutProvenanceBinding
import com.veilframe.app.ui.insets.WorkspaceInsets

/**
 * Secondary offline workspaces that are lazily inflated on demand via [android.view.ViewStub]s.
 */
enum class SecondaryWorkspace {
    IMAGE_UPSCALER,
    DOCUMENT_SCANNER,
    BACKGROUND_REMOVER,
    MOTION_LAB,
    PROVENANCE,
    PDF_SIGNATURE,
    PDF_UNLOCK,
    PDF_STUDIO,
    PDF_WATERMARK,
    PDF_EXTRACT_TEXT,
    PDF_EXTRACT_PAGES,
    PDF_FLATTEN,
    PDF_IMPOSITION,
    PDF_QUICK_TOOL
}

/**
 * Host managing lazy ViewStub inflation, window inset propagation, and lifecycle
 * coordination for secondary offline workspaces.
 */
class WorkspaceHost(
    private val binding: ActivityMainBinding,
    var onImageUpscalerInflated: ((LayoutImageUpscalerBinding) -> Unit)? = null,
    var onDocScannerInflated: ((LayoutDocumentScannerBinding) -> Unit)? = null,
    var onBackgroundRemoverInflated: ((LayoutBackgroundRemoverBinding) -> Unit)? = null,
    var onMotionLabInflated: ((LayoutMotionLabBinding) -> Unit)? = null,
    var onProvenanceInflated: ((LayoutProvenanceBinding) -> Unit)? = null,
    var onPdfSignatureInflated: ((LayoutPdfSignatureBinding) -> Unit)? = null,
    var onPdfUnlockInflated: ((LayoutPdfUnlockBinding) -> Unit)? = null,
    var onPdfStudioInflated: ((LayoutPdfStudioBinding) -> Unit)? = null,
    var onPdfWatermarkInflated: ((LayoutPdfWatermarkBinding) -> Unit)? = null,
    var onPdfExtractTextInflated: ((LayoutPdfExtractTextBinding) -> Unit)? = null,
    var onPdfExtractPagesInflated: ((LayoutPdfExtractPagesBinding) -> Unit)? = null,
    var onPdfFlattenInflated: ((LayoutPdfFlattenBinding) -> Unit)? = null,
    var onPdfImpositionInflated: ((LayoutPdfImpositionBinding) -> Unit)? = null,
    var onPdfQuickToolInflated: ((LayoutPdfQuickToolBinding) -> Unit)? = null
) {
    data class InsetCache(
        val statusBarTop: Int = 0,
        val navBarBottom: Int = 0,
        val imeBottom: Int = 0,
        val navBarLeft: Int = 0,
        val navBarRight: Int = 0
    )

    private var cachedInsets = InsetCache()

    var activeWorkspace: SecondaryWorkspace? = null
        private set

    var imageUpscalerBinding: LayoutImageUpscalerBinding? = null
        private set
    var docScannerBinding: LayoutDocumentScannerBinding? = null
        private set
    var backgroundRemoverBinding: LayoutBackgroundRemoverBinding? = null
        private set
    var motionLabBinding: LayoutMotionLabBinding? = null
        private set
    var provenanceBinding: LayoutProvenanceBinding? = null
        private set
    var pdfSignatureBinding: LayoutPdfSignatureBinding? = null
        private set
    var pdfUnlockBinding: LayoutPdfUnlockBinding? = null
        private set
    var pdfStudioBinding: LayoutPdfStudioBinding? = null
        private set
    var pdfWatermarkBinding: LayoutPdfWatermarkBinding? = null
        private set
    var pdfExtractTextBinding: LayoutPdfExtractTextBinding? = null
        private set
    var pdfExtractPagesBinding: LayoutPdfExtractPagesBinding? = null
        private set
    var pdfFlattenBinding: LayoutPdfFlattenBinding? = null
        private set
    var pdfImpositionBinding: LayoutPdfImpositionBinding? = null
        private set
    var pdfQuickToolBinding: LayoutPdfQuickToolBinding? = null
        private set

    val imageUpscalerRoot: View? get() = imageUpscalerBinding?.root
    val docScannerRoot: View? get() = docScannerBinding?.root
    val backgroundRemoverRoot: View? get() = backgroundRemoverBinding?.root
    val motionLabRoot: View? get() = motionLabBinding?.root
    val provenanceRoot: View? get() = provenanceBinding?.root
    val pdfSignatureRoot: View? get() = pdfSignatureBinding?.root
    val pdfUnlockRoot: View? get() = pdfUnlockBinding?.root
    val pdfStudioRoot: View? get() = pdfStudioBinding?.root
    val pdfWatermarkRoot: View? get() = pdfWatermarkBinding?.root
    val pdfExtractTextRoot: View? get() = pdfExtractTextBinding?.root
    val pdfExtractPagesRoot: View? get() = pdfExtractPagesBinding?.root
    val pdfFlattenRoot: View? get() = pdfFlattenBinding?.root
    val pdfImpositionRoot: View? get() = pdfImpositionBinding?.root
    val pdfQuickToolRoot: View? get() = pdfQuickToolBinding?.root

    val isImageUpscalerInflated: Boolean get() = imageUpscalerBinding != null
    val isDocScannerInflated: Boolean get() = docScannerBinding != null
    val isBackgroundRemoverInflated: Boolean get() = backgroundRemoverBinding != null
    val isMotionLabInflated: Boolean get() = motionLabBinding != null
    val isProvenanceInflated: Boolean get() = provenanceBinding != null
    val isPdfSignatureInflated: Boolean get() = pdfSignatureBinding != null
    val isPdfUnlockInflated: Boolean get() = pdfUnlockBinding != null
    val isPdfStudioInflated: Boolean get() = pdfStudioBinding != null
    val isPdfWatermarkInflated: Boolean get() = pdfWatermarkBinding != null
    val isPdfExtractTextInflated: Boolean get() = pdfExtractTextBinding != null
    val isPdfExtractPagesInflated: Boolean get() = pdfExtractPagesBinding != null
    val isPdfFlattenInflated: Boolean get() = pdfFlattenBinding != null
    val isPdfImpositionInflated: Boolean get() = pdfImpositionBinding != null
    val isPdfQuickToolInflated: Boolean get() = pdfQuickToolBinding != null

    fun isInflated(workspace: SecondaryWorkspace): Boolean = when (workspace) {
        SecondaryWorkspace.IMAGE_UPSCALER -> isImageUpscalerInflated
        SecondaryWorkspace.DOCUMENT_SCANNER -> isDocScannerInflated
        SecondaryWorkspace.BACKGROUND_REMOVER -> isBackgroundRemoverInflated
        SecondaryWorkspace.MOTION_LAB -> isMotionLabInflated
        SecondaryWorkspace.PROVENANCE -> isProvenanceInflated
        SecondaryWorkspace.PDF_SIGNATURE -> isPdfSignatureInflated
        SecondaryWorkspace.PDF_UNLOCK -> isPdfUnlockInflated
        SecondaryWorkspace.PDF_STUDIO -> isPdfStudioInflated
        SecondaryWorkspace.PDF_WATERMARK -> isPdfWatermarkInflated
        SecondaryWorkspace.PDF_EXTRACT_TEXT -> isPdfExtractTextInflated
        SecondaryWorkspace.PDF_EXTRACT_PAGES -> isPdfExtractPagesInflated
        SecondaryWorkspace.PDF_FLATTEN -> isPdfFlattenInflated
        SecondaryWorkspace.PDF_IMPOSITION -> isPdfImpositionInflated
        SecondaryWorkspace.PDF_QUICK_TOOL -> isPdfQuickToolInflated
    }

    fun getRoot(workspace: SecondaryWorkspace): View? = when (workspace) {
        SecondaryWorkspace.IMAGE_UPSCALER -> imageUpscalerRoot
        SecondaryWorkspace.DOCUMENT_SCANNER -> docScannerRoot
        SecondaryWorkspace.BACKGROUND_REMOVER -> backgroundRemoverRoot
        SecondaryWorkspace.MOTION_LAB -> motionLabRoot
        SecondaryWorkspace.PROVENANCE -> provenanceRoot
        SecondaryWorkspace.PDF_SIGNATURE -> pdfSignatureRoot
        SecondaryWorkspace.PDF_UNLOCK -> pdfUnlockRoot
        SecondaryWorkspace.PDF_STUDIO -> pdfStudioRoot
        SecondaryWorkspace.PDF_WATERMARK -> pdfWatermarkRoot
        SecondaryWorkspace.PDF_EXTRACT_TEXT -> pdfExtractTextRoot
        SecondaryWorkspace.PDF_EXTRACT_PAGES -> pdfExtractPagesRoot
        SecondaryWorkspace.PDF_FLATTEN -> pdfFlattenRoot
        SecondaryWorkspace.PDF_IMPOSITION -> pdfImpositionRoot
        SecondaryWorkspace.PDF_QUICK_TOOL -> pdfQuickToolRoot
    }

    @MainThread
    fun getOrInflateImageUpscaler(): LayoutImageUpscalerBinding {
        if (imageUpscalerBinding == null) {
            val stub = binding.stubImageUpscaler
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.scrollImageUpscaler)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutImageUpscalerBinding.bind(view)
                imageUpscalerBinding = b
                applyInsetsToDefault(b.root)
                onImageUpscalerInflated?.invoke(b)
            }
        }
        return imageUpscalerBinding!!
    }

    @MainThread
    fun getOrInflateDocScanner(): LayoutDocumentScannerBinding {
        if (docScannerBinding == null) {
            val stub = binding.stubDocumentScanner
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutDocumentScannerRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutDocumentScannerBinding.bind(view)
                docScannerBinding = b
                applyInsetsToDefault(b.root)
                onDocScannerInflated?.invoke(b)
            }
        }
        return docScannerBinding!!
    }

    @MainThread
    fun getOrInflateBackgroundRemover(): LayoutBackgroundRemoverBinding {
        if (backgroundRemoverBinding == null) {
            val stub = binding.stubBackgroundRemover
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutBackgroundRemoverRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutBackgroundRemoverBinding.bind(view)
                backgroundRemoverBinding = b
                applyInsetsToDefault(b.root)
                onBackgroundRemoverInflated?.invoke(b)
            }
        }
        return backgroundRemoverBinding!!
    }

    @MainThread
    fun getOrInflateMotionLab(): LayoutMotionLabBinding {
        if (motionLabBinding == null) {
            val stub = binding.stubMotionLab
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutMotionLabRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutMotionLabBinding.bind(view)
                motionLabBinding = b
                applyInsetsToDefault(b.root)
                onMotionLabInflated?.invoke(b)
            }
        }
        return motionLabBinding!!
    }

    @MainThread
    fun getOrInflateProvenance(): LayoutProvenanceBinding {
        if (provenanceBinding == null) {
            val stub = binding.stubProvenance
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutProvenanceRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutProvenanceBinding.bind(view)
                provenanceBinding = b
                applyInsetsToDefault(b.root)
                onProvenanceInflated?.invoke(b)
            }
        }
        return provenanceBinding!!
    }

    @MainThread
    fun getOrInflatePdfSignature(): LayoutPdfSignatureBinding {
        if (pdfSignatureBinding == null) {
            val stub = binding.stubPdfSignature
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfSignatureRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfSignatureBinding.bind(view)
                pdfSignatureBinding = b
                applyInsetsToDefault(b.root)
                onPdfSignatureInflated?.invoke(b)
            }
        }
        return pdfSignatureBinding!!
    }

    @MainThread
    fun getOrInflatePdfUnlock(): LayoutPdfUnlockBinding {
        if (pdfUnlockBinding == null) {
            val stub = binding.stubPdfUnlock
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfUnlockRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfUnlockBinding.bind(view)
                pdfUnlockBinding = b
                applyInsetsToDefault(b.root)
                onPdfUnlockInflated?.invoke(b)
            }
        }
        return pdfUnlockBinding!!
    }

    @MainThread
    fun getOrInflatePdfStudio(): LayoutPdfStudioBinding {
        if (pdfStudioBinding == null) {
            val stub = binding.stubPdfStudio
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfStudioRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfStudioBinding.bind(view)
                pdfStudioBinding = b
                applyInsetsToDefault(b.root)
                onPdfStudioInflated?.invoke(b)
            }
        }
        return pdfStudioBinding!!
    }

    @MainThread
    fun getOrInflatePdfWatermark(): LayoutPdfWatermarkBinding {
        if (pdfWatermarkBinding == null) {
            val stub = binding.stubPdfWatermark
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfWatermarkRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfWatermarkBinding.bind(view)
                pdfWatermarkBinding = b
                applyInsetsToDefault(b.root)
                onPdfWatermarkInflated?.invoke(b)
            }
        }
        return pdfWatermarkBinding!!
    }

    @MainThread
    fun getOrInflatePdfExtractText(): LayoutPdfExtractTextBinding {
        if (pdfExtractTextBinding == null) {
            val stub = binding.stubPdfExtractText
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfExtractTextRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfExtractTextBinding.bind(view)
                pdfExtractTextBinding = b
                applyInsetsToDefault(b.root)
                onPdfExtractTextInflated?.invoke(b)
            }
        }
        return pdfExtractTextBinding!!
    }

    @MainThread
    fun getOrInflatePdfExtractPages(): LayoutPdfExtractPagesBinding {
        if (pdfExtractPagesBinding == null) {
            val stub = binding.stubPdfExtractPages
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfExtractPagesRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfExtractPagesBinding.bind(view)
                pdfExtractPagesBinding = b
                applyInsetsToDefault(b.root)
                onPdfExtractPagesInflated?.invoke(b)
            }
        }
        return pdfExtractPagesBinding!!
    }

    @MainThread
    fun getOrInflatePdfFlatten(): LayoutPdfFlattenBinding {
        if (pdfFlattenBinding == null) {
            val stub = binding.stubPdfFlatten
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfFlattenRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfFlattenBinding.bind(view)
                pdfFlattenBinding = b
                applyInsetsToDefault(b.root)
                onPdfFlattenInflated?.invoke(b)
            }
        }
        return pdfFlattenBinding!!
    }

    @MainThread
    fun getOrInflatePdfImposition(): LayoutPdfImpositionBinding {
        if (pdfImpositionBinding == null) {
            val stub = binding.stubPdfImposition
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfImpositionRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfImpositionBinding.bind(view)
                pdfImpositionBinding = b
                applyInsetsToDefault(b.root)
                onPdfImpositionInflated?.invoke(b)
            }
        }
        return pdfImpositionBinding!!
    }

    @MainThread
    fun getOrInflatePdfQuickTool(): LayoutPdfQuickToolBinding {
        if (pdfQuickToolBinding == null) {
            val stub = binding.stubPdfQuickTool
            val view = if (stub.parent != null) {
                stub.inflate()
            } else {
                binding.root.findViewById<View>(com.veilframe.app.R.id.layoutPdfQuickToolRoot)
            }
            if (view != null) {
                view.visibility = View.GONE
                val b = LayoutPdfQuickToolBinding.bind(view)
                pdfQuickToolBinding = b
                applyInsetsToDefault(b.root)
                onPdfQuickToolInflated?.invoke(b)
            }
        }
        return pdfQuickToolBinding!!
    }

    fun getOrInflate(workspace: SecondaryWorkspace): View = when (workspace) {
        SecondaryWorkspace.IMAGE_UPSCALER -> getOrInflateImageUpscaler().root
        SecondaryWorkspace.DOCUMENT_SCANNER -> getOrInflateDocScanner().root
        SecondaryWorkspace.BACKGROUND_REMOVER -> getOrInflateBackgroundRemover().root
        SecondaryWorkspace.MOTION_LAB -> getOrInflateMotionLab().root
        SecondaryWorkspace.PROVENANCE -> getOrInflateProvenance().root
        SecondaryWorkspace.PDF_SIGNATURE -> getOrInflatePdfSignature().root
        SecondaryWorkspace.PDF_UNLOCK -> getOrInflatePdfUnlock().root
        SecondaryWorkspace.PDF_STUDIO -> getOrInflatePdfStudio().root
        SecondaryWorkspace.PDF_WATERMARK -> getOrInflatePdfWatermark().root
        SecondaryWorkspace.PDF_EXTRACT_TEXT -> getOrInflatePdfExtractText().root
        SecondaryWorkspace.PDF_EXTRACT_PAGES -> getOrInflatePdfExtractPages().root
        SecondaryWorkspace.PDF_FLATTEN -> getOrInflatePdfFlatten().root
        SecondaryWorkspace.PDF_IMPOSITION -> getOrInflatePdfImposition().root
        SecondaryWorkspace.PDF_QUICK_TOOL -> getOrInflatePdfQuickTool().root
    }

    @MainThread
    fun activate(workspace: SecondaryWorkspace): View {
        val root = getOrInflate(workspace)
        activeWorkspace = workspace
        return root
    }

    @MainThread
    fun deactivate(workspace: SecondaryWorkspace) {
        getRoot(workspace)?.let {
            it.visibility = View.GONE
            it.translationX = 0f
        }
        if (activeWorkspace == workspace) {
            activeWorkspace = null
        }
    }

    @MainThread
    fun pause() {
        // Pauses active state if any
    }

    fun updateInsets(
        statusBarTop: Int,
        navBarBottom: Int,
        imeBottom: Int,
        navBarLeft: Int,
        navBarRight: Int
    ) {
        cachedInsets = InsetCache(
            statusBarTop = statusBarTop,
            navBarBottom = navBarBottom,
            imeBottom = imeBottom,
            navBarLeft = navBarLeft,
            navBarRight = navBarRight
        )
        // Apply to any already inflated secondary workspaces
        imageUpscalerBinding?.root?.let { applyInsetsToDefault(it) }
        docScannerBinding?.root?.let { applyInsetsToDefault(it) }
        backgroundRemoverBinding?.root?.let { applyInsetsToDefault(it) }
        motionLabBinding?.root?.let { applyInsetsToDefault(it) }
        provenanceBinding?.root?.let { applyInsetsToDefault(it) }
        pdfSignatureBinding?.root?.let { applyInsetsToDefault(it) }
        pdfUnlockBinding?.root?.let { applyInsetsToDefault(it) }
        pdfStudioBinding?.root?.let { applyInsetsToDefault(it) }
        pdfWatermarkBinding?.root?.let { applyInsetsToDefault(it) }
        pdfExtractTextBinding?.root?.let { applyInsetsToDefault(it) }
        pdfExtractPagesBinding?.root?.let { applyInsetsToDefault(it) }
        pdfFlattenBinding?.root?.let { applyInsetsToDefault(it) }
        pdfImpositionBinding?.root?.let { applyInsetsToDefault(it) }
        pdfQuickToolBinding?.root?.let { applyInsetsToDefault(it) }
    }

    private fun applyInsetsToScrolledAppbar(view: View) {
        WorkspaceInsets.apply(
            root = view,
            statusBarTop = cachedInsets.statusBarTop,
            navBarBottom = cachedInsets.navBarBottom,
            imeBottom = cachedInsets.imeBottom,
            navBarLeft = cachedInsets.navBarLeft,
            navBarRight = cachedInsets.navBarRight,
            contract = WorkspaceInsets.SCROLLED_APPBAR
        )
    }

    private fun applyInsetsToDefault(view: View) {
        WorkspaceInsets.apply(
            root = view,
            statusBarTop = cachedInsets.statusBarTop,
            navBarBottom = cachedInsets.navBarBottom,
            imeBottom = cachedInsets.imeBottom,
            navBarLeft = cachedInsets.navBarLeft,
            navBarRight = cachedInsets.navBarRight,
            contract = WorkspaceInsets.DEFAULT
        )
    }

    fun onDestroy() {
        activeWorkspace = null
        imageUpscalerBinding = null
        docScannerBinding = null
        backgroundRemoverBinding = null
        motionLabBinding = null
        provenanceBinding = null
        pdfSignatureBinding = null
        pdfUnlockBinding = null
        pdfStudioBinding = null
        pdfWatermarkBinding = null
        pdfExtractTextBinding = null
        pdfExtractPagesBinding = null
        pdfFlattenBinding = null
        pdfImpositionBinding = null
        pdfQuickToolBinding = null
    }
}
