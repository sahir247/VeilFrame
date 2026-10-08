package com.veilframe.app.ui.insets

import android.view.View

/**
 * WorkspaceInsets — the ONE edge-to-edge contract for every workspace root.
 *
 * Phase-1 stabilization fix (production-readiness audit): the shell inset
 * listener only padded shell chrome (app bar, dock, nav bar/rail, settings),
 * so workspace roots placed their toolbars at y=0 — under the status bar —
 * and bottom controls under the gesture handle / navigation bar. Instead of
 * 15 per-layout hacks, MainActivity applies this contract to every workspace
 * root on each inset dispatch:
 *
 *   Root Activity (CoordinatorLayout)
 *    └── Workspace root            ← top: status bar · bottom: max(nav, IME)
 *         ├── toolbar              (now below the status bar)
 *         ├── scroll/content
 *         └── bottom action area   (now above the nav handle / keyboard)
 *
 * [Contract] allows per-workspace opt-outs (e.g. a future immersive camera
 * screen sets padTop=false). Idempotent: paddings are absolute, safe to
 * re-apply on every dispatch (rotation, IME, 3-button ⇄ gesture switch).
 */
object WorkspaceInsets {

    data class Contract(
        val padTop: Boolean = true,
        val padBottom: Boolean = true,
    )

    val DEFAULT = Contract()

    /**
     * Applies the contract to [root], preserving its horizontal padding.
     * Bottom padding uses max(navigationBar, ime) so keyboards and gesture
     * handles never overlap bottom action areas.
     */
    fun apply(
        root: View?,
        statusBarTop: Int,
        navBarBottom: Int,
        imeBottom: Int,
        contract: Contract = DEFAULT,
    ) {
        root ?: return
        val top = if (contract.padTop) statusBarTop else 0
        val bottom = if (contract.padBottom) maxOf(navBarBottom, imeBottom) else 0
        if (root.paddingTop != top || root.paddingBottom != bottom) {
            root.setPadding(root.paddingLeft, top, root.paddingRight, bottom)
        }
    }
}
