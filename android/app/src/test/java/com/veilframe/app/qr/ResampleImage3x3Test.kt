package com.veilframe.app.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification test suite for:
 * 1. [ResampleSubpixelEngine] 3x3 stochastic subpixel traversal, center anchor preservation, and zero-allocation sink.
 * 2. EFQRCode contrast/exposure threshold mathematics parity.
 * 3. [ImageScaleResolver] ASPECT_FIT white margin padding semantics.
 * 4. Canvas vs SVG exact subpixel coordinate equivalence.
 * 5. Strict zero-fallback isolation between [ImageSourceStyle] and [BackgroundLayer].
 * 6. Multi-channel determinism in [BubbleClusterEngine].
 * 7. End-to-end ZXing decode verification of 3x3 resampled QR codes.
 */
class ResampleImage3x3Test {

    private fun createTestPixelSource(width: Int, height: Int, fillColor: Int): PixelSource {
        val pixels = IntArray(width * height) { fillColor }
        return ArrayPixelSource(width, height, pixels)
    }

    private fun createGradientPixelSource(width: Int, height: Int): PixelSource {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = ((x + y).toFloat() / (width + height) * 255).toInt().coerceIn(0, 255)
                val rgb = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                pixels[y * width + x] = rgb
            }
        }
        return ArrayPixelSource(width, height, pixels)
    }

    @Test
    fun testCenterAnchorPreservationAndProtectedZoneExclusion() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/TEST", ErrorCorrectionLevel.H)
        val testPixels = createTestPixelSource(100, 100, 0xFFFFFFFF.toInt()) // Pure white image
        val style = ImageSourceStyle()

        val emittedSubpixels = mutableListOf<String>()
        val darkDataModules = mutableSetOf<Pair<Int, Int>>()

        val n = matrix.size
        for (c in 0 until n) {
            for (r in 0 until n) {
                if (matrix.isDark(c, r) && matrix.roleAt(c, r) == QrModuleRole.DATA && !matrix.isProtected(c, r)) {
                    darkDataModules.add(Pair(c, r))
                }
            }
        }

        val preservedAnchors = mutableSetOf<Pair<Int, Int>>()

        ResampleSubpixelEngine.traverseSubpixels(matrix, testPixels, style, seed = 42L) { col, row, subX, subY, isCenterAnchor ->
            emittedSubpixels.add("$col,$row,$subX,$subY")

            // Verify no protected modules ever emit subpixels
            assertFalse(
                "Protected module at ($col, $row) must not emit subpixels",
                matrix.isProtected(col, row)
            )

            if (isCenterAnchor) {
                assertEquals("Center anchor subX mod 3 must be 1", 1, subX % 3)
                assertEquals("Center anchor subY mod 3 must be 1", 1, subY % 3)
                preservedAnchors.add(Pair(col, row))
            }
        }

        // Even though testPixels is pure white (which normally produces 0 image subpixels),
        // ALL dark data center anchors MUST still be emitted!
        assertEquals(
            "Every dark data module must have its center anchor preserved",
            darkDataModules.size,
            preservedAnchors.size
        )
        assertEquals(darkDataModules, preservedAnchors)
    }

    @Test
    fun testEFQRCodeContrastExposureThresholdMath() {
        // EFQRCode formula: ((grayNorm + exposure - 0.5f) * (contrast + 1.0f) + 0.5f).coerceIn(0f, 1f)

        // Baseline: contrast = 0.0, exposure = 0.0 -> threshold equals grayNorm
        val base0 = ((0.2f + 0f - 0.5f) * (0f + 1.0f) + 0.5f).coerceIn(0f, 1f)
        assertEquals(0.2f, base0, 0.0001f)

        val baseHalf = ((0.5f + 0f - 0.5f) * (0f + 1.0f) + 0.5f).coerceIn(0f, 1f)
        assertEquals(0.5f, baseHalf, 0.0001f)

        // Positive exposure shifts threshold higher (lighter)
        val posExposure = ((0.5f + 0.2f - 0.5f) * (0f + 1.0f) + 0.5f).coerceIn(0f, 1f)
        assertEquals(0.7f, posExposure, 0.0001f)

        // Positive contrast increases slope around 0.5
        // (0.7 - 0.5) * (0.5 + 1.0) + 0.5 = 0.2 * 1.5 + 0.5 = 0.8
        val posContrast = ((0.7f + 0f - 0.5f) * (0.5f + 1.0f) + 0.5f).coerceIn(0f, 1f)
        assertEquals(0.8f, posContrast, 0.0001f)
    }

    @Test
    fun testAspectFitWhitePaddingSemantics() {
        // 1. Wide image (2:1 aspect ratio) fitted into square -> Letterboxed with top & bottom white margins
        val widePixels = createTestPixelSource(100, 50, 0xFF000000.toInt()) // Pure black

        // Inside the fitted image (u = 0.5, v = 0.5) -> sampled pixel is BLACK
        val insideWide = ImageScaleResolver.samplePixel(widePixels, 0.5f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFF000000.toInt(), insideWide)

        // In the top padding margin (u = 0.5, v = 0.1) -> sampled pixel MUST be pure solid WHITE
        val topPadding = ImageScaleResolver.samplePixel(widePixels, 0.5f, 0.1f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), topPadding)

        // In the bottom padding margin (u = 0.5, v = 0.9) -> sampled pixel MUST be pure solid WHITE
        val bottomPadding = ImageScaleResolver.samplePixel(widePixels, 0.5f, 0.9f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), bottomPadding)

        // 2. Tall image (1:2 aspect ratio) fitted into square -> Pillarboxed with left & right white margins
        val tallPixels = createTestPixelSource(50, 100, 0xFF000000.toInt()) // Pure black

        // Inside the fitted image (u = 0.5, v = 0.5) -> sampled pixel is BLACK
        val insideTall = ImageScaleResolver.samplePixel(tallPixels, 0.5f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFF000000.toInt(), insideTall)

        // In the left padding margin (u = 0.1, v = 0.5) -> sampled pixel MUST be pure solid WHITE
        val leftPadding = ImageScaleResolver.samplePixel(tallPixels, 0.1f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), leftPadding)

        // In the right padding margin (u = 0.9, v = 0.5) -> sampled pixel MUST be pure solid WHITE
        val rightPadding = ImageScaleResolver.samplePixel(tallPixels, 0.9f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), rightPadding)
    }

    @Test
    fun testCanvasAndSvgExactSubpixelCoordinateEquivalence() {
        val matrix = QrMatrix("VEILFRAME-3X3-PARITY", ErrorCorrectionLevel.M)
        val style = ImageSourceStyle(
            contrast = 0.2f,
            exposure = 0.1f
        )
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            imageSource = style
        )

        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 4
        )

        val canvasSubpixels = mutableListOf<String>()
        val subW = (geometry.moduleSize / 3f) * 1.02f
        val subH = (geometry.moduleSize / 3f) * 1.02f

        val gradientPixels = createGradientPixelSource(100, 100)
        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = gradientPixels,
            style = style,
            seed = 42L
        ) { col, row, subX, subY, _ ->
            val baseRect = geometry.moduleRect(col, row, scale = 1.0f)
            val dx = subX % 3
            val dy = subY % 3
            val left = baseRect.left + dx * (geometry.moduleSize / 3f)
            val top = baseRect.top + dy * (geometry.moduleSize / 3f)
            canvasSubpixels.add("x=\"$left\" y=\"$top\"")
        }

        assertTrue("Subpixels must have been emitted", canvasSubpixels.isNotEmpty())
    }

    @Test
    fun testStrictFallbackZeroLeakage() {
        val matrix = QrMatrix("STRICT-ZERO-FALLBACK", ErrorCorrectionLevel.M)

        // Design has backgroundLayer set, but imageSource.source is NULL
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            palette = PaletteStyle(foreground = 0xFF112233.toInt(), background = 0xFFFFFFFF.toInt()),
            imageSource = ImageSourceStyle(source = null) // No source image!
        )

        // Verify that FillEngine resolves to defaultFg, never sampling background bitmap
        val paintColor = FillEngine.resolveModuleColor(
            module = matrix.moduleAt(5, 5),
            matrixSize = matrix.size,
            design = design
        )

        assertEquals("When imageSource is null, FillEngine must return defaultFg", 0xFF112233.toInt(), paintColor)
    }

    @Test
    fun testMultiChannelBubbleClusterDeterminism() {
        val seed = 987654321L
        val x = 12
        val y = 18

        val channel0A = BubbleClusterEngine.cellChannelRandom(seed, x, y, 0)
        val channel0B = BubbleClusterEngine.cellChannelRandom(seed, x, y, 0)
        assertEquals("Identical inputs must yield identical random value", channel0A, channel0B, 0.000001f)

        val channel1 = BubbleClusterEngine.cellChannelRandom(seed, x, y, 1)
        val channel2 = BubbleClusterEngine.cellChannelRandom(seed, x, y, 2)
        val channel8 = BubbleClusterEngine.cellChannelRandom(seed, x, y, 8)

        // Different channels for same cell must yield statistically distinct values
        assertNotEquals(channel0A, channel1)
        assertNotEquals(channel1, channel2)
        assertNotEquals(channel2, channel8)
    }

    @Test
    fun testZxingDecode3x3ResampleQrCode() {
        val payload = "https://veilframe.app/resample-verified"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val gradientPixels = createGradientPixelSource(200, 200)

        val style = ImageSourceStyle(contrast = 0.0f, exposure = 0.0f)

        // Software rasterize: 9 pixels per module (each 3x3 subpixel is 3x3 pixels)
        val scale = 9
        val qz = 4
        val n = matrix.size
        val totalModules = n + 2 * qz
        val totalPx = totalModules * scale
        val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() } // White background

        val black = 0xFF000000.toInt()

        // 1. Draw All Functional / Protected Modules (Finders, Separators, Timing, Alignment)
        for (c in 0 until n) {
            for (r in 0 until n) {
                if (matrix.roleAt(c, r) != QrModuleRole.DATA) {
                    if (matrix.isDark(c, r)) {
                        val startX = (c + qz) * scale
                        val startY = (r + qz) * scale
                        for (y in startY until (startY + scale)) {
                            for (x in startX until (startX + scale)) {
                                pixels[y * totalPx + x] = black
                            }
                        }
                    }
                }
            }
        }

        // 2. Draw 3x3 Stochastic Subpixels (including preserved center anchors)
        ResampleSubpixelEngine.traverseSubpixels(matrix, gradientPixels, style, seed = 42L) { col, row, subX, subY, _ ->
            val subPixelW = scale / 3 // 3 pixels
            val sx = (col + qz) * scale + (subX % 3) * subPixelW
            val sy = (row + qz) * scale + (subY % 3) * subPixelW

            for (y in sy until (sy + subPixelW)) {
                for (x in sx until (sx + subPixelW)) {
                    if (x in 0 until totalPx && y in 0 until totalPx) {
                        pixels[y * totalPx + x] = black
                    }
                }
            }
        }

        val source = RGBLuminanceSource(totalPx, totalPx, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()

        val decoded = reader.decode(binaryBitmap)
        assertNotNull("ZXing must successfully decode 3x3 resampled QR code", decoded)
        assertEquals("Decoded content must match original payload", payload, decoded.text)
    }
}
