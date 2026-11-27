package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/** Derived per-cell graphics; no original workbook resources or bitmaps held. */
data class XlsxDataBarVisual(val start: Float, val end: Float, val rgb: Int, val showValue: Boolean,
    val borderRgb: Int? = null, val axis: Float? = null, val axisRgb: Int? = null,
    val gradient: Boolean = false)
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
    val fontSizePt: Float? = null,
    val fontFace: String? = null,
    val wrapText: Boolean = false
) {
    internal fun font(base: PDFont): PDFont = when (fontFace?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "times new roman", "times", "liberation serif", "nimbus roman" -> when {
            bold && italic -> PDType1Font.TIMES_BOLD_ITALIC
            bold -> PDType1Font.TIMES_BOLD
            italic -> PDType1Font.TIMES_ITALIC
            else -> PDType1Font.TIMES_ROMAN
        }
        "courier new", "courier", "liberation mono", "nimbus mono", "consolas" -> when {
            bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            bold -> PDType1Font.COURIER_BOLD
            italic -> PDType1Font.COURIER_OBLIQUE
            else -> PDType1Font.COURIER
        }
        else -> when {
            bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            bold -> PDType1Font.HELVETICA_BOLD
            italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> base
        }
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
