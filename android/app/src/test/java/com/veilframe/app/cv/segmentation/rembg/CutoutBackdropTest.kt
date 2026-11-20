package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.*
import org.junit.Test

class CutoutBackdropTest {
    @Test fun whiteAndBlackCompositeStraightAlphaWithoutHiddenRgbLeak() {
        val original = intArrayOf(0x00ABCDEF, 0x80FF0000.toInt(), 0xFF123456.toInt())
        val white = original.copyOf()
        val black = original.copyOf()
        CutoutBackdrop.WHITE.apply(white)
        CutoutBackdrop.BLACK.apply(black)
        assertArrayEquals(intArrayOf(-1, 0xFFFF7F7F.toInt(), 0xFF123456.toInt()), white)
        assertArrayEquals(intArrayOf(0xFF000000.toInt(), 0xFF800000.toInt(), 0xFF123456.toInt()), black)
        CutoutBackdrop.TRANSPARENT.apply(original)
        assertEquals(0x00ABCDEF, original.first())
    }

    @Test fun everyAlphaProducesAnOpaqueMonotonicResult() {
        val pixels = IntArray(256) { (it shl 24) or 0x00FFFFFF }
        CutoutBackdrop.BLACK.apply(pixels)
        for (i in pixels.indices) {
            assertEquals(0xFF000000.toInt() or (i * 0x010101), pixels[i])
        }
    }
}
