package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.ImageScaleMode
import org.junit.Assert.*
import org.junit.Test

/**
 * M1 — EfImagePreprocessor parity test corpus.
 *
 * Evidence level: UNVERIFIED (source-inspected; Layer B oracle not yet executed).
 * Target evidence level: REFERENCE-VERIFIED (after macOS EFQRCode 7.0.3 oracle execution).
 *
 * Test strategy:
 *  - TC-M1-01..TC-M1-04, TC-M1-11: Pure math — dimension calculations from EFImageMode.swift.
 *    These run fully in headless JVM and are the primary M1 exit-gate gate criteria.
 *  - TC-M1-05..TC-M1-10, TC-M1-12..TC-M1-13: Bitmap-based behavioral tests.
 *    Bitmap.createBitmap() returns null in headless JVM stubs; these tests are guarded and
 *    only execute when Android runtime is available (Robolectric or device).
 *    They document the required contract for Layer B oracle execution.
 *
 * Relates to audit sections: 2.A, 2.B, Milestone 1 exit gate.
 */
class EfImagePreprocessorTest {

    // -----------------------------------------------------------------------
    // PURE MATH TESTS — run in headless JVM, no Android framework dependency
    // Source: EFImageMode.swift:123-170
    // -----------------------------------------------------------------------

    // TC-M1-01: scaleToFillSize — wide source into square canvas (widthRatio > heightRatio branch)
    @Test
    fun `scaleToFillSize wide source square canvas uses heightRatio branch`() {
        // source: 400x200, canvas: 100x100
        // widthRatio = 4.0, heightRatio = 2.0 -> widthRatio > heightRatio
        // anchor height: (imageH/canvasH * canvasW, imageH) = (200/100*100, 200) = (200, 200)
        val (w, h) = EfImagePreprocessor.scaleToFillSize(
            imageWidth = 400f, imageHeight = 200f,
            canvasW = 100f, canvasH = 100f,
            widthRatio = 4f, heightRatio = 2f
        )
        assertEquals("newWidth", 200, w)
        assertEquals("newHeight", 200, h)
    }

    // TC-M1-02: scaleToFillSize — tall source into wide canvas (widthRatio <= heightRatio branch)
    @Test
    fun `scaleToFillSize tall source wide canvas uses widthRatio branch`() {
        // source: 200x400, canvas: 300x100
        // widthRatio ~= 0.667, heightRatio = 4.0 -> widthRatio <= heightRatio
        // anchor width: (imageW, imageW/canvasW * canvasH) = (200, 200/300*100) = (200, 66.666...)
        // Int truncation -> (200, 66)
        val (w, h) = EfImagePreprocessor.scaleToFillSize(
            imageWidth = 200f, imageHeight = 400f,
            canvasW = 300f, canvasH = 100f,
            widthRatio = 200f / 300f, heightRatio = 4f
        )
        assertEquals("newWidth", 200, w)
        assertEquals("newHeight", 66, h)
    }

    // TC-M1-03: scaleAspectFitSize — wide source into square canvas (letterbox expansion)
    @Test
    fun `scaleAspectFitSize wide source square canvas creates letterbox expansion`() {
        // source: 400x200, canvas: 100x100
        // widthRatio = 4.0, heightRatio = 2.0 -> widthRatio > heightRatio
        // anchor width: (imageW, imageW/canvasW * canvasH) = (400, 400/100*100) = (400, 400)
        // Result: 400x400 canvas for 400x200 source - source is letterboxed in center
        val (w, h) = EfImagePreprocessor.scaleAspectFitSize(
            imageWidth = 400f, imageHeight = 200f,
            canvasW = 100f, canvasH = 100f,
            widthRatio = 4f, heightRatio = 2f
        )
        assertEquals("newWidth", 400, w)
        assertEquals("newHeight", 400, h)
    }

    // TC-M1-03b: scaleAspectFitSize — tall source into square canvas (pillarbox expansion)
    @Test
    fun `scaleAspectFitSize tall source square canvas creates pillarbox expansion`() {
        // source: 200x400, canvas: 100x100
        // widthRatio = 2.0, heightRatio = 4.0 -> NOT widthRatio > heightRatio
        // anchor height: (imageH/canvasH * canvasW, imageH) = (400/100*100, 400) = (400, 400)
        val (w, h) = EfImagePreprocessor.scaleAspectFitSize(
            imageWidth = 200f, imageHeight = 400f,
            canvasW = 100f, canvasH = 100f,
            widthRatio = 2f, heightRatio = 4f
        )
        assertEquals("newWidth", 400, w)
        assertEquals("newHeight", 400, h)
    }

    // TC-M1-04: scaleAspectFillSize — REVERSED condition vs. scaleToFill/scaleAspectFit
    // Source: EFImageMode.swift:157 -> "if widthRatio < heightRatio" (NOT >)
    @Test
    fun `scaleAspectFillSize reversed condition for wide source square canvas`() {
        // source: 400x200, canvas: 100x100
        // widthRatio = 4.0, heightRatio = 2.0 -> widthRatio < heightRatio is FALSE
        // anchor height: (imageH/canvasH * canvasW, imageH) = (200/100*100, 200) = (200, 200)
        val (w, h) = EfImagePreprocessor.scaleAspectFillSize(
            imageWidth = 400f, imageHeight = 200f,
            canvasW = 100f, canvasH = 100f,
            widthRatio = 4f, heightRatio = 2f
        )
        assertEquals("newWidth", 200, w)
        assertEquals("newHeight", 200, h)
    }

