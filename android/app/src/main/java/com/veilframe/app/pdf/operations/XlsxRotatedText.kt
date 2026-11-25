package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlin.math.cos
import kotlin.math.sin

/** Geometry shared by layout admission and PDF rendering. No raster allocation. */
internal object XlsxRotatedText {
    data class Block(val width: Float, val height: Float, val unrotatedWidth: Float,
        val unrotatedHeight: Float, val cosine: Float, val sine: Float, val minX: Float, val minY: Float)

    fun lines(text: String, rotation: Int): List<String> {
        require(text.length <= 32767) { "Rotated cell exceeds Excel text length limit" }
        val paragraphs = text.split(Regex("\\r\\n|\\r|\\n"))
        if (rotation != 255) return paragraphs
        return paragraphs.flatMap { paragraph ->
            if (paragraph.isEmpty()) listOf("") else buildList {
                var i = 0
                while (i < paragraph.length) {
                    val cp = paragraph.codePointAt(i)
                    add(String(Character.toChars(cp)))
                    i += Character.charCount(cp)
                }
            }
        }
    }

    fun measure(text: String, rotation: Int, font: PDFont, size: Float, lineHeight: Float) =
        measureLines(lines(text, rotation), rotation, font, size, lineHeight)

    fun measureLines(lines: List<String>, rotation: Int, font: PDFont, size: Float, lineHeight: Float): Block {
        val w = lines.maxOfOrNull { line ->
            val clean = line.replace('\t', ' ')
            try { font.getStringWidth(clean) * size / 1000f }
            catch (_: Exception) { clean.length * size }
        } ?: 0f
        val h = size + (lines.size - 1).coerceAtLeast(0) * lineHeight
        return geometry(w,h,rotation)
    }

    fun geometry(w: Float,h: Float,rotation: Int): Block {
        val angle = when (rotation) { 255 -> 0; in 0..90 -> rotation; else -> 90 - rotation }
        val radians = Math.toRadians(angle.toDouble())
        val c = cos(radians).toFloat().let { if (kotlin.math.abs(it) < 0.000001f) 0f else it }
        val s = sin(radians).toFloat().let { if (kotlin.math.abs(it) < 0.000001f) 0f else it }
        val xs = floatArrayOf(0f, c * w, -s * h, c * w - s * h)
        val ys = floatArrayOf(0f, s * w, c * h, s * w + c * h)
        val x = xs.minOrNull() ?: 0f
        val y = ys.minOrNull() ?: 0f
        return Block(xs.maxOrNull()!! - x, ys.maxOrNull()!! - y, w, h, c, s, x, y)
    }
}
