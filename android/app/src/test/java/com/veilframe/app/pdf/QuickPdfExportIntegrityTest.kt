package com.veilframe.app.pdf

import org.junit.Assert.*
import org.junit.Test

class QuickPdfExportIntegrityTest {
    @Test fun untrustedDisplayNamesCannotCreatePaths() {
        val stem = QuickPdfExportIntegrity.safeStem("../../private\\secret\n")
        assertFalse(stem.contains('/'))
        assertFalse(stem.contains('\\'))
        assertFalse(stem.contains('\n'))
        assertFalse(stem.startsWith("."))
        assertEquals("document", QuickPdfExportIntegrity.safeStem("..."))
        assertEquals(100, QuickPdfExportIntegrity.safeStem("a".repeat(400)).length)
    }

    @Test fun rejectsMissingDuplicateOutOfOrderAndExcessParts() {
        val sequence = QuickPdfExportIntegrity.Sequence(2)
        sequence.accept(1)
        assertTrue(runCatching { sequence.requireComplete(1) }.isFailure)
        assertTrue(runCatching { sequence.accept(1) }.isFailure)
        assertTrue(runCatching { sequence.accept(3) }.isFailure)
        sequence.accept(2)
        sequence.requireComplete(2)
        assertTrue(runCatching { sequence.requireComplete(3) }.isFailure)
        assertTrue(runCatching { sequence.accept(3) }.isFailure)
    }
}
