package com.veilframe.app.pdf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfThumbnailSizingTest {
    @Test
    fun portraitPageFitsWithinRequestedBounds() {
        assertEquals(220 to 285, PdfThumbnailSizing.calculate(612, 792, 220, 1024))
    }

    @Test
    fun landscapePageFitsWidthBound() {
        assertEquals(220 to 170, PdfThumbnailSizing.calculate(792, 612, 220, 1024))
    }

    @Test
    fun extremeTallPageIsBoundedByMaximumHeight() {
        val dimensions = PdfThumbnailSizing.calculate(100, 10_000, 220, 1024)
        assertEquals(10 to 1024, dimensions)
    }

    @Test
    fun invalidPageDimensionsAreRejected() {
        assertNull(PdfThumbnailSizing.calculate(0, 100, 220, 1024))
        assertNull(PdfThumbnailSizing.calculate(100, -1, 220, 1024))
    }
}
