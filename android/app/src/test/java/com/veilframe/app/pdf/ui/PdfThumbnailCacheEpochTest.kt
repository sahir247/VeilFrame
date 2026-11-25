package com.veilframe.app.pdf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfThumbnailCacheEpochTest {
    @Test fun oldNativeRenderCannotRepopulateClearedCache() {
        val epoch = PdfThumbnailCacheEpoch()
        val entries = mutableListOf<String>()
        val old = epoch.snapshot()
        assertTrue(epoch.publish(old) { entries.add("first") })
        epoch.invalidate { entries.clear() }
        assertFalse(epoch.publish(old) { entries.add("stale") })
        assertTrue(entries.isEmpty())
        assertTrue(epoch.publish(epoch.snapshot()) { entries.add("fresh") })
        assertEquals(listOf("fresh"),entries)
    }

    @Test fun explicitPreviewRemainsBoundedAt1080By1920() {
        assertEquals(1080 to 1528,PdfThumbnailSizing.calculate(595,842,1080,1920))
        assertEquals(1 to 1920,PdfThumbnailSizing.calculate(1,100000,1080,1920))
    }
}
