package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskPostprocessorTest {

    @Test
    fun testApplyMaskBasicNormalization() {
        val src = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.BLUE)

        // Half 0f (background), half 1f (foreground)
        val rawMask = FloatArray(100) { i -> if (i >= 50) 1.0f else 0.0f }
        val output = MaskPostprocessor.applyMask(src, rawMask, 10, 10, applySigmoid = false)

        assertEquals(10, output.width)
        assertEquals(10, output.height)

        // Top pixel should be transparent (alpha == 0)
        val topAlpha = Color.alpha(output.getPixel(0, 0))
        assertEquals(0, topAlpha)

        // Bottom pixel should be fully opaque (alpha == 255)
        val bottomAlpha = Color.alpha(output.getPixel(0, 9))
        assertEquals(255, bottomAlpha)
    }

    @Test
    fun testTightenEdgesErodesAlpha() {
        val src = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        // Fill a 10x10 square from (5,5) to (14,14) with opaque black
        for (y in 5..14) {
            for (x in 5..14) {
                src.setPixel(x, y, Color.BLACK)
            }
        }

        // Before tightening, both (5,5) and (10,10) have alpha 255
        assertEquals(255, Color.alpha(src.getPixel(5, 5)))
        assertEquals(255, Color.alpha(src.getPixel(10, 10)))

        val tightened = MaskPostprocessor.tightenEdges(src, radius = 1)
        // After 1px erosion, boundary pixel (5,5) has neighbor (4,5) with alpha 0, so (5,5) becomes 0
        assertEquals(0, Color.alpha(tightened.getPixel(5, 5)))
        // Interior pixel (10,10) has all neighbors within 5..14, so it remains alpha 255
        assertEquals(255, Color.alpha(tightened.getPixel(10, 10)))
    }

    @Test
    fun testFeatherEdgesBlursAlpha() {
        val src = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        for (y in 5..14) {
            for (x in 5..14) {
                src.setPixel(x, y, Color.BLACK)
            }
        }

        val feathered = MaskPostprocessor.featherEdges(src, radius = 1)
        // Border pixel adjacent to opaque should have intermediate smoothed alpha
        val alphaAtBorder = Color.alpha(feathered.getPixel(5, 5))
        assertTrue("Alpha should be smoothed between 0 and 255", alphaAtBorder in 1..254)
    }

    @Test
    fun testTrimTransparentCropsToSubject() {
        val src = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        // Set a 10x10 block from (20,20) to (29,29)
        for (y in 20..29) {
            for (x in 20..29) {
                src.setPixel(x, y, Color.RED)
            }
        }

        val trimmed = MaskPostprocessor.trimTransparent(src)
        assertEquals(10, trimmed.width)
        assertEquals(10, trimmed.height)
        assertEquals(Color.RED, trimmed.getPixel(0, 0))
    }

    @Test
    fun testNegativeMaskTensorNormalization() {
        val src = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.GREEN)

        // Raw mask with entirely negative values, e.g. logits from -10.0f to -2.0f
        val rawMask = FloatArray(25) { i -> -10.0f + (i * (8.0f / 24.0f)) }
        val output = MaskPostprocessor.applyMask(src, rawMask, 5, 5, applySigmoid = false)

        // First pixel (-10.0f) should be normalized to min (alpha 0)
        assertEquals(0, Color.alpha(output.getPixel(0, 0)))
        // Last pixel (-2.0f) should be normalized to max (alpha 255)
        assertEquals(255, Color.alpha(output.getPixel(4, 4)))
    }

    @Test
    fun testTrimTransparentAllTransparent() {
        val src = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) // all transparent
        val trimmed = MaskPostprocessor.trimTransparent(src)
        assertEquals(20, trimmed.width)
        assertEquals(20, trimmed.height)
        assertEquals(0, Color.alpha(trimmed.getPixel(0, 0)))
    }

    @Test
    fun testTrimTransparentMultiStripeScanning() {
        // Test image taller than STRIPE_ROWS (256)
        val src = Bitmap.createBitmap(20, 300, Bitmap.Config.ARGB_8888)
        src.setPixel(5, 10, Color.BLUE)
        src.setPixel(15, 290, Color.BLUE)

        val trimmed = MaskPostprocessor.trimTransparent(src)
        assertEquals(11, trimmed.width) // 15 - 5 + 1
        assertEquals(281, trimmed.height) // 290 - 10 + 1
    }
}
