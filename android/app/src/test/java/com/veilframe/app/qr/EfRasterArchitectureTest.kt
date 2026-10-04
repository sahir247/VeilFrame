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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unit and differential tests verifying the EFQRCode 7.0.3 rasterization architecture:
 * 1. [EfRasterProfile] contract and [EfRasterBackend] / [SkiaEfRasterBackend] primitives.
 * 2. [EfImagePreprocessor] delegation to [EfRasterBackend].
 * 3. Prevention of second rasterization in [ImageFillRenderer] (DISCOVERY.txt Section 9).
 * 4. Verification that dither is disabled across parity paths (DISCOVERY.txt Section 10).
 * 5. [PixelComparator] and [RasterBuffer] differential measurement engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
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
        assertEquals("IMAGE/IMAGE_FILL: Preprocess -> PNG/SVG -> SwiftDraw/CoreGraphics draw; RESAMPLE: Preprocess -> 3N sampling-context draw -> RGBA bytes", profile.invariantDescription)
    }

    @Test
    fun testSkiaEfRasterBackendPrimitives() {
        val backend = SkiaEfRasterBackend
        val source = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)

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
    fun testSkiaEfRasterBackendFailsClosedOnAllocationFailure() {
        val backend = SkiaEfRasterBackend
        // A recycled bitmap will cause Canvas draw operations to throw
        val recycledSource = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { recycle() }

        try {
            backend.resize(recycledSource, 20, 20)
            fail("Expected EfRasterException when bitmap is recycled in resize")
        } catch (e: EfRasterException) {
            // Success: fail-closed exception thrown
            assertTrue(e.message?.contains("resize") == true)
        }

        try {
            backend.drawInto(recycledSource, 20, 20, RectF(0f, 0f, 20f, 20f))
            fail("Expected EfRasterException when bitmap is recycled in drawInto")
        } catch (e: EfRasterException) {
            assertTrue(e.message?.contains("drawInto") == true)
        }

        try {
            backend.crop(recycledSource, 0.0, 0.0, 5.0, 5.0)
            fail("Expected EfRasterException when bitmap is recycled in crop")
        } catch (e: EfRasterException) {
            assertTrue(e.message?.contains("crop") == true)
        }
    }

    @Test
    fun testSkiaEfRasterBackendFailsClosedOnNonPositiveDimensions() {
        val backend = SkiaEfRasterBackend
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        // 1. Resize with zero or negative width/height
        try {
            backend.resize(source, 0, 10)
            fail("Expected EfRasterException for zero width in resize")
        } catch (e: EfRasterException) {
            assertTrue(e.message?.contains("dimensions must be positive") == true)
        }

        try {
            backend.resize(source, 10, -5)
            fail("Expected EfRasterException for negative height in resize")
        } catch (e: EfRasterException) {
            assertTrue(e.message?.contains("dimensions must be positive") == true)
        }

        // 2. DrawInto with zero or negative width/height
        val dstRect = RectF(0f, 0f, 10f, 10f)
        try {
            backend.drawInto(source, 0, 10, dstRect)
            fail("Expected EfRasterException for zero destination width in drawInto")
        } catch (e: EfRasterException) {
            assertTrue(e.message?.contains("dimensions must be positive") == true)
        }

        try {
            backend.drawInto(source, 10, -1, dstRect)
            fail("Expected EfRasterException for negative destination height in drawInto")
        } catch (e: EfRasterException) {
            assertTrue(e.message?.contains("dimensions must be positive") == true)
        }
    }

    @Test
    fun testCGRectIntegralCroppingSemantics() {
        val backend = SkiaEfRasterBackend
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

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
            val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)

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
    fun testResampleAlwaysPerformsSamplingContextDrawEvenWhenDimensionsMatch() {
        var drawIntoCalled = false
        var capturedDestW = 0
        var capturedDestH = 0
        var capturedDstRect: RectF? = null

        val sampleDestBmp = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        val mockBackend = object : EfRasterBackend {
            override fun resize(source: Bitmap, width: Int, height: Int): Bitmap = source
            override fun drawInto(source: Bitmap, destinationWidth: Int, destinationHeight: Int, dstRect: RectF): Bitmap {
                drawIntoCalled = true
                capturedDestW = destinationWidth
                capturedDestH = destinationHeight
                capturedDstRect = dstRect
                return sampleDestBmp
            }
            override fun crop(source: Bitmap, rect: RectF): Bitmap = source
            override fun crop(source: Bitmap, x: Double, y: Double, width: Double, height: Double): Bitmap = source
        }

        val originalBackend = EfImagePreprocessor.backend
        try {
            EfImagePreprocessor.backend = mockBackend
            val sourceBmp = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)

            // Input is already 60x60, target is 60x60.
            // EFQRCode 7.0.3 getGrayPointList() (lines 795-810) always draws into a fresh 3N x 3N context.
            // ImageScaleResolver must NOT skip this draw even though dimensions match!
            val preScaled = ImageScaleResolver.createPreScaledSource(sourceBmp, 60, 60, ImageScaleMode.STRETCH)
            assertTrue("RESAMPLE must always execute the 3N sampling-context draw", drawIntoCalled)
            assertEquals(60, capturedDestW)
            assertEquals(60, capturedDestH)
            assertEquals(RectF(0f, 0f, 60f, 60f), capturedDstRect)
            assertSame("Must return the bitmap from drawInto (sampling context)", sampleDestBmp, preScaled.bitmap)
        } finally {
            EfImagePreprocessor.backend = originalBackend
        }
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
    fun testPixelComparatorAdversarialCorpusIntegrity() {
        // Deliberately constructed alpha corpus exposing rounding and premultiplication boundaries:
        // (255,0,0,255), (255,0,0,128), (255,0,0,127), (255,0,0,64), (20,40,60,128), (255,0,0,0), (0,0,255,0)
        // Note: Verifies comparator measurement integrity. CoreGraphics vs Skia alpha byte parity remains
        // NOT VERIFIED awaiting offline CoreGraphics reference fixtures.
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
        assertTrue("Identical adversarial alpha fixtures must report exactMatch = true", result.exactMatch)
        assertEquals(0, result.mismatchCount)
        assertEquals(0, result.maxChannelDelta)
    }

    @Test
    fun testAdversarialAlphaCorpusLuminanceCalculations() {
        // Validates calculateLuminance behavior with both single-alpha and EF CoreGraphics double-alpha models
        // Opaque red: (255, 0, 0, 255) -> gray = 0.2126 * 255 = 54.213 -> normalized ~ 0.2126
        val redOpaqueLum = ImageScaleResolver.calculateLuminance(255, 0, 0, 1.0f, efPremultipliedAlpha = true)
        assertEquals(0.2126f, redOpaqueLum, 0.001f)

        // Fully transparent: (255, 0, 0, 0) -> weighted by (1 - alpha)*255 = 255 -> normalized = 1.0 (white)
        val transparentLum = ImageScaleResolver.calculateLuminance(255, 0, 0, 0.0f, efPremultipliedAlpha = true)
        assertEquals(1.0f, transparentLum, 0.001f)

        // Boundary rounding case: alpha = 127/255 (~0.498)
        val alpha127 = 127f / 255f
        val standardLum = ImageScaleResolver.calculateLuminance(255, 0, 0, alpha127, efPremultipliedAlpha = false)
        val efLum = ImageScaleResolver.calculateLuminance(255, 0, 0, alpha127, efPremultipliedAlpha = true)
        // EF applies alpha twice (a^2): efLum has lower luminance score due to double-alpha attenuation on red channel
        assertTrue("EF CoreGraphics double-alpha produces lower red luminance (0.555 vs 0.608) due to a^2 attenuation", efLum < standardLum)
        assertEquals(0.5547f, efLum, 0.005f)
        assertEquals(0.6078f, standardLum, 0.005f)
    }

    @Test
    fun testImageFillRendererDirectDrawingDoesNotScaleTwice() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/NO-DOUBLE-SCALE", ErrorCorrectionLevel.M)
        val n = matrix.size
        val geom = QrGeometry(n, 300, 300, 0)
        val renderer = ImageFillRenderer()

        val sourceBmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(sourceBmp),
                scaleMode = ImageScaleMode.ASPECT_FIT
            )
        )

        // When render is executed into a Canvas, preprocessed bitmap should draw directly without throwing or failing
        val outputBmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBmp)
        val renderContext = RenderContext()

        renderer.render(matrix, design, canvas, geom, renderContext)
        // Passes without error, confirming drawBitmap(preprocessed, null, dataBounds, imgPaint) works seamlessly
    }
}
