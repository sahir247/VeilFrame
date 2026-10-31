package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Three-level export strategy for spreadsheet to PDF conversion.
 */
enum class ExportStrategy {
    /**
     * Level 1: Natural-size single page format conversion.
     * Both width and height are <= 14,400 pt (ISO 32000-1 coordinate limit).
     */
    NATURAL_SIZE,

    /**
     * Level 2: Extended-size single page using PDF /UserUnit scaling.
     * Operates when natural dimensions exceed 14,400 pt up to 75,000 pt.
     * Physical text, cell padding, and gridlines retain true dimensions.
     */
    EXTENDED_SIZE,

    /**
     * Level 3: Lossless two-dimensional tiling across multiple pages.
     * Used when dimensions exceed limits or when 2D tiling is explicitly requested.
     * Emits pages in strict row-major sequence (Row group 1 -> Col groups 1..N, Row group 2 -> Col groups 1..N).
     * Includes clear continuation banners and optional frozen identifying column repetition.
     */
    TILED
}

/**
 * Configuration and physical limits for spreadsheet geometry and layout.
 */
data class LayoutLimits(
    val minPageWidth: Float = 842f,       // A4 landscape width (297mm) minimum for small sheets
    val minPageHeight: Float = 595f,      // A4 landscape height (210mm) minimum for small sheets
    val maxStandardCoord: Float = 14400f, // ISO 32000-1 standard PDF coordinate ceiling (200 in @ 72 dpi)
    val maxExtendedCoord: Float = 75000f, // Maximum ISO 32000-1 /UserUnit extended coordinate ceiling
    val maxTileWidth: Float = 14400f,     // Default tile width ceiling for 2D tiling
    val maxTileHeight: Float = 14400f,    // Default tile height ceiling for 2D tiling
    val margin: Float = 30f,
    val headerHeight: Float = 36f,
    val bannerHeight: Float = 22f,
    val fontSizeCell: Float = 9f,
    val lineHeight: Float = 9f * 1.3f,
    val paddingVertical: Float = 8f,
    val allowExtendedSize: Boolean = true,
    val repeatFrozenColumnsInTiling: Boolean = false, // false by default for unique 1:1 cell ID mapping
    val maxTileWidthOverride: Float? = null,
    val maxTileHeightOverride: Float? = null,
    val forceStrategy: ExportStrategy? = null
)

/**
 * Extracted geometric layout of an individual worksheet.
 */
data class MergedCellGeometry(
    val range: OpenXmlDocumentParser.CellRangeAddress,
    val firstCol: Int,
    val lastCol: Int,
    val firstRow: Int,
    val lastRow: Int,
    val text: String,
    val wrappedLines: List<String>,
    val totalWidth: Float,
    val totalHeight: Float
)

data class SheetLayout(
    val name: String,
    val index: Int,
    val rows: List<List<String>>,
    val rowCount: Int,
    val colCount: Int,
    val colWidths: FloatArray,
    val colX: FloatArray,
    val rowHeights: FloatArray,
    val rowY: FloatArray,
    val rowWrappedLines: Array<Array<List<String>>>,
    val totalTableWidth: Float,
    val totalTableHeight: Float,
    val naturalPageWidth: Float,
    val naturalPageHeight: Float,
    val mergedRegions: List<OpenXmlDocumentParser.CellRangeAddress> = emptyList(),
    val mergedGeometries: List<MergedCellGeometry> = emptyList(),
    val frozenColumnsCount: Int = 0
)

/**
 * Group of columns assigned to a horizontal panel tile.
 */
data class TileColumnGroup(
    val groupIndex: Int,
    val totalGroups: Int,
    val startCol: Int,
    val endCol: Int, // inclusive
    val frozenCols: List<Int>,
    val activeCols: List<Int>,
    val groupTableWidth: Float,
    val tileWidth: Float,
    val hasLeftContinuation: Boolean,
    val hasRightContinuation: Boolean
)

/**
 * Group of rows assigned to a vertical panel tile.
 */
