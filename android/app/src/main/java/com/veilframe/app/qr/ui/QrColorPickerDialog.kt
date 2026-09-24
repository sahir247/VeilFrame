package com.veilframe.app.qr.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import java.util.Locale

/**
 * Material 3 Expressive Color Picker Dialog.
 * Provides interactive Hue/Saturation/Brightness spectrum sliders, live dual swatch preview,
 * direct Hex code editing, accessibility contrast feedback, and curated quick swatches.
 */
class QrColorPickerDialog(
    private val context: Context,
    private val initialColor: Int,
    private val contrastAgainstColor: Int,
    private val isForeground: Boolean,
    private val onColorSelected: (Int) -> Unit
) {

    private var activeHue = 0f
    private var activeSat = 1f
    private var activeVal = 1f
    private var isUpdatingFromInput = false

    fun show(): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_qr_color_picker, null)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()

        val titleView       = view.findViewById<TextView>(R.id.picker_title)
        val subtitleView    = view.findViewById<TextView>(R.id.picker_subtitle)
        val currentSwatch   = view.findViewById<View>(R.id.swatch_current_color)
        val newSwatch       = view.findViewById<View>(R.id.swatch_new_color)
        val hexInput        = view.findViewById<EditText>(R.id.picker_hex_input)
        val contrastCard    = view.findViewById<MaterialCardView>(R.id.card_contrast_badge)
        val contrastIcon    = view.findViewById<ImageView>(R.id.picker_contrast_icon)
        val contrastText    = view.findViewById<TextView>(R.id.picker_contrast_text)

        val hueSlider       = view.findViewById<SeekBar>(R.id.seekbar_hue)
        val satSlider       = view.findViewById<SeekBar>(R.id.seekbar_saturation)
        val valSlider       = view.findViewById<SeekBar>(R.id.seekbar_brightness)

        val hueLabel        = view.findViewById<TextView>(R.id.label_hue_val)
        val satLabel        = view.findViewById<TextView>(R.id.label_sat_val)
        val valLabel        = view.findViewById<TextView>(R.id.label_val_val)

        val swatchContainer = view.findViewById<LinearLayout>(R.id.container_preset_swatches)
        val applyBtn        = view.findViewById<MaterialButton>(R.id.picker_apply_btn)
        val cancelBtn       = view.findViewById<MaterialButton>(R.id.picker_cancel_btn)

        titleView.text = if (isForeground) "Select Foreground Color" else "Select Background Color"
        subtitleView.text = if (isForeground) {
            "Choose a dark or vibrant color for QR patterns"
        } else {
            "Choose a light or high-contrast backdrop for QR modules"
        }

        // Initialize HSV components
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        activeHue = hsv[0]
        activeSat = hsv[1]
        activeVal = hsv[2]

        // Style the initial swatches
        setSwatchColor(currentSwatch, initialColor)

        // Hue Slider Gradient
        val hueSpectrum = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        val hueTrack = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, hueSpectrum).apply {
            cornerRadius = 16f
        }
        hueSlider.background = hueTrack

        fun getSelectedColor(): Int = Color.HSVToColor(floatArrayOf(activeHue, activeSat, activeVal))

        fun updateSatGradient() {
            val satStart = Color.HSVToColor(floatArrayOf(activeHue, 0f, activeVal))
            val satEnd = Color.HSVToColor(floatArrayOf(activeHue, 1f, activeVal))
            val satTrack = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(satStart, satEnd)).apply {
                cornerRadius = 16f
            }
            satSlider.background = satTrack
        }

        fun updateValGradient() {
            val valStart = Color.BLACK
            val valEnd = Color.HSVToColor(floatArrayOf(activeHue, activeSat, 1f))
            val valTrack = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(valStart, valEnd)).apply {
                cornerRadius = 16f
            }
            valSlider.background = valTrack
        }

        fun updateUi(updateSliders: Boolean = true, updateHex: Boolean = true) {
            val selected = getSelectedColor()
            setSwatchColor(newSwatch, selected)

            hueLabel.text = "${activeHue.toInt()}°"
            satLabel.text = "${(activeSat * 100).toInt()}%"
            valLabel.text = "${(activeVal * 100).toInt()}%"

            if (updateSliders) {
                hueSlider.progress = activeHue.toInt()
                satSlider.progress = (activeSat * 100).toInt()
                valSlider.progress = (activeVal * 100).toInt()
            }

            updateSatGradient()
            updateValGradient()

            if (updateHex) {
                isUpdatingFromInput = true
                val hexStr = String.format(Locale.US, "%06X", 0xFFFFFF and selected)
                hexInput.setText(hexStr)
                hexInput.setSelection(hexStr.length)
                isUpdatingFromInput = false
            }

            // Calculate Contrast Ratio
            val ratio = calculateContrastRatio(selected, contrastAgainstColor)
            val formattedRatio = String.format(Locale.US, "%.1f", ratio)
            if (ratio >= 7.0f) {
                contrastText.text = "Contrast ratio: $formattedRatio:1 (Excellent for scanning)"
                contrastText.setTextColor(0xFF16A34A.toInt()) // Green
                contrastCard.setCardBackgroundColor(0x1816A34A)
                contrastIcon?.setImageResource(R.drawable.ic_check_circle)
                contrastIcon?.setColorFilter(0xFF16A34A.toInt())
            } else if (ratio >= 4.5f) {
                contrastText.text = "Contrast ratio: $formattedRatio:1 (Good)"
                contrastText.setTextColor(0xFF2563EB.toInt()) // Blue
                contrastCard.setCardBackgroundColor(0x182563EB)
                contrastIcon?.setImageResource(R.drawable.ic_check_circle)
                contrastIcon?.setColorFilter(0xFF2563EB.toInt())
            } else if (ratio >= 3.0f) {
                contrastText.text = "Contrast ratio: $formattedRatio:1 (Acceptable)"
                contrastText.setTextColor(0xFFD97706.toInt()) // Amber
                contrastCard.setCardBackgroundColor(0x18D97706)
                contrastIcon?.setImageResource(R.drawable.ic_info_outline)
                contrastIcon?.setColorFilter(0xFFD97706.toInt())
            } else {
                contrastText.text = "Low contrast: $formattedRatio:1 (May be difficult to scan)"
                contrastText.setTextColor(0xFFDC2626.toInt()) // Red
                contrastCard.setCardBackgroundColor(0x18DC2626)
                contrastIcon?.setImageResource(R.drawable.ic_info_outline)
                contrastIcon?.setColorFilter(0xFFDC2626.toInt())
            }
        }

        // Slider listeners
        hueSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    activeHue = p.toFloat().coerceIn(0f, 360f)
                    updateUi(updateSliders = false, updateHex = true)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        satSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    activeSat = (p / 100f).coerceIn(0f, 1f)
                    updateUi(updateSliders = false, updateHex = true)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        valSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    activeVal = (p / 100f).coerceIn(0f, 1f)
                    updateUi(updateSliders = false, updateHex = true)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Hex Input TextWatcher
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromInput) return
                val text = s?.toString()?.trim().orEmpty()
                if (text.length == 6) {
                    try {
                        val parsed = Color.parseColor("#$text")
                        val newHsv = FloatArray(3)
                        Color.colorToHSV(parsed, newHsv)
                        activeHue = newHsv[0]
                        activeSat = newHsv[1]
                        activeVal = newHsv[2]
                        updateUi(updateSliders = true, updateHex = false)
                    } catch (_: Exception) {}
                }
            }
        })

        // Curated Presets
        val presets = intArrayOf(
            Color.BLACK,
            Color.WHITE,
            0xFF18181B.toInt(), // Slate
            0xFF27272A.toInt(), // Charcoal
            0xFF1E3A8A.toInt(), // Navy
            0xFF2563EB.toInt(), // Royal Blue
            0xFF0891B2.toInt(), // Cyan
            0xFF059669.toInt(), // Emerald
            0xFF166534.toInt(), // Forest
            0xFFD97706.toInt(), // Amber
            0xFFEA580C.toInt(), // Orange
            0xFFDC2626.toInt(), // Crimson
            0xFFE11D48.toInt(), // Rose
            0xFF7C3AED.toInt(), // Violet
            0xFF4F46E5.toInt(), // Indigo
            0xFFB45309.toInt()  // Gold
        )

        for (presetColor in presets) {
            val swatch = View(context).apply {
                val size = (38 * context.resources.displayMetrics.density).toInt()
                val margin = (4 * context.resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setSwatchColor(this, presetColor)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val newHsv = FloatArray(3)
                    Color.colorToHSV(presetColor, newHsv)
                    activeHue = newHsv[0]
                    activeSat = newHsv[1]
                    activeVal = newHsv[2]
                    updateUi(updateSliders = true, updateHex = true)
                }
            }
            swatchContainer.addView(swatch)
        }

        // Initial UI sync
        updateUi(updateSliders = true, updateHex = true)

        applyBtn.setOnClickListener {
            onColorSelected(getSelectedColor())
            dialog.dismiss()
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }

    private fun setSwatchColor(view: View, color: Int) {
        val strokeColor = if (isColorVeryDark(color)) 0x40FFFFFF.toInt() else 0x30000000
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * context.resources.displayMetrics.density
            setColor(color)
            setStroke((1.5f * context.resources.displayMetrics.density).toInt(), strokeColor)
        }
        view.background = drawable
    }

    private fun isColorVeryDark(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return lum < 0.2
    }

    private fun calculateContrastRatio(color1: Int, color2: Int): Float {
        val lum1 = calculateLuminance(color1)
        val lum2 = calculateLuminance(color2)
        val brightest = maxOf(lum1, lum2)
        val darkest = minOf(lum1, lum2)
        return ((brightest + 0.05f) / (darkest + 0.05f))
    }

    private fun calculateLuminance(color: Int): Float {
        fun channel(v: Int): Float {
            val c = v / 255f
            return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055), 2.4).toFloat()
        }
        val r = channel(Color.red(color))
        val g = channel(Color.green(color))
        val b = channel(Color.blue(color))
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