    @Test
    fun `scaleAspectFillSize wide canvas tall source uses widthRatio lt heightRatio TRUE branch`() {
        // source: 200x400, canvas: 300x100
        // widthRatio ~= 0.667, heightRatio = 4.0 -> widthRatio < heightRatio is TRUE
        // anchor width: (imageW, imageW/canvasW * canvasH) = (200, 200/300*100) = (200, 66.666...)
        // Int truncation -> (200, 66)
        val (w, h) = EfImagePreprocessor.scaleAspectFillSize(
            imageWidth = 200f, imageHeight = 400f,
            canvasW = 300f, canvasH = 100f,
            widthRatio = 200f / 300f, heightRatio = 4f
        )
        assertEquals("newWidth", 200, w)
        assertEquals("newHeight", 66, h)
    }

    // TC-M1-11: Integer truncation — fractional intermediate dimensions are floored
    // Source: EFImageMode.swift:95-96 — "Int(newSize.width)" truncates toward zero
    @Test
    fun `fractional intermediate sizes are integer-truncated not rounded`() {
        // source: 300x200, canvas: 7x5 (ratio 1.4)
        // widthRatio = 300/7 ~= 42.86, heightRatio = 200/5 = 40.0 -> widthRatio > heightRatio
        // anchor height: (imageH/canvasH * canvasW, imageH) = (200/5*7, 200) = (280.0, 200.0)
        // Int truncation: (280, 200)
        val (w, h) = EfImagePreprocessor.scaleToFillSize(
            imageWidth = 300f, imageHeight = 200f,
            canvasW = 7f, canvasH = 5f,
            widthRatio = 300f / 7f, heightRatio = 40f
        )
        assertEquals("Width truncated to 280 not rounded to 281", 280, w)
        assertEquals("Height unchanged", 200, h)
    }

    @Test
    fun `fractional aspect fill size is integer-truncated not rounded`() {
        // source: 300x200, canvas: 7x5
        // widthRatio = 300/7 ~= 42.86 > heightRatio = 40.0 -> widthRatio < heightRatio is FALSE
        // anchor height: (200/5*7, 200) = (280.0, 200) -> Int: (280, 200)
        val (w, h) = EfImagePreprocessor.scaleAspectFillSize(
            imageWidth = 300f, imageHeight = 200f,
            canvasW = 7f, canvasH = 5f,
            widthRatio = 300f / 7f, heightRatio = 40f
        )
        assertEquals("Width truncated to 280 not rounded", 280, w)
        assertEquals("Height unchanged", 200, h)
    }

    @Test
    fun `floating-point newSize and origin precision preserved for aspect fit and fill`() {
        // source: 300x200, canvas: 7x5
        // widthRatio = 300/7 ~= 42.857 > heightRatio = 200/5 = 40.0
        // in EF scaleAspectFit:
        // newSize = (imageWidth, imageWidth / canvasW * canvasH) = (300.0, 300.0 / 7.0 * 5.0) = (300.0, 214.2857)
        val (wF, hF) = EfImagePreprocessor.scaleAspectFitSizeF(
            imageWidth = 300f, imageHeight = 200f,
            canvasW = 7f, canvasH = 5f,
            widthRatio = 300f / 7f, heightRatio = 40f
        )
        assertEquals(300.0f, wF, 0.001f)
        assertEquals(214.2857f, hF, 0.001f)

        // originY = -(imageHeight - newHeightF) / 2.0 = -(200 - 214.2857) / 2.0 = 7.14285f
        val originY = -(200f - hF) / 2f
        assertEquals("originY must be ~7.14285 not truncated to 7.0", 7.14285f, originY, 0.001f)
    }

    // -----------------------------------------------------------------------
    // BEHAVIORAL TESTS — Bitmap-based; null-guarded for headless JVM.
    // These document required M1 behavioral contracts for Layer B oracle execution.
    // -----------------------------------------------------------------------

