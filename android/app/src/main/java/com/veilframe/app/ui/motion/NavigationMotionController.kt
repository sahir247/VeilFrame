package com.veilframe.app.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import kotlin.math.abs

/**
 * Orchestrates top-level screen and overlay transitions adhering to Material 3 Expressive motion physics.
 *
 * Capabilities:
 * 1. Home <-> Tool screen transitions (directional slide + crossfade).
 * 2. Dedicated slide-over Settings overlay (slides from right edge, left-to-right swipe-to-dismiss).
 * 3. Reduced motion accessibility check for all transitions.
 */
object NavigationMotionController {

    private const val SLIDE_OFFSET_DP = 28f
    private const val SETTINGS_DURATION_MS = 280L

    /**
     * Hero-polish: the card that triggered a workspace open, consumed by the
     * next [showTool] as the container-transform origin. Set by click sites
     * right before navigation; always cleared on consume and on [hideTool],
     * so a stale value can never fire on an unrelated transition.
     */
    @Volatile
    var nextOriginView: View? = null

    /**
     * Navigates from Home screen to a Tool container with buttery smooth M3 expressive transition.
     * Keeps home chrome (toolbarHome and bottomNav) stable and gracefully fades them out
     * without instantaneous height collapse or shutter jump.
     */
    fun showTool(
        homeView: View,
        toolView: View,
        toolbarHome: View? = null,
        bottomNav: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = toolView.context
        val origin = nextOriginView
        nextOriginView = null
        if (origin != null) {
            VfSprings.cancel(origin)
        }

        if (ExpressiveMotion.isReducedMotion(context)) {
            homeView.isVisible = false
            toolbarHome?.isVisible = false
            bottomNav?.isVisible = false
            toolView.isVisible = true
            toolView.alpha = 1f
            toolView.translationX = 0f
            toolView.translationZ = 0f
            onComplete?.invoke()
            return
        }

        if (origin != null && tryContainerTransform(homeView, toolView, origin, toolbarHome, bottomNav, onComplete)) {
            return
        }

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val slideOffset = (screenWidth * 0.28f).coerceIn(80f * density, 180f * density)

        // Elevate incoming tool view above home chrome during the transition
        toolView.translationZ = 16f * density
        toolView.isVisible = true
        toolView.alpha = 0f
        toolView.translationX = slideOffset

        homeView.animate().cancel()
        homeView.animate().setListener(null)
        toolView.animate().cancel()
        toolView.animate().setListener(null)
        toolbarHome?.animate()?.cancel()
        toolbarHome?.animate()?.setListener(null)
        bottomNav?.animate()?.cancel()
        bottomNav?.animate()?.setListener(null)

        // Home view glides slightly left and fades
        homeView.animate()
            .alpha(0f)
            .translationX(-slideOffset * 0.35f)
            .setDuration(MotionSpec.NORMAL)
            .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    homeView.isVisible = false
                    homeView.translationX = 0f
                    homeView.alpha = 1f
                }
            })
            .start()

        // Home chrome fades out gracefully without layout jump
        toolbarHome?.animate()
            ?.alpha(0f)
            ?.setDuration(180L)
            ?.setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    toolbarHome.isVisible = false
                    toolbarHome.alpha = 1f
                }
            })
            ?.start()

        bottomNav?.animate()
            ?.alpha(0f)
            ?.translationY(bottomNav.height.toFloat().takeIf { it > 0 } ?: (80f * density))
            ?.setDuration(200L)
            ?.setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomNav.isVisible = false
                    bottomNav.alpha = 1f
                    bottomNav.translationY = 0f
                }
            })
            ?.start()

        // Tool slides in smoothly from right and settles into place
        toolView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(MotionSpec.NORMAL)
            .setInterpolator(MotionSpec.EMPHASIZED)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    toolView.translationZ = 0f
                    onComplete?.invoke()
                }
            })
            .start()
    }

    /**
     * Material container transform: the clicked card morphs into the workspace
     * surface (theme easing/duration, scrim, corner interpolation handled by
     * the Material library). Returns false on ANY problem so showTool falls
     * back to the classic directional slide.
     */
    private fun tryContainerTransform(
        homeView: View,
        toolView: View,
        origin: View,
        toolbarHome: View? = null,
        bottomNav: View? = null,
        onComplete: (() -> Unit)?
    ): Boolean {
        // Disabled in favor of buttery-smooth, hardware-accelerated slide/crossfade
        return false
    }

    /**
     * Navigates from a Tool container back to Home with buttery smooth M3 expressive transition.
     * Restores home chrome without sudden jumping.
     */
    fun hideTool(
        homeView: View,
        toolView: View,
        toolbarHome: View? = null,
        bottomNav: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        nextOriginView = null
        val context = homeView.context
        if (ExpressiveMotion.isReducedMotion(context)) {
            toolView.isVisible = false
            homeView.isVisible = true
            homeView.alpha = 1f
            homeView.translationX = 0f
            toolbarHome?.isVisible = true
            toolbarHome?.alpha = 1f
            bottomNav?.isVisible = true
            bottomNav?.alpha = 1f
            bottomNav?.translationY = 0f
            onComplete?.invoke()
            return
        }

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val slideOffset = (screenWidth * 0.28f).coerceIn(80f * density, 180f * density)

        // Prepare home view and chrome in advance without layout snap
        homeView.isVisible = true
        homeView.alpha = 0f
        homeView.translationX = -slideOffset * 0.35f

        if (toolbarHome != null) {
            toolbarHome.isVisible = true
            toolbarHome.alpha = 0f
        }

        if (bottomNav != null) {
            bottomNav.isVisible = true
            bottomNav.alpha = 0f
            bottomNav.translationY = bottomNav.height.toFloat().takeIf { it > 0 } ?: (80f * density)
        }

        toolView.translationZ = 16f * density

        homeView.animate().cancel()
        homeView.animate().setListener(null)
        toolView.animate().cancel()
        toolView.animate().setListener(null)
        toolbarHome?.animate()?.cancel()
        toolbarHome?.animate()?.setListener(null)
        bottomNav?.animate()?.cancel()
        bottomNav?.animate()?.setListener(null)

        // Tool slides out gracefully to the right
        toolView.animate()
            .alpha(0f)
            .translationX(slideOffset)
            .setDuration(240L)
            .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    toolView.isVisible = false
                    toolView.translationX = 0f
                    toolView.translationZ = 0f
                    toolView.alpha = 1f
                }
            })
            .start()

        // Home smoothly returns from left
        homeView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(MotionSpec.NORMAL)
            .setInterpolator(MotionSpec.EMPHASIZED)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    toolbarHome?.isVisible = true
                    toolbarHome?.alpha = 1f
                    bottomNav?.isVisible = true
                    bottomNav?.alpha = 1f
                    bottomNav?.translationY = 0f
                    onComplete?.invoke()
                }
            })
            .start()

        // Home chrome returns smoothly into place
        toolbarHome?.animate()
            ?.alpha(1f)
            ?.setDuration(240L)
            ?.setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    toolbarHome.isVisible = true
                    toolbarHome.alpha = 1f
                }
            })
            ?.start()

        bottomNav?.animate()
            ?.alpha(1f)
            ?.translationY(0f)
            ?.setDuration(MotionSpec.NORMAL)
            ?.setInterpolator(MotionSpec.EMPHASIZED)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomNav.isVisible = true
                    bottomNav.alpha = 1f
                    bottomNav.translationY = 0f
                }
            })
            ?.start()
    }

    /**
     * Slides in the Settings overlay from the right edge.
     */
    fun openSettings(
        settingsContainer: View,
        scrimView: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = settingsContainer.context
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()

        if (ExpressiveMotion.isReducedMotion(context)) {
            scrimView?.isVisible = true
            scrimView?.alpha = 0.6f
            settingsContainer.isVisible = true
            settingsContainer.translationX = 0f
            onComplete?.invoke()
            return
        }

        scrimView?.isVisible = true
        scrimView?.alpha = 0f
        scrimView?.animate()
            ?.alpha(0.6f)
            ?.setDuration(SETTINGS_DURATION_MS)
            ?.setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            ?.start()

        settingsContainer.isVisible = true
        settingsContainer.translationX = screenWidth
        settingsContainer.animate().cancel()
        settingsContainer.animate()
            .translationX(0f)
            .setDuration(SETTINGS_DURATION_MS)
            .setInterpolator(MotionSpec.EMPHASIZED)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete?.invoke()
                }
            })
            .start()
    }

    /**
     * Slides out the Settings overlay to the right edge.
     */
    fun closeSettings(
        settingsContainer: View,
        scrimView: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = settingsContainer.context
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()

        if (ExpressiveMotion.isReducedMotion(context)) {
            settingsContainer.isVisible = false
            scrimView?.isVisible = false
            onComplete?.invoke()
            return
        }

        scrimView?.animate()
            ?.alpha(0f)
            ?.setDuration(SETTINGS_DURATION_MS)
            ?.setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scrimView.isVisible = false
                }
            })
            ?.start()

        settingsContainer.animate().cancel()
        settingsContainer.animate()
            .translationX(screenWidth)
            .setDuration(SETTINGS_DURATION_MS)
            .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settingsContainer.isVisible = false
                    settingsContainer.translationX = 0f
                    onComplete?.invoke()
                }
            })
            .start()
    }

    /**
     * Attaches left-to-right swipe-to-dismiss gesture handling to the Settings panel.
     */
    fun attachSwipeToDismiss(
        settingsContainer: View,
        scrimView: View? = null,
        onDismiss: () -> Unit
    ) {
        val context = settingsContainer.context
        val viewConfig = ViewConfiguration.get(context)
        val touchSlop = viewConfig.scaledTouchSlop
        var initialX = 0f
        var initialY = 0f
        var isDragging = false
        var velocityTracker: VelocityTracker? = null

        settingsContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    isDragging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY

                    if (!isDragging && dx > touchSlop && dx > abs(dy) * 1.5f) {
                        isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isDragging) {
                        val transX = dx.coerceAtLeast(0f)
                        v.translationX = transX
                        val width = v.width.coerceAtLeast(1).toFloat()
                        val progress = (transX / width).coerceIn(0f, 1f)
                        scrimView?.alpha = (0.6f * (1f - progress)).coerceAtLeast(0f)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        velocityTracker?.addMovement(event)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val xVelocity = velocityTracker?.xVelocity ?: 0f
                        val transX = v.translationX
                        val width = v.width.coerceAtLeast(1).toFloat()

                        val shouldDismiss = transX > (width * 0.35f) || xVelocity > 1000f
                        if (shouldDismiss) {
                            closeSettings(settingsContainer, scrimView, onDismiss)
                        } else {
                            // Snap back to 0
                            v.animate()
                                .translationX(0f)
                                .setDuration(180L)
                                .setInterpolator(MotionSpec.EMPHASIZED)
                                .start()
                            scrimView?.animate()
                                ?.alpha(0.6f)
                                ?.setDuration(180L)
                                ?.start()
                        }
                        isDragging = false
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    } else {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        false
                    }
                }
                else -> false
            }
        }
    }
}
