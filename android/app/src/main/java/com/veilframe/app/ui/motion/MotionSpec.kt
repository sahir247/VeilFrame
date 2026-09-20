package com.veilframe.app.ui.motion

import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * Standardized Material 3 Expressive motion constants, scales, durations, and interpolators.
 * Guarantees a cohesive tactile visual language across all VeilFrame screens.
 */
object MotionSpec {
    // Tactile Touch Bounce
    const val PRESS_SCALE = 0.94f
    const val PRESS_DURATION = 75L
    const val RELEASE_DURATION = 180L

    // Jelly Oscillation (Reserved for state confirmation & completion)
    const val JELLY_DOWN_SCALE = 0.95f
    const val JELLY_OVERSHOOT_SCALE = 1.045f
    const val JELLY_DURATION = 320L

    // Motion Hierarchy Timing Levels
    // Level 1: Micro (taps, chips, icon buttons)
    const val FAST = 120L
    // Level 2: Component (cards, expandable surfaces, dock transitions)
    const val NORMAL = 220L
    // Level 3: Transformational (dialogs, major surfaces)
    const val EXPRESSIVE = 320L
    const val MORPH = 360L

    // Floating Smart Action Dock Threshold
    const val DOCK_THRESHOLD_DP = 120f
    const val SCROLL_DEADZONE_PX = 4f

    // Dialog Morph Expansion
    const val DIALOG_OVERSHOOT = 1.12f

    // Shared Standard Interpolators
    val OVERSHOOT_FAST = OvershootInterpolator(1.2f)
    val OVERSHOOT_DIALOG = OvershootInterpolator(DIALOG_OVERSHOOT)
    val DECELERATE_SMOOTH = DecelerateInterpolator(1.5f)
    // Material 3 Emphasized / Expressive Easing
    val EMPHASIZED = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
    val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
}
