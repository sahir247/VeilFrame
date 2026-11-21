package com.veilframe.app.pdf.operations

/** Validate the actual render rectangle, not just cells explicitly stored in XML. */
internal object SpreadsheetGeometry {
    const val MAX_MERGES = 4096
    const val MAX_GRID_CELLS = 2_000_000L
    const val MAX_WORKBOOK_CELLS = 4_000_000L
    const val MAX_TEXT_CHARS = 8_000_000L
    const val MAX_WORKBOOK_TEXT_CHARS = 16_000_000L
    const val MAX_PAGES = 2000

    data class Extent(val rows: Int, val columns: Int, val textChars: Long) {
        val cells: Long get() = rows.toLong() * columns
    }

    fun inspect(rows: List<List<String>>, merges: List<OpenXmlDocumentParser.CellRangeAddress>): Extent {
        require(merges.size <= MAX_MERGES) { "Worksheet exceeds merged-range count limit" }
        var height = rows.size
        var width = rows.maxOfOrNull { it.size } ?: 0
        for (m in merges) {
            require(m.firstRow in 0..m.lastRow && m.lastRow < 1_048_576 &&
                m.firstCol in 0..m.lastCol && m.lastCol < 16_384 &&
                (m.firstRow != m.lastRow || m.firstCol != m.lastCol)) { "Invalid merged range" }
            height = maxOf(height, m.lastRow + 1)
            width = maxOf(width, m.lastCol + 1)
        }
        val cells = height.toLong() * width
        require(height <= 1_048_576 && width <= 16_384 && cells <= MAX_GRID_CELLS) {
            "Worksheet render rectangle exceeds 2,000,000 cells; narrow the used range"
        }
        var textChars = 0L
        for (row in rows) for (value in row) {
            textChars += value.length
            require(textChars <= MAX_TEXT_CHARS) { "Worksheet expanded text exceeds layout budget" }
        }
        // Bitmap admission bounds memory even when many large merge ranges overlap.
        val occupied = java.util.BitSet(cells.toInt())
        for (m in merges) {
            val anchor = rows.getOrNull(m.firstRow)?.getOrNull(m.firstCol).orEmpty()
            for (row in m.firstRow..m.lastRow) {
                val from = row * width + m.firstCol
                val until = row * width + m.lastCol + 1
                val existing = occupied.nextSetBit(from)
                require(existing < 0 || existing >= until) { "Overlapping merged ranges" }
                occupied.set(from, until)
                val values = rows.getOrNull(row) ?: continue
                for (col in m.firstCol..minOf(m.lastCol, values.lastIndex)) {
                    if (row == m.firstRow && col == m.firstCol) continue
                    // Some producers redundantly store the same text in covered cells.
                    // Distinct non-anchor text must never be silently lost.
                    val value = values[col]
                    require(value.isEmpty() || value == anchor) { "Merged range covers distinct cell content" }
                }
            }
        }
        return Extent(height, width, textChars)
    }
}

/** One compact index for each sheet render; O(1) lookup per cell. */
internal class SpreadsheetMergeIndex(
    private val rowCount: Int, private val colCount: Int,
    private val ranges: List<OpenXmlDocumentParser.CellRangeAddress>
) {
    init {
        require(rowCount >= 0 && colCount >= 0 &&
            rowCount.toLong() * colCount <= SpreadsheetGeometry.MAX_GRID_CELLS)
    }
    private val ids = if (ranges.isEmpty()) IntArray(0) else IntArray(rowCount * colCount)
    init {
        for ((index, range) in ranges.withIndex()) {
            require(range.firstRow in 0..range.lastRow && range.lastRow < rowCount &&
                range.firstCol in 0..range.lastCol && range.lastCol < colCount)
            for (row in range.firstRow..range.lastRow) {
                val start = row * colCount + range.firstCol
                val end = row * colCount + range.lastCol + 1
                java.util.Arrays.fill(ids, start, end, index + 1)
            }
        }
    }
    fun at(row: Int, col: Int): Int {
        require(row in 0 until rowCount && col in 0 until colCount)
        return if (ids.isEmpty()) -1 else ids[row * colCount + col] - 1
    }
}
