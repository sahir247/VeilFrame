package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import com.veilframe.app.pdf.operations.XlsxCachedChart.BlankDisplay

class XlsxChartBlankDisplayTest {
    @Test fun gapBreaksLinesZeroDrawsZeroSpanConnectsWithoutInventingPoints() {
        assertNull(BlankDisplay.GAP.lineValue(null))
        assertTrue(BlankDisplay.GAP.breakOnMissing())
        assertEquals(0.0, BlankDisplay.ZERO.lineValue(null)!!, 0.0)
        assertNull(BlankDisplay.SPAN.lineValue(null))
        assertFalse(BlankDisplay.SPAN.breakOnMissing())
        for (policy in BlankDisplay.entries) {
            assertEquals(-3.5, policy.lineValue(-3.5)!!, 0.0)
        }
    }
}
