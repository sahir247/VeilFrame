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

    fun showHomeScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.HOME

        binding.toolbarHome.visibility = View.VISIBLE
        binding.toolbarTool.visibility = View.GONE
        binding.scrollHome.visibility = View.VISIBLE
        binding.scrollTool.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        binding.layoutImageStudio.root.visibility = View.GONE
        binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
        binding.layoutVideoStudio.root.visibility = View.GONE
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
        binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.GONE
        binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.GONE

        onHomeScreenEntered()
    }

    fun showToolScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.TOOL

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.VISIBLE
        binding.scrollHome.visibility = View.GONE
        binding.scrollTool.visibility = View.VISIBLE
        binding.bottomActionDock.visibility = View.VISIBLE
        binding.layoutImageStudio.root.visibility = View.GONE
        binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
        binding.layoutVideoStudio.root.visibility = View.GONE
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
        binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.GONE
        binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.GONE
    }

    fun showImageStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.scrollHome.visibility = View.GONE
        binding.scrollTool.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        binding.layoutImageStudio.root.visibility = View.VISIBLE
        binding.layoutImageStudio.scrollImageStudio.visibility = View.VISIBLE
        binding.layoutVideoStudio.root.visibility = View.GONE
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
        binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.GONE
        binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.GONE
    }

    fun showVideoStudioScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.VIDEO_STUDIO

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.scrollHome.visibility = View.GONE
        binding.scrollTool.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        binding.layoutImageStudio.root.visibility = View.GONE
        binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
        binding.layoutVideoStudio.root.visibility = View.VISIBLE
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.VISIBLE
        binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.GONE
        binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.GONE
    }

    fun showMarkdownViewerScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        if (currentScreen != ScreenState.MARKDOWN_VIEWER) {
            previousScreen = currentScreen
        }
        currentScreen = ScreenState.MARKDOWN_VIEWER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.scrollHome.visibility = View.GONE
        binding.scrollTool.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
        binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.VISIBLE
        binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.GONE
    }

    fun showImageUpscalerScreen() {
        onPauseVideoPlayback()
        if (currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        previousScreen = currentScreen
        currentScreen = ScreenState.IMAGE_UPSCALER

        binding.toolbarHome.visibility = View.GONE
        binding.toolbarTool.visibility = View.GONE
        binding.scrollHome.visibility = View.GONE
        binding.scrollTool.visibility = View.GONE
        binding.bottomActionDock.visibility = View.GONE
        binding.layoutImageStudio.scrollImageStudio.visibility = View.GONE
        binding.layoutVideoStudio.scrollVideoStudio.visibility = View.GONE
        binding.layoutMarkdownViewer.layoutMarkdownRoot.visibility = View.GONE
        binding.layoutImageUpscaler.scrollImageUpscaler.visibility = View.VISIBLE
    }
}
