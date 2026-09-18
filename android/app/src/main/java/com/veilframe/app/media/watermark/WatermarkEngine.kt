package com.veilframe.app.media.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.veilframe.app.media.ImageEditState

/**
 * Dedicated engine for text watermark rendering.
 * Supports all 9 spatial anchor positions, custom colors, opacity, and typography.
 */
object WatermarkEngine {

    fun applyWatermark(src: Bitmap, state: ImageEditState): Bitmap {
        if (state.watermarkText.isBlank()) return src

        return try {
            val config = src.config ?: Bitmap.Config.ARGB_8888
            val mutableBmp = src.copy(config, true) ?: return src
            val canvas = Canvas(mutableBmp)

            val scale = (mutableBmp.width.toFloat() / 1000f).coerceIn(0.5f, 4.0f)
            val fontSizePx = state.watermarkSize.toFloat() * scale

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseColor(state.watermarkColor)
                alpha = (state.watermarkOpacity * 255f).toInt().coerceIn(0, 255)
                textSize = fontSizePx
                style = Paint.Style.FILL
                typeface = resolveTypeface(state.watermarkFont)
                setShadowLayer(4f * scale, 2f * scale, 2f * scale, Color.argb(160, 0, 0, 0))
            }

            val text = state.watermarkText
            val textWidth = textPaint.measureText(text)
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            val textHeight = textBounds.height().toFloat()

            val margin = 28f * scale
            val (x, y) = computeCoordinates(
                position = state.watermarkPosition,
                canvasWidth = mutableBmp.width.toFloat(),
                canvasHeight = mutableBmp.height.toFloat(),
                textWidth = textWidth,
                textHeight = textHeight,
                margin = margin
            )

            canvas.drawText(text, x, y, textPaint)
            mutableBmp
        } catch (_: Exception) {
            src
        }
    }

    fun computeCoordinates(
        position: String,
        canvasWidth: Float,
        canvasHeight: Float,
        textWidth: Float,
        textHeight: Float,
        margin: Float
    ): Pair<Float, Float> {
        val clean = position.lowercase().replace("_", "-")
        return when (clean) {
            "top-left" -> margin to (margin + textHeight)
            "top-center" -> ((canvasWidth - textWidth) / 2f) to (margin + textHeight)
            "top-right" -> (canvasWidth - textWidth - margin) to (margin + textHeight)
            "center-left" -> margin to ((canvasHeight + textHeight) / 2f)
            "center" -> ((canvasWidth - textWidth) / 2f) to ((canvasHeight + textHeight) / 2f)
            "center-right" -> (canvasWidth - textWidth - margin) to ((canvasHeight + textHeight) / 2f)
            "bottom-left" -> margin to (canvasHeight - margin)
            "bottom-center" -> ((canvasWidth - textWidth) / 2f) to (canvasHeight - margin)
            "bottom-right" -> (canvasWidth - textWidth - margin) to (canvasHeight - margin)
            else -> (canvasWidth - textWidth - margin) to (canvasHeight - margin)
        }
    }

    private fun parseColor(colorStr: String): Int {
        val clean = colorStr.trim().lowercase()
        return try {
            when (clean) {
                "white" -> Color.WHITE
                "black" -> Color.BLACK
                "red" -> Color.parseColor("#EF4444")
                "yellow" -> Color.parseColor("#EAB308")
                "blue" -> Color.parseColor("#3B82F6")
                "green" -> Color.parseColor("#10B981")
                "cyan" -> Color.parseColor("#06B6D4")
                "magenta" -> Color.parseColor("#D946EF")
                "orange" -> Color.parseColor("#F97316")
                "gold" -> Color.parseColor("#F59E0B")
                "emerald" -> Color.parseColor("#059669")
                "teal" -> Color.parseColor("#14B8A6")
                "violet" -> Color.parseColor("#8B5CF6")
                "pink" -> Color.parseColor("#EC4899")
                "coral" -> Color.parseColor("#F43F5E")
                "slate" -> Color.parseColor("#64748B")
                else -> {
                    val hex = if (colorStr.startsWith("#")) colorStr else "#$colorStr"
                    Color.parseColor(hex)
                }
            }
        } catch (_: Exception) {
            Color.WHITE
        }
    }

    private fun resolveTypeface(fontName: String): Typeface {
        val clean = fontName.trim().lowercase()
        return when (clean) {
            "bold", "heavy" -> Typeface.DEFAULT_BOLD
            "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            "thin" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            "condensed bold" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            "serif" -> Typeface.SERIF
            "serif bold" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            "serif italic" -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            "monospace", "mono" -> Typeface.MONOSPACE
            "mono bold", "monospace bold" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            "cursive", "script" -> Typeface.create("cursive", Typeface.NORMAL)
            "casual" -> Typeface.create("casual", Typeface.NORMAL)
            "black", "heavy black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
    }
}
