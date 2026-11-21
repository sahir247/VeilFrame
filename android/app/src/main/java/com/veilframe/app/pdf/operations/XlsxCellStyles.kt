package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/** Derived per-cell graphics; no original workbook resources or bitmaps held. */
data class XlsxDataBarVisual(val start: Float, val end: Float, val rgb: Int, val showValue: Boolean)
data class XlsxIconVisual(val rgb: Int, val showValue: Boolean, val shape: String = "circle")

/** Supported subset of the Excel cell appearance contract. */
data class XlsxCellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val textRgb: Int? = null,
    val fillRgb: Int? = null,
    val horizontal: String = "left",
    val borders: XlsxCellBorders = XlsxCellBorders(),
    val vertical: String = "top",
    val textRotation: Int = 0,
    val dataBar: XlsxDataBarVisual? = null,
    val icon: XlsxIconVisual? = null,
    val fontSizePt: Float? = null
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
    val styledRows: Map<Int, IntArray> = emptyMap(),
    val showGridLines: Boolean = true
) {
    fun at(row: Int, column: Int): XlsxCellStyle {
        val index = styledRows[row]?.getOrNull(column) ?: 0
        return definitions[index]
    }
}
