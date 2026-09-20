package com.veilframe.app.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Centralized motion controller for tactile physics, jelly bounce, reduced-motion accessibility,
 * and floating dock animations.
 *
 * All animations are 100% native Android, zero external dependencies, hardware-accelerated,
 * and lifecycle-safe.
 */
object ExpressiveMotion {

    /**
     * Determines whether system animations have been disabled in Developer Options or Accessibility.
     * When reduced motion is active, spatial morphs collapse to instant transitions while
     * preserving haptic feedback.
     */
    fun isReducedMotion(context: Context): Boolean {
        return try {
            val durationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            durationScale == 0f
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Attaches tactile spring bounce to interactive views.
     *
     * Rules:
     * - Does NOT consume touch events (returns false so OnClickListener, ScrollView, and
     *   RecyclerView handle gestures normally).
     * - On ACTION_DOWN: scales to 0.96 over 75ms (NO haptic feedback on touch down to prevent
     *   accidental vibration during scrolling).
     * - On ACTION_UP: springs back via overshoot to 1.0 over 180ms.
     * - On ACTION_CANCEL or drag beyond touch bounds: returns directly to 1.0f.
     */
    fun applyTouchBounce(view: View) {
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(MotionSpec.PRESS_SCALE)
                        .scaleY(MotionSpec.PRESS_SCALE)
                        .setDuration(MotionSpec.PRESS_DURATION)
                        .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(MotionSpec.RELEASE_DURATION)
                        .setInterpolator(MotionSpec.OVERSHOOT_FAST)
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100L)
                        .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                        .start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    if (x < 0f || x > v.width || y < 0f || y > v.height) {
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100L)
                            .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                            .start()
                    }
                }
            }
            false
        }
    }

    /**
     * Attaches pure spring motion to tool cards.
     * Guaranteed zero vibration during touches or scrolling, with subtle spring response.
     */
    fun applyCardSpringMotion(view: View) {
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(80L)
                        .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(180L)
                        .setInterpolator(MotionSpec.OVERSHOOT_FAST)
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100L)
                        .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                        .start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    if (x < 0f || x > v.width || y < 0f || y > v.height) {
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100L)
                            .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                            .start()
                    }
                }
            }
            false
        }
    }

    /**
     * Tactile confirmation feedback reserved strictly for explicit button click confirmation,
     * toggle detents, and completion events.
     */
    fun performConfirmationHaptic(
        view: View,
        feedbackConstant: Int = HapticFeedbackConstants.KEYBOARD_TAP
    ) {
        try {
            view.performHapticFeedback(feedbackConstant)
        } catch (_: Throwable) {}
    }

    /**
     * Plays a multi-stage damped jelly oscillation:
     * 1.00 -> 0.95 -> 1.045 -> 0.98 -> 1.015 -> 1.00
     *
     * Reserved strictly for state confirmation and success events (dialog apply, completed export,
     * model selection, successful scan).
     */
    fun playJellyBounce(
        view: View,
        onComplete: (() -> Unit)? = null
    ) {
        if (!view.isAttachedToWindow || !view.isShown) {
            onComplete?.invoke()
            return
        }

        if (isReducedMotion(view.context)) {
            view.scaleX = 1.0f
            view.scaleY = 1.0f
            onComplete?.invoke()
            return
        }

        // Cancel existing animations on this view
        view.animate().cancel()

        // Keyframe-based multi-stage damped oscillation
        val kf0 = Keyframe.ofFloat(0.00f, 1.000f)
        val kf1 = Keyframe.ofFloat(0.18f, MotionSpec.JELLY_DOWN_SCALE) // 0.95f
        val kf2 = Keyframe.ofFloat(0.42f, MotionSpec.JELLY_OVERSHOOT_SCALE) // 1.045f
        val kf3 = Keyframe.ofFloat(0.65f, 0.982f)
        val kf4 = Keyframe.ofFloat(0.85f, 1.012f)
        val kf5 = Keyframe.ofFloat(1.00f, 1.000f)

        val pvhX = PropertyValuesHolder.ofKeyframe(View.SCALE_X, kf0, kf1, kf2, kf3, kf4, kf5)
        val pvhY = PropertyValuesHolder.ofKeyframe(View.SCALE_Y, kf0, kf1, kf2, kf3, kf4, kf5)

        ObjectAnimator.ofPropertyValuesHolder(view, pvhX, pvhY).apply {
            duration = MotionSpec.JELLY_DURATION
            interpolator = MotionSpec.EMPHASIZED
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    /**
     * Continuously merges a floating action dock towards docked bottom actions.
     * Updates lightweight properties directly (GPU-friendly translation, scale, alpha)
     * without thrashing layout or instantiating animators on each scroll frame.
     *
     * @param floatingDock The floating pill/overlay above scroll view
     * @param dockedActions The static bottom action buttons container
     * @param progress 0.0f (fully floating) to 1.0f (fully merged into docked buttons)
     */
    fun updateDockProgress(
        floatingDock: View,
        dockedActions: View,
        progress: Float
    ) {
        val clamped = progress.coerceIn(0.0f, 1.0f)
        val density = floatingDock.resources.displayMetrics.density
        val slidePx = 20f * density

        // Floating dock: fades out, scales slightly down, and slides down
        val floatAlpha = (1.0f - clamped).coerceIn(0.0f, 1.0f)
        floatingDock.alpha = floatAlpha
        floatingDock.scaleX = 1.0f - (0.10f * clamped)
        floatingDock.scaleY = 1.0f - (0.10f * clamped)
        floatingDock.translationY = slidePx * clamped

        if (clamped >= 0.96f) {
            if (floatingDock.visibility != View.GONE) floatingDock.visibility = View.GONE
        } else {
            if (floatingDock.visibility != View.VISIBLE) floatingDock.visibility = View.VISIBLE
        }

        // Docked bottom actions: fades in and settles
        val dockedAlpha = (0.25f + 0.75f * clamped).coerceIn(0.0f, 1.0f)
        dockedActions.alpha = dockedAlpha
    }

    /**
     * Cancels active animations and restores neutral transforms on a single view.
     */
    fun cancel(view: View) {
        view.animate().cancel()
        view.scaleX = 1.0f
        view.scaleY = 1.0f
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1.0f
    }

    /**
     * Recursively cancels active animations across an entire container tree.
     */
    fun cancelAll(container: ViewGroup) {
        cancel(container)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ViewGroup) {
                cancelAll(child)
            } else {
                cancel(child)
            }
        }
    }
}
