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
