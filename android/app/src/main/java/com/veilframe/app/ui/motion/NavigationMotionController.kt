package com.veilframe.app.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
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
 * Orchestrates stacked-page screen transitions and Settings overlay motion, aligned with
 * Android 17 / OxygenOS stacked card physics (`coui_open_slide_*` / `coui_close_slide_*`).
 */
object NavigationMotionController {

    private const val SETTINGS_DURATION_MS = 280L
    private const val STACK_DURATION_MS = 350L

    // Exact Settings.apk PathInterpolators for stacked push/pop card choreography
    private val OPEN_ENTER_INTERP = PathInterpolator(0.3f, 0.1f, 0.3f, 1.0f)
    private val OPEN_EXIT_INTERP = PathInterpolator(0.3f, 0.15f, 0.3f, 1.0f)
    private val CLOSE_ENTER_INTERP = PathInterpolator(0.3f, 0.26f, 0.4f, 1.0f)
    private val CLOSE_EXIT_INTERP = PathInterpolator(0.25f, 0.1f, 0.3f, 1.0f)

    private var activeStackAnimator: ValueAnimator? = null

    data class PopStartState(
        val topTranslationX: Float = 0f,
        val topScale: Float = 1f,
        val topCornerRadius: Float = 0f,
        val underTranslationX: Float = Float.NaN,
        val underScale: Float = 0.94f,
        val underAlpha: Float = 0.55f,
        val exitDirectionSign: Float = 1f
    )

    private class CardOutlineProvider(var radius: Float = 0f) : ViewOutlineProvider() {
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

