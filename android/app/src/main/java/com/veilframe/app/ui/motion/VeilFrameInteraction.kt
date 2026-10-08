package com.veilframe.app.ui.motion

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Universal interaction binder for VeilFrame workspaces.
 *
 * Automatically traverses view hierarchies to attach:
 * - Tactile spring bounce on all MaterialButtons, FABs, and icon buttons
 * - Subtle spring depression on MaterialCardViews
 * - Chips: library ripple + state morphs only (no bounce — semantic motion)
 * Without breaking click listeners, scroll views, or gesture detectors.
 */
object VeilFrameInteraction {

    /**
     * Recursively binds tactile feedback to all interactive controls in [root].
     */
    fun bindWorkspace(root: View) {
        when (root) {
            is MaterialCardView -> {
                ExpressiveMotion.applyCardSpringMotion(root)
            }
            // Chips deliberately EXCLUDED from scale-bounce: they are dense,
            // high-frequency controls with their own library ripple + expressive
            // state morphs. Motion is semantic, not ubiquitous — bounce belongs
            // to deliberate actions (buttons/FABs), not every tappable surface.
            is MaterialButton, is FloatingActionButton, is ImageButton, is Button -> {
                ExpressiveMotion.applyTouchBounce(root)
            }
        }

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                bindWorkspace(root.getChildAt(i))
            }
        }
    }

    /**
     * Attaches tactile bounce to a specific button or control.
     */
    fun applyTouch(view: View) {
        ExpressiveMotion.applyTouchBounce(view)
    }

    /**
     * Attaches card spring motion to a card.
     */
    fun applyCard(card: View) {
        ExpressiveMotion.applyCardSpringMotion(card)
    }
}