data class TileRowGroup(
    val groupIndex: Int,
    val totalGroups: Int,
    val startRow: Int,
    val endRow: Int, // inclusive
    val startLineOfStartRow: Int = 0,
    val linesInStartRow: Int = -1,
    val groupTableHeight: Float,
    val tileHeight: Float,
    val hasDownContinuation: Boolean
)

/**
 * A single 2D tile representing one output PDF page.
 */
data class SheetTile(
    val sheetName: String,
    val sheetIndex: Int,
    val rowGroup: TileRowGroup,
    val colGroup: TileColumnGroup,
    val tilePageWidth: Float,
    val tilePageHeight: Float
)

/**
 * Complete export execution plan for a worksheet.
 */
data class ExportPlan(
    val sheetLayout: SheetLayout,
    val strategy: ExportStrategy,
    val userUnit: Float = 1.0f,
    val pageWidth: Float = 0f,
    val pageHeight: Float = 0f,
    val tiles: List<SheetTile> = emptyList()
)

/**
 * Architecture-level engine responsible for:
 * 1. Geometry extraction from raw worksheet data into a formal layout model.
 * 2. Strategy selection across Natural (Level 1), Extended (Level 2), and Tiled (Level 3).
 * 3. Two-dimensional tile planning in row-major sequence with continuation metadata.
 */
object SpreadsheetLayoutEngine {

    /**
     * Converts a 0-based column index to an Excel column letter (0 -> "A", 25 -> "Z", 26 -> "AA").
     */
    fun colIndexToRef(index: Int): String {
        var col = index + 1
        val sb = StringBuilder()
        while (col > 0) {
            val rem = (col - 1) % 26
            sb.insert(0, ('A' + rem))
            col = (col - 1) / 26
        }
        return sb.toString()
    }

