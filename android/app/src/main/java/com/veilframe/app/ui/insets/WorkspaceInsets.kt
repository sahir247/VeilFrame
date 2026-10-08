package com.veilframe.app.ui.insets

import android.view.View
import com.veilframe.app.R

/**
 * WorkspaceInsets — the authoritative edge-to-edge insets contract for every workspace root.
 *
 * Additive Inset Architecture (P0 Architectural Inset Resolution):
 * Rather than destructively overwriting XML-defined base padding, WorkspaceInsets records
 * the view's initial layout padding on first dispatch and computes effective padding additively:
 *
 *   effectiveTop = initial.top + (if (contract.padTop) statusBarTop else 0)
 *   effectiveBottom = initial.bottom + (if (contract.padBottom) maxOf(navBarBottom, imeBottom) else 0)
 *   effectiveLeft = initial.left + (if (contract.padLeft) navBarLeft else 0)
 *   effectiveRight = initial.right + (if (contract.padRight) navBarRight else 0)
 *
 * This guarantees:
 *  1. XML clearances (such as the 160dp bottom dock clearance on scrollTool) are permanently
 *     preserved across all navigation bar / IME keyboard transitions.
 *  2. Workspaces scrolled under AppBarLayout via CoordinatorLayout's scrolling behavior
 *     use [SCROLLED_APPBAR] (`padTop = false`) to prevent double status-bar top gaps.
 *  3. Inset dispatches are strictly idempotent and non-compounding.
 */
object WorkspaceInsets {

    data class Contract(
        val padTop: Boolean = true,
        val padBottom: Boolean = true,
        val padLeft: Boolean = false,
        val padRight: Boolean = false,
    )

    /** Full-screen / custom-toolbar workspaces spanning full window (pads top + bottom). */
    val DEFAULT = Contract(padTop = true, padBottom = true)

    /** Workspaces positioned below AppBarLayout via appbar_scrolling_view_behavior. */
    val SCROLLED_APPBAR = Contract(padTop = false, padBottom = true)

    /** Full-bleed immersive views (camera viewfinders, full canvases). */
    val IMMERSIVE = Contract(padTop = false, padBottom = false)

    internal data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /**
     * Applies the contract to [root], adding insets to the view's initial base padding.
     */
    fun apply(
        root: View?,
        statusBarTop: Int,
        navBarBottom: Int,
        imeBottom: Int,
        navBarLeft: Int = 0,
        navBarRight: Int = 0,
        contract: Contract = DEFAULT,
    ) {
        root ?: return
        val initial = root.getTag(R.id.vf_tag_initial_padding) as? InitialPadding
            ?: InitialPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                root.paddingBottom
            ).also {
                root.setTag(R.id.vf_tag_initial_padding, it)
            }

        val top = initial.top + if (contract.padTop) statusBarTop else 0
        val bottom = initial.bottom + if (contract.padBottom) maxOf(navBarBottom, imeBottom) else 0
        val left = initial.left + if (contract.padLeft) navBarLeft else 0
        val right = initial.right + if (contract.padRight) navBarRight else 0

        if (root.paddingTop != top || root.paddingBottom != bottom ||
            root.paddingLeft != left || root.paddingRight != right
        ) {
            root.setPadding(left, top, right, bottom)
        }
    }
}
