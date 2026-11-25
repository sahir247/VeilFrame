package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.IOException

internal class OfficeTextEncodingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Visible text is not OCR: do not draw unshaped complex scripts or drop style. */
internal class OfficeVisibleFonts(context: Context, document: PDDocument) {
    private val regular by lazy {
        context.assets.open("fonts/ocr/NotoSans-Regular.ttf").use { PDType0Font.load(document, it) }
    }

    fun select(preferred: PDFont, text: String): PDFont {
        val normalized = normalizeOfficeText(text)
        if (encodes(preferred, normalized)) return preferred
        // The bundled face is regular only. Never silently discard emphasis,
        // and do not claim complex-script shaping from PDFBox glyph encoding.
        if (preferred === PDType1Font.HELVETICA && supportsSimpleVisibleText(normalized) &&
            encodes(regular, normalized)) return regular
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
