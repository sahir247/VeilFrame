package com.veilframe.app.upscale

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.upscale.inference.AdaptiveDetailEnhancer
import com.veilframe.app.upscale.inference.AlgorithmicUpscaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdaptiveDetailEnhancerTest {

    @Test
    fun testEnhancerZeroStrengthBypasses() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val result = AdaptiveDetailEnhancer.enhance(bmp, strength = 0.0f)
        // Zero strength should immediately return original bitmap instance without allocation
        assertTrue(result === bmp)
        bmp.recycle()
    }

    @Test
    fun testEnhancerPreservesDimensions() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { color = Color.BLUE }
        canvas.drawRect(0f, 0f, 32f, 32f, paint)
        paint.color = Color.YELLOW
        canvas.drawRect(32f, 32f, 64f, 64f, paint)

        val enhanced = AdaptiveDetailEnhancer.enhance(bmp, strength = 0.35f)
        assertNotNull(enhanced)
        assertEquals(64, enhanced.width)
        assertEquals(64, enhanced.height)
        if (enhanced !== bmp) {
            enhanced.recycle()
        }
        bmp.recycle()
    }

    @Test
    fun testScaleLanczosFallbackPreservesDimensions() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GREEN)
        val scaled = AlgorithmicUpscaler.scaleLanczos(bmp, 64, 64)
        assertNotNull(scaled)
        assertEquals(64, scaled.width)
        assertEquals(64, scaled.height)
        scaled.recycle()
        bmp.recycle()
    }
}
