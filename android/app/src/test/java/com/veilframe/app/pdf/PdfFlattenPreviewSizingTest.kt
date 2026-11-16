package com.veilframe.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFlattenPreviewSizingTest {
    @Test
    fun letterPageUsesScreenSizedPreview() {
        assertEquals(108f, PdfFlattenPreviewSizing.dpi(612f, 792f), 0.01f)
    }

    @Test
    fun largeFormatPageIsCappedToMaximumEdge() {
        val dpi = PdfFlattenPreviewSizing.dpi(1728f, 2592f)
        assertTrue(1728f * dpi / 72f <= 1280.1f)
        assertTrue(2592f * dpi / 72f <= 1280.1f)
    }

    @Test
    fun invalidPageDimensionsUseSafeDefault() {
        assertEquals(72f, PdfFlattenPreviewSizing.dpi(0f, 792f), 0f)
        assertEquals(72f, PdfFlattenPreviewSizing.dpi(612f, -1f), 0f)
    }
}
