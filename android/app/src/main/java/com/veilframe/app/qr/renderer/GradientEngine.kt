package com.veilframe.app.qr.renderer

import android.graphics.Color
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.PaletteStyle
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Evaluates per-module colors based on normalized (x, y) coordinates
 * and functional pattern classification.
 */
class GradientEngine(private val palette: PaletteStyle) {

    fun colorAt(normX: Float, normY: Float, type: FunctionPatternType): Int {
        // Protected function patterns can enforce the solid primary foreground color
        if (type == FunctionPatternType.FINDER_CORE ||
            type == FunctionPatternType.FINDER_OUTER ||
            type == FunctionPatternType.TIMING ||
            type == FunctionPatternType.ALIGNMENT_CENTER
        ) {
            return palette.foreground
        }

        val start = palette.gradientStart ?: return palette.foreground
        val end = palette.gradientEnd ?: return palette.foreground

        val t: Float = when (palette.gradientType) {
            GradientType.LINEAR -> {
                // Diagonal linear gradient (top-left to bottom-right)
                ((normX + normY) / 2f).coerceIn(0f, 1f)
            }
            GradientType.RADIAL -> {
                // Radial gradient from center
                val dx = normX - 0.5f
                val dy = normY - 0.5f
                (sqrt(dx * dx + dy * dy) / 0.7071f).coerceIn(0f, 1f)
            }
            GradientType.SWEEP -> {
                // Angular sweep
                val angle = atan2(normY - 0.5f, normX - 0.5f)
                val normalizedAngle = (angle + Math.PI) / (2 * Math.PI)
                normalizedAngle.toFloat().coerceIn(0f, 1f)
            }
            GradientType.NONE -> 0f
        }

        return interpolateColor(start, end, t)
    }

    private fun interpolateColor(colorA: Int, colorB: Int, t: Float): Int {
        val a = (Color.alpha(colorA) + (Color.alpha(colorB) - Color.alpha(colorA)) * t).toInt()
        val r = (Color.red(colorA) + (Color.red(colorB) - Color.red(colorA)) * t).toInt()
        val g = (Color.green(colorA) + (Color.green(colorB) - Color.green(colorA)) * t).toInt()
        val b = (Color.blue(colorA) + (Color.blue(colorB) - Color.blue(colorA)) * t).toInt()
        return Color.argb(a, r, g, b)
    }
}
