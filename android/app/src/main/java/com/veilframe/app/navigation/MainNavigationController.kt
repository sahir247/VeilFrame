package com.veilframe.app.navigation

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
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

    private var isSyncingBottomNav = false

    fun init() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.containerSettings.visibility == View.VISIBLE) {
                    com.veilframe.app.ui.motion.NavigationMotionController.closeSettings(
                        binding.containerSettings,
                        binding.scrimSettings
                    )
                    return
                }
                if (currentScreen == ScreenState.MARKDOWN_VIEWER) {
                    if (onMarkdownBackPressed()) {
                        return
                    }
                    if (previousScreen == ScreenState.TOOL) {
                        showToolScreen()
                    } else {
                        showHomeScreen()
                    }
                } else if (currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY) {
                    showHomeScreen()
                } else if (currentScreen != ScreenState.HOME) {
                    if (previousScreen == ScreenState.TOOLS) {
                        showToolsScreen()
                    } else if (previousScreen == ScreenState.LIBRARY) {
                        showLibraryScreen()
                    } else {
                        showHomeScreen()
                    }
                } else {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.btnBackToHome.setOnClickListener {
            showHomeScreen()
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (isSyncingBottomNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentScreen != ScreenState.HOME) showHomeScreen()
                    true
                }
                R.id.nav_tools -> {
                    if (currentScreen != ScreenState.TOOLS) showToolsScreen()
                    true
                }
                R.id.nav_library -> {
                    if (currentScreen != ScreenState.LIBRARY) showLibraryScreen()
                    true
                }
                else -> false
            }
        }

        binding.navigationRail.setOnItemSelectedListener { item ->
            if (isSyncingBottomNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentScreen != ScreenState.HOME) showHomeScreen()
                    true
                }
                R.id.nav_tools -> {
                    if (currentScreen != ScreenState.TOOLS) showToolsScreen()
                    true
                }
                R.id.nav_library -> {
                    if (currentScreen != ScreenState.LIBRARY) showLibraryScreen()
                    true
                }
                else -> false
            }
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
    }

    fun showHomeScreen() {
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
            ScreenState.HOME -> null
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.HOME

        binding.toolbarHome.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(true)
        syncBottomNavSelection(R.id.nav_home)

        if (outgoingToolView != null && outgoingToolView.visibility == View.VISIBLE && outgoingToolView != binding.layoutToolsCatalogue.scrollToolsCatalogue && outgoingToolView != binding.layoutLibrary.scrollLibrary) {
            com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                homeView = binding.scrollHome,
                toolView = outgoingToolView
            ) {
                hideAllToolViewsExcept(null)
                if (previousScreen == ScreenState.QR_STUDIO) {
                    val qrFrag = activity.supportFragmentManager.findFragmentById(binding.fragmentQrStudio.id)
                    if (qrFrag != null) {
                        activity.supportFragmentManager.beginTransaction().remove(qrFrag).commitAllowingStateLoss()
                    }
                }
            }
        } else {
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
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.TOOLS

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
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.LIBRARY

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

    fun showToolScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.TOOL

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.VISIBLE
        binding.cardCleanerActionDock.visibility = View.VISIBLE
        binding.bottomActionDock.visibility = View.VISIBLE
        setAdaptiveNavigationVisibility(false)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.scrollTool)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.scrollTool
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.scrollTool.visibility = View.VISIBLE
            binding.scrollTool.alpha = 1.0f
            binding.scrollTool.translationX = 0f
            hideAllToolViewsExcept(binding.scrollTool)
        }
    }

    fun showImageStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)
        binding.layoutImageStudio.scrollImageStudio.visibility = View.VISIBLE

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.layoutImageStudio.root)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.layoutImageStudio.root
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.layoutImageStudio.root.visibility = View.VISIBLE
            binding.layoutImageStudio.root.alpha = 1.0f
            binding.layoutImageStudio.root.translationX = 0f
            hideAllToolViewsExcept(binding.layoutImageStudio.root)
        }
    }

    fun showVideoStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.VIDEO_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.VISIBLE

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.layoutVideoStudio.root)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.layoutVideoStudio.root
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.layoutVideoStudio.root.visibility = View.VISIBLE
            binding.layoutVideoStudio.root.alpha = 1.0f
            binding.layoutVideoStudio.root.translationX = 0f
            hideAllToolViewsExcept(binding.layoutVideoStudio.root)
        }
    }

    fun showMarkdownViewerScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        if (currentScreen != ScreenState.MARKDOWN_VIEWER) {
            previousScreen = currentScreen
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        currentScreen = ScreenState.MARKDOWN_VIEWER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.layoutMarkdownViewer.layoutMarkdownRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.layoutMarkdownViewer.layoutMarkdownRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.VISIBLE
            binding.layoutMarkdownViewer.layoutMarkdownRoot.alpha = 1.0f
            binding.layoutMarkdownViewer.layoutMarkdownRoot.translationX = 0f
            hideAllToolViewsExcept(binding.layoutMarkdownViewer.layoutMarkdownRoot)
        }
    }

    fun showImageUpscalerScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_UPSCALER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        val upscalerRoot = workspaceHost.activate(SecondaryWorkspace.IMAGE_UPSCALER)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(upscalerRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = upscalerRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            upscalerRoot.visibility = View.VISIBLE
            upscalerRoot.alpha = 1.0f
            upscalerRoot.translationX = 0f
            hideAllToolViewsExcept(upscalerRoot)
        }
    }

    fun showQrStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.QR_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.fragmentQrStudio)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.fragmentQrStudio
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.fragmentQrStudio.visibility = View.VISIBLE
            binding.fragmentQrStudio.alpha = 1.0f
            binding.fragmentQrStudio.translationX = 0f
            hideAllToolViewsExcept(binding.fragmentQrStudio)
        }
    }

    fun showDocumentScannerScreen(state: ScreenState = ScreenState.DOCUMENT_SCANNER_ENTRY) {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = state

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        val docRoot = workspaceHost.activate(SecondaryWorkspace.DOCUMENT_SCANNER)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(docRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = docRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            docRoot.visibility = View.VISIBLE
            docRoot.alpha = 1.0f
            docRoot.translationX = 0f
            hideAllToolViewsExcept(docRoot)
        }
    }

    fun showBackgroundRemoverScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.BACKGROUND_REMOVER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        val bgRoot = workspaceHost.activate(SecondaryWorkspace.BACKGROUND_REMOVER)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(bgRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = bgRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            bgRoot.visibility = View.VISIBLE
            bgRoot.alpha = 1.0f
            bgRoot.translationX = 0f
            hideAllToolViewsExcept(bgRoot)
        }
    }

    fun showMotionLabScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.MOTION_LAB

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        val motionRoot = workspaceHost.activate(SecondaryWorkspace.MOTION_LAB)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(motionRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = motionRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            motionRoot.visibility = View.VISIBLE
            motionRoot.alpha = 1.0f
            motionRoot.translationX = 0f
            hideAllToolViewsExcept(motionRoot)
        }
    }

    fun showImageQualityScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_QUALITY

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.layoutImageQuality.layoutImageQualityRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.layoutImageQuality.layoutImageQualityRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.layoutImageQuality.layoutImageQualityRoot.visibility = View.VISIBLE
            binding.layoutImageQuality.layoutImageQualityRoot.alpha = 1.0f
            binding.layoutImageQuality.layoutImageQualityRoot.translationX = 0f
            hideAllToolViewsExcept(binding.layoutImageQuality.layoutImageQualityRoot)
        }
    }

    fun showProvenanceScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME || currentScreen == ScreenState.TOOLS || currentScreen == ScreenState.LIBRARY)
        previousScreen = currentScreen
        currentScreen = ScreenState.PROVENANCE

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        setAdaptiveNavigationVisibility(false)

        val provRoot = workspaceHost.activate(SecondaryWorkspace.PROVENANCE)

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(provRoot)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = provRoot
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            provRoot.visibility = View.VISIBLE
            provRoot.alpha = 1.0f
            provRoot.translationX = 0f
            hideAllToolViewsExcept(provRoot)
        }
    }
}
