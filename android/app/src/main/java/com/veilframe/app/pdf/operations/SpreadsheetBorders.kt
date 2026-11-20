package com.veilframe.app.pdf.operations

/** Supported OOXML orthogonal border styles, rendered at physical point widths. */
data class XlsxBorderLine(val kind: String, val rgb: Int = 0) {
    val width: Float get() = when (kind) {
        "hair" -> 0.25f
        "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> 1.5f
        "thick" -> 2.25f
        "double" -> 2.5f
        "grid" -> 0.5f
        else -> 0.75f
    }
    internal fun dash(): FloatArray = when (kind) {
        "dotted" -> floatArrayOf(0.75f, 1.5f)
        "dashed", "mediumDashed" -> floatArrayOf(4f, 2f)
        "dashDot", "mediumDashDot", "slantDashDot" -> floatArrayOf(4f, 2f, 1f, 2f)
        "dashDotDot", "mediumDashDotDot" -> floatArrayOf(4f, 2f, 1f, 2f, 1f, 2f)
        else -> floatArrayOf()
    }
    companion object {
        internal val supported = setOf("hair", "thin", "medium", "thick", "double", "dotted",
            "dashed", "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot")
        internal val GRID = XlsxBorderLine("grid", 0xD2D7DC)
    }
}

data class XlsxCellBorders(
    val left: XlsxBorderLine? = null,
    val right: XlsxBorderLine? = null,
    val top: XlsxBorderLine? = null,
    val bottom: XlsxBorderLine? = null
)

internal class SpreadsheetBorderResolver(
    private val layout: SheetLayout,
    private val merges: SpreadsheetMergeIndex
) {
    enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    private fun XlsxCellBorders.get(side: Side) = when (side) {
        Side.LEFT -> left
        Side.RIGHT -> right
        Side.TOP -> top
        Side.BOTTOM -> bottom
    }

    /** Null inside a merge; otherwise prefer the actual perimeter cell's style. */
    private fun side(row: Int, col: Int, side: Side): XlsxBorderLine? {
        if (row !in 0 until layout.rowCount || col !in 0 until layout.colCount) return null
        val range = layout.mergedRegions.getOrNull(merges.at(row, col))
        if (range != null) {
            val edge = when (side) {
                Side.LEFT -> col == range.firstCol
                Side.RIGHT -> col == range.lastCol
                Side.TOP -> row == range.firstRow
                Side.BOTTOM -> row == range.lastRow
            }
            if (!edge) return null
        }
        val direct = layout.cellStyles.at(row, col).borders.get(side)
        val anchor = range?.let { layout.cellStyles.at(it.firstRow, it.firstCol).borders.get(side) }
        return direct ?: anchor ?: if (layout.cellStyles.showGridLines) XlsxBorderLine.GRID else null
    }

    // One physical stroke per shared edge. Explicit borders beat gridlines;
    // thicker borders win; equal-strength conflict prefers top/left cell.
    private fun choose(first: XlsxBorderLine?, second: XlsxBorderLine?): XlsxBorderLine? {
        if (first == null) return second
        if (second == null) return first
        if (first.kind == "grid") return second
        if (second.kind == "grid") return first
        return if (second.width > first.width) second else first
    }

    fun horizontal(belowRow: Int, col: Int): XlsxBorderLine? =
        choose(side(belowRow - 1, col, Side.BOTTOM), side(belowRow, col, Side.TOP))

    fun vertical(row: Int, leftCol: Int?, rightCol: Int?): XlsxBorderLine? =
        choose(leftCol?.let { side(row, it, Side.RIGHT) }, rightCol?.let { side(row, it, Side.LEFT) })
}
