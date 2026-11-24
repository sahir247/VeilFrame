package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class OcrRasterBudgetTest {
    @Test fun ordinaryPagesRetainUsefulResolutionAndLargePagesRespectHardBudget() {
        val letter = OcrDpiUtil.getSafeOcrDpi(612f, 792f)
        assertTrue(letter in 190f..200f)
        for ((w,h) in listOf(2384f to 3370f, 7000f to 120f, 120f to 7000f)) {
            val dpi = OcrDpiUtil.getSafeOcrDpi(w,h)
            val width = ceil((w * (dpi/72f)).toDouble()).toLong()
            val height = ceil((h * (dpi/72f)).toDouble()).toLong()
            assertTrue(width <= OcrDpiUtil.MAX_EDGE && height <= OcrDpiUtil.MAX_EDGE)
            assertTrue(width * height <= OcrDpiUtil.MAX_PIXELS)
        }
    }

    @Test fun hostileOrUnreadablyLargeGeometryFailsBeforeAllocating() {
        for ((w,h) in listOf(0f to 10f, Float.NaN to 10f, Float.POSITIVE_INFINITY to 10f,
            1_000_000f to 1_000_000f, 100_000f to 1f)) {
            assertThrows(IllegalArgumentException::class.java) { OcrDpiUtil.getSafeOcrDpi(w,h) }
        }
    }
}
