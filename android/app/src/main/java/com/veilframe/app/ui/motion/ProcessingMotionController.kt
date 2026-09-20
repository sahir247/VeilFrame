package com.veilframe.app.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ProgressBar
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Controller for execution state morphs, progress transitions, and completion animations.
 */
object ProcessingMotionController {

    /**
     * Smoothly transitions between processing state subviews (e.g. from Preparing to Neural Inference).
     */
    fun transitionState(
        fromView: View?,
        toView: View?,
        onComplete: (() -> Unit)? = null
    ) {
        if (fromView == null && toView == null) {
            onComplete?.invoke()
            return
        }

        fromView?.animate()?.cancel()
        toView?.animate()?.cancel()

        if (fromView != null && toView != null) {
            fromView.animate()
                .alpha(0f)
                .setDuration(120L)
                .setInterpolator(MotionSpec.DECELERATE_SMOOTH)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        fromView.isVisible = false
                        toView.isVisible = true
                        toView.alpha = 0f
                        toView.animate()
                            .alpha(1f)
                            .setDuration(140L)
                            .setInterpolator(MotionSpec.EMPHASIZED)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(anim: Animator) {
                                    onComplete?.invoke()
                                }
                            })
                            .start()
                    }
                })
                .start()
        } else if (toView != null) {
            toView.isVisible = true
            toView.alpha = 0f
            toView.animate()
                .alpha(1f)
                .setDuration(160L)
                .setInterpolator(MotionSpec.EMPHASIZED)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(anim: Animator) {
                        onComplete?.invoke()
                    }
                })
                .start()
        } else {
            fromView?.animate()
                ?.alpha(0f)
                ?.setDuration(120L)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(anim: Animator) {
                        fromView.isVisible = false
                        onComplete?.invoke()
                    }
                })
                ?.start()
        }
    }

    /**
     * Updates an M3 LinearProgressIndicator with fluid animation.
     */
    fun updateProgress(
        indicator: LinearProgressIndicator,
        percent: Int,
        animated: Boolean = true
    ) {
        val clamped = percent.coerceIn(0, 100)
        indicator.setProgressCompat(clamped, animated)
    }

    /**
     * Updates standard ProgressBar with smooth tweening.
     */
    fun updateProgress(
        progressBar: ProgressBar,
        percent: Int
    ) {
        val clamped = percent.coerceIn(0, 100)
        ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, clamped).apply {
            duration = 140L
            interpolator = MotionSpec.DECELERATE_SMOOTH
            start()
        }
    }

    /**
     * Celebrates job completion with a gentle spring settle and subtle haptic feedback.
     */
    fun confirmCompletion(
        targetView: View,
        onSettled: (() -> Unit)? = null
    ) {
        ExpressiveMotion.performConfirmationHaptic(targetView, HapticFeedbackConstants.CONFIRM)

        targetView.animate().cancel()
        targetView.scaleX = 0.98f
        targetView.scaleY = 0.98f
        targetView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(180L)
            .setInterpolator(MotionSpec.OVERSHOOT_FAST)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onSettled?.invoke()
                }
            })
            .start()
    }
}
