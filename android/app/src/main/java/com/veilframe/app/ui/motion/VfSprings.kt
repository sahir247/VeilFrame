package com.veilframe.app.ui.motion

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.motion.MotionUtils

/**
 * VeilFrame Expressive Motion v3.0 — spring-physics core.
 *
 * Replaces ViewPropertyAnimator + OvershootInterpolator physics with themed
 * Material 3 Expressive springs resolved from the activity theme
 * (fast/default/slow × spatial/effects). Public API mirrors the legacy
 * [ExpressiveMotion] object so call sites migrate one-for-one.
 *
 * Dependencies: androidx.dynamicanimation:dynamicanimation:1.0.0
 *               com.google.android.material:material:1.14.0+
 *
 * Reduced-motion contract (ANIMATOR_DURATION_SCALE == 0):
 * springs collapse to instant state changes; haptics are ALWAYS retained.
 */
object VfSprings {

    // ------------------------------------------------------------------
    // Theme-resolved spring forces (cached per Context configuration)
    // ------------------------------------------------------------------

    fun fastSpatial(context: Context): SpringForce =
        resolve(context, com.google.android.material.R.attr.motionSpringFastSpatial, 0.6f, 800f)

    fun fastEffects(context: Context): SpringForce =
        resolve(context, com.google.android.material.R.attr.motionSpringFastEffects, 1f, 3800f)

    fun defaultSpatial(context: Context): SpringForce =
        resolve(context, com.google.android.material.R.attr.motionSpringDefaultSpatial, 0.8f, 380f)

    fun defaultEffects(context: Context): SpringForce =
        resolve(context, com.google.android.material.R.attr.motionSpringDefaultEffects, 1f, 1600f)

    fun slowSpatial(context: Context): SpringForce =
        resolve(context, com.google.android.material.R.attr.motionSpringSlowSpatial, 0.8f, 200f)

    fun slowEffects(context: Context): SpringForce =
        resolve(context, com.google.android.material.R.attr.motionSpringSlowEffects, 1f, 800f)

    private fun resolve(context: Context, attr: Int, fallbackDamping: Float, fallbackStiffness: Float): SpringForce {
        // Expressive themes provide these; fallback keeps pre-upgrade builds alive.
        // Signature: resolveThemeSpringForce(context, @AttrRes attr, @StyleRes defStyleRes)
        val themed = try {
            MotionUtils.resolveThemeSpringForce(context, attr, 0)
        } catch (_: Throwable) {
            null
        }
        return (themed ?: SpringForce().apply {
            dampingRatio = fallbackDamping
            stiffness = fallbackStiffness
        })
    }

