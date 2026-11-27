package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test

class XlsxColorsTest {
    @Test fun defaultAndCustomPaletteRemainDistinct() {
        assertEquals(0xFF0000, XlsxColors.indexed(2, null) ?: -1)
        assertEquals(0xFF0000, XlsxColors.indexed(10, null) ?: -1)
        assertEquals(0x333333, XlsxColors.indexed(63, null) ?: -1)
        assertNull(XlsxColors.indexed(64, null))
        assertNull(XlsxColors.indexed(65, null))
        assertEquals(0x112233, XlsxColors.indexed(0, listOf(0x112233)) ?: -1)
        assertNull(XlsxColors.indexed(1, listOf(0x112233)))
    }

    @Test fun tintPreservesHueAndSaturationRatherThanMixingRgbChannels() {
        assertEquals(0x8080FF, XlsxColors.tint(0x0000FF, 0.5))
        assertEquals(0x000080, XlsxColors.tint(0x0000FF, -0.5))
        assertEquals(0x808080, XlsxColors.tint(0x000000, 0.5))
        assertEquals(0x808080, XlsxColors.tint(0xFFFFFF, -0.5))
        assertEquals(0xFFFFFF, XlsxColors.tint(0x123456, 1.0))
        assertEquals(0, XlsxColors.tint(0x123456, -1.0))
        assertEquals(0x123456, XlsxColors.tint(0x123456, 0.0))
        assertEquals(0x3399FF, XlsxColors.tint(0x007FFF, 0.2))
        assertThrows(IllegalArgumentException::class.java) { XlsxColors.tint(0x123456, Double.NaN) }
    }
}
