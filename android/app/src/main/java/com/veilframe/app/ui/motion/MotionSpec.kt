package com.veilframe.app.ui.motion

import android.graphics.Path
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
    // M3 Emphasized Path: M 0,0 C 0.05, 0, 0.133333, 0.06, 0.166666, 0.4 C 0.208333, 0.82, 0.25, 1, 1, 1
    val EMPHASIZED: PathInterpolator = Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    }.let { PathInterpolator(it) }

    // M3 Emphasized Accelerate: cubic-bezier(0.3, 0, 0.8, 0.2)
    val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0.0f, 0.8f, 0.2f)

    // M3 Emphasized Decelerate: cubic-bezier(0.1, 0.7, 0.1, 1)
    val EMPHASIZED_DECELERATE = PathInterpolator(0.1f, 0.7f, 0.1f, 1.0f)

    // M3 Standard: cubic-bezier(0.2, 0, 0, 1)
    val STANDARD = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)

    // M3 Standard Decelerate: cubic-bezier(0, 0, 0, 1)
    val STANDARD_DECELERATE = PathInterpolator(0.0f, 0.0f, 0.0f, 1.0f)
}