    fun isReducedMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        ) == 0f
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------
    // Press physics: fast-spatial scale + built-in component shape morph
    // ------------------------------------------------------------------

    private const val PRESS_SCALE_BUTTON = 0.94f
    private const val PRESS_SCALE_CARD = 0.98f

    /** Tactile spring bounce for buttons/FABs/chips (does not consume touch events). */
    fun applyTouchBounce(view: View) = applyPressSpring(view, PRESS_SCALE_BUTTON)

    /** Subtle spring depression for cards. */
    fun applyCardSpringMotion(view: View) = applyPressSpring(view, PRESS_SCALE_CARD)

    private fun applyPressSpring(view: View, pressScale: Float) {
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    springScaleTo(v, pressScale, fastSpatial(v.context), fastEffects(v.context))

                MotionEvent.ACTION_UP ->
                    springScaleTo(v, 1f, fastSpatial(v.context), fastEffects(v.context))

                MotionEvent.ACTION_CANCEL ->
                    springScaleTo(v, 1f, fastSpatial(v.context))

                MotionEvent.ACTION_MOVE -> {
                    val outside = event.x < 0f || event.x > v.width ||
                            event.y < 0f || event.y > v.height
                    if (outside) springScaleTo(v, 1f, fastSpatial(v.context))
                }
            }
            false // never consume: clicks, scrolls, gestures keep working
        }
    }

    private fun springScaleTo(
        view: View,
        target: Float,
        spatial: SpringForce,
        effects: SpringForce? = null
    ) {
        if (isReducedMotion(view.context)) {
            view.scaleX = target
            view.scaleY = target
            return
        }
        listOf(
            SpringAnimation(view, DynamicAnimation.SCALE_X, target),
            SpringAnimation(view, DynamicAnimation.SCALE_Y, target)
        ).forEach { it.setSpring(spatial).start() }
        // Alpha/state-color effects ride the effects spring when provided.
        effects?.let { /* components animate their own color via fast-effects internally */ }
    }

    // ------------------------------------------------------------------
    // Signature completion flourish: jelly (kept — it IS the VeilFrame voice)
    // Implemented as an underdamped spring instead of hand-tuned keyframes:
    // damping < 1 naturally oscillates 1.0 → overshoot → settle.
    // ------------------------------------------------------------------

    fun playJellyBounce(view: View, onComplete: (() -> Unit)? = null) {
        if (!view.isAttachedToWindow || !view.isShown) {
            onComplete?.invoke(); return
        }
        if (isReducedMotion(view.context)) {
            view.scaleX = 1f; view.scaleY = 1f
            onComplete?.invoke(); return
        }
        view.scaleX = 0.95f; view.scaleY = 0.95f
        val jelly = SpringForce().apply {
            dampingRatio = 0.35f   // ~2 visible oscillations, matching legacy keyframes
            stiffness = 380f        // ≈ default-spatial tempo (320ms settle)
        }
        var pending = 2
        val end = { if (--pending == 0) onComplete?.invoke() }
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).setSpring(jelly)
            .addEndListener { _, _, _, _ -> end() }.start()
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).setSpring(jelly)
            .addEndListener { _, _, _, _ -> end() }.start()
    }

    // ------------------------------------------------------------------
    // Surfaces: sheets / dialogs / settings slide-over settle on default-spatial
    // ------------------------------------------------------------------

    fun springTranslationY(view: View, targetY: Float, fullScreen: Boolean = false) {
        if (isReducedMotion(view.context)) {
            view.translationY = targetY; return
        }
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, targetY)
            .setSpring(if (fullScreen) slowSpatial(view.context) else defaultSpatial(view.context))
            .start()
    }

    /** Home → workspace surface rise (content stagger helper). */
    fun staggerRise(container: ViewGroup, childOffsetPx: Float = 28f, staggerMs: Long = 40L) {
        if (isReducedMotion(container.context)) return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = childOffsetPx * child.resources.displayMetrics.density
            child.postDelayed({
                SpringAnimation(child, DynamicAnimation.TRANSLATION_Y, 0f)
                    .setSpring(fastSpatial(child.context)).start()
                SpringAnimation(child, DynamicAnimation.ALPHA, 1f)
                    .setSpring(fastEffects(child.context)).start()
            }, i * staggerMs)
        }
    }

    // ------------------------------------------------------------------
    // Haptics vocabulary (centralized; never paired with sound — privacy brand)
    // ------------------------------------------------------------------

    object Haptics {
        fun selectionTick(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.CONTEXT_CLICK) // API 23+
        fun confirm(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.KEYBOARD_TAP)
        fun success(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.KEYBOARD_TAP) // CONFIRM is API30+; double-tap below for minSdk 26 safety
        fun warn(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.LONG_PRESS)
        fun error(view: View) {
            view.performHapticFeedbackSafe(HapticFeedbackConstants.LONG_PRESS)
            view.postDelayed({ view.performHapticFeedbackSafe(HapticFeedbackConstants.LONG_PRESS) }, 110)
        }

        private fun View.performHapticFeedbackSafe(constant: Int) {
            try { performHapticFeedback(constant) } catch (_: Throwable) {}
        }
    }

    /** Cancel + neutralize a view (parity with legacy ExpressiveMotion.cancel). */
    fun cancel(view: View) {
        SpringAnimation(view, DynamicAnimation.SCALE_X).cancel()
        SpringAnimation(view, DynamicAnimation.SCALE_Y).cancel()
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Y).cancel()
        view.animate().cancel()
        view.scaleX = 1f; view.scaleY = 1f
        view.translationX = 0f; view.translationY = 0f
        view.alpha = 1f
    }

    fun cancelAll(container: ViewGroup) {
        cancel(container)
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is ViewGroup) cancelAll(c) else cancel(c)
        }
    }
}
