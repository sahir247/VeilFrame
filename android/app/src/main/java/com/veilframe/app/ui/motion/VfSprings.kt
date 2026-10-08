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
 * VeilFrame Expressive Motion v3.1 — spring-physics core.
 *
 * Themed Material 3 Expressive spring parameters (fast/default/slow ×
 * spatial/effects) are resolved from the activity theme via
 * [MotionUtils.resolveThemeSpringForce] and carried as IMMUTABLE [SpringSpec]
 * values. Every animation gets its OWN [SpringForce] instance created with an
 * explicit final position.
 *
 * Why the spec/force split matters (v3.1 crash fix):
 * `SpringAnimation(view, prop, finalPosition).setSpring(force)` DISCARDS the
 * constructor's final position — `setSpring` assigns `mSpring = force` verbatim
 * (androidx source). A themed template force has no final position, so
 * `start()` failed its sanity check with
 * "Final position of the spring cannot be greater than the max value" on every
 * press-bounce. Additionally, a single mutable SpringForce shared between the
 * X and Y animations corrupts state (start()/updateValueAndVelocity mutate the
 * force's thresholds/velocity). [SpringSpec.create] eliminates both classes:
 * fresh force, per axis, per animation, final position baked in.
 *
 * Reduced-motion contract (ANIMATOR_DURATION_SCALE == 0): springs collapse to
 * instant state changes; haptics are ALWAYS retained.
 */
object VfSprings {

    // ------------------------------------------------------------------
    // Immutable spring configuration (damping + stiffness only)
    // ------------------------------------------------------------------

    /** Immutable spring parameters resolved from the theme (or fallbacks). */
    data class SpringSpec(
        val dampingRatio: Float,
        val stiffness: Float,
    ) {
        /**
         * Creates a FRESH [SpringForce] with [finalPosition] baked in.
         * Never share SpringForce instances between animations.
         */
        fun create(finalPosition: Float): SpringForce =
            SpringForce(finalPosition)
                .setDampingRatio(dampingRatio)
                .setStiffness(stiffness)
    }

    fun fastSpatial(context: Context): SpringSpec =
        resolveSpec(context, com.google.android.material.R.attr.motionSpringFastSpatial, 0.6f, 800f)

    fun fastEffects(context: Context): SpringSpec =
        resolveSpec(context, com.google.android.material.R.attr.motionSpringFastEffects, 1f, 3800f)

    fun defaultSpatial(context: Context): SpringSpec =
        resolveSpec(context, com.google.android.material.R.attr.motionSpringDefaultSpatial, 0.8f, 380f)

    fun defaultEffects(context: Context): SpringSpec =
        resolveSpec(context, com.google.android.material.R.attr.motionSpringDefaultEffects, 1f, 1600f)

    fun slowSpatial(context: Context): SpringSpec =
        resolveSpec(context, com.google.android.material.R.attr.motionSpringSlowSpatial, 0.8f, 200f)

    fun slowEffects(context: Context): SpringSpec =
        resolveSpec(context, com.google.android.material.R.attr.motionSpringSlowEffects, 1f, 800f)

    private fun resolveSpec(
        context: Context,
        attr: Int,
        fallbackDamping: Float,
        fallbackStiffness: Float,
    ): SpringSpec {
        // Signature (1.14.0-verified): resolveThemeSpringForce(Context, @AttrRes int, @StyleRes int).
        // Expressive themes provide the springy values; fallbacks keep
        // pre-upgrade/non-expressive themes alive.
        val themed = try {
            MotionUtils.resolveThemeSpringForce(context, attr, 0)
        } catch (_: Throwable) {
            null
        }
        return if (themed != null) {
            SpringSpec(themed.dampingRatio, themed.stiffness)
        } else {
            SpringSpec(fallbackDamping, fallbackStiffness)
        }
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

    /** Tactile spring bounce for buttons/FABs (does not consume touch events). */
    fun applyTouchBounce(view: View) = applyPressSpring(view, PRESS_SCALE_BUTTON)

    /** Subtle spring depression for cards. */
    fun applyCardSpringMotion(view: View) = applyPressSpring(view, PRESS_SCALE_CARD)

    private fun applyPressSpring(view: View, pressScale: Float) {
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    springScaleTo(v, pressScale, fastSpatial(v.context))

                MotionEvent.ACTION_UP ->
                    springScaleTo(v, 1f, fastSpatial(v.context))

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

    /**
     * Scales [view] to [target] on both axes with the given spatial spec.
     * Each axis gets its own SpringForce with the final position baked in
     * (v3.1 crash fix — see class KDoc).
     */
    private fun springScaleTo(view: View, target: Float, spatial: SpringSpec) {
        if (isReducedMotion(view.context)) {
            view.scaleX = target
            view.scaleY = target
            return
        }
        fun axis(property: DynamicAnimation.ViewProperty) {
            track(view, SpringAnimation(view, property).setSpring(spatial.create(target))).start()
        }

        axis(DynamicAnimation.SCALE_X)
        axis(DynamicAnimation.SCALE_Y)
    }

    // ------------------------------------------------------------------
    // Per-view animation tracking so cancel()/cancelAll() actually stop
    // in-flight springs (previously resets fought running animations).
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun animList(view: View): MutableList<SpringAnimation> =
        (view.getTag(com.veilframe.app.R.id.vf_tag_spring_anims) as? MutableList<SpringAnimation>)
            ?: mutableListOf<SpringAnimation>().also {
                view.setTag(com.veilframe.app.R.id.vf_tag_spring_anims, it)
            }

    private fun track(view: View, animation: SpringAnimation): SpringAnimation {
        val list = animList(view)
        list.removeAll { !it.isRunning }
        list.add(animation)
        return animation
    }

    // ------------------------------------------------------------------
    // Signature completion flourish: jelly (kept — it IS the VeilFrame voice)
    // Underdamped spring (damping 0.35) naturally oscillates 0.95 → ~1.045 → 1.0,
    // reproducing the legacy keyframe feel with real physics.
    // ------------------------------------------------------------------

    fun playJellyBounce(view: View, onComplete: (() -> Unit)? = null) {
        if (!view.isAttachedToWindow || !view.isShown) {
            onComplete?.invoke(); return
        }
        if (isReducedMotion(view.context)) {
            view.scaleX = 1f; view.scaleY = 1f
            onComplete?.invoke(); return
        }
        view.scaleX = 0.95f
        view.scaleY = 0.95f

        val jelly = SpringSpec(dampingRatio = 0.35f, stiffness = 380f)
        var pending = 2
        val finished = {
            pending--
            if (pending == 0) onComplete?.invoke()
        }
        track(view, SpringAnimation(view, DynamicAnimation.SCALE_X)
            .setSpring(jelly.create(1f))
            .addEndListener { _, _, _, _ -> finished() })
            .start()
        track(view, SpringAnimation(view, DynamicAnimation.SCALE_Y)
            .setSpring(jelly.create(1f))
            .addEndListener { _, _, _, _ -> finished() })
            .start()
    }

    // ------------------------------------------------------------------
    // Surfaces: sheets / dialogs / slide-overs settle on default-spatial
    // ------------------------------------------------------------------

    fun springTranslationY(view: View, targetY: Float, fullScreen: Boolean = false) {
        if (isReducedMotion(view.context)) {
            view.translationY = targetY
            return
        }
        val spec = if (fullScreen) slowSpatial(view.context) else defaultSpatial(view.context)
        track(view, SpringAnimation(view, DynamicAnimation.TRANSLATION_Y)
            .setSpring(spec.create(targetY)))
            .start()
    }

    /** Home → workspace content stagger helper (first frame only). */
    fun staggerRise(container: ViewGroup, childOffsetPx: Float = 28f, staggerMs: Long = 40L) {
        if (isReducedMotion(container.context)) return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = childOffsetPx * child.resources.displayMetrics.density
            child.postDelayed({
                val spatial = fastSpatial(child.context)
                val effects = fastEffects(child.context)
                track(child, SpringAnimation(child, DynamicAnimation.TRANSLATION_Y)
                    .setSpring(spatial.create(0f))).start()
                track(child, SpringAnimation(child, DynamicAnimation.ALPHA)
                    .setSpring(effects.create(1f))).start()
            }, i * staggerMs)
        }
    }

    // ------------------------------------------------------------------
    // Haptics vocabulary (centralized; never paired with sound — privacy brand)
    // ------------------------------------------------------------------

    object Haptics {
        fun selectionTick(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.CONTEXT_CLICK) // API 23+
        fun confirm(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.KEYBOARD_TAP)
        fun success(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.KEYBOARD_TAP) // CONFIRM is API30+; kept API26-safe
        fun warn(view: View) = view.performHapticFeedbackSafe(HapticFeedbackConstants.LONG_PRESS)
        fun error(view: View) {
            view.performHapticFeedbackSafe(HapticFeedbackConstants.LONG_PRESS)
            view.postDelayed({ view.performHapticFeedbackSafe(HapticFeedbackConstants.LONG_PRESS) }, 110)
        }

        private fun View.performHapticFeedbackSafe(constant: Int) {
            try { performHapticFeedback(constant) } catch (_: Throwable) {}
        }
    }

    /**
     * Neutralizes a view: cancels tracked in-flight springs AND
     * ViewPropertyAnimators, then resets transforms.
     */
    fun cancel(view: View) {
        animList(view).forEach { runCatching { if (it.isRunning) it.cancel() } }
        animList(view).clear()
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
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