    /**
     * Extracts physical geometry, column widths, row heights, and total table bounds.
     */
    fun buildSheetLayout(
        sheet: OpenXmlDocumentParser.XlsxSheetData,
        sheetIndex: Int,
        fontNormal: PDFont,
        limits: LayoutLimits
    ): SheetLayout {
        val rows = sheet.rows
        val maxCol = rows.maxOfOrNull { it.size } ?: 0

        val effectiveMaxTileW = limits.maxTileWidthOverride ?: limits.maxTileWidth
        val maxAllowedColWidth = (minOf(effectiveMaxTileW, limits.maxStandardCoord) - (2 * limits.margin)).coerceAtLeast(45f)

        val colWidths = FloatArray(maxCol) { c ->
            var maxW = 32f
            for (r in rows) {
                val cellVal = r.getOrNull(c) ?: ""
                if (cellVal.isNotEmpty()) {
                    val maxLineW = cellVal.split(Regex("\r\n|\r|\n")).maxOfOrNull { line ->
                        val cleanLine = sanitizeForFontMetrics(line)
                        try {
                            fontNormal.getStringWidth(cleanLine) / 1000f * limits.fontSizeCell
                        } catch (_: Exception) {
                            cleanLine.length * 5f
                        }
                    } ?: 0f
                    if (maxLineW > maxW) maxW = maxLineW
                }
            }
            // Strict ceiling: individual column width cannot exceed maximum tile table width budget
            minOf(maxAllowedColWidth, (maxW + 16f).coerceAtLeast(45f))
        }

        val colX = FloatArray(maxCol)
        var runningX = limits.margin
        for (c in 0 until maxCol) {
            colX[c] = runningX
            runningX += colWidths[c]
        }

        val rowWrappedLines = Array(rows.size) { rIdx ->
            val row = rows[rIdx]
            Array(maxCol) { c ->
                val text = row.getOrNull(c) ?: ""
                if (text.isNotBlank()) {
                    wrapCellText(text, fontNormal, limits.fontSizeCell, colWidths[c] - 8f)
                } else {
                    emptyList()
                }
            }
        }

        val rowHeights = FloatArray(rows.size) { rIdx ->
            val maxLinesInRow = rowWrappedLines[rIdx].maxOfOrNull { it.size } ?: 1
            maxOf(20f, maxLinesInRow * limits.lineHeight + limits.paddingVertical)
        }

        val rowY = FloatArray(rows.size)
        var runningY = 0f
        for (r in rows.indices) {
            rowY[r] = runningY
            runningY += rowHeights[r]
        }

        val totalTableWidth = colWidths.sum()
        val totalTableHeight = rowHeights.sum()

        val naturalPageWidth = maxOf(limits.minPageWidth, totalTableWidth + (2 * limits.margin))
        val naturalPageHeight = maxOf(limits.minPageHeight, limits.headerHeight + totalTableHeight + (2 * limits.margin))

        val mergedGeometries = sheet.mergedRegions.mapNotNull { region ->
            if (maxCol == 0 || rows.isEmpty()) return@mapNotNull null
            val fCol = region.firstCol.coerceIn(0, maxCol - 1)
            val lCol = region.lastCol.coerceIn(0, maxCol - 1)
            val fRow = region.firstRow.coerceIn(0, rows.size - 1)
            val lRow = region.lastRow.coerceIn(0, rows.size - 1)
            if (lCol < fCol || lRow < fRow) return@mapNotNull null

            val mWidth = (fCol..lCol).sumOf { colWidths[it].toDouble() }.toFloat()
            val mHeight = (fRow..lRow).sumOf { rowHeights[it].toDouble() }.toFloat()
            val mText = rows.getOrNull(fRow)?.getOrNull(fCol) ?: ""
            val mWrapped = if (mText.isNotBlank()) {
                wrapCellText(mText, fontNormal, limits.fontSizeCell, (mWidth - 8f).coerceAtLeast(10f))
            } else {
                emptyList()
            }
            MergedCellGeometry(
                range = region,
                firstCol = fCol,
                lastCol = lCol,
                firstRow = fRow,
                lastRow = lRow,
                text = mText,
                wrappedLines = mWrapped,
                totalWidth = mWidth,
                totalHeight = mHeight
            )
        }

        val frozenCols = if (sheet.frozenColumns > 0) {
            minOf(sheet.frozenColumns, maxCol)
        } else if (maxCol > 0 && limits.repeatFrozenColumnsInTiling) {
            1
        } else {
            0
        }

        return SheetLayout(
            name = sheet.name,
            index = sheetIndex,
            rows = rows,
            rowCount = rows.size,
            colCount = maxCol,
            colWidths = colWidths,
            colX = colX,
            rowHeights = rowHeights,
            rowY = rowY,
            rowWrappedLines = rowWrappedLines,
            totalTableWidth = totalTableWidth,
            totalTableHeight = totalTableHeight,
            naturalPageWidth = naturalPageWidth,
            naturalPageHeight = naturalPageHeight,
            mergedRegions = sheet.mergedRegions,
            mergedGeometries = mergedGeometries,
            frozenColumnsCount = frozenCols
        )
    }

    /**
     * Decides between Level 1 (Natural), Level 2 (Extended /UserUnit), and Level 3 (Tiled).
     */
    fun selectStrategy(layout: SheetLayout, limits: LayoutLimits): ExportStrategy {
        if (limits.forceStrategy != null) {
            return limits.forceStrategy
        }

        val effectiveMaxTileW = limits.maxTileWidthOverride ?: limits.maxTileWidth
        val effectiveMaxTileH = limits.maxTileHeightOverride ?: limits.maxTileHeight

        // If explicitly restricted by tile overrides, trigger tiling
        if (limits.maxTileWidthOverride != null && layout.naturalPageWidth > effectiveMaxTileW) {
            return ExportStrategy.TILED
        }
        if (limits.maxTileHeightOverride != null && layout.naturalPageHeight > effectiveMaxTileH) {
            return ExportStrategy.TILED
        }

        val exceedsStandard = layout.naturalPageWidth > limits.maxStandardCoord ||
            layout.naturalPageHeight > limits.maxStandardCoord

        if (!exceedsStandard) {
            return ExportStrategy.NATURAL_SIZE
        }

        if (limits.allowExtendedSize &&
            layout.naturalPageWidth <= limits.maxExtendedCoord &&
            layout.naturalPageHeight <= limits.maxExtendedCoord
        ) {
            return ExportStrategy.EXTENDED_SIZE
        }

        return ExportStrategy.TILED
    }

