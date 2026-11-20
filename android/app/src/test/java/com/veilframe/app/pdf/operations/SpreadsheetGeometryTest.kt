package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test

class SpreadsheetGeometryTest {
    @Test fun rejectsMalformedReversedAndOutOfRangeMerges() {
        for (ref in listOf("A:B2", "A1:B2:C3", "B2:A1", "A0:B2", "A1:XFE2", "A1:B1048577", "A1:A1")) {
            assertThrows(IllegalArgumentException::class.java) { OpenXmlDocumentParser.parseMergeRange(ref) }
        }
        assertEquals(OpenXmlDocumentParser.CellRangeAddress(0, 199, 0, 49),
            OpenXmlDocumentParser.parseMergeRange("A1:AX200"))
    }

    @Test fun sparseFarApartCoordinatesCannotAllocateHugeRectangle() {
        val rows = List(2000) { if (it == 1999) List(16_384) { "" } else emptyList<String>() }
        assertThrows(IllegalArgumentException::class.java) { SpreadsheetGeometry.inspect(rows, emptyList()) }
    }

    @Test fun mergeExtentsIncludeBlankRowsAndColumnsAndRejectConflictingContent() {
        val merge = OpenXmlDocumentParser.parseMergeRange("A1:C4")
        val extent = SpreadsheetGeometry.inspect(listOf(listOf("title")), listOf(merge))
        assertEquals(4, extent.rows)
        assertEquals(3, extent.columns)
        assertEquals(12L, extent.cells)
        assertThrows(IllegalArgumentException::class.java) {
            SpreadsheetGeometry.inspect(listOf(listOf("title", "different")), listOf(merge))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpreadsheetGeometry.inspect(listOf(listOf("title")), listOf(merge,
                OpenXmlDocumentParser.parseMergeRange("B2:D5")))
        }
    }

    @Test fun repeatedSharedStringReferencesCountExpandedTextNotUniqueStrings() {
        val text = "a".repeat(100_000)
        val rows = List(81) { listOf(text) }
        assertThrows(IllegalArgumentException::class.java) { SpreadsheetGeometry.inspect(rows, emptyList()) }
    }
}
