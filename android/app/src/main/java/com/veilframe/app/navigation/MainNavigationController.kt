package com.veilframe.app.navigation

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.databinding.ActivityMainBinding

/**
 * Controller orchestrating top-level screen navigation, view visibility toggles,
 * and system back-press routing for the VeilFrame Mobile Hub.
 */
class MainNavigationController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onSaveActiveToolState: () -> Unit,
    private val onPauseVideoPlayback: () -> Unit,
    private val onHomeScreenEntered: () -> Unit,
    private val onMarkdownBackPressed: () -> Boolean = { false }
) {
    var currentScreen: ScreenState = ScreenState.HOME
        private set
    var previousScreen: ScreenState = ScreenState.HOME
        private set

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
                } else if (currentScreen != ScreenState.HOME) {
                    showHomeScreen()
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
        if (activeView != binding.layoutImageUpscaler.scrollImageUpscaler) {
            binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.GONE
            binding.layoutImageUpscaler.scrollImageUpscaler.translationX = 0f
        }
        if (activeView != binding.fragmentQrStudio) {
            binding.fragmentQrStudio.visibility = View.GONE
            binding.fragmentQrStudio.translationX = 0f
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
            ScreenState.IMAGE_UPSCALER -> binding.layoutImageUpscaler.scrollImageUpscaler
            ScreenState.MARKDOWN_VIEWER -> binding.layoutMarkdownViewer.layoutMarkdownRoot
            ScreenState.QR_STUDIO -> binding.fragmentQrStudio
            ScreenState.HOME -> null
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.HOME

        binding.toolbarHome.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        if (outgoingToolView != null && outgoingToolView.visibility == View.VISIBLE) {
            com.veilframe.app.ui.motion.NavigationMotionController.hideTool(
                homeView = binding.scrollHome,
                toolView = outgoingToolView
            ) {
                hideAllToolViewsExcept(null)
            }
        } else {
            binding.scrollHome.visibility = View.VISIBLE
            binding.scrollHome.alpha = 1.0f
            binding.scrollHome.translationX = 0f
            hideAllToolViewsExcept(null)
        }

        onHomeScreenEntered()
    }

    fun showToolScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME)
        previousScreen = currentScreen
        currentScreen = ScreenState.TOOL

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.VISIBLE
        binding.cardCleanerActionDock.visibility = View.VISIBLE
        binding.bottomActionDock.visibility = View.VISIBLE

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
        val wasHome = (currentScreen == ScreenState.HOME)
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
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
        val wasHome = (currentScreen == ScreenState.HOME)
        previousScreen = currentScreen
        currentScreen = ScreenState.VIDEO_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
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
        val wasHome = (currentScreen == ScreenState.HOME)
        currentScreen = ScreenState.MARKDOWN_VIEWER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

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
        val wasHome = (currentScreen == ScreenState.HOME)
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_UPSCALER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

        if (wasHome && binding.scrollHome.visibility == View.VISIBLE) {
            hideAllToolViewsExcept(binding.layoutImageUpscaler.scrollImageUpscaler)
            com.veilframe.app.ui.motion.NavigationMotionController.showTool(
                homeView = binding.scrollHome,
                toolView = binding.layoutImageUpscaler.scrollImageUpscaler
            )
        } else {
            binding.scrollHome.visibility = View.GONE
            binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.VISIBLE
            binding.layoutImageUpscaler.scrollImageUpscaler.alpha = 1.0f
            binding.layoutImageUpscaler.scrollImageUpscaler.translationX = 0f
            hideAllToolViewsExcept(binding.layoutImageUpscaler.scrollImageUpscaler)
        }
    }

    fun showQrStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        val wasHome = (currentScreen == ScreenState.HOME)
        previousScreen = currentScreen
        currentScreen = ScreenState.QR_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.cardCleanerActionDock.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE

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
}
