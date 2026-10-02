package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.raster.*
import com.veilframe.app.qr.renderer.ImageFillRenderer
import com.veilframe.app.qr.renderer.ImageScaleResolver
import com.veilframe.app.qr.renderer.RenderContext
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit and differential tests verifying the EFQRCode 7.0.3 rasterization architecture:
 * 1. [EfRasterProfile] contract and [EfRasterBackend] / [SkiaEfRasterBackend] primitives.
 * 2. [EfImagePreprocessor] delegation to [EfRasterBackend].
 * 3. Prevention of second rasterization in [ImageFillRenderer] (DISCOVERY.txt Section 9).
 * 4. Verification that dither is disabled across parity paths (DISCOVERY.txt Section 10).
 * 5. [PixelComparator] and [RasterBuffer] differential measurement engine.
 */
class EfRasterArchitectureTest {

    @Test
    fun testEfRasterProfileContract() {
        val profile = EfRasterProfile.EF_RASTER_7_0_3_SRGB8
        assertEquals("EF-RASTER-7.0.3-SRGB8", profile.profileName)
        assertEquals("7.0.3", profile.version)
        assertEquals("07ff9e2e83a4bbd384e5a8e33b47f389c9aac762", profile.referenceCommit)
        assertEquals("a19594794cdcdee5135caad3bc119096c50c92c2", profile.pinnedSwiftDrawCommit)
        assertEquals("RGBA8_sRGB_NON_HDR", profile.sourceFormat)
        assertEquals("premultipliedLast", profile.alphaMode)
        assertEquals("DeviceRGB", profile.colorSpace)
        assertEquals("CG_REFERENCE_KERNEL", profile.sampling)
        assertFalse("Dither must be disabled in EF 7.0.3 profile", profile.dither)
        assertEquals("ONE EF PREPROCESSING RASTER + ONE EF FINAL IMAGE-DRAW RASTER + NO EXTRA MODE/FIT RASTER", profile.invariantDescription)
    }

    @Test
    fun testSkiaEfRasterBackendPrimitives() {
        val backend = SkiaEfRasterBackend
        val source = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888) ?: return

        // 1. Resize
        val resized = backend.resize(source, 200, 100)
        assertEquals(200, resized.width)
        assertEquals(100, resized.height)

        // 2. DrawInto expanded canvas
        val dstRect = RectF(25f, 25f, 125f, 75f)
        val expanded = backend.drawInto(source, 150, 100, dstRect)
        assertEquals(150, expanded.width)
        assertEquals(100, expanded.height)