    private fun resetViewTransform(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
        view.elevation = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        view.clipToOutline = false
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /**
     * Pushes [toolView] on top of [homeView] (any outgoing screen) using opaque stacked card
     * translation over a dimmed, subtle parallax-shifted underlying page.
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

        activeStackAnimator?.cancel()
        activeStackAnimator = null

        if (parent == null || ExpressiveMotion.isReducedMotion(context) || homeView === toolView) {
            homeView.isVisible = false
            resetViewTransform(homeView)
            toolbarHome?.isVisible = false
            bottomNav?.isVisible = false
            resetViewTransform(toolView)
            toolView.isVisible = true
            onComplete?.invoke()
            return
        }

        TransitionManager.endTransitions(parent)
        resetViewTransform(homeView)
        resetViewTransform(toolView)
        toolbarHome?.animate()?.cancel()
        bottomNav?.animate()?.cancel()

        val width = (parent.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels).toFloat()
        val density = context.resources.displayMetrics.density
        val outline = CardOutlineProvider(16f * density)

        homeView.isVisible = true
        homeView.elevation = 0f
        homeView.translationZ = 0f

        toolView.isVisible = true
        toolView.alpha = 1f
        toolView.elevation = 16f * density
        toolView.translationZ = 16f * density
        toolView.translationX = width
        toolView.outlineProvider = outline
        toolView.clipToOutline = true

        toolbarHome?.let { tb ->
            if (tb.isVisible) {
                tb.animate().alpha(0f).setDuration(160L).withEndAction {
                    tb.isVisible = false
                    tb.alpha = 1f
                }.start()
            }
        }
        bottomNav?.let { nav ->
            if (nav.isVisible) {
                nav.animate().translationY(nav.height.toFloat().coerceAtLeast(64f * density)).alpha(0f).setDuration(180L).withEndAction {
                    nav.isVisible = false
                    nav.translationY = 0f
                    nav.alpha = 1f
                }.start()
            }
        }

        var finished = false
        fun finishPush() {
            if (finished) return
            finished = true
            activeStackAnimator = null
            toolbarHome?.animate()?.cancel()
            toolbarHome?.isVisible = false
            toolbarHome?.alpha = 1f
            bottomNav?.animate()?.cancel()
            bottomNav?.isVisible = false
            bottomNav?.translationY = 0f
            bottomNav?.alpha = 1f
            homeView.isVisible = false
            resetViewTransform(homeView)
            resetViewTransform(toolView)
            toolView.isVisible = true
            onComplete?.invoke()
        }

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STACK_DURATION_MS
            interpolator = null
            addUpdateListener { va ->
                val raw = va.animatedFraction
                val tEnter = OPEN_ENTER_INTERP.getInterpolation(raw)
                val tExit = OPEN_EXIT_INTERP.getInterpolation(raw)

                toolView.translationX = width * (1f - tEnter)
                outline.radius = (1f - tEnter) * 16f * density
                toolView.invalidateOutline()

                homeView.translationX = -width * 0.18f * tExit
                val underScale = 1f - 0.06f * tExit
                homeView.scaleX = underScale
                homeView.scaleY = underScale
                homeView.alpha = 1f - 0.45f * tExit
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishPush()
                override fun onAnimationCancel(animation: Animator) = finishPush()
            })
        }
        activeStackAnimator = anim
        anim.start()
    }

    /**
     * Pops [toolView] off top of [homeView] (any destination screen) as a solid stacked card,
     * optionally continuing seamlessly from a predictive-back swipe [popStart].
     */
    fun hideTool(
        homeView: View,
        toolView: View,
        toolbarHome: View? = null,
        bottomNav: View? = null,
        popStart: PopStartState? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val context = homeView.context
        val parent = (homeView.parent as? ViewGroup) ?: (toolView.parent as? ViewGroup)

        activeStackAnimator?.cancel()
        activeStackAnimator = null

        if (parent == null || ExpressiveMotion.isReducedMotion(context) || homeView === toolView) {
            toolView.isVisible = false
            resetViewTransform(toolView)
            resetViewTransform(homeView)
            homeView.isVisible = true
            toolbarHome?.isVisible = true
            toolbarHome?.alpha = 1f
            bottomNav?.isVisible = true
            bottomNav?.alpha = 1f
            bottomNav?.translationY = 0f
            onComplete?.invoke()
            return
        }

        TransitionManager.endTransitions(parent)
        homeView.animate().cancel()
        toolView.animate().cancel()
        toolbarHome?.animate()?.cancel()
        bottomNav?.animate()?.cancel()

        val width = (parent.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels).toFloat()
        val density = context.resources.displayMetrics.density

        val startTopTx = popStart?.topTranslationX ?: 0f
        val startTopScale = popStart?.topScale ?: 1f
        val startRadius = popStart?.topCornerRadius ?: (16f * density)
        val sign = popStart?.exitDirectionSign ?: 1f
        val endTopTx = sign * width

        val defaultUnderTx = -sign * width * 0.18f
        val startUnderTx = if (popStart != null && !popStart.underTranslationX.isNaN()) popStart.underTranslationX else defaultUnderTx
        val startUnderScale = popStart?.underScale ?: 0.94f
        val startUnderAlpha = popStart?.underAlpha ?: 0.55f

        val outline = CardOutlineProvider(startRadius)
        homeView.isVisible = true
        homeView.elevation = 0f
        homeView.translationZ = 0f
        homeView.translationX = startUnderTx
        homeView.scaleX = startUnderScale
        homeView.scaleY = startUnderScale
        homeView.alpha = startUnderAlpha

        toolView.isVisible = true
        toolView.alpha = 1f
        toolView.elevation = 16f * density
        toolView.translationZ = 16f * density
        toolView.translationX = startTopTx
        toolView.scaleX = startTopScale
        toolView.scaleY = startTopScale
        toolView.outlineProvider = outline
        toolView.clipToOutline = true

        toolbarHome?.let { tb ->
            tb.isVisible = true
            tb.alpha = 0f
            tb.animate().alpha(1f).setDuration(240L).start()
        }
        bottomNav?.let { nav ->
            nav.isVisible = true
            nav.alpha = 0f
            nav.translationY = 24f * density
            nav.animate().translationY(0f).alpha(1f).setDuration(240L).start()
        }

        val remainingFraction = (1f - (abs(startTopTx) / width.coerceAtLeast(1f))).coerceIn(0.35f, 1f)
        val animDuration = (STACK_DURATION_MS * remainingFraction).toLong().coerceIn(190L, STACK_DURATION_MS)

        var finished = false
        fun finishPop() {
            if (finished) return
            finished = true
            activeStackAnimator = null
            toolbarHome?.animate()?.cancel()
            bottomNav?.animate()?.cancel()
            toolView.isVisible = false
            resetViewTransform(toolView)
            resetViewTransform(homeView)
            homeView.isVisible = true
            toolbarHome?.isVisible = true
            toolbarHome?.alpha = 1f
            bottomNav?.isVisible = true
            bottomNav?.alpha = 1f
            bottomNav?.translationY = 0f
            onComplete?.invoke()
        }

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
            interpolator = null
            addUpdateListener { va ->
                val raw = va.animatedFraction
                val tEnter = CLOSE_ENTER_INTERP.getInterpolation(raw)
                val tExit = CLOSE_EXIT_INTERP.getInterpolation(raw)

                toolView.translationX = startTopTx + (endTopTx - startTopTx) * tExit
                val topScale = startTopScale + (0.96f - startTopScale) * tExit
                toolView.scaleX = topScale
                toolView.scaleY = topScale
                outline.radius = startRadius.coerceAtLeast(16f * density)
                toolView.invalidateOutline()

                homeView.translationX = startUnderTx * (1f - tEnter)
                val underScale = startUnderScale + (1f - startUnderScale) * tEnter
                homeView.scaleX = underScale
                homeView.scaleY = underScale
                homeView.alpha = startUnderAlpha + (1f - startUnderAlpha) * tEnter
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishPop()
                override fun onAnimationCancel(animation: Animator) = finishPop()
            })
        }
        activeStackAnimator = anim
        anim.start()
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
