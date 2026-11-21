package com.veilframe.app.navigation

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.veilframe.app.MainActivity
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.*
import com.veilframe.app.ui.motion.ExpressiveMotion
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
    private var transitionEpoch = 0L
    private var handledExitScreen: ScreenState? = null

    private fun proceedAfterExitAction(screen: ScreenState, action: () -> Unit, onProceed: () -> Unit) {
        val previous = handledExitScreen
        handledExitScreen = screen
        try {
            action()
            onProceed()
        } finally {
            handledExitScreen = previous
        }
    }

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
            updateBackCallbackState()
            return
        }
        if (currentScreen != newScreen) {
            if (isRootScreen(currentScreen)) {
                workspaceOriginScreen = currentScreen
            }
            screenBackStack.addLast(currentScreen)
            previousScreen = currentScreen
            currentScreen = newScreen
            updateBackCallbackState()
        }
    }

    internal fun setScreenForTesting(newScreen: ScreenState) {
        if (currentScreen != newScreen) {
            currentScreen = newScreen
            updateBackCallbackState()
        }
    }

    internal fun getBackStackForTesting(): List<ScreenState> = screenBackStack.toList()

    private var isSyncingBottomNav = false
    var isRestoringState: Boolean = false
    private var pendingPopStart: com.veilframe.app.ui.motion.NavigationMotionController.PopStartState? = null

    private fun getRootOrWorkspaceView(screen: ScreenState): View = when (screen) {
        ScreenState.HOME -> binding.scrollHome
        else -> getActiveViewForScreen(screen) ?: binding.scrollHome
    }

    fun getActiveViewForScreen(screen: ScreenState = currentScreen): View? = when (screen) {
        ScreenState.TOOL -> binding.scrollTool
        ScreenState.IMAGE_STUDIO -> binding.layoutImageStudio.root
        ScreenState.VIDEO_STUDIO -> binding.layoutVideoStudio.root
        ScreenState.IMAGE_UPSCALER -> workspaceHost.imageUpscalerRoot
        ScreenState.MARKDOWN_VIEWER -> binding.layoutMarkdownViewer.layoutMarkdownRoot
        ScreenState.QR_STUDIO -> workspaceHost.qrStudioRoot
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

    private class PredictiveBackOutlineProvider(var radius: Float = 0f) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                outline.setRoundRect(0, 0, w, h, radius.coerceAtLeast(0f))
            } else {
                outline.setEmpty()
            }
        }
    }

    private val predictiveOutlineProvider = PredictiveBackOutlineProvider()

    val backCallback = object : OnBackPressedCallback(false) {
        private var isPredictiveForSettings = false
        private var isPredictiveForInnerTool = false
        private var isPredictiveForWorkspace = false
        private var activeTargetView: View? = null
        private var underlyingDestinationView: View? = null
        private var lastSwipeEdgeSign: Float = 1f

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            pendingPopStart = null
            if (ExpressiveMotion.isReducedMotion(activity)) {
                return
            }

            val edgeSign = if (backEvent.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
            lastSwipeEdgeSign = edgeSign

            if (binding.containerSettings.visibility == View.VISIBLE) {
                isPredictiveForSettings = true
                isPredictiveForInnerTool = false
                isPredictiveForWorkspace = false
                activeTargetView = binding.containerSettings
                underlyingDestinationView = null
                return
            }

            val mainAct = activity as? MainActivity
            if (mainAct?.canHandleInnerToolBack() == true) {
                isPredictiveForSettings = false
                isPredictiveForInnerTool = true
                isPredictiveForWorkspace = false
                activeTargetView = mainAct.getInnerToolPredictiveView()
                underlyingDestinationView = null
                return
            }

            isPredictiveForSettings = false
            isPredictiveForInnerTool = false
            val target = getActiveViewForScreen(currentScreen)
            if (target != null && target.visibility == View.VISIBLE) {
                isPredictiveForWorkspace = true
                activeTargetView = target

                val destination = when {
                    currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY -> ScreenState.HOME
                    screenBackStack.isNotEmpty() -> screenBackStack.peekLast() ?: workspaceOriginScreen
                    else -> workspaceOriginScreen
                }
                val destView = getRootOrWorkspaceView(destination)
                val w = (target.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels).toFloat()
                if (destView !== target) {
                    underlyingDestinationView = destView
                    destView.visibility = View.VISIBLE
                    destView.scaleX = 0.94f
                    destView.scaleY = 0.94f
                    destView.alpha = 0.72f
                    destView.elevation = 0f
                    destView.translationZ = 0f
                    destView.translationX = -edgeSign * (w * 0.14f)
                } else {
                    underlyingDestinationView = null
                }

                val density = target.resources.displayMetrics.density
                target.elevation = 16f * density
                target.translationZ = 16f * density
                target.alpha = 1f
                predictiveOutlineProvider.radius = 0f
                target.outlineProvider = predictiveOutlineProvider
                target.clipToOutline = true
            } else {
                isPredictiveForWorkspace = false
                activeTargetView = null
                underlyingDestinationView = null
            }
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            val progress = backEvent.progress.coerceIn(0f, 1f)
            val edge = backEvent.swipeEdge
            val edgeSign = if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
            lastSwipeEdgeSign = edgeSign

            if (isPredictiveForSettings && activeTargetView != null) {
                val width = activeTargetView!!.width.toFloat().coerceAtLeast(100f)
                activeTargetView!!.translationX = progress * width
                binding.scrimSettings.alpha = (0.6f * (1f - progress)).coerceAtLeast(0f)
                return
            }

            if (isPredictiveForInnerTool) {
                (activity as? MainActivity)?.handleInnerToolBackProgressed(progress, edge)
                return
            }

            if (isPredictiveForWorkspace && activeTargetView != null) {
                val v = activeTargetView!!
                val density = v.resources.displayMetrics.density
                val w = v.width.toFloat().coerceAtLeast(300f)

                // Outgoing stacked card: slide far enough to give a generous preview (~39% clear peek)
                val scale = 1f - (progress * 0.10f)
                v.scaleX = scale
                v.scaleY = scale
                v.translationX = progress * edgeSign * (w * 0.34f)

                // Morph corners smoothly to rounded floating card
                val cornerRadius = progress * 28f * density
                predictiveOutlineProvider.radius = cornerRadius
                v.invalidateOutline()

                // Underlying destination page: crisp parallax glide & reveal
                underlyingDestinationView?.let { dest ->
                    dest.scaleX = 0.94f + (progress * 0.06f)
                    dest.scaleY = 0.94f + (progress * 0.06f)
                    dest.alpha = 0.72f + (progress * 0.28f)
                    dest.translationX = -edgeSign * (w * 0.14f) * (1f - progress)
                }
            }
        }

        override fun handleOnBackCancelled() {
            pendingPopStart = null
            if (isPredictiveForSettings && activeTargetView != null) {
                activeTargetView?.animate()
                    ?.translationX(0f)
                    ?.setDuration(180L)
                    ?.setInterpolator(FastOutSlowInInterpolator())
                    ?.start()
                binding.scrimSettings.animate()
                    ?.alpha(0.6f)
                    ?.setDuration(180L)
                    ?.start()
            }

            if (isPredictiveForInnerTool) {
                (activity as? MainActivity)?.handleInnerToolBackCancelled()
            }

            if (isPredictiveForWorkspace && activeTargetView != null) {
                val v = activeTargetView!!
                val w = v.width.toFloat().coerceAtLeast(300f)
                v.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.translationX(0f)
                    ?.setDuration(200L)
                    ?.setInterpolator(FastOutSlowInInterpolator())
                    ?.withEndAction {
                        v.clipToOutline = false
                        v.elevation = 0f
                        v.translationZ = 0f
                        v.outlineProvider = ViewOutlineProvider.BACKGROUND
                    }
                    ?.start()

                underlyingDestinationView?.let { dest ->
                    dest.animate()
                        ?.alpha(0f)
                        ?.scaleX(0.94f)
                        ?.scaleY(0.94f)
                        ?.translationX(-lastSwipeEdgeSign * w * 0.14f)
                        ?.setDuration(180L)
                        ?.setInterpolator(FastOutSlowInInterpolator())
                        ?.withEndAction {
                            dest.visibility = View.GONE
                            dest.scaleX = 1f
                            dest.scaleY = 1f
                            dest.alpha = 1f
                            dest.translationX = 0f
                        }
                        ?.start()
                }
            }

            isPredictiveForSettings = false
            isPredictiveForInnerTool = false
            isPredictiveForWorkspace = false
            activeTargetView = null
            underlyingDestinationView = null
        }

        override fun handleOnBackPressed() {
            if (binding.containerSettings.visibility == View.VISIBLE) {
                if (isPredictiveForSettings && activeTargetView != null) {
                    activeTargetView?.translationX = 0f
                }
                isPredictiveForSettings = false
                isPredictiveForInnerTool = false
                isPredictiveForWorkspace = false
                activeTargetView = null
                underlyingDestinationView = null
                pendingPopStart = null
                (activity as? MainActivity)?.closeSettingsOverlay() ?: com.veilframe.app.ui.motion.NavigationMotionController.closeSettings(
                    binding.containerSettings,
                    binding.scrimSettings
                )
                updateBackCallbackState()
                return
            }

            val mainAct = activity as? MainActivity
            if (mainAct?.handleToolBackPressed(currentScreen) == true) {
                isPredictiveForSettings = false
                isPredictiveForInnerTool = false
                isPredictiveForWorkspace = false
                activeTargetView = null
                underlyingDestinationView = null
                pendingPopStart = null
                updateBackCallbackState()
                return
            }

            if (isPredictiveForWorkspace && activeTargetView != null) {
                val v = activeTargetView!!
                val dest = underlyingDestinationView
                pendingPopStart = com.veilframe.app.ui.motion.NavigationMotionController.PopStartState(
                    topTranslationX = v.translationX,
                    topScale = v.scaleX,
                    topCornerRadius = predictiveOutlineProvider.radius,
                    underTranslationX = dest?.translationX ?: Float.NaN,
                    underScale = dest?.scaleX ?: 0.94f,
                    underAlpha = dest?.alpha ?: 0.72f,
                    exitDirectionSign = lastSwipeEdgeSign
                )
            } else {
                activeTargetView?.let { v ->
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.translationX = 0f
                    v.translationZ = 0f
                    v.elevation = 0f
                    v.alpha = 1f
                    v.clipToOutline = false
                    v.outlineProvider = ViewOutlineProvider.BACKGROUND
                }
                underlyingDestinationView?.let { dest ->
                    if (dest !== activeTargetView) {
                        dest.visibility = View.GONE
                    }
                    dest.scaleX = 1f
                    dest.scaleY = 1f
                    dest.alpha = 1f
                    dest.elevation = 0f
                    dest.translationZ = 0f
                    dest.translationX = 0f
                }
                pendingPopStart = null
            }

            isPredictiveForSettings = false
            isPredictiveForInnerTool = false
            isPredictiveForWorkspace = false
            activeTargetView = null
            underlyingDestinationView = null

            performBackNavigation()
        }
    }

    fun updateBackCallbackState() {
        val isSettingsOpen = binding.containerSettings.visibility == View.VISIBLE
        val isToolHandlingBack = (activity as? MainActivity)?.canHandleInnerToolBack() == true
        val isNotHomeScreen = currentScreen != ScreenState.HOME
        val hasBackStack = screenBackStack.isNotEmpty()

        val shouldBeEnabled = isSettingsOpen || isToolHandlingBack || isNotHomeScreen || hasBackStack
        if (backCallback.isEnabled != shouldBeEnabled) {
            backCallback.isEnabled = shouldBeEnabled
        }
    }

    fun init() {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        updateBackCallbackState()

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

        binding.bottomNavigation.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.scrollHome.smoothScrollTo(0, 0)
                R.id.nav_tools -> binding.layoutToolsCatalogue.scrollToolsCatalogue.smoothScrollTo(0, 0)
                R.id.nav_library -> binding.layoutLibrary.scrollLibrary.smoothScrollTo(0, 0)
            }
        }

        binding.navigationRail.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.scrollHome.smoothScrollTo(0, 0)
                R.id.nav_tools -> binding.layoutToolsCatalogue.scrollToolsCatalogue.smoothScrollTo(0, 0)
                R.id.nav_library -> binding.layoutLibrary.scrollLibrary.smoothScrollTo(0, 0)
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
        if (mainAct?.handleToolBackPressed(currentScreen) == true) {
            return
        }
        val promptScreen = currentScreen
        val contract = mainAct?.getActiveToolWorkContract(promptScreen)
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
                            if (currentScreen == promptScreen) {
                                proceedAfterExitAction(promptScreen, contract::moveToBackground, onProceed)
                            }
                        }
                        .setNeutralButton("Leave & Discard") { _, _ ->
                            isHandlingWorkStatePrompt = false
                            if (currentScreen == promptScreen) {
                                proceedAfterExitAction(promptScreen, contract::discardAndReleaseAllResources, onProceed)
                            }
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
                            if (currentScreen == promptScreen) {
                                proceedAfterExitAction(promptScreen, contract::discardAndReleaseAllResources, onProceed)
                            }
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
                        if (currentScreen == promptScreen) {
                            proceedAfterExitAction(promptScreen, contract::discardAndReleaseAllResources, onProceed)
                        }
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

    private fun cleanupOutgoingWorkspace(closingScreen: ScreenState) {
        if (isRootScreen(closingScreen) || handledExitScreen == closingScreen) return
        val mainAct = activity as? MainActivity ?: return
        val closingContract = mainAct.getActiveToolWorkContract(closingScreen)
        if (closingContract != null && (!closingContract.supportsBackgroundExecution || !closingContract.isProcessing)) {
            closingContract.discardAndReleaseAllResources()
        }
     }

    private fun popWorkspaceAndNavigate() {
        val closingScreen = currentScreen
        val destination = if (screenBackStack.isNotEmpty()) {
            screenBackStack.removeLast()
        } else {
            workspaceOriginScreen
        }
        // Root destinations perform their own cleanup; do not discard twice.
        if (destination !in setOf(ScreenState.HOME, ScreenState.TOOLS, ScreenState.LIBRARY)) {
            cleanupOutgoingWorkspace(closingScreen)
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
                updateBackCallbackState()
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
        updateBackCallbackState()
    }

    private fun setAdaptiveNavigationVisibility(visible: Boolean) {
        val isExpanded = activity.resources.configuration.smallestScreenWidthDp >= 600
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

    private fun resetAndHideToolView(view: View?) {
        if (view == null) return
        view.animate().cancel()
        view.visibility = View.GONE
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
        view.elevation = 0f
        view.clipToOutline = false
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun hideAllToolViewsExcept(activeView: View?, preserveView: View? = null) {
        androidx.transition.TransitionManager.endTransitions(binding.rootCoordinator)
        if (activeView != binding.scrollTool && preserveView != binding.scrollTool) {
            resetAndHideToolView(binding.scrollTool)
        }
        if (activeView != binding.layoutImageStudio.root && preserveView != binding.layoutImageStudio.root) {
            binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
            resetAndHideToolView(binding.layoutImageStudio.root)
        }
        if (activeView != binding.layoutVideoStudio.root && preserveView != binding.layoutVideoStudio.root) {
            binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
            resetAndHideToolView(binding.layoutVideoStudio.root)
        }
        if (activeView != binding.layoutMarkdownViewer.layoutMarkdownRoot && preserveView != binding.layoutMarkdownViewer.layoutMarkdownRoot) {
            resetAndHideToolView(binding.layoutMarkdownViewer.layoutMarkdownRoot)
        }
        workspaceHost.hideInactive(activeView, preserveView)
        if (activeView != binding.layoutToolsCatalogue.scrollToolsCatalogue && preserveView != binding.layoutToolsCatalogue.scrollToolsCatalogue) {
            resetAndHideToolView(binding.layoutToolsCatalogue.scrollToolsCatalogue)
        }
        if (activeView != binding.layoutLibrary.scrollLibrary && preserveView != binding.layoutLibrary.scrollLibrary) {
            resetAndHideToolView(binding.layoutLibrary.scrollLibrary)
        }
        if (activeView != binding.layoutImageQuality.layoutImageQualityRoot && preserveView != binding.layoutImageQuality.layoutImageQualityRoot) {
            resetAndHideToolView(binding.layoutImageQuality.layoutImageQualityRoot)
        }

    }

    fun showHomeScreen() {
        val epoch = ++transitionEpoch
        val closingScreen = currentScreen
        cleanupOutgoingWorkspace(closingScreen)
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val outgoingToolView = getActiveViewForScreen(currentScreen)
        val popStart = pendingPopStart
        pendingPopStart = null
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

        val animOutgoingView = if (
            outgoingToolView != null &&
            outgoingToolView.visibility == View.VISIBLE &&
            !isRestoringState &&
            (popStart != null ||
                (outgoingToolView != binding.layoutToolsCatalogue.scrollToolsCatalogue &&
                    outgoingToolView != binding.layoutLibrary.scrollLibrary))
        ) outgoingToolView else null

        if (animOutgoingView != null) {
            val navView = if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
                binding.navigationRail
            } else {
                binding.bottomNavigation
            }
            com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                homeView = binding.scrollHome,
                toolView = animOutgoingView,
                toolbarHome = binding.toolbarHome,
                bottomNav = navView,
                popStart = popStart
            ) {
                if (currentScreen == ScreenState.HOME && epoch == transitionEpoch) {
                    hideAllToolViewsExcept(null)
                    binding.appBarLayout.visibility = View.VISIBLE
                    binding.toolbarHome.visibility = View.VISIBLE
                    binding.toolbarHome.alpha = 1f
                    setAdaptiveNavigationVisibility(true)
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
         }

        onHomeScreenEntered()
        updateBackCallbackState()
    }

    fun showToolsScreen() {
        val epoch = ++transitionEpoch
        (activity as? MainActivity)?.clearHomeFileIntake()
        val closingScreen = currentScreen
        cleanupOutgoingWorkspace(closingScreen)
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val outgoingToolView = getActiveViewForScreen(currentScreen)
        val popStart = pendingPopStart
        pendingPopStart = null
        val returningFromTool = (isNavigatingBack && closingScreen != ScreenState.HOME && closingScreen != ScreenState.LIBRARY) || popStart != null

        if (!isNavigatingBack) {
            screenBackStack.clear()
            workspaceOriginScreen = ScreenState.TOOLS
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.TOOLS

        binding.appBarLayout.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        syncBottomNavSelection(R.id.nav_tools)

        val toolsView = binding.layoutToolsCatalogue.scrollToolsCatalogue
        toolsView.scrollTo(0, 0)

        if (returningFromTool && outgoingToolView != null && outgoingToolView !== toolsView &&
            outgoingToolView.visibility == View.VISIBLE && !isRestoringState
        ) {
            binding.scrollHome.visibility = View.GONE
            hideAllToolViewsExcept(toolsView, preserveView = outgoingToolView)
            val navView = if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
                binding.navigationRail
            } else {
                binding.bottomNavigation
            }
            com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                homeView = toolsView,
                toolView = outgoingToolView,
                toolbarHome = binding.toolbarHome,
                bottomNav = navView,
                popStart = popStart
            ) {
                if (currentScreen == ScreenState.TOOLS && epoch == transitionEpoch) {
                    hideAllToolViewsExcept(toolsView)
                    binding.appBarLayout.visibility = View.VISIBLE
                    binding.toolbarHome.visibility = View.VISIBLE
                    binding.toolbarHome.alpha = 1f
                    setAdaptiveNavigationVisibility(true)
                }
            }
        } else {
            binding.toolbarHome.visibility = View.VISIBLE
            setAdaptiveNavigationVisibility(true)
            hideAllToolViewsExcept(toolsView)
            binding.scrollHome.visibility = View.GONE
            toolsView.visibility = View.VISIBLE
            toolsView.alpha = 1.0f
            toolsView.translationX = 0f
        }
        updateBackCallbackState()
    }

    fun showLibraryScreen() {
        val epoch = ++transitionEpoch
        val closingScreen = currentScreen
        cleanupOutgoingWorkspace(closingScreen)
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val outgoingToolView = getActiveViewForScreen(currentScreen)
        val popStart = pendingPopStart
        pendingPopStart = null
        val returningFromTool = (isNavigatingBack && closingScreen != ScreenState.HOME && closingScreen != ScreenState.TOOLS) || popStart != null

        if (!isNavigatingBack) {
            screenBackStack.clear()
            workspaceOriginScreen = ScreenState.LIBRARY
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.LIBRARY

        binding.appBarLayout.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        syncBottomNavSelection(R.id.nav_library)

        val libraryView = binding.layoutLibrary.scrollLibrary

        if (returningFromTool && outgoingToolView != null && outgoingToolView !== libraryView &&
            outgoingToolView.visibility == View.VISIBLE && !isRestoringState
        ) {
            binding.scrollHome.visibility = View.GONE
            hideAllToolViewsExcept(libraryView, preserveView = outgoingToolView)
            val navView = if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
                binding.navigationRail
            } else {
                binding.bottomNavigation
            }
            com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                homeView = libraryView,
                toolView = outgoingToolView,
                toolbarHome = binding.toolbarHome,
                bottomNav = navView,
                popStart = popStart
            ) {
                if (currentScreen == ScreenState.LIBRARY && epoch == transitionEpoch) {
                    hideAllToolViewsExcept(libraryView)
                    binding.appBarLayout.visibility = View.VISIBLE
                    binding.toolbarHome.visibility = View.VISIBLE
                    binding.toolbarHome.alpha = 1f
                    setAdaptiveNavigationVisibility(true)
                }
            }
        } else {
            binding.toolbarHome.visibility = View.VISIBLE
            setAdaptiveNavigationVisibility(true)
            hideAllToolViewsExcept(libraryView)
            binding.scrollHome.visibility = View.GONE
            libraryView.visibility = View.VISIBLE
            libraryView.alpha = 1.0f
            libraryView.translationX = 0f
        }

        onLibraryScreenEntered?.invoke()
        updateBackCallbackState()
    }

    private fun transitionToTool(
        targetView: View,
        @Suppress("UNUSED_PARAMETER") wasHome: Boolean,
        onPrepare: () -> Unit = {}
    ) {
        val epoch = ++transitionEpoch
        val targetScreen = currentScreen
        val isStandardTool = (targetView == binding.scrollTool)
        val fromView = getRootOrWorkspaceView(previousScreen)
        val popStart = pendingPopStart
        pendingPopStart = null

        if (!isStandardTool) {
            binding.appBarLayout.visibility = View.GONE
        }

        if (fromView !== targetView && fromView.visibility == View.VISIBLE && !isRestoringState) {
            val navView = if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
                binding.navigationRail
            } else {
                binding.bottomNavigation
            }
            if (isNavigatingBack) {
                hideAllToolViewsExcept(targetView, preserveView = fromView)
                onPrepare()
                com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                    homeView = targetView,
                    toolView = fromView,
                    toolbarHome = if (isStandardTool) binding.toolbarHome else null,
                    bottomNav = null,
                    popStart = popStart
                ) {
                    if (epoch != transitionEpoch || currentScreen != targetScreen) return@hideTool
                    hideAllToolViewsExcept(targetView)
                    if (fromView !== binding.scrollHome) {
                        binding.scrollHome.visibility = View.GONE
                    }
                    setAdaptiveNavigationVisibility(false)
                    if (isStandardTool) {
                        binding.appBarLayout.visibility = View.VISIBLE
                    }
                }
            } else {
                hideAllToolViewsExcept(targetView, preserveView = fromView)
                onPrepare()
                com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                    homeView = fromView,
                    toolView = targetView,
                    toolbarHome = binding.toolbarHome,
                    bottomNav = navView
                ) {
                    if (epoch != transitionEpoch || currentScreen != targetScreen) return@showTool
                    hideAllToolViewsExcept(targetView)
                    if (fromView !== binding.scrollHome) {
                        binding.scrollHome.visibility = View.GONE
                    }
                    setAdaptiveNavigationVisibility(false)
                    if (isStandardTool) {
                        binding.appBarLayout.visibility = View.VISIBLE
                    }
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
        val enteringFresh = currentScreen != ScreenState.QR_STUDIO && !isRestoringState
        if (enteringFresh) {
            (activity as? MainActivity)?.let { act ->
                if (act.isQrStudioControllerInitialized) {
                    act.qrStudioController.discardAndReleaseAllResources()
                }
            }
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        recordNavigationTo(ScreenState.QR_STUDIO)

        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        val qrRoot = workspaceHost.activate(SecondaryWorkspace.QR_STUDIO)
        transitionToTool(qrRoot, wasHome)
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
        (activity as? MainActivity)?.syncMotionLabIfActive()
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