    /**
     * Creates the complete export execution plan.
     */
    fun createExportPlan(
        sheet: OpenXmlDocumentParser.XlsxSheetData,
        sheetIndex: Int,
        fontNormal: PDFont,
        limits: LayoutLimits
    ): ExportPlan {
        val layout = buildSheetLayout(sheet, sheetIndex, fontNormal, limits)
        val strategy = selectStrategy(layout, limits)

        return when (strategy) {
            ExportStrategy.NATURAL_SIZE -> {
                ExportPlan(
                    sheetLayout = layout,
                    strategy = strategy,
                    userUnit = 1.0f,
                    pageWidth = layout.naturalPageWidth,
                    pageHeight = layout.naturalPageHeight
                )
            }
            ExportStrategy.EXTENDED_SIZE -> {
                val scaleX = if (layout.naturalPageWidth > limits.maxStandardCoord) {
                    layout.naturalPageWidth / limits.maxStandardCoord
                } else {
                    1f
                }
                val scaleY = if (layout.naturalPageHeight > limits.maxStandardCoord) {
                    layout.naturalPageHeight / limits.maxStandardCoord
                } else {
                    1f
                }
                val rawUserUnit = ceil(maxOf(scaleX, scaleY)).coerceAtLeast(1f)
                val userUnit = minOf(rawUserUnit, 75000f / limits.maxStandardCoord)
                val scaledWidth = layout.naturalPageWidth / userUnit
                val scaledHeight = layout.naturalPageHeight / userUnit

                ExportPlan(
                    sheetLayout = layout,
                    strategy = strategy,
                    userUnit = userUnit,
                    pageWidth = scaledWidth,
                    pageHeight = scaledHeight
                )
            }
            ExportStrategy.TILED -> {
                val tiles = planTiles(layout, limits)
                ExportPlan(
                    sheetLayout = layout,
                    strategy = strategy,
                    userUnit = 1.0f,
                    tiles = tiles
                )
            }
        }
    }

    /**
     * Partitions columns into groups that fit horizontally on tiles.
     */
    fun partitionColumns(layout: SheetLayout, limits: LayoutLimits, availWidth: Float): List<TileColumnGroup> {
        val groups = mutableListOf<TileColumnGroup>()
        val maxCol = layout.colCount
        if (maxCol == 0) return emptyList()

        val effectiveMaxTileW = limits.maxTileWidthOverride ?: limits.maxTileWidth

        val repeatFrozen = (limits.repeatFrozenColumnsInTiling || layout.frozenColumnsCount > 0) &&
            layout.frozenColumnsCount > 0 &&
            maxCol > layout.frozenColumnsCount

        val frozenCols = if (repeatFrozen) {
            (0 until min(layout.frozenColumnsCount, maxCol)).toList()
        } else {
            emptyList()
        }
        val frozenWidth = frozenCols.sumOf { layout.colWidths[it].toDouble() }.toFloat()

        var colIdx = 0
        while (colIdx < maxCol) {
            val activeCols = mutableListOf<Int>()
            var groupWidth = 0f
            val baseAvail = if (colIdx > 0 && repeatFrozen) {
                (availWidth - frozenWidth).coerceAtLeast(45f)
            } else {
                availWidth
            }

            while (colIdx < maxCol) {
                val nextW = layout.colWidths[colIdx]
                // If this is the first column in the panel, take it even if it exceeds baseAvail
                if (activeCols.isEmpty() || groupWidth + nextW <= baseAvail) {
                    activeCols.add(colIdx)
                    groupWidth += nextW
                    colIdx++
                } else {
                    break
                }
            }

            val groupTotalTableWidth = if (repeatFrozen && activeCols.first() > 0) {
                frozenWidth + groupWidth
            } else {
                groupWidth
            }

            val maxAllowedWidth = minOf(effectiveMaxTileW, limits.maxStandardCoord)
            val tileWidth = minOf(maxAllowedWidth, maxOf(limits.minPageWidth, groupTotalTableWidth + (2 * limits.margin)))

            groups.add(
                TileColumnGroup(
                    groupIndex = groups.size,
                    totalGroups = 0, // Assigned after loop
                    startCol = activeCols.first(),
                    endCol = activeCols.last(),
                    frozenCols = if (repeatFrozen && activeCols.first() > 0) frozenCols else emptyList(),
                    activeCols = activeCols,
                    groupTableWidth = groupTotalTableWidth,
                    tileWidth = tileWidth,
                    hasLeftContinuation = groups.isNotEmpty(),
                    hasRightContinuation = colIdx < maxCol
                )
            )
        }

        val total = groups.size
        return groups.map { it.copy(totalGroups = total) }
    }

