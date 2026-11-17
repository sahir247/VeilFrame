package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MultiPersonMattingEngineTest {
    @Test
    fun `strip fusion preserves max alpha on odd-sized ROIs and all stripe boundaries`() {
        val base = Bitmap.createBitmap(19, 141, Bitmap.Config.ARGB_8888)
        val subject = Bitmap.createBitmap(11, 129, Bitmap.Config.ARGB_8888)
        val originalAlpha = IntArray(base.width * base.height)

        for (y in 0 until base.height) {
            for (x in 0 until base.width) {
                val alpha = (x * 19 + y * 7) % 256
                originalAlpha[y * base.width + x] = alpha
                base.setPixel(x, y, Color.argb(alpha, 255, 255, 255))
            }
        }
        for (y in 0 until subject.height) {
            for (x in 0 until subject.width) {
                subject.setPixel(x, y, Color.argb((x * 31 + y * 11) % 256, 255, 255, 255))
            }
        }

        try {
            val left = 4
            val top = 7
            MultiPersonMattingEngine.mergeAlphaRegion(base, subject, left, top, maxStripRows = 9)

            for (y in 0 until base.height) {
                for (x in 0 until base.width) {
                    val original = originalAlpha[y * base.width + x]
                    val expected = if (x in left until left + subject.width &&
                        y in top until top + subject.height) {
                        maxOf(original, Color.alpha(subject.getPixel(x - left, y - top)))
                    } else original
                    assertEquals("Alpha mismatch at ($x, $y)", expected, Color.alpha(base.getPixel(x, y)))
                }
            }
        } finally {
            base.recycle()
            subject.recycle()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `strip fusion rejects ROI outside global mask`() {
        val base = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val subject = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        try {
            MultiPersonMattingEngine.mergeAlphaRegion(base, subject, 3, 2)
        } finally {
            base.recycle()
            subject.recycle()
        }
    }
}
