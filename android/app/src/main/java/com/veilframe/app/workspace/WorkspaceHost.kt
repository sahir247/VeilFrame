package com.veilframe.app.workspace

import android.view.View
import androidx.annotation.MainThread
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import com.veilframe.app.databinding.LayoutDocumentScannerBinding
import com.veilframe.app.databinding.LayoutImageUpscalerBinding
import com.veilframe.app.databinding.LayoutMotionLabBinding
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
    PROVENANCE
}

/**
 * Host managing lazy ViewStub inflation, window inset propagation, and lifecycle
 * coordination for secondary offline workspaces (Motion Lab, AI Upscaler, Background Remover,
 * Provenance, Document Scanner).
 *
 * Implements lazy inflation to eliminate startup jank and unnecessary memory allocation
 * during [com.veilframe.app.MainActivity.onCreate].
 */
class WorkspaceHost(
    private val binding: ActivityMainBinding,
    var onImageUpscalerInflated: ((LayoutImageUpscalerBinding) -> Unit)? = null,
    var onDocScannerInflated: ((LayoutDocumentScannerBinding) -> Unit)? = null,
    var onBackgroundRemoverInflated: ((LayoutBackgroundRemoverBinding) -> Unit)? = null,
    var onMotionLabInflated: ((LayoutMotionLabBinding) -> Unit)? = null,
    var onProvenanceInflated: ((LayoutProvenanceBinding) -> Unit)? = null
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

    val imageUpscalerRoot: View? get() = imageUpscalerBinding?.root
    val docScannerRoot: View? get() = docScannerBinding?.root
    val backgroundRemoverRoot: View? get() = backgroundRemoverBinding?.root
    val motionLabRoot: View? get() = motionLabBinding?.root
    val provenanceRoot: View? get() = provenanceBinding?.root

    val isImageUpscalerInflated: Boolean get() = imageUpscalerBinding != null
    val isDocScannerInflated: Boolean get() = docScannerBinding != null
    val isBackgroundRemoverInflated: Boolean get() = backgroundRemoverBinding != null
    val isMotionLabInflated: Boolean get() = motionLabBinding != null
    val isProvenanceInflated: Boolean get() = provenanceBinding != null

    fun isInflated(workspace: SecondaryWorkspace): Boolean = when (workspace) {
        SecondaryWorkspace.IMAGE_UPSCALER -> isImageUpscalerInflated
        SecondaryWorkspace.DOCUMENT_SCANNER -> isDocScannerInflated
        SecondaryWorkspace.BACKGROUND_REMOVER -> isBackgroundRemoverInflated
        SecondaryWorkspace.MOTION_LAB -> isMotionLabInflated
        SecondaryWorkspace.PROVENANCE -> isProvenanceInflated
    }

    fun getRoot(workspace: SecondaryWorkspace): View? = when (workspace) {
        SecondaryWorkspace.IMAGE_UPSCALER -> imageUpscalerRoot
        SecondaryWorkspace.DOCUMENT_SCANNER -> docScannerRoot
        SecondaryWorkspace.BACKGROUND_REMOVER -> backgroundRemoverRoot
        SecondaryWorkspace.MOTION_LAB -> motionLabRoot
        SecondaryWorkspace.PROVENANCE -> provenanceRoot
    }

    @MainThread
    fun getOrInflateImageUpscaler(): LayoutImageUpscalerBinding {
        if (imageUpscalerBinding == null) {
            val stub = binding.stubImageUpscaler
            val view = stub.inflate()
            view.visibility = View.GONE
            val b = LayoutImageUpscalerBinding.bind(view)
            imageUpscalerBinding = b
            applyInsetsToScrolledAppbar(b.root)
            onImageUpscalerInflated?.invoke(b)
        }
        return imageUpscalerBinding!!
    }

    @MainThread
    fun getOrInflateDocScanner(): LayoutDocumentScannerBinding {
        if (docScannerBinding == null) {
            val stub = binding.stubDocumentScanner
            val view = stub.inflate()
            view.visibility = View.GONE
            val b = LayoutDocumentScannerBinding.bind(view)
            docScannerBinding = b
            applyInsetsToDefault(b.root)
            onDocScannerInflated?.invoke(b)
        }
        return docScannerBinding!!
    }

    @MainThread
    fun getOrInflateBackgroundRemover(): LayoutBackgroundRemoverBinding {
        if (backgroundRemoverBinding == null) {
            val stub = binding.stubBackgroundRemover
            val view = stub.inflate()
            view.visibility = View.GONE
            val b = LayoutBackgroundRemoverBinding.bind(view)
            backgroundRemoverBinding = b
            applyInsetsToDefault(b.root)
            onBackgroundRemoverInflated?.invoke(b)
        }
        return backgroundRemoverBinding!!
    }

    @MainThread
    fun getOrInflateMotionLab(): LayoutMotionLabBinding {
        if (motionLabBinding == null) {
            val stub = binding.stubMotionLab
            val view = stub.inflate()
            view.visibility = View.GONE
            val b = LayoutMotionLabBinding.bind(view)
            motionLabBinding = b
            applyInsetsToDefault(b.root)
            onMotionLabInflated?.invoke(b)
        }
        return motionLabBinding!!
    }

    @MainThread
    fun getOrInflateProvenance(): LayoutProvenanceBinding {
        if (provenanceBinding == null) {
            val stub = binding.stubProvenance
            val view = stub.inflate()
            view.visibility = View.GONE
            val b = LayoutProvenanceBinding.bind(view)
            provenanceBinding = b
            applyInsetsToDefault(b.root)
            onProvenanceInflated?.invoke(b)
        }
        return provenanceBinding!!
    }

    fun getOrInflate(workspace: SecondaryWorkspace): View = when (workspace) {
        SecondaryWorkspace.IMAGE_UPSCALER -> getOrInflateImageUpscaler().root
        SecondaryWorkspace.DOCUMENT_SCANNER -> getOrInflateDocScanner().root
        SecondaryWorkspace.BACKGROUND_REMOVER -> getOrInflateBackgroundRemover().root
        SecondaryWorkspace.MOTION_LAB -> getOrInflateMotionLab().root
        SecondaryWorkspace.PROVENANCE -> getOrInflateProvenance().root
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
        imageUpscalerBinding?.root?.let { applyInsetsToScrolledAppbar(it) }
        docScannerBinding?.root?.let { applyInsetsToDefault(it) }
        backgroundRemoverBinding?.root?.let { applyInsetsToDefault(it) }
        motionLabBinding?.root?.let { applyInsetsToDefault(it) }
        provenanceBinding?.root?.let { applyInsetsToDefault(it) }
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
    }
}