    /**
     * Partitions rows into vertical groups that fit on tiles.
     * Oversized rows exceeding availHeight are automatically chunked into contiguous vertical fragments.
     */
    fun partitionRows(layout: SheetLayout, limits: LayoutLimits, availHeight: Float): List<TileRowGroup> {
        val groups = mutableListOf<TileRowGroup>()
        val rowCount = layout.rowCount
        if (rowCount == 0) return emptyList()

        val effectiveMaxTileH = limits.maxTileHeightOverride ?: limits.maxTileHeight
        val maxAllowedHeight = minOf(effectiveMaxTileH, limits.maxStandardCoord)

        var rIdx = 0
        var currentChunkStartLine = 0

        while (rIdx < rowCount) {
            val totalLinesInRow = layout.rowWrappedLines[rIdx].maxOfOrNull { it.size } ?: 1
            val totalRowH = layout.rowHeights[rIdx]

            if (totalRowH > availHeight) {
                // Oversized row: split into vertical chunks across panels
                val maxFittingLines = ((availHeight - limits.paddingVertical) / limits.lineHeight).toInt().coerceAtLeast(1)
                val linesInChunk = minOf(totalLinesInRow - currentChunkStartLine, maxFittingLines)
                val chunkHeight = maxOf(20f, linesInChunk * limits.lineHeight + limits.paddingVertical)

                val tileHeight = minOf(
                    maxAllowedHeight,
                    maxOf(
                        limits.minPageHeight,
                        limits.headerHeight + chunkHeight + (2 * limits.margin) + (limits.bannerHeight * 2)
                    )
                )

                val isLastChunkOfRow = currentChunkStartLine + linesInChunk >= totalLinesInRow
                val hasDown = !isLastChunkOfRow || (rIdx + 1 < rowCount)

                groups.add(
                    TileRowGroup(
                        groupIndex = groups.size,
                        totalGroups = 0,
                        startRow = rIdx,
                        endRow = rIdx,
                        startLineOfStartRow = currentChunkStartLine,
                        linesInStartRow = linesInChunk,
                        groupTableHeight = chunkHeight,
                        tileHeight = tileHeight,
                        hasDownContinuation = hasDown
                    )
                )

                if (isLastChunkOfRow) {
                    currentChunkStartLine = 0
                    rIdx++
                } else {
                    currentChunkStartLine += linesInChunk
                }
            } else {
                var groupHeight = 0f
                val startRow = rIdx

                while (rIdx < rowCount) {
                    val nextH = layout.rowHeights[rIdx]
                    if (rIdx == startRow || (nextH <= availHeight && groupHeight + nextH <= availHeight)) {
                        groupHeight += nextH
                        rIdx++
                    } else {
                        break
                    }
                }

                val tileHeight = minOf(
                    maxAllowedHeight,
                    maxOf(
                        limits.minPageHeight,
                        limits.headerHeight + groupHeight + (2 * limits.margin) + (limits.bannerHeight * 2)
                    )
                )

                groups.add(
                    TileRowGroup(
                        groupIndex = groups.size,
                        totalGroups = 0,
                        startRow = startRow,
                        endRow = rIdx - 1,
                        startLineOfStartRow = 0,
                        linesInStartRow = -1,
                        groupTableHeight = groupHeight,
                        tileHeight = tileHeight,
                        hasDownContinuation = rIdx < rowCount
                    )
                )
            }
        }

        val total = groups.size
        return groups.map { it.copy(totalGroups = total) }
    }

