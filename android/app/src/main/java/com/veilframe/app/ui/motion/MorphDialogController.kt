package com.veilframe.app.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Dialog
import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.core.view.doOnLayout

/**
 * Owns coordinates-aware transformational dialog expansion and collapse.
 *
 * Responsibilities:
 * - Measures originating trigger view screen coordinates (x, y, width, height)
 * - Measures dialog content view upon layout
 * - Computes translation deltas and scale ratios
 * - Orchestrates expressive opening morph with spring overshoot
 * - Orchestrates reverse collapse back into the originating button on close / back / apply
 * - Triggers state-confirmation jelly bounce on the originating view once dismissed
 * - Protects against animation races and double-dismiss through DialogMotionState
 * - Seamless fallback when originView is null or reduced motion is enabled
 */
class MorphDialogController {

    var state: DialogMotionState = DialogMotionState.CLOSED
        private set

    /**
     * Shows a dialog with origin-aware morphing animation.
     *
     * @param context Host Context
     * @param originView The button / tool tile that triggered this dialog (can be null for fallback)
     * @param dialog The initialized Dialog instance
     * @param dialogView The root content card inside the dialog
     * @param onDismissComplete Optional callback fired after full dismiss and settle
     */
    fun showMorphDialog(
        context: Context,
        originView: View?,
        dialog: Dialog,
        dialogView: View,
        onDismissComplete: (() -> Unit)? = null
    ) {
        if (state == DialogMotionState.OPENING || state == DialogMotionState.OPEN) {
            return
        }
        state = DialogMotionState.OPENING

        // Transparent window background so only the styled Material dialog card translates/scales
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogView.setBackgroundResource(com.veilframe.app.R.drawable.bg_popup_dialog)
        dialogView.clipToOutline = true

        // Reduced motion check
        if (ExpressiveMotion.isReducedMotion(context)) {
            state = DialogMotionState.OPEN
            dialog.show()
            dialog.setOnDismissListener {
                state = DialogMotionState.DISMISSED
                onDismissComplete?.invoke()
            }
            return
        }

        // Null origin fallback
        if (originView == null || !originView.isAttachedToWindow) {
            dialogView.alpha = 0f
            dialogView.scaleX = 0.92f
            dialogView.scaleY = 0.92f
            dialog.show()
            dialogView.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(MotionSpec.NORMAL)
                .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        state = DialogMotionState.OPEN
                    }
                })
                .start()

            wireBackAndDismiss(dialog, null, dialogView, onDismissComplete)
            return
        }

        // 1. Capture origin screen bounds
        val originLoc = IntArray(2)
        originView.getLocationOnScreen(originLoc)
        val originW = originView.width
        val originH = originView.height
        val originCenterX = originLoc[0] + (originW / 2.0f)
        val originCenterY = originLoc[1] + (originH / 2.0f)

        // Make dialog visible initially with alpha 0 to measure layout
        dialogView.alpha = 0f
        dialog.show()

        wireBackAndDismiss(dialog, originView, dialogView, onDismissComplete)

        dialogView.doOnLayout {
            val dialogLoc = IntArray(2)
            dialogView.getLocationOnScreen(dialogLoc)
            val dialogW = dialogView.width.coerceAtLeast(1)
            val dialogH = dialogView.height.coerceAtLeast(1)
            val dialogCenterX = dialogLoc[0] + (dialogW / 2.0f)
            val dialogCenterY = dialogLoc[1] + (dialogH / 2.0f)

            // Calculate scale and translation deltas
            val initialScaleX = (originW.toFloat() / dialogW.toFloat()).coerceIn(0.08f, 1.0f)
            val initialScaleY = (originH.toFloat() / dialogH.toFloat()).coerceIn(0.08f, 1.0f)
            val initialTransX = originCenterX - dialogCenterX
            val initialTransY = originCenterY - dialogCenterY

            // Set initial transformed state
            dialogView.scaleX = initialScaleX
            dialogView.scaleY = initialScaleY
            dialogView.translationX = initialTransX
            dialogView.translationY = initialTransY
            dialogView.alpha = 0f

            // Animate organically expanding from the origin button
            dialogView.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(MotionSpec.MORPH)
                .setInterpolator(MotionSpec.OVERSHOOT_DIALOG)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        state = DialogMotionState.OPEN
                    }
                })
                .start()
        }
    }

    /**
     * Gracefully collapses the dialog back into its originating view.
     * Invoked when user taps Cancel, Apply, or Back.
     */
    fun requestDismiss(
        dialog: Dialog,
        originView: View?,
        dialogView: View,
        commitAction: (() -> Unit)? = null,
        onDismissComplete: (() -> Unit)? = null
    ) {
        if (state == DialogMotionState.CLOSING || state == DialogMotionState.DISMISSED) {
            return
        }
        state = DialogMotionState.CLOSING

        // Execute commit actions immediately before closing presentation
        commitAction?.invoke()

        if (originView == null || !originView.isAttachedToWindow || ExpressiveMotion.isReducedMotion(dialogView.context)) {
            dialog.dismiss()
            state = DialogMotionState.DISMISSED
            onDismissComplete?.invoke()
            return
        }

        // Recompute current origin coordinates (in case of scroll)
        val originLoc = IntArray(2)
        originView.getLocationOnScreen(originLoc)
        val originW = originView.width
        val originH = originView.height
        val originCenterX = originLoc[0] + (originW / 2.0f)
        val originCenterY = originLoc[1] + (originH / 2.0f)

        val dialogLoc = IntArray(2)
        dialogView.getLocationOnScreen(dialogLoc)
        val dialogW = dialogView.width.coerceAtLeast(1)
        val dialogH = dialogView.height.coerceAtLeast(1)
        val dialogCenterX = dialogLoc[0] + (dialogW / 2.0f)
        val dialogCenterY = dialogLoc[1] + (dialogH / 2.0f)

        val targetScaleX = (originW.toFloat() / dialogW.toFloat()).coerceIn(0.08f, 1.0f)
        val targetScaleY = (originH.toFloat() / dialogH.toFloat()).coerceIn(0.08f, 1.0f)
        val targetTransX = originCenterX - dialogCenterX
        val targetTransY = originCenterY - dialogCenterY

        dialogView.animate().cancel()
        dialogView.animate()
            .alpha(0f)
            .scaleX(targetScaleX)
            .scaleY(targetScaleY)
            .translationX(targetTransX)
            .translationY(targetTransY)
            .setDuration(MotionSpec.NORMAL)
            .setInterpolator(MotionSpec.EMPHASIZED_DECELERATE)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        dialog.dismiss()
                    } catch (_: Throwable) {}
                    state = DialogMotionState.DISMISSED
                    ExpressiveMotion.performConfirmationHaptic(originView)

                    // Gentle spring settle on originating control without jarring multi-stage wobble
                    originView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160L)
                        .setInterpolator(MotionSpec.EMPHASIZED_DECELERATE)
                        .withEndAction {
                            onDismissComplete?.invoke()
                        }
                        .start()
                }
            })
            .start()
    }

    private fun wireBackAndDismiss(
        dialog: Dialog,
        originView: View?,
        dialogView: View,
        onDismissComplete: (() -> Unit)?
    ) {
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestDismiss(dialog, originView, dialogView, null, onDismissComplete)
                true
            } else {
                false
            }
        }

        dialog.setOnCancelListener {
            requestDismiss(dialog, originView, dialogView, null, onDismissComplete)
        }
    }

    companion object {
        /**
         * Convenience static method to launch a morph dialog and tag the controller onto dialogView.
         */
        fun showWithMorph(
            dialog: Dialog,
            dialogView: View,
            originView: View?,
            onDismissComplete: (() -> Unit)? = null
        ): MorphDialogController {
            val controller = MorphDialogController()
            dialogView.tag = controller
            controller.showMorphDialog(dialogView.context, originView, dialog, dialogView, onDismissComplete)
            return controller
        }

        /**
         * Convenience static method to collapse a morph dialog back into its originating view.
         */
        fun dismissWithMorph(
            dialog: Dialog,
            dialogView: View,
            originView: View?,
            commitAction: (() -> Unit)? = null,
            onDismissComplete: (() -> Unit)? = null
        ) {
            val controller = (dialogView.tag as? MorphDialogController) ?: MorphDialogController()
            controller.requestDismiss(dialog, originView, dialogView, commitAction, onDismissComplete)
        }
    }
}
