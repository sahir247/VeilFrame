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
        return try {
            when (colorStr.lowercase()) {
                "white" -> Color.WHITE
                "black" -> Color.BLACK
                "red" -> Color.RED
                "yellow" -> Color.YELLOW
                "blue" -> Color.BLUE
                "green" -> Color.GREEN
                "cyan" -> Color.CYAN
                "magenta" -> Color.MAGENTA
                else -> Color.parseColor(colorStr)
            }
        } catch (_: Exception) {
            Color.WHITE
        }
    }

    private fun resolveTypeface(fontName: String): Typeface {
        return when (fontName.lowercase()) {
            "monospace", "mono" -> Typeface.MONOSPACE
            "serif" -> Typeface.SERIF
            "bold", "heavy" -> Typeface.DEFAULT_BOLD
            else -> Typeface.SANS_SERIF
        }
    }
}
