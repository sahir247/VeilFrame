package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrConfidenceTest {
    @Test
    fun aggregatesActualWordConfidence() {
        val words = listOf(OcrWord("first", null, 0.2f), OcrWord("second", null, 0.6f))
        assertEquals(0.4f, pageOcrConfidence(words) ?: -1f, 0.0001f)
    }

    @Test
    fun missingOrInvalidConfidenceIsUnknown() {
        assertNull(pageOcrConfidence(emptyList()))
        assertNull(pageOcrConfidence(listOf(OcrWord("word", null, null))))
        assertNull(pageOcrConfidence(listOf(OcrWord("good", null, 0.95f), OcrWord("unknown", null, null))))
        assertNull(pageOcrConfidence(listOf(OcrWord("invalid", null, Float.NaN))))
    }
}