    // TC-M1-05: EF early-exit — matching ratio returns same instance
    // Source: EFImageMode.swift:127
    @Test
    fun `preprocess returns same bitmap when ratio already matches`() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) ?: return
        val result = EfImagePreprocessor.preprocess(source, 50f, 50f, ImageScaleMode.ASPECT_FILL)
        assertSame("Should return same instance when ratio matches", source, result)
    }

    @Test
    fun `preprocess returns same bitmap for non-square matching ratio`() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888) ?: return
        val result = EfImagePreprocessor.preprocess(source, 4f, 2f, ImageScaleMode.STRETCH)
        assertSame("Should return same instance for matching non-square ratio", source, result)
    }

    // TC-M1-06: STRETCH output dimensions match scaleToFill intermediate contract
    @Test
    fun `STRETCH produces intermediate dimensions not canvas dimensions`() {
        // source: 400x200, canvas: 100x100 -> intermediate: (200, 200), NOT (100, 100)
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) ?: return
        val result = EfImagePreprocessor.preprocess(source, 100f, 100f, ImageScaleMode.STRETCH)
        assertEquals("Width should be intermediate 200, not canvas 100", 200, result.width)
        assertEquals("Height should be intermediate 200", 200, result.height)
    }

    // TC-M1-07: ASPECT_FIT transparent canvas — non-covered pixels are clear (alpha=0)
    // Source: CGImage+EFQRCode.swift:178 context.clear() -> RGBA(0,0,0,0)
    @Test
    fun `ASPECT_FIT letterbox pixels are fully transparent`() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) ?: return
        source.eraseColor(Color.RED)
        val result = EfImagePreprocessor.preprocess(source, 100f, 100f, ImageScaleMode.ASPECT_FIT)
        assertEquals("Result width", 400, result.width)
        assertEquals("Result height is expanded", 400, result.height)
        // Top-left pixel (y=0) should be in the transparent margin
        val topLeftAlpha = Color.alpha(result.getPixel(0, 0))
        assertEquals("Top margin pixel alpha must be 0 (fully transparent, not white)", 0, topLeftAlpha)
    }

    // TC-M1-08: ASPECT_FILL fractional-offset contract — matches EF clipAndExpandingTransparencyWith
    // Source: EFImageMode.swift:155-168 -> self.clipAndExpandingTransparencyWith(rect: rect)
    @Test
    fun `ASPECT_FILL fractional-offset contract matches EF clipAndExpandingTransparencyWith`() {
        // source: 301x203, canvas: 99x100
        // widthRatio = 301/99 ~= 3.0404, heightRatio = 203/100 = 2.03 -> widthRatio < heightRatio is FALSE
        // anchor height: (imageH/canvasH * canvasW, imageH) = (203/100*99, 203) = (200.97, 203.0)
        val (wF, hF) = EfImagePreprocessor.scaleAspectFillSizeF(
            imageWidth = 301f, imageHeight = 203f,
            canvasW = 99f, canvasH = 100f,
            widthRatio = 301f / 99f, heightRatio = 203f / 100f
        )
        assertEquals(200.97f, wF, 0.01f)
        assertEquals(203.0f, hF, 0.01f)

        // originX = -(imageWidth - newWidthF) / 2.0 = -(301 - 200.97) / 2.0 = -50.015f
        val originX = -(301f - wF) / 2f
        assertEquals(-50.015f, originX, 0.01f)
        // originY = -(203 - 203) / 2.0 = 0.0f
        val originY = -(203f - hF) / 2f
        assertEquals(0.0f, originY, 0.01f)
    }

    @Test
    fun `ASPECT_FILL produces intermediate canvas dimensions via clipAndExpandTransparency`() {
        val srcW = 400; val srcH = 200
        val source = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888) ?: return
        source.eraseColor(Color.BLUE)
        val result = EfImagePreprocessor.preprocess(source, 100f, 100f, ImageScaleMode.ASPECT_FILL)
        assertEquals("Output width matches newWidth truncated", 200, result.width)
        assertEquals("Output height matches newHeight truncated", 200, result.height)
    }

    // TC-M1-09: resizeBitmap — same instance returned when dimensions match
    @Test
    fun `resizeBitmap returns same instance when dimensions already match`() {
        val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888) ?: return
        val result = EfImagePreprocessor.resizeBitmap(source, 100, 200)
        assertSame(source, result)
    }

    // TC-M1-10: clipAndExpandTransparency — no-op when rect exactly matches source
    @Test
    fun `clipAndExpandTransparency returns same instance when rect matches source`() {
        val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888) ?: return
        val result = EfImagePreprocessor.clipAndExpandTransparency(source, 0f, 0f, 100, 200)
        assertSame(source, result)
    }

    // TC-M1-12: CENTER_CROP maps to same geometry as ASPECT_FILL
    @Test
    fun `CENTER_CROP and ASPECT_FILL produce same output dimensions`() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) ?: return
        source.eraseColor(Color.BLUE)
        val fill = EfImagePreprocessor.preprocess(source.copy(Bitmap.Config.ARGB_8888, false)
            ?: return, 100f, 100f, ImageScaleMode.ASPECT_FILL)
        val crop = EfImagePreprocessor.preprocess(source.copy(Bitmap.Config.ARGB_8888, false)
            ?: return, 100f, 100f, ImageScaleMode.CENTER_CROP)
        assertEquals("Width matches", fill.width, crop.width)
        assertEquals("Height matches", fill.height, crop.height)
    }

    // TC-M1-13: ASPECT_FIT output is larger than source for letterbox case
    @Test
    fun `ASPECT_FIT output is larger than source for letterbox case`() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) ?: return
        val result = EfImagePreprocessor.preprocess(source, 100f, 100f, ImageScaleMode.ASPECT_FIT)
        assertEquals("Width matches source width", 400, result.width)
        assertEquals("Height is expanded to match canvas ratio", 400, result.height)
        assertTrue("Output height > source height (letterbox)", result.height > source.height)
    }
}
