package com.veilframe.app.upscale

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.upscale.inference.AdaptiveDetailEnhancer
import com.veilframe.app.upscale.inference.UpscaleBandPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamingImagePostProcessorTest {
    @Test fun disjointCoreRowsCoverOddDimensionsExactly() {
        val plan = UpscaleBandPlan(259, 3, 64, 5)
        val bands = plan.bands().toList()
        assertEquals(5, plan.count)
        var expectedTop = 0
        bands.forEach { band ->
            assertEquals(expectedTop, band.readTop * 3 + band.outputOffset)
            assertTrue(band.outputOffset + band.outputRows <= band.readHeight * 3)
            expectedTop += band.outputRows
        }
        assertEquals(777, expectedTop)
        assertEquals(0, bands.first().readTop)
        assertEquals(259, bands.last().readTop + bands.last().readHeight)
    }

    @Test fun haloDetailMatchesWholeImageAcrossEveryBand() {
        val w = 13
        val h = 19
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { i ->
            val y = i / w
            val x = i % w
            Color.argb(if (x % 3 == 0) 128 else 255, (y * 53 + x * 11) % 256,
                (y * 17 + x * 29) % 256, (y * 7 + x * 47) % 256)
        }
        source.setPixels(pixels, 0, w, 0, 0, w, h)
        val whole = AdaptiveDetailEnhancer.enhanceScanlines(source, .7f)
        try {
            var y = 0
            for (band in UpscaleBandPlan(h, 1, 4, 1).bands()) {
                val input = Bitmap.createBitmap(source, 0, band.readTop, w, band.readHeight)
                val result = AdaptiveDetailEnhancer.enhanceScanlines(input, .7f)
                try {
                    for (row in 0 until band.outputRows) {
                        for (x in 0 until w) {
                            assertEquals("pixel ($x,$y)", whole.getPixel(x, y), result.getPixel(x, band.outputOffset + row))
                            assertEquals(Color.alpha(source.getPixel(x, y)), Color.alpha(result.getPixel(x, band.outputOffset + row)))
                        }
                        y++
                    }
                } finally {
                    result.recycle()
                    input.recycle()
                }
            }
            assertEquals(h, y)
        } finally {
            whole.recycle()
            source.recycle()
        }
    }
}
