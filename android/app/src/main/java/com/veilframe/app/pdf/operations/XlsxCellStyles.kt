package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/** Supported subset of the Excel cell appearance contract. */
data class XlsxCellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val textRgb: Int? = null,
    val fillRgb: Int? = null,
    val horizontal: String = "left"
) {
    internal fun font(base: PDFont): PDFont = when {
        bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
        bold -> PDType1Font.HELVETICA_BOLD
        italic -> PDType1Font.HELVETICA_OBLIQUE
        else -> base
    }
}

/** Only explicitly styled rows allocate a packed cell-style index array. */
data class XlsxCellStyles(
    val definitions: List<XlsxCellStyle> = listOf(XlsxCellStyle()),
    val styledRows: Map<Int, IntArray> = emptyMap()
) {
    fun at(row: Int, column: Int): XlsxCellStyle {
        val index = styledRows[row]?.getOrNull(column) ?: 0
        return definitions[index]
    }
}
