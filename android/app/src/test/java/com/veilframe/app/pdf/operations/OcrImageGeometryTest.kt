package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test

class OcrImageGeometryTest {
    @Test fun samplingAvoidsOverflowAndCapsSkinnyImages() {
        for ((w,h) in listOf(Int.MAX_VALUE to Int.MAX_VALUE, 100000 to 10, 100000 to 100000, 4032 to 3024)) {
            val sample = OcrImageGeometry.sampleSize(w,h,4_000_000)
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
            val x = (w.toLong()+sample-1)/sample
            val y = (h.toLong()+sample-1)/sample
            assertTrue(x <= OcrDpiUtil.MAX_EDGE && y <= OcrDpiUtil.MAX_EDGE && x*y <= 4_000_000)
        }
    }

    @Test fun wrapperNeverStretchesThinOrSmallImages() {
        for ((w,h) in listOf(40 to 1200, 1 to 8192, 100 to 100, 2000 to 3000)) {
            val (x,y) = OcrImageGeometry.pdfPoints(w,h)
            assertEquals(w.toDouble()/h, x.toDouble()/y, 1e-5)
            assertTrue(x > 0 && y > 0 && x <= 3000 && y <= 3000)
        }
    }

    @Test fun unexpectedDecoderAllocationReleasesBitmapBeforeFailure() {
        var released = false
        assertThrows(java.io.IOException::class.java) {
            OcrImageGeometry.requireDecodedDimensions(5000,5000) { released = true }
        }
        assertTrue(released)
    }

    @Test fun scriptsAreExplicitAndIncludeAllBundledRecognitionOptions() {
        assertEquals(setOf("LATIN","DEVANAGARI","CHINESE","JAPANESE","KOREAN"), OcrLanguage.entries.map { it.name }.toSet())
    }
}
