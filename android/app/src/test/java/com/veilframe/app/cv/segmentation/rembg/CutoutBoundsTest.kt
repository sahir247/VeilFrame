package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.*
import org.junit.Test

class CutoutBoundsTest {
    @Test fun includesSingleAlphaAndIgnoresHiddenRgb() {
        val bounds = CutoutBounds.find(8, 6) { y, row ->
            row.fill(0x00123456)
            if (y == 2) row[3] = 0x01FF0000
            if (y == 4) row[6] = 0xFF000000.toInt()
        }
        assertEquals(CutoutBounds(3, 2, 4, 3), bounds)
    }

    @Test fun entirelyTransparentAndOpaqueKeepFullCanvas() {
        for (color in listOf(0, -1)) {
            assertEquals(CutoutBounds(0, 0, 5, 9), CutoutBounds.find(5, 9) { _, row -> row.fill(color) })
        }
    }

    @Test fun trimUsesMultipliedSourceAlphaNotJustTheMask() {
        val matte = RembgAlphaMatte.fromAlpha(1, 1, byteArrayOf(255.toByte()))
        val sampler = matte.sampler(6, 5)
        val result = CutoutBounds.find(6, 5) { y, row ->
            row.fill(0)
            if (y == 3) row[2] = 0x80123456.toInt()
            sampler.apply(row, y)
        }
        assertEquals(CutoutBounds(2, 3, 1, 1), result)
    }
}
