package com.veilframe.app.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPreviewRequestGateTest {
    @Test fun newerPageAndInvalidationRejectOlderResults() {
        val gate = PdfPreviewRequestGate()
        val first = gate.next()
        val second = gate.next()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        gate.next()
        assertFalse(gate.isCurrent(second))
    }
}
