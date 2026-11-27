package com.veilframe.app.pdf.operations

import kotlin.math.floor

/**
 * Saved Excel dimensions are lower bounds in the content-preserving PDF mode.
 * XLSX column widths are expressed in maximum-digit-width units, not points.
 * Use the conventional 7 px at 96 dpi reference metric (Calibri 11);
 * the actual source font may have different metrics.
 */
data class XlsxDimensions(
    val defaultColumnWidth: Float? = null,
    val defaultRowHeightPt: Float? = null,
    val columnWidths: Map<Int, Float> = emptyMap(),
    val rowHeightsPt: Map<Int, Float> = emptyMap()
) {
    fun columnMinPt(index: Int): Float {
        val width = columnWidths[index] ?: defaultColumnWidth ?: return 0f
        require(width.isFinite() && width in 0f..255f) { "Invalid XLSX column width" }
        return (floor((width.toDouble() * 256.0 + floor(128.0 / 7.0)) / 256.0 * 7.0) * 0.75).toFloat()
    }

    fun rowMinPt(index: Int): Float {
        val height = rowHeightsPt[index] ?: defaultRowHeightPt ?: return 0f
        require(height.isFinite() && height in 0f..409.5f) { "Invalid XLSX row height" }
        return height
    }
}
