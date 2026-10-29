package com.veilframe.app.navigation

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.MainActivity
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.*
import com.veilframe.app.workspace.SecondaryWorkspace
import com.veilframe.app.workspace.WorkspaceHost

/**
 * Controller orchestrating top-level screen navigation, view visibility toggles,
 * bottom navigation tab state, and system back-press routing for the VeilFrame Mobile Hub.
 */
class MainNavigationController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val workspaceHost: WorkspaceHost,
    private val onSaveActiveToolState: () -> Unit,
    private val onPauseVideoPlayback: () -> Unit,
    private val onHomeScreenEntered: () -> Unit,
    private val onMarkdownBackPressed: () -> Boolean = { false },
    private val onLibraryScreenEntered: (() -> Unit)? = null
) {
    var currentScreen: ScreenState = ScreenState.HOME
        private set
    var previousScreen: ScreenState = ScreenState.HOME
        private set

    /** Authoritative originating tab/root screen for currently open workspace */
    var workspaceOriginScreen: ScreenState = ScreenState.HOME
        private set

    /** Transactional back stack preserving intermediate transitions */
    private val screenBackStack = java.util.ArrayDeque<ScreenState>()

    private var isNavigatingBack = false

    fun isRootScreen(screen: ScreenState): Boolean {
        return screen == ScreenState.HOME ||
               screen == ScreenState.TOOLS ||
               screen == ScreenState.LIBRARY ||
               screen == ScreenState.TOOL
    }

    private fun recordNavigationTo(newScreen: ScreenState) {
        if (isNavigatingBack) {
            previousScreen = currentScreen
            currentScreen = newScreen
            return
        }
        if (currentScreen != newScreen) {
            if (isRootScreen(currentScreen)) {
                workspaceOriginScreen = currentScreen
            }
            screenBackStack.addLast(currentScreen)
            previousScreen = currentScreen
            currentScreen = newScreen
        }
    }

    internal fun getBackStackForTesting(): List<ScreenState> = screenBackStack.toList()

    private var isSyncingBottomNav = false
    var isRestoringState: Boolean = false

    fun init() {
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.containerSettings.visibility == View.VISIBLE) {
                    (activity as? MainActivity)?.closeSettingsOverlay() ?: com.veilframe.app.ui.motion.NavigationMotionController.closeSettings(
                        binding.containerSettings,
                        binding.scrimSettings
                    )
                    return
                }

                performBackNavigation()
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)

        binding.btnBackToHome.setOnClickListener {
            performBackNavigation { showHomeScreen() }
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (isSyncingBottomNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentScreen != ScreenState.HOME) {
                        performBackNavigation { showHomeScreen() }
                    }
                    true
                }
                R.id.nav_tools -> {
                    (activity as? MainActivity)?.clearHomeFileIntake()
                    if (currentScreen != ScreenState.TOOLS) {
                        performBackNavigation { showToolsScreen() }
                    }
                    true
                }
                R.id.nav_library -> {
                    (activity as? MainActivity)?.clearHomeFileIntake()
                    if (currentScreen != ScreenState.LIBRARY) {
                        performBackNavigation { showLibraryScreen() }
                    }
                    true
                }
                else -> false
            }
        }

        binding.navigationRail.setOnItemSelectedListener { item ->
            if (isSyncingBottomNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentScreen != ScreenState.HOME) {
                        performBackNavigation { showHomeScreen() }
                    }
                    true
                }
                R.id.nav_tools -> {
                    (activity as? MainActivity)?.clearHomeFileIntake()
                    if (currentScreen != ScreenState.TOOLS) {
                        performBackNavigation { showToolsScreen() }
                    }
                    true
                }
                R.id.nav_library -> {
                    (activity as? MainActivity)?.clearHomeFileIntake()
                    if (currentScreen != ScreenState.LIBRARY) {
                        performBackNavigation { showLibraryScreen() }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @Volatile
    private var isHandlingWorkStatePrompt = false

    fun performBackNavigation(onProceed: () -> Unit = { doActualBackNavigation() }) {
        if (isHandlingWorkStatePrompt) return
        val mainAct = activity as? MainActivity
        val contract = mainAct?.getActiveToolWorkContract(currentScreen)
        val isRootScreen = currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY

        if (!isRootScreen && contract != null) {
            if (contract.isProcessing) {
                if (contract.supportsBackgroundExecution) {
                    // Option to work in background for Image Upscale, Motion Lab, and Video Studio
                    isHandlingWorkStatePrompt = true
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle("Active Task Running")
                        .setMessage("Processing is currently active. Would you like to let it finish in the background, or cancel and discard now?")
                        .setPositiveButton("Work in Background") { _, _ ->
                            isHandlingWorkStatePrompt = false
                            contract.moveToBackground()
                            onProceed()
                        }
                        .setNeutralButton("Leave & Discard") { _, _ ->
                            isHandlingWorkStatePrompt = false
                            contract.discardAndReleaseAllResources()
                            onProceed()
                        }
                        .setNegativeButton("Stay") { _, _ ->
                            isHandlingWorkStatePrompt = false
                        }
                        .setOnCancelListener {
                            isHandlingWorkStatePrompt = false
                        }
                        .show()
                    return
                } else {
                    // Non-backgroundable active processing
                    isHandlingWorkStatePrompt = true
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle("Cancel Active Work?")
                        .setMessage("A task is currently running. Leaving now will cancel the process immediately and discard unsaved results.")
                        .setPositiveButton("Leave & Discard") { _, _ ->
                            isHandlingWorkStatePrompt = false
                            contract.discardAndReleaseAllResources()
                            onProceed()
                        }
                        .setNegativeButton("Stay") { _, _ ->
                            isHandlingWorkStatePrompt = false
                        }
                        .setOnCancelListener {
                            isHandlingWorkStatePrompt = false
                        }
                        .show()
                    return
                }
            } else if (contract.hasPickedMedia() || contract.hasUnsavedWork()) {
                // Media is picked or unsaved work exists
                isHandlingWorkStatePrompt = true
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("Unsaved Work")
                    .setMessage("You have unsaved changes or media loaded in this tool. Are you sure you want to leave?")
                    .setPositiveButton("Leave & Discard") { _, _ ->
                        isHandlingWorkStatePrompt = false
                        contract.discardAndReleaseAllResources()
                        onProceed()
                    }
                    .setNegativeButton("Stay") { _, _ ->
                        isHandlingWorkStatePrompt = false
                    }
                    .setOnCancelListener {
                        isHandlingWorkStatePrompt = false
                    }
                    .show()
                return
            }
        }

        onProceed()
    }

    /**
     * Unified back-navigation contract for all dedicated media studios and secondary workspaces.
     * Routes back to the true originating screen (Tools, Library, or Home), prompting if active
     * processing is underway.
     */
    fun navigateBackFromWorkspace() {
        performBackNavigation {
            popWorkspaceAndNavigate()
        }
    }

    private fun popWorkspaceAndNavigate() {
        val closingScreen = currentScreen
        val closingContract = (activity as? MainActivity)?.getActiveToolWorkContract(closingScreen)
        if (closingContract?.isProcessing != true) {
            closingContract?.discardAndReleaseAllResources()
        }
        val destination = if (screenBackStack.isNotEmpty()) {
            screenBackStack.removeLast()
        } else {
            workspaceOriginScreen
        }
        isNavigatingBack = true
        try {
            previousScreen = currentScreen
            when (destination) {
                ScreenState.TOOLS -> showToolsScreen()
                ScreenState.LIBRARY -> showLibraryScreen()
                ScreenState.TOOL -> showToolScreen()
                ScreenState.IMAGE_UPSCALER -> showImageUpscalerScreen()
                ScreenState.IMAGE_STUDIO -> showImageStudioScreen()
                ScreenState.VIDEO_STUDIO -> showVideoStudioScreen()
                ScreenState.QR_STUDIO -> showQrStudioScreen()
                ScreenState.BACKGROUND_REMOVER -> showBackgroundRemoverScreen()
                ScreenState.DOCUMENT_SCANNER_ENTRY,
                ScreenState.DOCUMENT_SCANNER_CAMERA,
                ScreenState.DOCUMENT_SCANNER_PAGES,
                ScreenState.DOCUMENT_SCANNER_EDITOR,
                ScreenState.DOCUMENT_SCANNER_EXPORT -> showDocumentScannerScreen(destination)
                ScreenState.MOTION_LAB -> showMotionLabScreen()
                ScreenState.IMAGE_QUALITY -> showImageQualityScreen()
                ScreenState.PROVENANCE -> showProvenanceScreen()
                ScreenState.PDF_SIGNATURE -> showPdfSignatureScreen()
                ScreenState.PDF_UNLOCK -> showPdfUnlockScreen()
                ScreenState.PDF_STUDIO -> showPdfStudioScreen()
                ScreenState.PDF_WATERMARK -> showPdfWatermarkScreen()
                ScreenState.PDF_EXTRACT_TEXT -> showPdfExtractTextScreen()
                ScreenState.PDF_EXTRACT_PAGES -> showPdfExtractPagesScreen()
                ScreenState.PDF_FLATTEN -> showPdfFlattenScreen()
                ScreenState.PDF_IMPOSITION -> showPdfImpositionScreen()
                ScreenState.PDF_QUICK_TOOL -> showPdfQuickToolScreen()
                else -> showHomeScreen()
            }
        } finally {
            isNavigatingBack = false
        }
    }

    private fun doActualBackNavigation() {
        if (currentScreen == ScreenState.MARKDOWN_VIEWER) {
            if (onMarkdownBackPressed()) {
                return
            }
            popWorkspaceAndNavigate()
        } else if (currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY) {
            showHomeScreen()
        } else if (currentScreen != ScreenState.HOME) {
            popWorkspaceAndNavigate()
        } else {
            activity.finish()
        }
    }

    private fun setAdaptiveNavigationVisibility(visible: Boolean) {
        val isExpanded = activity.resources.configuration.screenWidthDp >= 600
        if (!visible) {
            binding.bottomNavigation.visibility = View.GONE
            binding.navigationRail.visibility = View.GONE
        } else {
            if (isExpanded) {
                binding.navigationRail.visibility = View.VISIBLE
                binding.bottomNavigation.visibility = View.GONE
            } else {
                binding.navigationRail.visibility = View.GONE
                binding.bottomNavigation.visibility = View.VISIBLE
            }
        }
    }

    private fun syncBottomNavSelection(itemId: Int) {
        if (binding.bottomNavigation.selectedItemId != itemId || binding.navigationRail.selectedItemId != itemId) {
            isSyncingBottomNav = true
            binding.bottomNavigation.selectedItemId = itemId
            binding.navigationRail.selectedItemId = itemId
            isSyncingBottomNav = false
        }
    }

    private fun hideAllToolViewsExcept(activeView: View?) {
        if (activeView != binding.scrollTool) {
            binding.scrollTool.visibility = View.GONE
            binding.scrollTool.translationX = 0f
        }
        if (activeView != binding.layoutImageStudio.root) {
            binding.layoutImageStudio.root.visibility = View.GONE
            binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
            binding.layoutImageStudio.root.translationX = 0f
        }
        if (activeView != binding.layoutVideoStudio.root) {
            binding.layoutVideoStudio.root.visibility = View.GONE
            binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
            binding.layoutVideoStudio.root.translationX = 0f
        }
        if (activeView != binding.layoutMarkdownViewer.layoutMarkdownRoot) {
            binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.GONE
            binding.layoutMarkdownViewer.layoutMarkdownRoot.translationX = 0f
        }
        workspaceHost.imageUpscalerRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        if (activeView != binding.fragmentQrStudio) {
            binding.fragmentQrStudio.visibility = View.GONE
            binding.fragmentQrStudio.translationX = 0f
        }
        if (activeView != binding.layoutToolsCatalogue.scrollToolsCatalogue) {
            binding.layoutToolsCatalogue.scrollToolsCatalogue.visibility = View.GONE
            binding.layoutToolsCatalogue.scrollToolsCatalogue.translationX = 0f
        }
        if (activeView != binding.layoutLibrary.scrollLibrary) {
            binding.layoutLibrary.scrollLibrary.visibility = View.GONE
            binding.layoutLibrary.scrollLibrary.translationX = 0f
        }
        workspaceHost.docScannerRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.backgroundRemoverRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        if (activeView != binding.layoutImageQuality.layoutImageQualityRoot) {
            binding.layoutImageQuality.layoutImageQualityRoot.visibility = View.GONE
            binding.layoutImageQuality.layoutImageQualityRoot.translationX = 0f
        }
        workspaceHost.motionLabRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.provenanceRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfSignatureRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfUnlockRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfStudioRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfWatermarkRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfExtractTextRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfExtractPagesRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfFlattenRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfImpositionRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
        workspaceHost.pdfQuickToolRoot?.let { view ->
            if (activeView != view) {
                view.visibility = View.GONE
                view.translationX = 0f
            }
        }
    }

    fun showHomeScreen() {
        val closingScreen = currentScreen
        if (!isRootScreen(closingScreen)) {
            val closingContract = (activity as? MainActivity)?.getActiveToolWorkContract(closingScreen)
            if (closingContract?.isProcessing != true) {
                closingContract?.discardAndReleaseAllResources()
            }
        }
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val outgoingToolView = when (currentScreen) {
            ScreenState.TOOL -> binding.scrollTool
            ScreenState.IMAGE_STUDIO -> binding.layoutImageStudio.root
            ScreenState.VIDEO_STUDIO -> binding.layoutVideoStudio.root
            ScreenState.IMAGE_UPSCALER -> workspaceHost.imageUpscalerRoot
            ScreenState.MARKDOWN_VIEWER -> binding.layoutMarkdownViewer.layoutMarkdownRoot
            ScreenState.QR_STUDIO -> binding.fragmentQrStudio
            ScreenState.TOOLS -> binding.layoutToolsCatalogue.scrollToolsCatalogue
            ScreenState.LIBRARY -> binding.layoutLibrary.scrollLibrary
            ScreenState.DOCUMENT_SCANNER_ENTRY,
            ScreenState.DOCUMENT_SCANNER_CAMERA,
            ScreenState.DOCUMENT_SCANNER_PAGES,
            ScreenState.DOCUMENT_SCANNER_EDITOR,
            ScreenState.DOCUMENT_SCANNER_EXPORT -> workspaceHost.docScannerRoot
            ScreenState.BACKGROUND_REMOVER -> workspaceHost.backgroundRemoverRoot
            ScreenState.IMAGE_QUALITY -> binding.layoutImageQuality.layoutImageQualityRoot
            ScreenState.MOTION_LAB -> workspaceHost.motionLabRoot
            ScreenState.PROVENANCE -> workspaceHost.provenanceRoot
            ScreenState.PDF_SIGNATURE -> workspaceHost.pdfSignatureRoot
            ScreenState.PDF_UNLOCK -> workspaceHost.pdfUnlockRoot
            ScreenState.PDF_STUDIO -> workspaceHost.pdfStudioRoot
            ScreenState.PDF_WATERMARK -> workspaceHost.pdfWatermarkRoot
            ScreenState.PDF_EXTRACT_TEXT -> workspaceHost.pdfExtractTextRoot
            ScreenState.PDF_EXTRACT_PAGES -> workspaceHost.pdfExtractPagesRoot
            ScreenState.PDF_FLATTEN -> workspaceHost.pdfFlattenRoot
            ScreenState.PDF_IMPOSITION -> workspaceHost.pdfImpositionRoot
            ScreenState.PDF_QUICK_TOOL -> workspaceHost.pdfQuickToolRoot
            ScreenState.HOME -> null
        }
        if (!isNavigatingBack) {
            screenBackStack.clear()
            workspaceOriginScreen = ScreenState.HOME
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.HOME

        binding.appBarLayout.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        syncBottomNavSelection(R.id.nav_home)

        if (outgoingToolView != null && outgoingToolView.visibility == View.VISIBLE &&
            outgoingToolView != binding.layoutToolsCatalogue.scrollToolsCatalogue &&
            outgoingToolView != binding.layoutLibrary.scrollLibrary &&
            !isRestoringState
        ) {
            val navView = if (activity.resources.configuration.screenWidthDp >= 600) {
                binding.navigationRail
            } else {
                binding.bottomNavigation
            }
            com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                homeView = binding.scrollHome,
                toolView = outgoingToolView,
                toolbarHome = binding.toolbarHome,
                bottomNav = navView
            ) {
                hideAllToolViewsExcept(null)
                binding.appBarLayout.visibility = View.VISIBLE
                binding.toolbarHome.visibility = View.VISIBLE
                binding.toolbarHome.alpha = 1f
                setAdaptiveNavigationVisibility(true)
                if (previousScreen == ScreenState.QR_STUDIO) {
                    val qrFrag = activity.supportFragmentManager.findFragmentById(binding.fragmentQrStudio.id)
                    if (qrFrag != null) {
                        activity.supportFragmentManager.beginTransaction().remove(qrFrag).commitAllowingStateLoss()
                    }
                }
            }
        } else {
            binding.appBarLayout.visibility = View.VISIBLE
            binding.toolbarHome.visibility = View.VISIBLE
            setAdaptiveNavigationVisibility(true)
            binding.scrollHome.visibility = View.VISIBLE
            binding.scrollHome.alpha = 1.0f
            binding.scrollHome.translationX = 0f
            hideAllToolViewsExcept(null)
            if (previousScreen == ScreenState.QR_STUDIO) {
                val qrFrag = activity.supportFragmentManager.findFragmentById(binding.fragmentQrStudio.id)
                if (qrFrag != null) {
                    activity.supportFragmentManager.beginTransaction().remove(qrFrag).commitAllowingStateLoss()
                }
            }
        }

        onHomeScreenEntered()
    }

    fun showToolsScreen() {
        (activity as? MainActivity)?.clearHomeFileIntake()
        val closingScreen = currentScreen
        if (!isRootScreen(closingScreen)) {
            val closingContract = (activity as? MainActivity)?.getActiveToolWorkContract(closingScreen)
            if (closingContract?.isProcessing != true) {
                closingContract?.discardAndReleaseAllResources()
            }
        }
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        if (!isNavigatingBack) {
            screenBackStack.clear()
            workspaceOriginScreen = ScreenState.TOOLS
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.TOOLS

        binding.appBarLayout.visibility = View.VISIBLE
        binding.toolbarHome.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(true)
        syncBottomNavSelection(R.id.nav_tools)

        hideAllToolViewsExcept(binding.layoutToolsCatalogue.scrollToolsCatalogue)
        binding.scrollHome.visibility = View.GONE
        binding.layoutToolsCatalogue.scrollToolsCatalogue.visibility = View.VISIBLE
        binding.layoutToolsCatalogue.scrollToolsCatalogue.alpha = 1.0f
        binding.layoutToolsCatalogue.scrollToolsCatalogue.translationX = 0f
    }

    fun showLibraryScreen() {
        val closingScreen = currentScreen
        if (!isRootScreen(closingScreen)) {
            val closingContract = (activity as? MainActivity)?.getActiveToolWorkContract(closingScreen)
            if (closingContract?.isProcessing != true) {
                closingContract?.discardAndReleaseAllResources()
            }
        }
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        if (!isNavigatingBack) {
            screenBackStack.clear()
            workspaceOriginScreen = ScreenState.LIBRARY
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.LIBRARY

        binding.appBarLayout.visibility = View.VISIBLE
        binding.toolbarHome.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(true)
        syncBottomNavSelection(R.id.nav_library)

        hideAllToolViewsExcept(binding.layoutLibrary.scrollLibrary)
        binding.scrollHome.visibility = View.GONE
        binding.layoutLibrary.scrollLibrary.visibility = View.VISIBLE
        binding.layoutLibrary.scrollLibrary.alpha = 1.0f
        binding.layoutLibrary.scrollLibrary.translationX = 0f

        onLibraryScreenEntered?.invoke()
    }

    private fun transitionToTool(
        targetView: View,
        wasHome: Boolean,
        onPrepare: () -> Unit = {}
    ) {
        val isStandardTool = (targetView == binding.scrollTool)
        if (!isStandardTool) {
            binding.appBarLayout.visibility = View.GONE
        }
        if (wasHome && binding.scrollHome.visibility == View.VISIBLE && !isRestoringState) {
            hideAllToolViewsExcept(targetView)
            onPrepare()
            val navView = if (activity.resources.configuration.screenWidthDp >= 600) {
                binding.navigationRail
            } else {
                binding.bottomNavigation
            }
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = targetView,
                toolbarHome = binding.toolbarHome,
                bottomNav = navView
            ) {
                setAdaptiveNavigationVisibility(false)
                if (isStandardTool) {
                    binding.appBarLayout.visibility = View.VISIBLE
                }
            }
        } else {
            binding.appBarLayout.visibility = if (isStandardTool) View.VISIBLE else View.GONE
            binding.toolbarHome.visibility = View.GONE
            setAdaptiveNavigationVisibility(false)
            binding.scrollHome.visibility = View.GONE
            targetView.visibility = View.VISIBLE
            targetView.alpha = 1.0f
            targetView.translationX = 0f
            hideAllToolViewsExcept(targetView)
            onPrepare()
        }
    }

    fun showToolScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.TOOL)

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.VISIBLE
        binding.cardCleanerActionDock.visibility = View.VISIBLE
        binding.bottomActionDock.visibility = View.VISIBLE

        transitionToTool(binding.scrollTool, wasHome)
    }

    fun showImageStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.IMAGE_STUDIO)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        transitionToTool(binding.layoutImageStudio.root, wasHome) {
            binding.layoutImageStudio.scrollImageStudio.visibility = View.VISIBLE
        }
    }

    fun showVideoStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.VIDEO_STUDIO)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        transitionToTool(binding.layoutVideoStudio.root, wasHome) {
            binding.layoutVideoStudio.scrollVideoStudio.visibility = View.VISIBLE
        }
    }

    fun showMarkdownViewerScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.MARKDOWN_VIEWER)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        transitionToTool(binding.layoutMarkdownViewer.layoutMarkdownRoot, wasHome)
    }

    fun showImageUpscalerScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.IMAGE_UPSCALER)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val upscalerRoot = workspaceHost.activate(SecondaryWorkspace.IMAGE_UPSCALER)
        transitionToTool(upscalerRoot, wasHome)
    }

    fun showQrStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.QR_STUDIO)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        transitionToTool(binding.fragmentQrStudio, wasHome)
    }

    fun showDocumentScannerScreen(state: ScreenState = ScreenState.DOCUMENT_SCANNER_ENTRY) {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(state)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val docRoot = workspaceHost.activate(SecondaryWorkspace.DOCUMENT_SCANNER)
        transitionToTool(docRoot, wasHome)
    }

    fun showBackgroundRemoverScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.BACKGROUND_REMOVER)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val bgRoot = workspaceHost.activate(SecondaryWorkspace.BACKGROUND_REMOVER)
        transitionToTool(bgRoot, wasHome)
    }

    fun showMotionLabScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.MOTION_LAB)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val motionRoot = workspaceHost.activate(SecondaryWorkspace.MOTION_LAB)
        transitionToTool(motionRoot, wasHome)
    }

    fun showImageQualityScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.IMAGE_QUALITY)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        transitionToTool(binding.layoutImageQuality.layoutImageQualityRoot, wasHome)
    }

    fun showProvenanceScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PROVENANCE)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val provRoot = workspaceHost.activate(SecondaryWorkspace.PROVENANCE)
        transitionToTool(provRoot, wasHome)
    }

    fun showPdfSignatureScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_SIGNATURE)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val pdfSigRoot = workspaceHost.activate(SecondaryWorkspace.PDF_SIGNATURE)
        transitionToTool(pdfSigRoot, wasHome)
    }

    fun showPdfUnlockScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_UNLOCK)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val pdfUnlockRoot = workspaceHost.activate(SecondaryWorkspace.PDF_UNLOCK)
        transitionToTool(pdfUnlockRoot, wasHome)
    }

    fun showPdfStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_STUDIO)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_STUDIO)
        transitionToTool(root, wasHome)
    }

    fun showPdfWatermarkScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_WATERMARK)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_WATERMARK)
        transitionToTool(root, wasHome)
    }

    fun showPdfExtractTextScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_EXTRACT_TEXT)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_EXTRACT_TEXT)
        transitionToTool(root, wasHome)
    }

    fun showPdfExtractPagesScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_EXTRACT_PAGES)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_EXTRACT_PAGES)
        transitionToTool(root, wasHome)
    }

    fun showPdfFlattenScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_FLATTEN)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_FLATTEN)
        transitionToTool(root, wasHome)
    }

    fun showPdfImpositionScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_IMPOSITION)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_IMPOSITION)
        transitionToTool(root, wasHome)
    }

    fun showPdfQuickToolScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.PDF_QUICK_TOOL)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val root = workspaceHost.activate(SecondaryWorkspace.PDF_QUICK_TOOL)
        transitionToTool(root, wasHome)
    }
}
