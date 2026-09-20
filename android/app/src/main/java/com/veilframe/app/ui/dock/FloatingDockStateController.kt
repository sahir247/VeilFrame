package com.veilframe.app.ui.dock

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.veilframe.app.R
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MotionSpec

/**
 * Explicit state machine for the Material 3 Expressive Floating Action Dock.
 * Transitions smoothly between EMPTY, READY, PROCESSING, and COMPLETED states.
 * In EMPTY state, the dock stays visible with "Pick a File" or "Pick a Folder".
 * In COMPLETED state, the dock offers [ Save ] and [ Share ] actions.
 * On saving, the primary button updates to "Saved" with a 2.5s auto-revert timer.
 * On any subsequent edit, the dock transitions back to READY ("Start").
 */
enum class FloatingActionState {
    EMPTY,
    READY,
    PROCESSING,
    COMPLETED
}

class FloatingDockStateController(
    private val dockCard: MaterialCardView,
    private val primaryButton: MaterialButton,
    private val secondaryButton: MaterialButton?,
    private val onPrimaryClick: () -> Unit,
    private val onSecondaryClick: (() -> Unit)? = null
) {
    var currentState: FloatingActionState = FloatingActionState.EMPTY
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var revertSaveRunnable: Runnable? = null

    init {
        ExpressiveMotion.applyTouchBounce(primaryButton)
        secondaryButton?.let { ExpressiveMotion.applyTouchBounce(it) }

        primaryButton.setOnClickListener {
            ExpressiveMotion.performConfirmationHaptic(it)
            onPrimaryClick()
        }
        secondaryButton?.setOnClickListener {
            ExpressiveMotion.performConfirmationHaptic(it)
            onSecondaryClick?.invoke()
        }
    }

    fun transitionTo(
        newState: FloatingActionState,
        actionTitle: String = "Start",
        @DrawableRes actionIcon: Int? = null,
        secondaryTitle: String = "Share",
        @DrawableRes secondaryIcon: Int? = R.drawable.ic_action_share,
        animate: Boolean = true
    ) {
        currentState = newState
        revertSaveRunnable?.let { mainHandler.removeCallbacks(it) }
        revertSaveRunnable = null

        val isReduced = ExpressiveMotion.isReducedMotion(dockCard.context)

        when (newState) {
            FloatingActionState.EMPTY -> {
                primaryButton.text = actionTitle.ifBlank { "Pick a File" }
                primaryButton.isEnabled = true
                primaryButton.alpha = 1.0f
                primaryButton.setIconResource(actionIcon ?: R.drawable.ic_file_pick)
                secondaryButton?.visibility = View.GONE
                showDockWithSpring(animate && !isReduced)
            }

            FloatingActionState.READY -> {
                primaryButton.text = actionTitle.ifBlank { "Start" }
                primaryButton.isEnabled = true
                primaryButton.alpha = 1.0f
                primaryButton.setIconResource(actionIcon ?: R.drawable.ic_action_play)
                secondaryButton?.visibility = View.GONE
                showDockWithSpring(animate && !isReduced)
            }

            FloatingActionState.PROCESSING -> {
                primaryButton.text = actionTitle.ifBlank { "Processing..." }
                primaryButton.isEnabled = false
                primaryButton.alpha = 0.85f
                primaryButton.setIconResource(actionIcon ?: R.drawable.ic_compress)
                secondaryButton?.visibility = View.GONE
                showDockWithSpring(animate && !isReduced)
            }

            FloatingActionState.COMPLETED -> {
                primaryButton.text = actionTitle.ifBlank { "Save" }
                primaryButton.isEnabled = true
                primaryButton.alpha = 1.0f
                primaryButton.setIconResource(actionIcon ?: R.drawable.ic_action_save)

                if (secondaryButton != null) {
                    secondaryButton.visibility = View.VISIBLE
                    secondaryButton.text = secondaryTitle.ifBlank { "Share" }
                    secondaryButton.setIconResource(secondaryIcon ?: R.drawable.ic_action_share)
                }

                showDockWithSpring(animate && !isReduced)
                if (animate && !isReduced) {
                    ExpressiveMotion.playJellyBounce(dockCard)
                }
            }
        }
    }

    /**
     * Signals that the exported output was successfully saved.
     * Updates primary action to "Saved" with a checkmark and automatically reverts to "Save"
     * after a 2.5 second dwell period.
     */
    fun onSaved() {
        if (currentState != FloatingActionState.COMPLETED) return
        revertSaveRunnable?.let { mainHandler.removeCallbacks(it) }

        primaryButton.text = "Saved"
        primaryButton.setIconResource(R.drawable.ic_done)

        revertSaveRunnable = Runnable {
            if (currentState == FloatingActionState.COMPLETED) {
                primaryButton.text = "Save"
                primaryButton.setIconResource(R.drawable.ic_action_save)
            }
        }
        mainHandler.postDelayed(revertSaveRunnable!!, 2500L)
    }

    /**
     * Call whenever any media edit or transformation parameter is adjusted.
     * If the session is currently in COMPLETED state, resets the action dock to READY ("Start").
     */
    fun onEditApplied() {
        if (currentState == FloatingActionState.COMPLETED) {
            revertSaveRunnable?.let { mainHandler.removeCallbacks(it) }
            revertSaveRunnable = null
            transitionTo(FloatingActionState.READY, actionTitle = "Start", actionIcon = R.drawable.ic_action_play)
        }
    }

    private fun showDockWithSpring(animate: Boolean) {
        dockCard.visibility = View.VISIBLE
        if (animate) {
            dockCard.alpha = 0f
            dockCard.scaleX = 0.88f
            dockCard.scaleY = 0.88f
            dockCard.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(MotionSpec.NORMAL)
                .setInterpolator(MotionSpec.OVERSHOOT_FAST)
                .start()
        } else {
            dockCard.alpha = 1.0f
            dockCard.scaleX = 1.0f
            dockCard.scaleY = 1.0f
        }
    }
}
