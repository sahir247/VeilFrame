package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test

class SpreadsheetBorderResolverTest {
    @Test fun sharedEdgePrefersExplicitStrengthAndMergeInteriorIsEmpty() {
        val thin = XlsxBorderLine("thin", 0x123456)
        val thick = XlsxBorderLine("thick", 0x654321)
        val styles = XlsxCellStyles(listOf(XlsxCellStyle(),
            XlsxCellStyle(borders = XlsxCellBorders(right = thin, bottom = thin)),
            XlsxCellStyle(borders = XlsxCellBorders(left = thick, top = thick))),
            mapOf(0 to intArrayOf(1, 2, 0), 1 to intArrayOf(0, 0, 2)))
        val merge = OpenXmlDocumentParser.CellRangeAddress(1, 1, 0, 1)
        val layout = SheetLayout("test", 0, List(2) { List(3) { "" } }, 2, 3,
            floatArrayOf(50f, 50f, 50f), floatArrayOf(0f,50f,100f),
            floatArrayOf(20f,20f), floatArrayOf(0f,20f),
            Array(2) { Array<List<String>>(3) { emptyList() } }, 150f, 40f, 150f,40f,
            mergedRegions = listOf(merge), cellStyles = styles)
        val index = SpreadsheetMergeIndex(2, 3, listOf(merge))
        val resolver = SpreadsheetBorderResolver(layout, index)
        assertEquals(thick, resolver.vertical(0, 0, 1))
        assertEquals(thin, resolver.horizontal(1, 0))
        assertNull(resolver.vertical(1, 0, 1))
        assertEquals(thick, resolver.vertical(1, 1, 2))
        val withoutGrid = SpreadsheetBorderResolver(layout.copy(cellStyles = styles.copy(showGridLines = false)), index)
        assertNull(withoutGrid.horizontal(0, 2))
    }
}
