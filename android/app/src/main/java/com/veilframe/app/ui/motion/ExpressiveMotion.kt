package com.veilframe.app.ui.motion

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup

/**
 * Centralized motion controller for tactile physics, jelly bounce, reduced-motion accessibility,
 * and floating dock animations.
 *
 * v3.0 (M3 Expressive): touch bounce, card springs, and jelly completion are now delegated to
 * [VfSprings], which resolves themed spring physics (fast/default/slow × spatial/effects) from the
 * Material 3 Expressive theme via MotionUtils. All animations remain 100% native, zero external
 * dependencies beyond androidx.dynamicanimation, hardware-accelerated, and lifecycle-safe.
 */
object ExpressiveMotion {

    /**
     * Determines whether system animations have been disabled in Developer Options or Accessibility.
     * When reduced motion is active, spatial morphs collapse to instant transitions while
     * preserving haptic feedback.
     */
    fun isReducedMotion(context: Context): Boolean = VfSprings.isReducedMotion(context)

    /**
     * Attaches tactile spring bounce to interactive views (fast-spatial spring, scale 0.94).
     *
     * Rules:
     * - Does NOT consume touch events (returns false so OnClickListener, ScrollView, and
     *   RecyclerView handle gestures normally).
     * - No haptic feedback on touch down (prevents accidental vibration during scrolling).
     * - Release springs back via themed physics instead of a fixed-duration overshoot.
     */
    fun applyTouchBounce(view: View) = VfSprings.applyTouchBounce(view)

    /**
     * Attaches pure spring motion to tool cards (fast-spatial spring, scale 0.98).
     * Guaranteed zero vibration during touches or scrolling.
     */
    fun applyCardSpringMotion(view: View) = VfSprings.applyCardSpringMotion(view)

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
     * Plays the signature VeilFrame completion flourish.
     *
     * v3.0: re-implemented as an underdamped spring (damping 0.35, stiffness 380) which naturally
     * reproduces the legacy multi-stage oscillation (1.00 → 0.95 → ~1.045 → settle) with real
     * physics instead of hand-tuned keyframes. Reserved strictly for state confirmation and
     * success events (dialog apply, completed export, model selection, successful scan).
     */
    fun playJellyBounce(
        view: View,
        onComplete: (() -> Unit)? = null
    ) = VfSprings.playJellyBounce(view, onComplete)

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
    fun cancel(view: View) = VfSprings.cancel(view)

    /**
     * Recursively cancels active animations across an entire container tree.
     */
    fun cancelAll(container: ViewGroup) = VfSprings.cancelAll(container)
}
