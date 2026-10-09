package com.veilframe.app.cv.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Comprehensive unit tests verifying the mathematical Letterboxing, ROI extraction,
 * bilinear unletterbox interpolation, alpha matting, and tensor decoding of [BgRemovalOnnxEngine].
 */
@RunWith(RobolectricTestRunner::class)
class BgRemovalOnnxEngineTest {

    @Test
    fun testCalculateLetterboxMetaSquare() {
        val meta = BgRemovalOnnxEngine.calculateLetterboxMeta(
            srcW = 1000,
            srcH = 1000,
            targetSize = 1024
        )
        assertEquals(1024f / 1000f, meta.scale, 0.001f)
        assertEquals(0, meta.padX)
        assertEquals(0, meta.padY)
        assertEquals(1024, meta.roiWidth)
        assertEquals(1024, meta.roiHeight)
    }

    @Test
    fun testCalculateLetterboxMetaLandscape() {
        // 1920x1080 (16:9 Landscape) scaled into 1024x1024
        val meta = BgRemovalOnnxEngine.calculateLetterboxMeta(
            srcW = 1920,
            srcH = 1080,
            targetSize = 1024
        )
        // scale = 1024 / 1920 = 0.53333336
        // newW = 1024, newH = 1080 * (1024 / 1920) = 576
        // padX = 0, padY = (1024 - 576) / 2 = 224
        assertEquals(1024f / 1920f, meta.scale, 0.001f)
        assertEquals(0, meta.padX)
        assertEquals(224, meta.padY)
        assertEquals(1024, meta.roiWidth)
        assertEquals(576, meta.roiHeight)
    }

    @Test
    fun testCalculateLetterboxMetaPortrait() {
        // 1080x1920 (9:16 Portrait) scaled into 1024x1024
        val meta = BgRemovalOnnxEngine.calculateLetterboxMeta(
            srcW = 1080,
            srcH = 1920,
            targetSize = 1024
        )
        // newW = 1080 * (1024 / 1920) = 576, newH = 1024
        // padX = (1024 - 576) / 2 = 224, padY = 0
        assertEquals(1024f / 1920f, meta.scale, 0.001f)
        assertEquals(224, meta.padX)
        assertEquals(0, meta.padY)
        assertEquals(576, meta.roiWidth)
        assertEquals(1024, meta.roiHeight)
    }

    @Test
    fun testLetterboxPreprocessBitmap() {
        val src = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)

        val targetSize = 256
        val (padded, meta) = BgRemovalOnnxEngine.letterboxPreprocess(src, targetSize)

        assertEquals(targetSize, padded.width)
        assertEquals(targetSize, padded.height)
        assertEquals(src.width, meta.origW)
        assertEquals(src.height, meta.origH)

        // Top padding area (0, 0) should have neutral padding color (128, 128, 128)
        val padPixel = padded.getPixel(0, 0)
        assertEquals(128, Color.red(padPixel))
        assertEquals(128, Color.green(padPixel))
        assertEquals(128, Color.blue(padPixel))

        // Center should have original image color (RED)
        val centerPixel = padded.getPixel(targetSize / 2, targetSize / 2)
        assertEquals(255, Color.red(centerPixel))
        assertEquals(0, Color.green(centerPixel))
        assertEquals(0, Color.blue(centerPixel))
    }

    @Test
    fun testUnletterboxMaskInterpolation() {
        val origW = 100
        val origH = 50
        val targetSize = 200
        val meta = BgRemovalOnnxEngine.calculateLetterboxMeta(origW, origH, targetSize)

        // Create a synthetic letterbox mask of size 200x200
        val mask = FloatArray(targetSize * targetSize)
        // Fill the ROI area with 1.0f (foreground)
        for (y in meta.padY until (meta.padY + meta.roiHeight)) {
            for (x in meta.padX until (meta.padX + meta.roiWidth)) {
                mask[y * targetSize + x] = 1.0f
            }
        }

        val unletterboxed = BgRemovalOnnxEngine.unletterboxMask(mask, meta)
        assertEquals(origW * origH, unletterboxed.size)

        // Every pixel of the unletterboxed mask should be 1.0f
        for (i in unletterboxed.indices) {
            assertEquals(1.0f, unletterboxed[i], 0.05f)
        }
    }

    @Test
    fun testApplyAlphaMatteProducesTransparentCutout() {
        val width = 100
        val height = 100
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.BLUE)

        val targetSize = 100
        val meta = BgRemovalOnnxEngine.calculateLetterboxMeta(width, height, targetSize)

        // Create mask where center 50x50 is foreground (1.0f) and borders are background (0.0f)
        val mask = FloatArray(targetSize * targetSize)
        for (y in 25 until 75) {
            for (x in 25 until 75) {
                mask[y * targetSize + x] = 1.0f
            }
        }

        val cutout = BgRemovalOnnxEngine.applyAlphaMatte(src, mask, meta)
        assertEquals(width, cutout.width)
        assertEquals(height, cutout.height)

        // Center pixel should be opaque BLUE
        val centerPixel = cutout.getPixel(50, 50)
        assertEquals(255, (centerPixel ushr 24) and 0xFF)
        assertEquals(Color.BLUE, centerPixel)

        // Corner pixel should be transparent (alpha = 0)
        val cornerPixel = cutout.getPixel(5, 5)
        assertEquals(0, (cornerPixel ushr 24) and 0xFF)

        // Verify quality gate audit
        assertTrue(BgRemovalOnnxEngine.verifyAlphaCutout(cutout))
    }

    @Test
    fun testExtractMaskFromOutput4DNesting() {
        val targetSize = 4
        // [1, 1, 4, 4]
        val row = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val plane = arrayOf(row, row, row, row)
        val batch = arrayOf(arrayOf(plane))

        val extracted = BgRemovalOnnxEngine.extractMaskFromOutput(batch, targetSize)
        assertEquals(16, extracted.size)
        assertEquals(0.1f, extracted[0], 0.001f)
        assertEquals(0.4f, extracted[3], 0.001f)
    }

    @Test
    fun testExtractMaskFromOutput3DNesting() {
        val targetSize = 4
        // [1, 4, 4]
        val row = floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f)
        val plane = arrayOf(row, row, row, row)
        val batch = arrayOf(plane)

        val extracted = BgRemovalOnnxEngine.extractMaskFromOutput(batch, targetSize)
        assertEquals(16, extracted.size)
        assertEquals(0.5f, extracted[0], 0.001f)
        assertEquals(0.8f, extracted[3], 0.001f)
    }

    @Test
    fun testExtractMaskFromOutputFlatArray() {
        val targetSize = 4
        val flat = FloatArray(16) { it.toFloat() * 0.1f }

        val extracted = BgRemovalOnnxEngine.extractMaskFromOutput(flat, targetSize)
        assertEquals(16, extracted.size)
        assertEquals(0.0f, extracted[0], 0.001f)
        assertEquals(1.5f, extracted[15], 0.001f)
    }

    @Test
    fun testVerifyAlphaCutoutRejectsBlankOrOpaqueImages() {
        // All opaque white
        val opaque = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        opaque.eraseColor(Color.WHITE)
        assertFalse("100% opaque image has no transparent background", BgRemovalOnnxEngine.verifyAlphaCutout(opaque))

        // All transparent
        val transparent = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        transparent.eraseColor(Color.TRANSPARENT)
        assertFalse("100% transparent image has no foreground subject", BgRemovalOnnxEngine.verifyAlphaCutout(transparent))
    }
}
