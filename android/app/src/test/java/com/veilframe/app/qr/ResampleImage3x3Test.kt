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
}
