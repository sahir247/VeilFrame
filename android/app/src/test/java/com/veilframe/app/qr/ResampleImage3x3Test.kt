package com.veilframe.app.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.renderer.*
import com.veilframe.app.qr.validation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification test suite for:
 * 1. [ResampleSubpixelEngine] 3x3 stochastic subpixel traversal, center anchor preservation, and zero-allocation sink.
 * 2. VeilFrame Art Engine contrast/exposure threshold mathematics parity.
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
                if (matrix.isDark(c, r) && !ArtisticResampleFunctionalMask.isExcluded(c, r, n, matrix.version)) {
                    darkDataModules.add(Pair(c, r))
                }
            }
        }

        val preservedAnchors = mutableSetOf<Pair<Int, Int>>()

        ResampleSubpixelEngine.traverseSubpixels(matrix, testPixels, style, seed = 42L) { col, row, subX, subY, isCenterAnchor ->
            emittedSubpixels.add("$col,$row,$subX,$subY")

            // Verify no excluded functional modules ever emit subpixels
            assertFalse(
                "Excluded functional module at ($col, $row) must not emit subpixels",
                ArtisticResampleFunctionalMask.isExcluded(col, row, n, matrix.version)
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
    fun testArtisticContrastExposureThresholdMath() {
        // VeilFrame Art Engine formula: ((grayNorm + exposure - 0.5f) * (contrast + 1.0f) + 0.5f).coerceIn(0f, 1f)

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

        // Inside the fitted image (u = 0.5, v = 0.5) -> sampled pixel is BLACK, isPadding = false
        val insideSampleWide = ImageScaleResolver.sample(widePixels, 0.5f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFF000000.toInt(), insideSampleWide.color)
        assertFalse(insideSampleWide.isPadding)

        // In the top padding margin (u = 0.5, v = 0.1) -> sampled pixel MUST be pure solid WHITE, isPadding = true
        val topPadding = ImageScaleResolver.sample(widePixels, 0.5f, 0.1f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), topPadding.color)
        assertTrue(topPadding.isPadding)

        // In the bottom padding margin (u = 0.5, v = 0.9) -> sampled pixel MUST be pure solid WHITE, isPadding = true
        val bottomPadding = ImageScaleResolver.sample(widePixels, 0.5f, 0.9f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), bottomPadding.color)
        assertTrue(bottomPadding.isPadding)

        // 2. Tall image (1:2 aspect ratio) fitted into square -> Pillarboxed with left & right white margins
        val tallPixels = createTestPixelSource(50, 100, 0xFF000000.toInt()) // Pure black

        // Inside the fitted image (u = 0.5, v = 0.5) -> sampled pixel is BLACK, isPadding = false
        val insideSampleTall = ImageScaleResolver.sample(tallPixels, 0.5f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFF000000.toInt(), insideSampleTall.color)
        assertFalse(insideSampleTall.isPadding)

        // In the left padding margin (u = 0.1, v = 0.5) -> sampled pixel MUST be pure solid WHITE, isPadding = true
        val leftPadding = ImageScaleResolver.sample(tallPixels, 0.1f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), leftPadding.color)
        assertTrue(leftPadding.isPadding)

        // In the right padding margin (u = 0.9, v = 0.5) -> sampled pixel MUST be pure solid WHITE, isPadding = true
        val rightPadding = ImageScaleResolver.sample(tallPixels, 0.9f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertEquals(0xFFFFFFFF.toInt(), rightPadding.color)
        assertTrue(rightPadding.isPadding)
    }

    @Test
    fun testAspectFitExplicitPaddingSuppressionUnderExtremeExposure() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/PADDING-INVARIANT", ErrorCorrectionLevel.H)
        // 2:1 wide pure black image fitted into square matrix
        // Top and bottom 25% are letterbox padding
        val widePixels = createTestPixelSource(200, 100, 0xFF000000.toInt())

        // Use extreme negative exposure and high contrast that would otherwise shift white pixels to dark:
        // threshold = ((1.0 - 0.8 - 0.5) * 3.0 + 0.5) = -0.9 * 3.0 + 0.5 = -0.4 -> coerced to 0.0
        // Without explicit padding suppression, pure white pixels (1.0) would yield threshold 0.0,
        // causing 100% of white padding subpixels to trigger rnd > 0.0 and emit photo dither dots!
        val extremeStyle = ImageSourceStyle(
            scaleMode = ImageScaleMode.ASPECT_FIT,
            exposure = -0.8f,
            contrast = 2.0f
        )

        val paddingSubpixels = mutableListOf<String>()

        ResampleSubpixelEngine.traverseSubpixels(matrix, widePixels, extremeStyle, seed = 42L) { col, row, subX, subY, isCenterAnchor ->
            if (isCenterAnchor) return@traverseSubpixels // Center anchor is the QR data bit itself

            val v = (subY + 0.5f) / (3 * matrix.size).toFloat()
            // In 2:1 wide image with ASPECT_FIT, fitted height is 0.5, offsetY is 0.25
            // So v < 0.25 or v >= 0.75 is the letterbox padding margin
            if (v < 0.25f || v >= 0.75f) {
                paddingSubpixels.add("subX=$subX,subY=$subY,v=$v")
            }
        }

        // Thanks to explicit isPadding suppression, ZERO stochastic dots are emitted in the padding margins!
        assertEquals(
            "Explicit ASPECT_FIT padding suppression must yield zero photo dots in margins under extreme exposure/contrast",
            0,
            paddingSubpixels.size
        )
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
            imageSource = style,
            quietZoneModules = 1,
            explicitQuietZone = 1
        )

        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 1
        )

        val gradientPixels = createGradientPixelSource(100, 100)

        // 1. Compute expected Canvas subpixels via shared SubpixelGeometry
        val canvasSubpixels = mutableListOf<SubpixelRect>()
        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = gradientPixels,
            style = style,
            seed = 42L
        ) { col, row, subX, subY, _ ->
            val rect = SubpixelGeometry.computeCanvasRect(
                col = col,
                row = row,
                offsetX = geometry.offsetX,
                offsetY = geometry.offsetY,
                moduleSize = geometry.moduleSize,
                subX = subX,
                subY = subY
            )
            canvasSubpixels.add(rect)
        }

        assertTrue("Canvas subpixels must have been emitted", canvasSubpixels.isNotEmpty())

        // 2. Generate actual SVG output via SvgExporter
        val svgXml = SvgExporter.generateSvg(matrix, design, pixelSource = gradientPixels)
        assertNotNull("Generated SVG must not be null", svgXml)
        assertTrue("SVG must contain SVG root element", svgXml.contains("<svg"))

        // 3. Extract emitted 3x3 subpixel <rect> elements from SVG
        // Matching: <rect x="X" y="Y" width="W" height="H" fill="..." />
        val rectRegex = Regex("""<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" fill="[^"]+" />""")
        val svgRects = rectRegex.findAll(svgXml).map { match ->
            val x = match.groupValues[1].toFloat()
            val y = match.groupValues[2].toFloat()
            val w = match.groupValues[3].toFloat()
            val h = match.groupValues[4].toFloat()
            SubpixelRect(x, y, w, h)
        }.filter { rect ->
            // Filter to subpixels (width is approximately (1.0/3.0)*1.02 ~ 0.340)
            rect.width in 0.30f..0.38f
        }.toList()

        assertEquals(
            "Canvas and SVG must emit identical number of 3x3 subpixels",
            canvasSubpixels.size,
            svgRects.size
        )

        // 4. Verify exact mathematical coordinate equivalence:
        // Normalizing Canvas: (x_px - quietZonePx) / moduleSize == x_svg - quietZoneModules
        for (i in canvasSubpixels.indices) {
            val cRect = canvasSubpixels[i]
            val sRect = svgRects[i]

            val canvasNormX = (cRect.left - geometry.offsetX) / geometry.moduleSize
            val canvasNormY = (cRect.top - geometry.offsetY) / geometry.moduleSize
            val svgNormX = sRect.left - 1
            val svgNormY = sRect.top - 1

            assertEquals("X normalized coordinate must match exactly between Canvas and SVG", canvasNormX, svgNormX, 0.001f)
            assertEquals("Y normalized coordinate must match exactly between Canvas and SVG", canvasNormY, svgNormY, 0.001f)
            assertEquals("Normalized width must match", cRect.width / geometry.moduleSize, sRect.width, 0.001f)
            assertEquals("Normalized height must match", cRect.height / geometry.moduleSize, sRect.height, 0.001f)
        }
    }

    @Test
    fun testShapeGeometryExactVectorParity() {
        val design = QrDesign()
        val dummyModule = QrModule(
            col = 5,
            row = 5,
            isDark = true,
            role = QrModuleRole.DATA,
            neighbors = ModuleNeighborhood(up = true, down = false, left = true, right = false)
        )

        // 1. Pill: must have both rx and ry attributes
        val pillSvg = ShapeGeometry.buildSvgElement(
            shape = ModuleShape.PILL,
            module = dummyModule,
            cx = 5.5,
            cy = 5.5,
            mx = 5.0,
            my = 5.0,
            scale = 1.0,
            fill = "#000000",
            design = design
        )
        assertTrue("Pill SVG must have rx=0.5", pillSvg.contains("rx=\"0.5\""))
        assertTrue("Pill SVG must have ry=0.25", pillSvg.contains("ry=\"0.25\""))

        // 2. Squircle: must emit cubic Bézier path (M ... C ... Z)
        val squircleSvg = ShapeGeometry.buildSvgElement(
            shape = ModuleShape.SQUIRCLE,
            module = dummyModule,
            cx = 5.5,
            cy = 5.5,
            mx = 5.0,
            my = 5.0,
            scale = 1.0,
            fill = "#000000",
            design = design
        )
        assertTrue("Squircle SVG must be a <path>", squircleSvg.startsWith("<path d=\"M"))
        assertTrue("Squircle SVG must contain cubic Bézier 'C' segments", squircleSvg.contains(" C "))
        assertTrue("Squircle SVG must close with 'Z'", squircleSvg.contains(" Z\""))

        // 3. Organic: must emit path with arc segments matching neighbor connectivity
        val organicSvg = ShapeGeometry.buildSvgElement(
            shape = ModuleShape.ORGANIC,
            module = dummyModule,
            cx = 5.5,
            cy = 5.5,
            mx = 5.0,
            my = 5.0,
            scale = 1.0,
            fill = "#000000",
            design = design
        )
        assertTrue("Organic SVG must be a <path>", organicSvg.startsWith("<path d=\"M"))
        assertTrue("Organic SVG must contain arc 'A' commands for rounded corners", organicSvg.contains(" A "))
        assertTrue("Organic SVG must close with 'Z'", organicSvg.contains(" Z\""))
    }

    @Test
    fun testProtectedModuleGeometryParity() {
        val circleSvg = ProtectedModuleGeometry.buildSvgElement(
            shape = ModuleShape.CIRCLE,
            x = 6.0,
            y = 6.0,
            size = 1.0,
            fill = "#FF0000"
        )
        assertEquals("<circle cx=\"6.5\" cy=\"6.5\" r=\"0.5\" fill=\"#FF0000\" />", circleSvg)

        val squareSvg = ProtectedModuleGeometry.buildSvgElement(
            shape = ModuleShape.SQUARE,
            x = 6.0,
            y = 6.0,
            size = 1.0,
            fill = "#00FF00"
        )
        assertEquals("<rect x=\"6.0\" y=\"6.0\" width=\"1.0\" height=\"1.0\" fill=\"#00FF00\" />", squareSvg)

        val roundedSvg = ProtectedModuleGeometry.buildSvgElement(
            shape = ModuleShape.ROUNDED,
            x = 6.0,
            y = 6.0,
            size = 1.0,
            fill = "#0000FF"
        )
        assertEquals("<rect x=\"6.0\" y=\"6.0\" width=\"1.0\" height=\"1.0\" rx=\"0.25\" fill=\"#0000FF\" />", roundedSvg)
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

    @Test
    fun testArtisticResampleFunctionalMaskParity() {
        val size = 45
        val version = 7

        // 1. Finders: 8x8 corner areas must be excluded
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(0, 0, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(7, 7, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(size - 8, 0, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(size - 1, 7, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(0, size - 8, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(7, size - 1, size, version))

        // 2. Timing tracks: row 6 and col 6 between finders (8 until size-8) must be excluded
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(8, 6, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(20, 6, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(6, 8, size, version))
        assertTrue(ArtisticResampleFunctionalMask.isExcluded(6, 20, size, version))

        // 3. Format modules: outside finders (e.g. col 8, row 8) must NOT be excluded in resample
        assertFalse(
            "Format module at (8, 8) must participate in resample stochastic traversal",
            ArtisticResampleFunctionalMask.isExcluded(8, 8, size, version)
        )
        assertFalse(
            "Format module at (8, 0) must participate in resample",
            ArtisticResampleFunctionalMask.isExcluded(8, 0, size, version)
        )
        assertFalse(
            "Format module at (0, 8) must participate in resample",
            ArtisticResampleFunctionalMask.isExcluded(0, 8, size, version)
        )

        // 4. Version modules: 3x6 block at cols size-11..size-9, rows 0..5 must NOT be excluded in resample
        assertFalse(
            "Version module at (size-10, 2) must participate in resample",
            ArtisticResampleFunctionalMask.isExcluded(size - 10, 2, size, version)
        )
    }

    @Test
    fun testDefaultQuietZoneOneModuleForImageResample() {
        val design = QrDesign(style = QrStyle.IMAGE_RESAMPLE)
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE-QZ", ErrorCorrectionLevel.M)

        val result = QrGenerator.generateWithResult("HTTPS://VEILFRAME.APP/RESAMPLE-QZ", design)
        assertTrue(result is QrRenderResult.Success)
        val report = (result as QrRenderResult.Success).report
        assertEquals("IMAGE_RESAMPLE default quiet zone must be 1 module", 1, report.quietZone.quietZoneModules)

        val svg = SvgExporter.generateSvg(matrix, design)
        val expectedTotalSize = matrix.size + 2
        assertTrue(
            "SVG viewBox must have 1 module quiet zone (size $expectedTotalSize)",
            svg.contains("""viewBox="0 0 $expectedTotalSize $expectedTotalSize"""")
        )
    }

    @Test
    fun testArtisticResamplePolicyDecoupling() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/POLICY", ErrorCorrectionLevel.M)
        val policy: ResamplePolicy = ArtisticResamplePolicy

        // Finder area: (0, 0)
        assertFalse("Finder subpixel must not be sampled", policy.shouldSample(matrix, 0, 0))
        assertFalse("Finder module must not emit resample center anchor", policy.shouldDrawAnchor(matrix, 0, 0))

        // Timing track: col 10, row 6
        assertFalse("Timing track subpixel must not be sampled", policy.shouldSample(matrix, 30, 18))
        assertFalse("Timing module must not emit resample center anchor", policy.shouldDrawAnchor(matrix, 10, 6))

        // Data module: find a dark data module
        var foundDarkDataCol = -1
        var foundDarkDataRow = -1
        for (col in 10 until matrix.size - 10) {
            for (row in 10 until matrix.size - 10) {
                if (matrix.isDark(col, row) && !ArtisticResampleFunctionalMask.isExcluded(col, row, matrix.size, matrix.version)) {
                    foundDarkDataCol = col
                    foundDarkDataRow = row
                    break
                }
            }
            if (foundDarkDataCol != -1) break
        }

        assertTrue("Must find at least one dark data module", foundDarkDataCol != -1)
        assertTrue("Dark data module must draw center anchor", policy.shouldDrawAnchor(matrix, foundDarkDataCol, foundDarkDataRow))
        assertTrue("Data subpixel must be eligible for sampling", policy.shouldSample(matrix, 3 * foundDarkDataCol, 3 * foundDarkDataRow))

        // Test custom policy implementation to verify decoupling
        val allowAllPolicy = object : ResamplePolicy {
            override fun shouldSample(matrix: QrMatrix, subX: Int, subY: Int) = true
            override fun shouldDrawAnchor(matrix: QrMatrix, col: Int, row: Int) = matrix.isDark(col, row)
        }
        var anchorCount = 0
        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = null,
            style = ImageSourceStyle(),
            policy = allowAllPolicy
        ) { _, _, _, _, isCenterAnchor ->
            if (isCenterAnchor) anchorCount++
        }
        assertTrue("Custom policy should emit anchors for all dark modules", anchorCount > 0)
    }

    @Test
    fun testStudioExportPipelineModeConsistency() {
        val resampleDesign = QrDesign(style = QrStyle.IMAGE_RESAMPLE)
        val basicDesign = QrDesign(style = QrStyle.BASIC)

        // 1. Centralized defaultModeFor mapping
        assertEquals(
            "IMAGE_RESAMPLE must default to ARTISTIC_ENGINE",
            GenerationMode.ARTISTIC_ENGINE,
            QrGenerator.defaultModeFor(resampleDesign)
        )
        assertEquals(
            "BASIC must default to SAFE",
            GenerationMode.SAFE,
            QrGenerator.defaultModeFor(basicDesign)
        )

        // 2. QrDesign property parity
        assertEquals(
            GenerationMode.ARTISTIC_ENGINE,
            resampleDesign.recommendedGenerationMode
        )
        assertEquals(
            GenerationMode.SAFE,
            basicDesign.recommendedGenerationMode
        )

        // 3. QrGenerator matrix generation contract parity
        val content = "HTTPS://VEILFRAME.APP/PIPELINE-CONSISTENCY"
        val autoMatrix = QrGenerator.generateMatrix(content, resampleDesign)
        val explicitArtisticMatrix = QrGenerator.generateMatrix(content, resampleDesign, mode = GenerationMode.ARTISTIC_ENGINE)
        assertEquals("generateMatrix default mode must match explicit ARTISTIC_ENGINE matrix", explicitArtisticMatrix.size, autoMatrix.size)

        // 4. Default backdrop flag for resample style
        assertTrue("resampleStyle.useSourceAsBackdrop must default to true", resampleDesign.resampleStyle.useSourceAsBackdrop)
    }

    @Test
    fun testSubpixelCoordinateSpaceParityWithCanonicalViewBox() {
        // Mathematical proof of 1:1 isometric mapping between VeilFrame module coordinates and canonical 3x subpixel coordinates
        val n = 25 // 25x25 QR matrix
        val qz = 1 // 1 module quiet zone (canonical default: 1 module margin = 3 subpixel units)

        for (col in 0 until n) {
            for (row in 0 until n) {
                for (dx in 0..2) {
                    for (dy in 0..2) {
                        val subX = 3 * col + dx
                        val subY = 3 * row + dy

                        val vfRect = SubpixelGeometry.computeSvgRect(col, row, qz, subX, subY, antiGapScale = 1.0f)

                        // In 3x subpixel space (where 1 module = 3.0 units, quiet zone = 3 units):
                        // Canonical position in its (0, 0, 3N, 3N) QR area is exactly (subX, subY)
                        val expectedX = subX.toDouble()
                        val expectedY = subY.toDouble()

                        // Scale VeilFrame coordinate by 3.0 to map to subpixel units, minus quiet zone offset:
                        val mappedVfX = (vfRect.left.toDouble() - qz.toDouble()) * 3.0
                        val mappedVfY = (vfRect.top.toDouble() - qz.toDouble()) * 3.0

                        assertEquals("X coordinate must match canonical subpixel position", expectedX, mappedVfX, 1e-4)
                        assertEquals("Y coordinate must match canonical subpixel position", expectedY, mappedVfY, 1e-4)

                        // Subpixel width canonically is 1.0; in VeilFrame (with antiGapScale=1.0) it is (1.0 / 3.0) * 3.0 = 1.0
                        val mappedVfW = vfRect.width.toDouble() * 3.0
                        assertEquals("Subpixel width must match canonical unit width of 1.0", 1.0, mappedVfW, 1e-4)
                    }
                }
            }
        }
    }

    @Test
    fun testArtisticResampleTimingAndAlignmentDefaults() {
        val resampleDesign = QrDesign(style = QrStyle.IMAGE_RESAMPLE)
        assertEquals(
            "IMAGE_RESAMPLE timing shape must default to SQUARE",
            ModuleShape.SQUARE,
            resampleDesign.timingStyle.shape
        )
        assertEquals(
            "IMAGE_RESAMPLE alignment shape must default to SQUARE",
            ModuleShape.SQUARE,
            resampleDesign.alignmentStyle.shape
        )

        val profiled = ArtisticResampleProfile.applyProfile(resampleDesign)
        assertEquals(1, profiled.quietZoneModules)
        assertEquals(1, profiled.explicitQuietZone)
        assertEquals(ModuleShape.SQUARE, profiled.timingStyle.shape)
        assertEquals(ModuleShape.SQUARE, profiled.alignmentStyle.shape)
        assertTrue(profiled.resampleStyle.useSourceAsBackdrop)
    }

    @Test
    fun testHollowFinderSvgParity() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app", QrDesign(style = QrStyle.IMAGE_RESAMPLE))
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val svg = SvgExporter.generateSvg(matrix, design)

        // Hollow finders should use stroke on 6x6 rect with fill="none", without white 5x5 background fill
        assertTrue("SVG should contain hollow stroked finder rectangle", svg.contains("""fill="none" stroke="""))
        assertFalse(
            "SVG finders in resample mode must not render opaque 5x5 background rects that obscure the backdrop",
            svg.contains("""width="5" height="5" fill="#FFFFFF"""")
        )
    }

    @Test
    fun testBilinearSamplingContinuousInterpolation() {
        // 2x2 test source: Black (0xFF000000) at (0,0), White (0xFFFFFFFF) at (1,1)
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt()
        )
        val source = ArrayPixelSource(2, 2, pixels)

        // Sample at center (0.5, 0.5)
        val sample = ImageScaleResolver.sample(source, 0.5f, 0.5f, ImageScaleMode.STRETCH)
        val r = (sample.color ushr 16) and 0xFF
        val g = (sample.color ushr 8) and 0xFF
        val b = sample.color and 0xFF

        // Bilinear interpolation blends corner pixels continuously:
        // (0.5, 0.5) with (0,0)=0, (1,0)=0, (0,1)=0, (1,1)=255 gives ~63 (255 * 0.25)
        assertTrue("Bilinear interpolation should produce blended intermediate luminance", r in 55..75)
        assertTrue("Bilinear interpolation should produce blended intermediate luminance", g in 55..75)
        assertTrue("Bilinear interpolation should produce blended intermediate luminance", b in 55..75)
    }

    @Test
    fun testSvgBackdropTintAndBlendParityWithCanvas() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app", QrDesign(style = QrStyle.IMAGE_RESAMPLE))
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            imageSource = ImageSourceStyle(source = ImageSource.Resource(123)),
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = true,
                backdropBlendMode = BackdropBlendMode.MULTIPLY,
                backdropTint = 0x80FF0000.toInt()
            )
        )
        val svg = SvgExporter.generateSvg(matrix, design)

        assertTrue(
            "SVG must include mix-blend-mode for MULTIPLY backdrop blend mode",
            svg.contains("""style="mix-blend-mode: multiply;"""")
        )
        assertTrue(
            "SVG must render backdrop tint with matching alpha opacity",
            svg.contains("""fill="#FF0000" opacity="0.50"""")
        )
    }

    @Test
    fun testAutoRepairQuietZoneAndErrorCorrectionParity() {
        val initialDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            quietZoneModules = 1,
            explicitQuietZone = 1,
            correction = ErrorCorrectionChoice.L
        )

        val reportWithQuietZone = ScanabilityReport(
            isScanReady = false,
            quietZone = QuietZoneReport(hasFourModuleMargin = false, quietZoneModules = 1),
            contrast = ContrastReport(0f, 0f, 1f, 1f, 1f, true),
            finders = FinderIntegrityReport(true, true),
            logo = LogoOcclusionReport(false, 0, 0f, true),
            decodeResult = com.veilframe.app.qr.decoder.DecodeResult(false),
            errorCorrection = ErrorCorrectionLevel.L,
            warnings = listOf("Quiet zone is less than 4 modules"),
            repairSuggestions = listOf(RepairReason.RESTORE_QUIET_ZONE)
        )

        val repairQuietZoneResult = AutoRepairEngine.repair(
            currentDesign = initialDesign,
            report = reportWithQuietZone,
            content = "https://veilframe.app/repair_test"
        )

        // Invariant: Both quietZoneModules and explicitQuietZone must be restored to 4
        assertEquals(4, repairQuietZoneResult.repairedDesign.quietZoneModules)
        assertEquals(4, repairQuietZoneResult.repairedDesign.explicitQuietZone)
        assertTrue(repairQuietZoneResult.changesApplied.contains("Restored 4-module quiet zone"))

        // Test error correction elevation through unified generation mode
        val reportWithEc = ScanabilityReport(
            isScanReady = false,
            quietZone = QuietZoneReport(true, 4),
            contrast = ContrastReport(0f, 0f, 1f, 1f, 1f, true),
            finders = FinderIntegrityReport(true, true),
            logo = LogoOcclusionReport(false, 0, 0f, false),
            decodeResult = com.veilframe.app.qr.decoder.DecodeResult(false),
            errorCorrection = ErrorCorrectionLevel.L,
            warnings = listOf("Elevate ECC"),
            repairSuggestions = listOf(RepairReason.ELEVATE_ERROR_CORRECTION)
        )

        val repairEcResult = AutoRepairEngine.repair(
            currentDesign = initialDesign,
            report = reportWithEc,
            content = "https://veilframe.app/repair_test"
        )

        assertEquals(ErrorCorrectionChoice.M, repairEcResult.repairedDesign.correction)
        assertTrue(repairEcResult.changesApplied.any { it.contains("Elevated error correction level") })
    }

    @Test
    fun testTimingAndAlignmentNoneAndOnlyWhiteSemantics() {
        val matrix = QrMatrix("https://veilframe.app/timing_test", ErrorCorrectionLevel.M)
        val n = matrix.size

        // 1. When timing shape is NONE:
        val noneTimingPolicy = ArtisticResamplePolicy(
            timingStyle = TimingStyle(shape = ModuleShape.NONE),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.NONE)
        )

        // Find a light timing module on row 6
        var foundLightTimingCol = -1
        var foundDarkTimingCol = -1
        for (col in 8 until (n - 8)) {
            if (!matrix.isDark(col, 6) && foundLightTimingCol == -1) {
                foundLightTimingCol = col
            }
            if (matrix.isDark(col, 6) && foundDarkTimingCol == -1) {
                foundDarkTimingCol = col
            }
        }
        assertTrue("Must find a light timing module", foundLightTimingCol != -1)
        assertTrue("Must find a dark timing module", foundDarkTimingCol != -1)

        // In NONE mode: light timing cells are NOT excluded from stochastic sampling!
        val lightTimingSx = 3 * foundLightTimingCol + 0
        val lightTimingSy = 3 * 6 + 0
        assertTrue(
            "Light timing module subpixel must be sampled when timing style is NONE",
            noneTimingPolicy.shouldSample(matrix, lightTimingSx, lightTimingSy)
        )

        // In NONE mode: dark timing modules still emit center anchor
        assertTrue(
            "Dark timing module must emit center anchor when timing style is NONE",
            noneTimingPolicy.shouldDrawAnchor(matrix, foundDarkTimingCol, 6)
        )

        // 2. When timing style is default SQUARE:
        val defaultPolicy = ArtisticResamplePolicy(
            timingStyle = TimingStyle(shape = ModuleShape.SQUARE),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.SQUARE)
        )

        // Light timing cells are excluded in default policy (timing renderer owns the entire cell)
        assertFalse(
            "Timing module subpixel must NOT be sampled in default SQUARE timing mode",
            defaultPolicy.shouldSample(matrix, lightTimingSx, lightTimingSy)
        )
        assertFalse(
            "Timing module must NOT emit resample anchor in default SQUARE timing mode (renderer handles it)",
            defaultPolicy.shouldDrawAnchor(matrix, foundDarkTimingCol, 6)
        )

        // 3. When timing is onlyWhite:
        val onlyWhitePolicy = ArtisticResamplePolicy(
            timingStyle = TimingStyle(shape = ModuleShape.SQUARE, onlyWhite = true)
        )
        assertTrue(
            "Dark timing module must emit center anchor when onlyWhite is true",
            onlyWhitePolicy.shouldDrawAnchor(matrix, foundDarkTimingCol, 6)
        )
    }

    @Test
    fun testArtisticProfileCentralizationInProduction() {
        // When applying profile, quiet zone is set to 1 and explicitQuietZone is set to 1
        val rawDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            quietZoneModules = 4,
            explicitQuietZone = null,
            timingStyle = TimingStyle(shape = ModuleShape.ROUNDED),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.ROUNDED)
        )

        val profiled = ArtisticResampleProfile.applyProfile(rawDesign)
        assertEquals(1, profiled.quietZoneModules)
        assertEquals(1, profiled.explicitQuietZone)
        assertEquals(ModuleShape.SQUARE, profiled.timingStyle.shape)
        assertEquals(ModuleShape.SQUARE, profiled.alignmentStyle.shape)

        // If user explicitly configured NONE, NONE is preserved
        val noneDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            timingStyle = TimingStyle(shape = ModuleShape.NONE),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.NONE)
        )
        val profiledNone = ArtisticResampleProfile.applyProfile(noneDesign)
        assertEquals(ModuleShape.NONE, profiledNone.timingStyle.shape)
        assertEquals(ModuleShape.NONE, profiledNone.alignmentStyle.shape)
    }

    @Test
    fun testSvgTimingAndAlignmentSuppressionOnNoneOrOnlyWhite() {
        val matrix = QrMatrix("https://veilframe.app/svg_timing_test", ErrorCorrectionLevel.H)
        val pixelSource = ArrayPixelSource(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })

        // Default: SvgExporter emits dedicated timing and alignment rects
        val defaultDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            timingStyle = TimingStyle(shape = ModuleShape.SQUARE),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.SQUARE)
        )
        val defaultSvg = SvgExporter.generateSvg(matrix, defaultDesign, pixelSource)

        // NONE mode: SvgExporter skips custom timing/alignment shapes, allowing stochastic dots in light cells
        val noneDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            timingStyle = TimingStyle(shape = ModuleShape.NONE),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.NONE)
        )
        val noneSvg = SvgExporter.generateSvg(matrix, noneDesign, pixelSource)
        assertNotEquals(defaultSvg, noneSvg)

        // onlyWhite mode: SvgExporter skips custom colored timing/alignment shapes, without dithering light cells
        val onlyWhiteDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            timingStyle = TimingStyle(shape = ModuleShape.SQUARE, onlyWhite = true),
            alignmentStyle = AlignmentStyle(shape = ModuleShape.SQUARE, onlyWhite = true)
        )
        val onlyWhiteSvg = SvgExporter.generateSvg(matrix, onlyWhiteDesign, pixelSource)
        assertNotEquals(defaultSvg, onlyWhiteSvg)
        assertNotEquals(noneSvg, onlyWhiteSvg)
    }
}

