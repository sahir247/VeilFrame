package com.veilframe.app.ui.haptics

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.veilframe.app.R
import kotlin.math.abs

/**
 * SliderHapticsHelper — provides tactile micro-haptic feedback across sliders and seekbars.
 *
 * Implements subtle, throttled ticks (HapticFeedbackConstants.CLOCK_TICK) to provide
 * tactile confirmation as values change or snap across notches, without motor saturation.
 */
object SliderHapticsHelper {

    fun triggerTick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /**
     * Recursively traverses a view hierarchy and attaches subtle haptic feedback
     * to all Slider, RangeSlider, and SeekBar components.
     */
    fun attachRecursively(root: View) {
        when (root) {
            is Slider -> root.addHapticFeedback()
            is RangeSlider -> root.addHapticFeedback()
            is SeekBar -> root.addHapticFeedback()
            is ViewGroup -> {
                for (i in 0 until root.childCount) {
                    attachRecursively(root.getChildAt(i))
                }
            }
        }
    }
}

/**
 * Attaches throttled, quantized haptic feedback to Material Slider.
 */
fun Slider.addHapticFeedback(tickInterval: Float? = null) {
    if (getTag(R.id.vf_tag_slider_haptics) == true) return
    setTag(R.id.vf_tag_slider_haptics, true)

    val interval = tickInterval ?: if (stepSize > 0f) {
        stepSize
    } else {
        ((valueTo - valueFrom) / 50f).coerceAtLeast(0.01f)
    }

    var lastHapticValue = value
    var lastHapticTime = 0L

    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
            SliderHapticsHelper.triggerTick(slider)
            lastHapticValue = slider.value
            lastHapticTime = SystemClock.uptimeMillis()
        }

        override fun onStopTrackingTouch(slider: Slider) {
            SliderHapticsHelper.triggerTick(slider)
            lastHapticValue = slider.value
            lastHapticTime = SystemClock.uptimeMillis()
        }
    })

    addOnChangeListener { slider, value, fromUser ->
        if (!fromUser) return@addOnChangeListener
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticTime >= 25L && abs(value - lastHapticValue) >= interval) {
            SliderHapticsHelper.triggerTick(slider)
            lastHapticValue = value
            lastHapticTime = now
        }
    }
}

/**
 * Attaches throttled, quantized haptic feedback to Material RangeSlider.
 */
fun RangeSlider.addHapticFeedback(tickInterval: Float? = null) {
    if (getTag(R.id.vf_tag_slider_haptics) == true) return
    setTag(R.id.vf_tag_slider_haptics, true)

    val interval = tickInterval ?: if (stepSize > 0f) {
        stepSize
    } else {
        ((valueTo - valueFrom) / 50f).coerceAtLeast(0.01f)
    }

    var lastValues = values.toList()
    var lastHapticTime = 0L

    addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: RangeSlider) {
            SliderHapticsHelper.triggerTick(slider)
            lastValues = slider.values.toList()
            lastHapticTime = SystemClock.uptimeMillis()
        }

        override fun onStopTrackingTouch(slider: RangeSlider) {
            SliderHapticsHelper.triggerTick(slider)
            lastValues = slider.values.toList()
            lastHapticTime = SystemClock.uptimeMillis()
        }
    })

    addOnChangeListener { slider, _, fromUser ->
        if (!fromUser) return@addOnChangeListener
        val current = slider.values
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticTime >= 25L) {
            var diff = false
            for (i in current.indices) {
                val prev = lastValues.getOrNull(i) ?: 0f
                if (abs(current[i] - prev) >= interval) {
                    diff = true
                    break
                }
            }
            if (diff) {
                SliderHapticsHelper.triggerTick(slider)
                lastValues = current.toList()
                lastHapticTime = now
            }
        }
    }
}

/**
 * Attaches throttled haptic feedback to Android SeekBar.
 */
fun SeekBar.addHapticFeedback() {
    if (getTag(R.id.vf_tag_slider_haptics) == true) return
    setTag(R.id.vf_tag_slider_haptics, true)

    var lastProgress = progress
    var lastHapticTime = 0L

    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val now = SystemClock.uptimeMillis()
            if (now - lastHapticTime >= 25L && abs(progress - lastProgress) >= 1) {
                SliderHapticsHelper.triggerTick(seekBar)
                lastProgress = progress
                lastHapticTime = now
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            SliderHapticsHelper.triggerTick(seekBar)
            lastProgress = seekBar.progress
            lastHapticTime = SystemClock.uptimeMillis()
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            SliderHapticsHelper.triggerTick(seekBar)
            lastProgress = seekBar.progress
            lastHapticTime = SystemClock.uptimeMillis()
        }
    })
}

/**
 * Extension on View to attach slider haptics recursively.
 */
fun View.attachSliderHapticsRecursively() {
    SliderHapticsHelper.attachRecursively(this)
}