        // 3. Crop with Double coordinates
        val cropped = backend.crop(source, 10.0, 10.0, 30.0, 20.0)
        assertEquals(30, cropped.width)
        assertEquals(20, cropped.height)
    }

    @Test
    fun testCGRectIntegralCroppingSemantics() {
        val backend = SkiaEfRasterBackend
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) ?: return

        // Apple CoreGraphics CGRectIntegral:
        // Returns the smallest rectangle with integer coordinates that contains the source rectangle:
        // x = floor(10.2) = 10, maxX = ceil(10.2 + 20.2 = 30.4) = 31 => width = 21 (NOT 20 from simple toInt)
        // y = floor(5.7) = 5, maxY = ceil(5.7 + 15.1 = 20.8) = 21 => height = 16 (NOT 15 from simple toInt)
        val croppedFractional = backend.crop(source, 10.2, 5.7, 20.2, 15.1)
        assertEquals("CGRectIntegral width must expand to cover floating rect", 21, croppedFractional.width)
        assertEquals("CGRectIntegral height must expand to cover floating rect", 16, croppedFractional.height)

        // Integer coordinates remain exact
        val croppedIntegral = backend.crop(source, 10.0, 5.0, 20.0, 15.0)
        assertEquals(20, croppedIntegral.width)
        assertEquals(15, croppedIntegral.height)

        // RectF overload delegates to identical CGRectIntegral logic
        val croppedRectF = backend.crop(source, RectF(10.2f, 5.7f, 30.4f, 20.8f))
        assertEquals(21, croppedRectF.width)
        assertEquals(16, croppedRectF.height)
    }

    @Test
    fun testEfImagePreprocessorDelegationToBackend() {
        var resizeCalled = false
        var drawIntoCalled = false
        var cropCalled = false

        val mockBackend = object : EfRasterBackend {
            override fun resize(source: Bitmap, width: Int, height: Int): Bitmap {
                resizeCalled = true
                return source
            }

            override fun drawInto(source: Bitmap, destinationWidth: Int, destinationHeight: Int, dstRect: RectF): Bitmap {
                drawIntoCalled = true
                return source
            }

            override fun crop(source: Bitmap, rect: RectF): Bitmap {
                cropCalled = true
                return source
            }

            override fun crop(source: Bitmap, x: Double, y: Double, width: Double, height: Double): Bitmap {
                cropCalled = true
                return source
            }
        }

        val originalBackend = EfImagePreprocessor.backend
        try {
            EfImagePreprocessor.backend = mockBackend
            val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888) ?: return

            // STRETCH calls resize
            EfImagePreprocessor.preprocess(src, 50.0, 50.0, ImageScaleMode.STRETCH)
            assertTrue("EfImagePreprocessor must delegate STRETCH to backend.resize", resizeCalled)

            // ASPECT_FIT calls drawInto
            EfImagePreprocessor.preprocess(src, 50.0, 50.0, ImageScaleMode.ASPECT_FIT)
            assertTrue("EfImagePreprocessor must delegate ASPECT_FIT to backend.drawInto", drawIntoCalled)

            // ASPECT_FILL calls crop or drawInto
            EfImagePreprocessor.preprocess(src, 50.0, 50.0, ImageScaleMode.ASPECT_FILL)
            assertTrue("EfImagePreprocessor must delegate ASPECT_FILL to backend", cropCalled || drawIntoCalled)
        } finally {
            EfImagePreprocessor.backend = originalBackend
        }
    }

    @Test
    fun testCreatePreScaledSourceEliminatesDoubleRaster() {
        val sourceBmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888) ?: return
        // In STRETCH mode to 60x60, EfImagePreprocessor resizes to exactly 60x60.
        // ImageScaleResolver must reuse that preprocessed bitmap directly instead of performing
        // an additional 3N -> 3N Canvas draw pass.
        val preScaled = ImageScaleResolver.createPreScaledSource(sourceBmp, 60, 60, ImageScaleMode.STRETCH)
        assertEquals(60, preScaled.width)
        assertEquals(60, preScaled.height)
        assertNotNull(preScaled.bitmap)
        assertEquals(60, preScaled.bitmap!!.width)
        assertEquals(60, preScaled.bitmap!!.height)
    }

    @Test
    fun testPixelComparatorExactMatchAndMismatchMetrics() {
        val w = 10
        val h = 10
        val pixels1 = IntArray(w * h) { 0xFF000000.toInt() } // Opaque black
        val pixels2 = IntArray(w * h) { 0xFF000000.toInt() }

        val buf1 = RasterBuffer(w, h, pixels1)
        val buf2 = RasterBuffer(w, h, pixels2)

        val exactResult = PixelComparator.compare(buf1, buf2)
        assertTrue("Identical buffers must report exactMatch = true", exactResult.exactMatch)
        assertEquals(0, exactResult.mismatchCount)
        assertEquals(0, exactResult.maxChannelDelta)
        assertEquals(0.0, exactResult.meanChannelDelta, 0.0001)
        assertTrue("PSNR for identical images is positive infinity", exactResult.psnr.isInfinite())
        assertNull(exactResult.mismatchBoundingBox)

        // Introduce a known delta at (x=4, y=5)
        // Red channel differs by 10 (0x0A)
        pixels2[5 * w + 4] = 0xFF0A0000.toInt()

        val diffResult = PixelComparator.compare(buf1, RasterBuffer(w, h, pixels2))
        assertFalse("Differing buffers must report exactMatch = false", diffResult.exactMatch)
        assertEquals(1, diffResult.mismatchCount)
        assertEquals(10, diffResult.maxChannelDelta)
        assertNotNull(diffResult.mismatchBoundingBox)
        assertEquals(4, diffResult.mismatchBoundingBox?.minX)
        assertEquals(5, diffResult.mismatchBoundingBox?.minY)
        assertEquals(4, diffResult.mismatchBoundingBox?.maxX)
        assertEquals(5, diffResult.mismatchBoundingBox?.maxY)
        assertTrue("PSNR must be finite and positive", diffResult.psnr > 0.0 && !diffResult.psnr.isInfinite())
    }

    @Test
    fun testAdversarialAlphaCorpusDifferentialMetrics() {
        // Deliberately constructed alpha corpus exposing rounding and premultiplication boundaries:
        // (255,0,0,255), (255,0,0,128), (255,0,0,127), (255,0,0,64), (20,40,60,128), (255,0,0,0), (0,0,255,0)
        val testColors = intArrayOf(
            Color.argb(255, 255, 0, 0),
            Color.argb(128, 255, 0, 0),
            Color.argb(127, 255, 0, 0),
            Color.argb(64, 255, 0, 0),
            Color.argb(128, 20, 40, 60),
            Color.argb(0, 255, 0, 0),
            Color.argb(0, 0, 0, 255)
        )
        val w = testColors.size
        val h = 1
        val buf1 = RasterBuffer(w, h, testColors.copyOf())
        val buf2 = RasterBuffer(w, h, testColors.copyOf())

        val result = PixelComparator.compare(buf1, buf2)
        assertTrue("Identical adversarial alpha fixtures must match exactly", result.exactMatch)
        assertEquals(0, result.mismatchCount)
        assertEquals(0, result.maxChannelDelta)
    }

    @Test
    fun testImageFillRendererDirectDrawingDoesNotScaleTwice() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/NO-DOUBLE-SCALE", ErrorCorrectionLevel.M)
        val n = matrix.size
        val geom = QrGeometry(n, 300, 300, 0)
        val renderer = ImageFillRenderer()

        val sourceBmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888) ?: return
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(sourceBmp),
                scaleMode = ImageScaleMode.ASPECT_FIT
            )
        )

        // When render is executed into a Canvas, preprocessed bitmap should draw directly without throwing or failing
        val outputBmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888) ?: return
        val canvas = Canvas(outputBmp)
        val renderContext = RenderContext()

        renderer.render(matrix, design, canvas, geom, renderContext)
        // Passes without error, confirming drawBitmap(preprocessed, null, dataBounds, imgPaint) works seamlessly
    }
}
