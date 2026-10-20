package com.veilframe.app.ui.motion

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import kotlin.math.abs

/**
 * Orchestrates top-level screen and overlay transitions using native standard Android transitions.
 *
 * Capabilities:
 * 1. Home <-> Tool screen transitions (native slide + fade transitions via TransitionManager).
 * 2. Dedicated slide-over Settings overlay (native slide transition + swipe-to-dismiss gesture).
 * 3. Reduced motion accessibility check for all transitions.
 */
object NavigationMotionController {

    private const val SETTINGS_DURATION_MS = 280L

    /**
     * Navigates from Home screen to a Tool container using standard Android Transitions.
     */
    fun showTool(
        homeView: View,
        toolView: View,
        toolbarHome: View? = null,
        bottomNav: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = toolView.context
        val parent = (homeView.parent as? ViewGroup) ?: (toolView.parent as? ViewGroup)

        if (parent == null || ExpressiveMotion.isReducedMotion(context)) {
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

        // Reset manual transforms
        homeView.animate().cancel()
        toolView.animate().cancel()
        toolbarHome?.animate()?.cancel()
        bottomNav?.animate()?.cancel()
        homeView.translationX = 0f
        toolView.translationX = 0f
        toolView.translationZ = 0f

        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(Fade(Fade.OUT).addTarget(homeView))
            toolbarHome?.let { addTransition(Fade(Fade.OUT).addTarget(it)) }
            bottomNav?.let { addTransition(Slide(Gravity.BOTTOM).addTarget(it)) }
            addTransition(Fade(Fade.IN).addTarget(toolView))
            addTransition(Slide(Gravity.END).addTarget(toolView))
            duration = 240L
            interpolator = FastOutSlowInInterpolator()
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) {
                    onComplete?.invoke()
                }
            })
        }

        TransitionManager.beginDelayedTransition(parent, transition)
        homeView.isVisible = false
        toolbarHome?.isVisible = false
        bottomNav?.isVisible = false
        toolView.isVisible = true
    }

    /**
     * Navigates from a Tool container back to Home using standard Android Transitions.
     */
    fun hideTool(
        homeView: View,
        toolView: View,
        toolbarHome: View? = null,
        bottomNav: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = homeView.context
        val parent = (homeView.parent as? ViewGroup) ?: (toolView.parent as? ViewGroup)

        if (parent == null || ExpressiveMotion.isReducedMotion(context)) {
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

        homeView.animate().cancel()
        toolView.animate().cancel()
        toolbarHome?.animate()?.cancel()
        bottomNav?.animate()?.cancel()
        homeView.translationX = 0f
        toolView.translationX = 0f
        toolView.translationZ = 0f

        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(Fade(Fade.OUT).addTarget(toolView))
            addTransition(Slide(Gravity.END).addTarget(toolView))
            addTransition(Fade(Fade.IN).addTarget(homeView))
            toolbarHome?.let { addTransition(Fade(Fade.IN).addTarget(it)) }
            bottomNav?.let { addTransition(Slide(Gravity.BOTTOM).addTarget(it)) }
            duration = 200L
            interpolator = FastOutSlowInInterpolator()
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) {
                    toolbarHome?.isVisible = true
                    bottomNav?.isVisible = true
                    onComplete?.invoke()
                }
            })
        }

        TransitionManager.beginDelayedTransition(parent, transition)
        toolView.isVisible = false
        homeView.isVisible = true
        toolbarHome?.isVisible = true
        bottomNav?.isVisible = true
    }

    /**
     * Slides in the Settings overlay using native standard Android transition.
     */
    fun openSettings(
        settingsContainer: View,
        scrimView: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = settingsContainer.context
        val parent = settingsContainer.parent as? ViewGroup

        if (parent == null || ExpressiveMotion.isReducedMotion(context)) {
            scrimView?.isVisible = true
            scrimView?.alpha = 0.6f
            settingsContainer.isVisible = true
            settingsContainer.translationX = 0f
            onComplete?.invoke()
            return
        }

        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            scrimView?.let { addTransition(Fade(Fade.IN).addTarget(it)) }
            addTransition(Slide(Gravity.END).addTarget(settingsContainer))
            duration = SETTINGS_DURATION_MS
            interpolator = FastOutSlowInInterpolator()
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) {
                    onComplete?.invoke()
                }
            })
        }

        TransitionManager.beginDelayedTransition(parent, transition)
        scrimView?.isVisible = true
        settingsContainer.isVisible = true
        settingsContainer.translationX = 0f
    }

    /**
     * Slides out the Settings overlay using native standard Android transition.
     */
    fun closeSettings(
        settingsContainer: View,
        scrimView: View? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = settingsContainer.context
        val parent = settingsContainer.parent as? ViewGroup

        if (parent == null || ExpressiveMotion.isReducedMotion(context)) {
            settingsContainer.isVisible = false
            scrimView?.isVisible = false
            settingsContainer.translationX = 0f
            onComplete?.invoke()
            return
        }

        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            scrimView?.let { addTransition(Fade(Fade.OUT).addTarget(it)) }
            addTransition(Slide(Gravity.END).addTarget(settingsContainer))
            duration = SETTINGS_DURATION_MS
            interpolator = FastOutSlowInInterpolator()
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) {
                    settingsContainer.translationX = 0f
                    onComplete?.invoke()
                }
            })
        }

        TransitionManager.beginDelayedTransition(parent, transition)
        settingsContainer.isVisible = false
        scrimView?.isVisible = false
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
                            v.animate()
                                .translationX(0f)
                                .setDuration(180L)
                                .setInterpolator(FastOutSlowInInterpolator())
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
