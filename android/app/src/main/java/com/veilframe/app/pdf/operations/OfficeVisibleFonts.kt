package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.IOException

internal class OfficeTextEncodingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Visible text is not OCR: do not draw unshaped complex scripts or drop style. */
internal class OfficeVisibleFonts(private val context: Context, private val document: PDDocument) {
    private fun load(style: String) =
        context.assets.open("fonts/ocr/NotoSans-$style.ttf").use { PDType0Font.load(document,it) }
    private val regular by lazy { load("Regular") }
    private val bold by lazy { load("Bold") }
    private val italic by lazy { load("Italic") }
    private val boldItalic by lazy { load("BoldItalic") }

    fun select(preferred: PDFont, text: String): PDFont {
        val normalized = normalizeOfficeText(text)
        if (encodes(preferred, normalized)) return preferred
        // Do not substitute serif/mono family metrics or claim shaping.
        if (supportsSimpleVisibleText(normalized)) {
            val fallback = when (preferred) {
                PDType1Font.HELVETICA -> regular
                PDType1Font.HELVETICA_BOLD -> bold
                PDType1Font.HELVETICA_OBLIQUE -> italic
                PDType1Font.HELVETICA_BOLD_OBLIQUE -> boldItalic
                else -> null
            }
            if (fallback != null && encodes(fallback,normalized)) return fallback
        }
        throw OfficeTextEncodingException("Visible text has glyphs or script shaping unsupported by the Office PDF renderer; export refused")
    }

    private fun encodes(font: PDFont, text: String): Boolean = try {
        font.encode(text)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

internal fun normalizeOfficeText(text: String): String =
    text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

internal fun supportsSimpleVisibleText(text: String): Boolean = text.codePoints().allMatch { cp ->
    val script = Character.UnicodeScript.of(cp)
    val type = Character.getType(cp)
    cp in 32..0xffff &&
        type != Character.NON_SPACING_MARK.toInt() &&
        type != Character.COMBINING_SPACING_MARK.toInt() &&
        type != Character.ENCLOSING_MARK.toInt() &&
        type != Character.FORMAT.toInt() &&
        type != Character.CONTROL.toInt() &&
        type != Character.SURROGATE.toInt() &&
        (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK ||
            script == Character.UnicodeScript.CYRILLIC || script == Character.UnicodeScript.COMMON)
}