    /**
     * Plans all 2D tiles in row-major sequence.
     */
    fun planTiles(layout: SheetLayout, limits: LayoutLimits): List<SheetTile> {
        val effectiveMaxTileW = limits.maxTileWidthOverride ?: limits.maxTileWidth
        val effectiveMaxTileH = limits.maxTileHeightOverride ?: limits.maxTileHeight

        val availWidth = (effectiveMaxTileW - (2 * limits.margin)).coerceAtLeast(100f)
        val availHeight = (effectiveMaxTileH - (2 * limits.margin) - limits.headerHeight - (limits.bannerHeight * 2)).coerceAtLeast(100f)

        val colGroups = partitionColumns(layout, limits, availWidth)
        val rowGroups = partitionRows(layout, limits, availHeight)

        if (colGroups.isEmpty() || rowGroups.isEmpty()) {
            return emptyList()
        }

        val tiles = mutableListOf<SheetTile>()
        // Row-major sequence: for each row group, export all horizontal column groups left-to-right
        for (rGroup in rowGroups) {
            for (cGroup in colGroups) {
                tiles.add(
                    SheetTile(
                        sheetName = layout.name,
                        sheetIndex = layout.index,
                        rowGroup = rGroup,
                        colGroup = cGroup,
                        tilePageWidth = cGroup.tileWidth,
                        tilePageHeight = rGroup.tileHeight
                    )
                )
            }
        }
        return tiles
    }

    // --- Helper text wrapping & font metrics ---

    private fun sanitizeForFontMetrics(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            val code = char.code
            if (code in 32..126 || code in 160..255) {
                sb.append(char)
            } else {
                sb.append(' ')
            }
        }
        return sb.toString()
    }

    fun wrapCellText(text: String, font: PDFont, fontSize: Float, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val rawParagraphs = text.split(Regex("\r\n|\r|\n"))
        val lines = mutableListOf<String>()

        for (paragraph in rawParagraphs) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            val words = paragraph.split(Regex("[ \t]+"))
            var currentLine = StringBuilder()

            for (word in words) {
                if (word.isEmpty()) continue
                val cleanWord = sanitizeForFontMetrics(word)
                val wordWidth = try {
                    font.getStringWidth(cleanWord) / 1000f * fontSize
                } catch (_: Exception) {
                    cleanWord.length * 5f
                }

                if (wordWidth > maxWidth && maxWidth > 10f) {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                        currentLine = StringBuilder()
                    }
                    var remaining = word
                    while (remaining.isNotEmpty()) {
                        var low = 1
                        var high = remaining.length
                        var bestLen = 1
                        while (low <= high) {
                            val mid = (low + high) ushr 1
                            val candidate = sanitizeForFontMetrics(remaining.substring(0, mid))
                            val w = try {
                                font.getStringWidth(candidate) / 1000f * fontSize
                            } catch (_: Exception) {
                                candidate.length * 5f
                            }
                            if (w <= maxWidth) {
                                bestLen = mid
                                low = mid + 1
                            } else {
                                high = mid - 1
                            }
                        }
                        lines.add(remaining.substring(0, bestLen))
                        remaining = remaining.substring(bestLen)
                    }
                    continue
                }

                val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                val cleanTestLine = sanitizeForFontMetrics(testLine)
                val width = try {
                    font.getStringWidth(cleanTestLine) / 1000f * fontSize
                } catch (_: Exception) {
                    cleanTestLine.length * 5f
                }

                if (width <= maxWidth) {
                    currentLine = StringBuilder(testLine)
                } else {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                    }
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
        }

        return lines
    }
}
