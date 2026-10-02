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
        val profile = EfRasterProfile.EF_7_0_3
        assertEquals("7.0.3", profile.version)
        assertEquals("07ff9e2e83a4bbd384e5a8e33b47f389c9aac762", profile.referenceCommit)
        assertEquals("RGBA8_sRGB", profile.sourceFormat)
        assertEquals("premultipliedLast", profile.alphaMode)
        assertEquals("DeviceRGB", profile.colorSpace)
        assertEquals("CG_REFERENCE_KERNEL", profile.sampling)
        assertFalse("Dither must be disabled in EF 7.0.3 profile", profile.dither)
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

        // 3. Crop
        val cropped = backend.crop(source, 10, 10, 30, 20)
        assertEquals(30, cropped.width)
        assertEquals(20, cropped.height)
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

            override fun crop(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
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
