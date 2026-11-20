package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class CelBackgroundRemoverTest {

    @Test
    fun originalResolutionBudgetRejectsUnboundedAllocations() {
        assertEquals(true, RembgImageUtils.canCompositeOriginal(6000, 4000, 512L * 1024 * 1024))
        assertEquals(false, RembgImageUtils.canCompositeOriginal(6000, 4000, 128L * 1024 * 1024))
        assertEquals(false, RembgImageUtils.canCompositeOriginal(30000, 30000, Long.MAX_VALUE))
    }

    @Test
    fun scaledMaskPreservesOriginalTransparency() {
        val src = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        src.setPixel(3, 0, Color.argb(128, 255, 0, 0))
        val mask = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        mask.setPixel(0, 0, Color.TRANSPARENT)
        mask.setPixel(1, 0, Color.BLACK)
        val output = CelBackgroundRemover.applyFullResolutionColors(src, mask)
        assertEquals(4, output.width)
        assertEquals(0, Color.alpha(output.getPixel(0, 0)))
        assertEquals(64, Color.alpha(output.getPixel(1, 0)))
        assertEquals(191, Color.alpha(output.getPixel(2, 0)))
        assertEquals(128, Color.alpha(output.getPixel(3, 0)))
    }

    @Test
    fun testApplyFullResolutionColorsPreservesAlpha() {
        val src = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)

        val alphaMask = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        alphaMask.eraseColor(Color.TRANSPARENT)
        alphaMask.setPixel(0, 0, Color.BLACK) // Alpha 255

        val output = CelBackgroundRemover.applyFullResolutionColors(src, alphaMask)

        assertEquals(10, output.width)
        assertEquals(10, output.height)
        assertEquals(255, Color.alpha(output.getPixel(0, 0)))
        assertEquals(0, Color.alpha(output.getPixel(1, 1)))
        assertEquals(Color.red(Color.RED), Color.red(output.getPixel(0, 0)))
    }
}
