package com.veilframe.app.pdf

import com.veilframe.app.pdf.operations.PageRange
import org.junit.Assert.*
import org.junit.Test

class PdfSplitRangeParserTest {
    @Test fun validPartsPreserveRequestedOrderAndNormalizeEndpoints() {
        assertEquals(listOf(PageRange(1,2),PageRange(5,5),PageRange(3,4)),
            PdfSplitRangeParser.parse("1-2, 5; 4-3",5))
    }
    @Test fun oneInvalidPartRejectsTheEntireRequest() {
        for (input in listOf("1-2,9","1-2,typo","2-","0,1","1-2,2147483648","1-2,3-4-5")) {
            assertTrue(input,PdfSplitRangeParser.parse(input,5).isEmpty())
        }
    }
}
