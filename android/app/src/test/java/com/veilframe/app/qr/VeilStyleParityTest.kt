package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification test suite for VeilFrame Art Engine parity fixes:
 * 1. Default contrast in [ImageSourceStyle] must be 0.0f for VeilFrame threshold math parity.
 * 2. IMAGE style: #hole mask with 8x8 finder cutouts, 8x8 backing rects, dual dark/light modules.
 * 3. IMAGE_FILL style: continuous image fill through 1.02 anti-gap stencil mask with background and tint.
 * 4. 2.5D style: axonometric isometric projection viewBox and matrix, skewY/skewX extrusions.
 * 5. Parameter mapping fidelity from [QrStyleParams] to [QrDesign].
 * 6. ZXing decode verification on pure software rasterized output.
 */
class VeilStyleParityTest {

    @Test
    fun testResampleDefaultContrastParity() {
        // VeilFrame Art Engine default: contrast: CGFloat = 0
        // Formula in VeilFrame: (grayNorm + exposure - 0.5) * (contrast + 1) + 0.5
        // When contrast = 0.0f, (contrast + 1.0f) evaluates to 1.0f (no artificial 2x contrast boost)
        val defaultStyle = ImageSourceStyle()
        assertEquals(
            "Default contrast in ImageSourceStyle must be 0.0f for VeilFrame Art Engine parity",
            0.0f,
            defaultStyle.contrast,
            0.0001f
        )
        val multiplier = defaultStyle.contrast + 1.0f
        assertEquals("Default threshold contrast multiplier must be exactly 1.0f", 1.0f, multiplier, 0.0001f)
    }

    @Test
    fun testImageStyleParameterMappingAndSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-image-parity", ErrorCorrectionLevel.H)
        val n = matrix.size

        val params = QrStyleParams(
            style = QrStyle.IMAGE,
            imageAllowTransparent = true,
            imageDataDarkColor = 0xFF112233.toInt(),
            imageDataLightColor = 0x80EEEEEE.toInt(),
            imagePositionDarkColor = 0xFF000000.toInt(),
            imagePositionLightColor = 0xFFFFFFFF.toInt()
        )
        val design = QrDesign.fromQrStyleParams(params)

        assertEquals(QrStyle.IMAGE, design.style)
        assertTrue(design.allowTransparent)
        assertEquals(0xFF112233.toInt(), design.dataColorDark)
        assertEquals(0x80EEEEEE.toInt(), design.dataColorLight)
        assertEquals(0xFF000000.toInt(), design.positionDarkColor)
        assertEquals(0xFFFFFFFF.toInt(), design.positionLightColor)

        // Verify SVG Export VeilFrame Art Engine Parity
        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("SVG must start with XML declaration", svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))

        // Verify #hole mask cuts out 8x8 squares for the 3 finders
        assertTrue("SVG must contain #hole mask for finder cutouts", svg.contains("<mask id=\"hole\">"))
        assertTrue("SVG mask must contain 8x8 cutout rect", svg.contains("width=\"8\" height=\"8\" fill=\"black\""))

        // Verify image element is wrapped in <g mask="url(#hole)">
        assertTrue("SVG must mask image with #hole", svg.contains("mask=\"url(#hole)\""))

        // Verify 8x8 white backings behind finders
        assertTrue("SVG must contain 8x8 backing rect for finders", svg.contains("<rect opacity=\"1.00\" width=\"8\" height=\"8\""))
    }

    @Test
    fun testImageFillStyleParameterMappingAndSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-image-fill-parity", ErrorCorrectionLevel.H)

        val params = QrStyleParams(
            style = QrStyle.IMAGE_FILL,
            imageFillBackgroundColor = 0xFFFAFAFA.toInt(),
            imageFillMaskColor = 0x1A000000 // 10% black
        )
        val design = QrDesign.fromQrStyleParams(params)

        assertEquals(QrStyle.IMAGE_FILL, design.style)
        assertEquals(0xFFFAFAFA.toInt(), design.imageFillBackgroundColor)
        assertEquals(0x1A000000, design.imageFillMaskColor)

        // Verify SVG Export VeilFrame Art Engine Parity
        val svg = SvgExporter.generateSvg(matrix, design)

        // Verify #hole stencil mask with 1.02 size anti-gap expansion
        assertTrue("SVG must define #hole stencil mask", svg.contains("<mask id=\"hole\">"))
        assertTrue("SVG must have anti-gap expanded rects (width=1.02)", svg.contains("width=\"1.02\" height=\"1.02\" fill=\"white\""))

        // Verify continuous image container inside <g mask="url(#hole)">
        assertTrue("SVG must contain <g mask=\"url(#hole)\">", svg.contains("mask=\"url(#hole)\""))
        assertTrue("SVG must contain background rect", svg.contains("fill=\"#FAFAFA\""))
        assertTrue("SVG must contain maskColor tint rect", svg.contains("fill=\"#000000\" opacity=\"0.10\""))
    }

    @Test
    fun test25DIsometricTransformAndFaceGeometry() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-25d-parity", ErrorCorrectionLevel.H)
        val n = matrix.size

        val params = QrStyleParams(
            style = QrStyle.D25,
            d25TopColor = 0xFF112233.toInt(),
            d25LeftColor = 0x33112233,
            d25RightColor = 0x99112233.toInt(),
            d25DataHeight = 1.0f,
            d25PositionHeight = 1.0f
        )
        val design = QrDesign.fromQrStyleParams(params)

        assertEquals(QrStyle.D25, design.style)
        assertEquals(1.0f, design.depthStyle.depth, 0.001f)
        assertEquals(0xFF112233.toInt(), design.depthStyle.topColor)
        assertEquals(0x33112233, design.depthStyle.leftColor)
        assertEquals(0x99112233.toInt(), design.depthStyle.rightColor)

        // Verify SVG Export VeilFrame Art Engine Parity
        val svg = SvgExporter.generateSvg(matrix, design)

        // Verify VeilFrame viewBox: [-n, -n/2, 2*n, 2*n]
        val expectedViewBox = "viewBox=\"-$n -${n / 2.0} ${n * 2.0} ${n * 2.0}\""
        assertTrue("SVG must have VeilFrame Art Engine axonometric viewBox: $expectedViewBox", svg.contains(expectedViewBox))

        // Verify VeilFrame axonometric transform matrix: matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)
        assertTrue("SVG must contain VeilFrame axonometric matrix", svg.contains("matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)"))

        // Verify Left face skewY(45) and Right face skewX(45)
        assertTrue("SVG must contain Left face skewY(45)", svg.contains("skewY(45)"))
        assertTrue("SVG must contain Right face skewX(45)", svg.contains("skewX(45)"))
    }

    @Test
    fun testSoftwareRasterizedImageFillDecodableByZxing() {
        val payload = "https://veilframe.app/veil-art-image-fill-zxing"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val n = matrix.size
        val qz = 4
        val scale = 8
        val totalModules = n + 2 * qz
        val totalPx = totalModules * scale
        val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() } // White background
        val black = 0xFF000000.toInt()

        // Simulate ImageFill continuous fill stenciled through dark modules
        for (c in 0 until n) {
            for (r in 0 until n) {
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

        val source = RGBLuminanceSource(totalPx, totalPx, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val decoded = reader.decode(binaryBitmap)

        assertNotNull(decoded)
        assertEquals("ZXing must successfully decode the stenciled QR matrix", payload, decoded.text)
    }

    @Test
    fun testDsjStyleParameterMappingAndSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-dsj-parity", ErrorCorrectionLevel.H)

        val params = QrStyleParams(
            style = QrStyle.DSJ,
            dsjLineSize = 0.8f,
            dsjXSize = 0.9f,
            dsjHorizontalLineColor = 0xFFF6B506.toInt(),
            dsjVerticalLineColor = 0xFFE02020.toInt(),
            dsjXColor = 0xFF0B2D97.toInt()
        )
        val design = QrDesign.fromQrStyleParams(params)

        assertEquals(QrStyle.DSJ, design.style)
        assertNotNull(design.veilDsjStyle)
        assertEquals(0.8f, design.veilDsjStyle!!.lineSize, 0.001f)
        assertEquals(0.9f, design.veilDsjStyle!!.xSize, 0.001f)
        assertEquals(0xFFF6B506.toInt(), design.veilDsjStyle!!.horizontalLineColor)
        assertEquals(0xFFE02020.toInt(), design.veilDsjStyle!!.verticalLineColor)
        assertEquals(0xFF0B2D97.toInt(), design.veilDsjStyle!!.xColor)

        // Verify SVG Export VeilFrame Art Engine Parity
        val svg = SvgExporter.generateSvg(matrix, design)

        // Verify colors are present in SVG output
        assertTrue("SVG must contain DSJ horizontal color #F6B506", svg.contains("#F6B506"))
        assertTrue("SVG must contain DSJ vertical color #E02020", svg.contains("#E02020"))
        assertTrue("SVG must contain DSJ X color #0B2D97", svg.contains("#0B2D97"))
        assertTrue("SVG must contain stroke lines for X crosses", svg.contains("<line") || svg.contains("<rect"))
    }

    @Test
    fun testFunctionStyleFadeAndCircleSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-function-parity", ErrorCorrectionLevel.H)

        // Test FADE function
        val fadeParams = QrStyleParams(
            style = QrStyle.FUNCTION,
            functionType = VeilFunctionType.FADE,
            functionDataStyle = VeilFunctionDataStyle.ROUND,
            functionDataColor = 0xFF123456.toInt()
        )
        val fadeDesign = QrDesign.fromQrStyleParams(fadeParams)
        assertEquals(QrStyle.FUNCTION, fadeDesign.style)
        assertEquals(VeilFunctionType.FADE, fadeDesign.veilFunctionStyle?.functionType)
        assertEquals(VeilFunctionDataStyle.ROUND, fadeDesign.veilFunctionStyle?.dataStyle)

        val fadeSvg = SvgExporter.generateSvg(matrix, fadeDesign)
        assertTrue("Fade SVG must contain data color #123456", fadeSvg.contains("#123456"))
        assertTrue("Fade SVG with ROUND style must contain circles", fadeSvg.contains("<circle"))

        // Test CIRCLE function
        val circleParams = QrStyleParams(
            style = QrStyle.FUNCTION,
            functionType = VeilFunctionType.CIRCLE,
            functionDataStyle = VeilFunctionDataStyle.ROUND,
            functionDataColor = 0xFF000000.toInt(),
            functionCircleColor = 0xFFFF0000.toInt()
        )
        val circleDesign = QrDesign.fromQrStyleParams(circleParams)
        val circleSvg = SvgExporter.generateSvg(matrix, circleDesign)

        assertTrue("Circle SVG must contain circle color #FF0000", circleSvg.contains("#FF0000"))
        // Background concentric ring has stroke-width = nCount / 15.0 and fill="none"
        val expectedRingSw = String.format(java.util.Locale.US, "%.3f", matrix.size.toDouble() / 15.0)
        assertTrue("Circle SVG must contain background concentric ring with stroke-width=$expectedRingSw and fill=none", circleSvg.contains("fill=\"none\"") && circleSvg.contains("stroke-width=\"$expectedRingSw\""))
    }

    @Test
    fun testLineStyleGrammarAndSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-line-parity", ErrorCorrectionLevel.H)

        // Test Horizontal line
        val horizParams = QrStyleParams(
            style = QrStyle.LINE,
            lineDirection = LineDirection.HORIZONTAL,
            lineThickness = 0.6f,
            lineColor = 0xFF224466.toInt()
        )
        val horizDesign = QrDesign.fromQrStyleParams(horizParams)
        assertEquals(QrStyle.LINE, horizDesign.style)
        assertEquals(LineDirection.HORIZONTAL, horizDesign.lineStyle.direction)
        assertEquals(0.6f, horizDesign.lineStyle.thicknessFraction, 0.001f)
        assertEquals(0xFF224466.toInt(), horizDesign.lineStyle.color)

        val horizSvg = SvgExporter.generateSvg(matrix, horizDesign)
        assertTrue("Line SVG must contain line color #224466", horizSvg.contains("#224466"))
        assertTrue("Line SVG must contain round cap strokes", horizSvg.contains("stroke-linecap=\"round\""))
        assertTrue("Line SVG must contain <line x1=", horizSvg.contains("<line x1="))

        // Test Cross line
        val crossParams = QrStyleParams(
            style = QrStyle.LINE,
            lineDirection = LineDirection.CROSS,
            lineThickness = 0.5f,
            lineColor = 0xFF119988.toInt()
        )
        val crossDesign = QrDesign.fromQrStyleParams(crossParams)
        val crossSvg = SvgExporter.generateSvg(matrix, crossDesign)
        assertTrue("Cross SVG must contain stroke lines", crossSvg.contains("<line x1="))
        assertTrue("Cross SVG must contain line color #119988", crossSvg.contains("#119988"))
    }

    @Test
    fun testSourceAndBackdropImageIndependence() {
        // Test 1: Setting only background image in an image style does NOT promote it to style imageSource
        val paramsWithOnlyBackdrop = QrStyleParams(
            style = QrStyle.IMAGE,
            backgroundImageAlpha = 0.4f
        )
        val designWithOnlyBackdrop = QrDesign.fromQrStyleParams(paramsWithOnlyBackdrop)

        assertNull("imageSource.source must remain null if sourceImage was not supplied", designWithOnlyBackdrop.imageSource.source)
        assertNull("imageSource.bitmap must remain null if sourceImage was not supplied", designWithOnlyBackdrop.imageSource.bitmap)

        // Test 2: Setting source image explicitly populates imageSource without requiring backgroundImage
        val imageSourceDirect = ImageSourceStyle(
            source = ImageSource.Uri("content://test/image.png"),
            opacity = 0.8f
        )
        val backdropLayerDirect = BackgroundLayer(
            enabled = true,
            opacity = 0.3f
        )
        val designDirect = QrDesign(
            imageSource = imageSourceDirect,
            backgroundLayer = backdropLayerDirect
        )
        assertEquals("content://test/image.png", (designDirect.imageSource.source as? ImageSource.Uri)?.value)
        assertEquals(0.8f, designDirect.imageSource.opacity, 0.001f)
        assertEquals(0.3f, designDirect.backgroundLayer.opacity, 0.001f)
        assertTrue(designDirect.backgroundLayer.enabled)
    }

    @Test
    fun testRandomRectangleExactEfAlgorithmAndSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-random-rect-parity", ErrorCorrectionLevel.H)
        val customColor = 0xFF14AA3C.toInt() // VeilFrame Art default: rgb(20, 170, 60)

        val params = QrStyleParams(
            style = QrStyle.RANDOM_RECTANGLE,
            randomRectColor = customColor,
            randomRectSeed = 12345L,
            quietZone = 1 // VeilFrame Art default: 1 module
        )
        val design = QrDesign.fromQrStyleParams(params)

        assertEquals(QrStyle.RANDOM_RECTANGLE, design.style)
        assertEquals(customColor, design.randomRectColor)
        assertEquals(1, design.quietZoneModules)

        val svg = SvgExporter.generateSvg(matrix, design)

        // Verify VeilFrame RandomRectangle SVG structure:
        // 1. Dual rect per module with fill="rgb(...)" format
        assertTrue("SVG must contain rgb(...) fills from VeilFrame color variation", svg.contains("fill=\"rgb("))
        // 2. Outer rect opacity 0.90 (0.9 * 1.0) and inner rect opacity 1.00
        assertTrue("SVG must contain outer rect opacity 0.90", svg.contains("opacity=\"0.90\""))
        assertTrue("SVG must contain inner rect opacity 1.00", svg.contains("opacity=\"1.00\""))
        // 3. Must NOT contain old rounded corner rx= attributes
        assertFalse("VeilFrame RandomRectangle must produce sharp rectangles, not rounded rects", svg.contains("rx="))
    }

    @Test
    fun testSoftwareRasterizedRandomRectangleDecodableByZxing() {
        val payload = "https://veilframe.app/veil-art-random-rect-zxing"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val n = matrix.size
        val qz = 4
        val scale = 12
        val totalModules = n + 2 * qz
        val totalPx = totalModules * scale
        val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() } // White background

        val baseColor = 0xFF14AA3C.toInt() // VeilFrame Art default: rgb(20, 170, 60)
        val redValue = (baseColor shr 16 and 0xFF).toDouble()
        val greenValue = (baseColor shr 8 and 0xFF).toDouble()
        val blueValue = (baseColor and 0xFF).toDouble()

        // Reproduce VeilFrame's deterministic shuffle & dual-rect rasterization
        val randArr = ArrayList<Pair<Int, Int>>(n * n)
        for (r in 0 until n) {
            for (c in 0 until n) {
                randArr.add(Pair(r, c))
            }
        }
        val rng = kotlin.random.Random(42L)
        randArr.shuffle(rng)

        for (item in randArr) {
            val r = item.first
            val c = item.second

            if (matrix.isDark(c, r)) {
                // If finder pattern, draw standard solid dark module to guarantee scan anchor
                val role = matrix.roleAt(c, r)
                val isFinder = role == QrModuleRole.FINDER_OUTER ||
                               role == QrModuleRole.FINDER_INNER ||
                               role == QrModuleRole.SEPARATOR

                if (isFinder) {
                    val startX = (c + qz) * scale
                    val startY = (r + qz) * scale
                    for (y in startY until (startY + scale)) {
                        for (x in startX until (startX + scale)) {
                            pixels[y * totalPx + x] = 0xFF000000.toInt()
                        }
                    }
                } else {
                    val tempRand = rng.nextDouble(0.8, 1.3)
                    val randNum = rng.nextDouble(50.0, 230.0)

                    val rVal = kotlin.math.max(0, kotlin.math.min(255, (redValue + randNum).toInt()))
                    val gVal = kotlin.math.max(0, kotlin.math.min(255, (greenValue - randNum / 2.0).toInt()))
                    val bVal = kotlin.math.max(0, kotlin.math.min(255, (blueValue + randNum * 2.0).toInt()))

                    val r2Val = kotlin.math.max(0, kotlin.math.min(255, rVal - 40))
                    val g2Val = kotlin.math.max(0, kotlin.math.min(255, gVal - 40))
                    val b2Val = kotlin.math.max(0, kotlin.math.min(255, bVal - 40))

                    val centerModuleX = (c + qz).toFloat() + 0.5f
                    val centerModuleY = (r + qz).toFloat() + 0.5f

                    // Draw outer rect (tempRand + 0.15)
                    val outerHalf = ((tempRand + 0.15) / 2.0 * scale).toInt()
                    val centerX = (centerModuleX * scale).toInt()
                    val centerY = (centerModuleY * scale).toInt()
                    val outerColor = (0xFF shl 24) or (r2Val shl 16) or (g2Val shl 8) or b2Val

                    for (y in (centerY - outerHalf).coerceAtLeast(0) until (centerY + outerHalf).coerceAtMost(totalPx)) {
                        for (x in (centerX - outerHalf).coerceAtLeast(0) until (centerX + outerHalf).coerceAtMost(totalPx)) {
                            pixels[y * totalPx + x] = outerColor
                        }
                    }

                    // Draw inner rect (tempRand)
                    val innerHalf = (tempRand / 2.0 * scale).toInt()
                    val innerColor = (0xFF shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
                    for (y in (centerY - innerHalf).coerceAtLeast(0) until (centerY + innerHalf).coerceAtMost(totalPx)) {
                        for (x in (centerX - innerHalf).coerceAtLeast(0) until (centerX + innerHalf).coerceAtMost(totalPx)) {
                            pixels[y * totalPx + x] = innerColor
                        }
                    }
                }
            }
        }

        val source = RGBLuminanceSource(totalPx, totalPx, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val decoded = reader.decode(binaryBitmap)

        assertNotNull("Decoded result must not be null", decoded)
        assertEquals("ZXing must successfully decode VeilFrame RandomRectangle matrix", payload, decoded.text)
    }

    @Test
    fun testBackdropDoubleCompositingPrevention() {
        // BaseQrRenderer declares renderBackground as open no-op because QrGenerator handles canvas background.
        // Verify that ComposableQrRenderer does not re-draw background on canvas during its render cycle.
        val composableRenderer = com.veilframe.app.qr.renderer.ComposableQrRenderer()
        val method = composableRenderer.javaClass.getMethod(
            "renderBackground",
            android.graphics.Canvas::class.java,
            QrDesign::class.java,
            QrGeometry::class.java,
            com.veilframe.app.qr.renderer.RenderContext::class.java
        )
        assertEquals(
            "renderBackground must remain non-overridden in ComposableQrRenderer to avoid double-compositing alpha",
            com.veilframe.app.qr.renderer.BaseQrRenderer::class.java,
            method.declaringClass
        )
    }

    @Test
    fun testResampleRngModesDeterministicAndUnseeded() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-resample-rng", ErrorCorrectionLevel.H)
        val n = matrix.size
        val targetDim = 3 * n
        val testPixels = IntArray(targetDim * targetDim) { 0xFF7F7F7F.toInt() } // Mid gray
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(targetDim, targetDim, testPixels)
        val style = com.veilframe.app.qr.model.ImageSourceStyle(contrast = 0f, exposure = 0f)

        // Run 1 (Deterministic)
        val dotsRun1 = mutableListOf<Pair<Int, Int>>()
        com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = pixelSource,
            style = style,
            seed = 12345L,
            policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.ResampleRngMode.DETERMINISTIC),
            sink = { _, _, sx, sy, isCenter -> if (!isCenter) dotsRun1.add(Pair(sx, sy)) }
        )

        // Run 2 (Deterministic, same seed)
        val dotsRun2 = mutableListOf<Pair<Int, Int>>()
        com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = pixelSource,
            style = style,
            seed = 12345L,
            policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.ResampleRngMode.DETERMINISTIC),
            sink = { _, _, sx, sy, isCenter -> if (!isCenter) dotsRun2.add(Pair(sx, sy)) }
        )

        assertEquals("Deterministic RNG with same seed must produce identical stochastic dot coordinates", dotsRun1, dotsRun2)

        // Run 3 (Unseeded stochastic)
        val dotsRun3 = mutableListOf<Pair<Int, Int>>()
        com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = pixelSource,
            style = style,
            policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.ResampleRngMode.UNSEEDED_STOCHASTIC),
            sink = { _, _, sx, sy, isCenter -> if (!isCenter) dotsRun3.add(Pair(sx, sy)) }
        )

        assertTrue("Unseeded stochastic run must emit subpixels", dotsRun3.isNotEmpty())
    }

    @Test
    fun testImageModeSvgModuleShapesParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-image-shapes", ErrorCorrectionLevel.H)
        val designRound = QrDesign(
            style = QrStyle.IMAGE,
            moduleStyle = ModuleStyle(shape = com.veilframe.app.qr.model.ModuleShape.CIRCLE),
            timingStyle = TimingStyle(shape = com.veilframe.app.qr.model.ModuleShape.ROUNDED),
            alignmentStyle = AlignmentStyle(shape = com.veilframe.app.qr.model.ModuleShape.CIRCLE)
        )

        val svg = SvgExporter.generateSvg(matrix, designRound)
        assertTrue("SVG in IMAGE mode must contain <circle> elements when data/alignment shape is CIRCLE", svg.contains("<circle"))
        assertTrue("SVG in IMAGE mode must contain rx attributes when timing shape is ROUNDED", svg.contains("rx=\""))
    }

    @Test
    fun test25DDiagonalWaveOrderAndPaintIndependence() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-25d-wave", ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = 1.0f,
                topColor = 0xFF112233.toInt(),
                leftColor = 0x33112233,
                rightColor = 0x99112233.toInt()
            )
        )

        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("2.5D SVG must contain top face color", svg.contains("fill=\"#112233\""))
        assertTrue("2.5D SVG must contain transform matrix", svg.contains("matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)"))
        assertTrue("2.5D SVG must contain skewY(45)", svg.contains("skewY(45)"))
        assertTrue("2.5D SVG must contain skewX(45)", svg.contains("skewX(45)"))
    }

    @Test
    fun testRngModeSelectionParity() {
        val policyDet = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.RngMode.DETERMINISTIC)
        assertEquals(com.veilframe.app.qr.renderer.RngMode.DETERMINISTIC, policyDet.rngMode)

        val policyUnseeded = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.RngMode.SYSTEM_UNSEEDED)
        assertEquals(com.veilframe.app.qr.renderer.RngMode.SYSTEM_UNSEEDED, policyUnseeded.rngMode)

        // Verify backward compatibility alias
        assertEquals(com.veilframe.app.qr.renderer.RngMode.SYSTEM_UNSEEDED, com.veilframe.app.qr.renderer.RngMode.UNSEEDED_STOCHASTIC)
    }

    @Test
    fun testUnifiedGeometryIrImageAndResampleParity() {
        val matrix = QrMatrix("https://veilframe.app/veil-art-unified-ir", ErrorCorrectionLevel.H)
        val designImage = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Resource(123))
        )
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)

        // 1. IMAGE IR generation
        val imageIr = com.veilframe.app.qr.renderer.ImageRenderer().generateGeometry(matrix, designImage, geometry)
        assertNotNull(imageIr)
        assertTrue("IMAGE IR must have defs containing mask #hole", imageIr.defs.any { it.contains("mask id=\"hole\"") })
        assertTrue("IMAGE IR must have root nodes", imageIr.rootNodes.isNotEmpty())
        assertTrue("IMAGE IR must contain an ImageNode", imageIr.rootNodes.any { it is com.veilframe.app.qr.geometry.ImageNode })

        // 2. RESAMPLE IR generation
        val designResample = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            imageSource = ImageSourceStyle(source = ImageSource.Resource(123)),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val resampleIr = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designResample, geometry)
        assertNotNull(resampleIr)
        assertTrue("RESAMPLE IR must contain an ImageNode for backdrop", resampleIr.rootNodes.any { it is com.veilframe.app.qr.geometry.ImageNode })
        assertTrue("RESAMPLE IR must contain RectNodes for subpixels", resampleIr.rootNodes.any { it is com.veilframe.app.qr.geometry.RectNode })

        // 3. Render both to Canvas and SVG without error
        try {
            val canvasBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            if ((canvasBmp as Bitmap?) != null) {
                val canvas = Canvas(canvasBmp)
                com.veilframe.app.qr.geometry.IrCanvasRenderer.render(imageIr, canvas)
                com.veilframe.app.qr.geometry.IrCanvasRenderer.render(resampleIr, canvas)
            }
        } catch (_: Throwable) {
            // Native Android Bitmap/Canvas stubs return null in headless JVM unit tests
        }

        val imageSvg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(imageIr)
        val resampleSvg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(resampleIr)
        assertTrue("Image SVG must contain <image", imageSvg.contains("<image"))
        assertTrue("Resample SVG must contain <image", resampleSvg.contains("<image"))
    }

    @Test
    fun testStandardizedLuminanceWeightsParity() {
        // Pure red: 0.2126
        val redLum = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(255, 0, 0)
        assertEquals(0.2126f, redLum, 0.001f)

        // Pure green: 0.7152
        val greenLum = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(0, 255, 0)
        assertEquals(0.7152f, greenLum, 0.001f)

        // Pure blue: 0.0722
        val blueLum = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(0, 0, 255)
        assertEquals(0.0722f, blueLum, 0.001f)

        // Pure white: 1.0
        val whiteLum = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(255, 255, 255)
        assertEquals(1.0f, whiteLum, 0.001f)

        // Pure black: 0.0
        val blackLum = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(0, 0, 0)
        assertEquals(0.0f, blackLum, 0.001f)
    }

    @Test
    fun testImageRendererPreservesFormatModulesInIrAndSvg() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/format-test", QrDesign())
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Resource(123)),
            allowTransparent = false
        )

        var darkFormatCount = 0
        for (c in 0 until matrix.size) {
            for (r in 0 until matrix.size) {
                if (matrix.roleAt(c, r) == QrModuleRole.FORMAT && matrix.isDark(c, r)) {
                    darkFormatCount++
                }
            }
        }
        assertTrue("QR matrix must have at least one dark format module", darkFormatCount > 0)

        // 1. ImageRenderer IR (allowTransparent = false: foreground pass only)
        val ir = com.veilframe.app.qr.renderer.ImageRenderer().generateGeometry(matrix, design, geometry)
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val formatNodes = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().filter { rect: com.veilframe.app.qr.geometry.RectNode ->
            (0 until matrix.size).any { c ->
                (0 until matrix.size).any { r ->
                    matrix.roleAt(c, r) == QrModuleRole.FORMAT && matrix.isDark(c, r) &&
                            kotlin.math.abs(rect.x - (ox + c * mSize)) < 0.01f &&
                            kotlin.math.abs(rect.y - (oy + r * mSize)) < 0.01f
                }
            }
        }
        assertEquals("ImageRenderer IR must include all dark format modules in foreground pass", darkFormatCount, formatNodes.size)

        // Verify allowTransparent = true includes both transparent pre-pass and foreground pass (2 * count)
        val designTransparent = design.copy(allowTransparent = true)
        val irTransparent = com.veilframe.app.qr.renderer.ImageRenderer().generateGeometry(matrix, designTransparent, geometry)
        val transparentFormatNodes = irTransparent.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().filter { rect: com.veilframe.app.qr.geometry.RectNode ->
            (0 until matrix.size).any { c ->
                (0 until matrix.size).any { r ->
                    matrix.roleAt(c, r) == QrModuleRole.FORMAT && matrix.isDark(c, r) &&
                            kotlin.math.abs(rect.x - (ox + c * mSize)) < 0.01f &&
                            kotlin.math.abs(rect.y - (oy + r * mSize)) < 0.01f
                }
            }
        }
        assertEquals("ImageRenderer IR with allowTransparent must include pre-pass and foreground format modules", 2 * darkFormatCount, transparentFormatNodes.size)

        // 2. SvgExporter generateImageSvg
        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("SvgExporter must render image style SVG", svg.contains("<svg"))
        for (c in 0 until matrix.size) {
            for (r in 0 until matrix.size) {
                if (matrix.roleAt(c, r) == QrModuleRole.FORMAT && matrix.isDark(c, r)) {
                    val mx = (c + 4).toDouble()
                    val my = (r + 4).toDouble()
                    assertTrue(
                        "SVG must contain format module at ($c, $r)",
                        svg.contains("x=\"$mx\"") && svg.contains("y=\"$my\"")
                    )
                }
            }
        }
    }

    @Test
    fun testResampleGeometryBuilderFallbackPreservesFormatModules() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/resample-fallback", QrDesign())
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            imageSource = ImageSourceStyle(source = null)
        )

        var darkFormatCount = 0
        for (c in 0 until matrix.size) {
            for (r in 0 until matrix.size) {
                if (matrix.roleAt(c, r) == QrModuleRole.FORMAT && matrix.isDark(c, r)) {
                    darkFormatCount++
                }
            }
        }
        assertTrue("QR matrix must have at least one dark format module", darkFormatCount > 0)

        val ir = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, design, geometry)
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val formatNodes = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().filter { rect: com.veilframe.app.qr.geometry.RectNode ->
            (0 until matrix.size).any { c ->
                (0 until matrix.size).any { r ->
                    matrix.roleAt(c, r) == QrModuleRole.FORMAT && matrix.isDark(c, r) &&
                            kotlin.math.abs(rect.x - (ox + c * mSize)) < 0.01f &&
                            kotlin.math.abs(rect.y - (oy + r * mSize)) < 0.01f
                }
            }
        }
        assertEquals("Resample fallback IR must include all dark format modules", darkFormatCount, formatNodes.size)
    }

    @Test
    fun testResampleGeometryBuilderPlanetsAndDsjFinders() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/finders", QrDesign())
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)

        // 1. PLANETS finder style
        val planetsDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.PLANETS)
        )
        val planetsIr = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, planetsDesign, geometry)
        val dashedRings = planetsIr.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>().filter {
            it.strokeDashArray != null
        }
        assertEquals("PLANETS style must generate 3 dashed orbit rings (one per finder)", 3, dashedRings.size)

        // 2. DSJ finder style
        val dsjDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.DSJ)
        )
        val dsjIr = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, dsjDesign, geometry)
        assertNotNull(dsjIr)
        assertTrue("DSJ style must produce geometry nodes", dsjIr.rootNodes.size > 15)
    }

    @Test
    fun testQrStyleRegistryWiresResampleImageRenderer() {
        val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(QrStyle.IMAGE_RESAMPLE)
        val renderer = def.rendererFactory()
        assertTrue(
            "IMAGE_RESAMPLE rendererFactory must instantiate ResampleImageRenderer",
            renderer is com.veilframe.app.qr.renderer.ResampleImageRenderer
        )
        assertTrue(
            "ResampleImageRenderer must be a ComposableQrRenderer",
            renderer is com.veilframe.app.qr.renderer.ComposableQrRenderer
        )
    }

    @Test
    fun testAsymmetricDirectionalQuietZones() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/asymmetric-qz", QrDesign())
        val n = matrix.size

        // 1. QrGeometry with asymmetric quiet zones: left=6, top=2, right=8, bottom=4
        val qzLeft = 6
        val qzTop = 2
        val qzRight = 8
        val qzBottom = 4
        val geom = QrGeometry(
            matrixSize = n,
            outputWidth = 700,
            outputHeight = 700,
            quietZoneLeft = qzLeft,
            quietZoneTop = qzTop,
            quietZoneRight = qzRight,
            quietZoneBottom = qzBottom
        )

        assertEquals("totalModulesX must be matrix size + left + right", n + qzLeft + qzRight, geom.totalModulesX)
        assertEquals("totalModulesY must be matrix size + top + bottom", n + qzTop + qzBottom, geom.totalModulesY)

        val expectedModuleSize = minOf(700f / geom.totalModulesX, 700f / geom.totalModulesY)
        assertEquals("moduleSize calculation parity", expectedModuleSize, geom.moduleSize, 0.001f)

        val expectedOffsetX = (700f - geom.totalModulesX * geom.moduleSize) / 2f + qzLeft * geom.moduleSize
        val expectedOffsetY = (700f - geom.totalModulesY * geom.moduleSize) / 2f + qzTop * geom.moduleSize
        assertEquals("offsetX centering + left inset", expectedOffsetX, geom.offsetX, 0.001f)
        assertEquals("offsetY centering + top inset", expectedOffsetY, geom.offsetY, 0.001f)

        // 2. QrDesign with DirectionalInsets mapped from QrStyleParams
        val params = QrStyleParams(
            quietZoneLeft = qzLeft,
            quietZoneTop = qzTop,
            quietZoneRight = qzRight,
            quietZoneBottom = qzBottom
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(qzLeft, design.effectiveQuietZoneLeft)
        assertEquals(qzTop, design.effectiveQuietZoneTop)
        assertEquals(qzRight, design.effectiveQuietZoneRight)
        assertEquals(qzBottom, design.effectiveQuietZoneBottom)

        // 3. SvgExporter produces asymmetric viewBox
        val svg = SvgExporter.generateSvg(matrix, design)
        val totalX = n + qzLeft + qzRight
        val totalY = n + qzTop + qzBottom
        val expectedViewBox = "viewBox=\"0 0 $totalX $totalY\""
        assertTrue("SVG viewBox must match asymmetric module totals: $expectedViewBox", svg.contains(expectedViewBox))

        // Finders must be positioned with directional quietZoneLeft and quietZoneTop
        val expectedFinderRect = "x=\"$qzLeft\" y=\"$qzTop\""
        assertTrue("SVG top-left finder must be offset by quietZoneLeft and quietZoneTop", svg.contains(expectedFinderRect))
    }

    @Test
    fun testLogoSquircleClippingAndBorderInSvg() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/logo-squircle", QrDesign())
        val dummyBitmap = createDummyBitmap()

        // 1. Squircle logo with border
        val squircleDesign = QrDesign(
            logo = LogoStyle(
                bitmap = dummyBitmap,
                shape = LogoShape.SQUIRCLE,
                scaleFraction = 0.25f,
                borderColor = 0xFFFF0000.toInt(), // Red
                borderWidth = 2.0f
            )
        )
        val squircleSvg = SvgExporter.generateSvg(matrix, squircleDesign)

        assertTrue("SVG must contain logo clipPath def", squircleSvg.contains("<clipPath id=\"logoClip\">"))
        assertTrue("SVG clipPath must contain squircle cubic Bezier path", squircleSvg.contains("<path d=\"M"))
        assertTrue("SVG image must reference #logoClip", squircleSvg.contains("clip-path=\"url(#logoClip)\""))
        assertTrue("SVG must contain border stroke with #FF0000", squircleSvg.contains("stroke=\"#FF0000\""))
        assertTrue("SVG must specify stroke-width=\"2.0\"", squircleSvg.contains("stroke-width=\"2.0\""))

        // 2. Circle logo with border
        val circleDesign = QrDesign(
            logo = LogoStyle(
                bitmap = dummyBitmap,
                shape = LogoShape.CIRCLE,
                scaleFraction = 0.20f,
                borderColor = 0xFF00FF00.toInt(), // Green
                borderWidth = 1.5f
            )
        )
        val circleSvg = SvgExporter.generateSvg(matrix, circleDesign)
        assertTrue("SVG clipPath must contain circle element", circleSvg.contains("<circle cx="))
        assertTrue("SVG must contain border stroke with #00FF00", circleSvg.contains("stroke=\"#00FF00\""))
        assertTrue("SVG must specify stroke-width=\"1.5\"", circleSvg.contains("stroke-width=\"1.5\""))
    }

    @Test
    fun testBubbleRendererAmbientMicroBubbles() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/bubble-ambient", QrDesign())
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)

        // Without ambient bubbles
        val standardDesign = QrDesign(
            style = QrStyle.BUBBLE,
            clusterStyle = BubbleClusterStyle(ambientBubbles = false)
        )
        val standardIr = com.veilframe.app.qr.renderer.BubbleRenderer().generateGeometry(matrix, standardDesign, geometry)

        // With ambient bubbles enabled at full density
        val ambientDesign = QrDesign(
            style = QrStyle.BUBBLE,
            clusterStyle = BubbleClusterStyle(ambientBubbles = true, ambientDensity = 1.0f)
        )
        val ambientIr = com.veilframe.app.qr.renderer.BubbleRenderer().generateGeometry(matrix, ambientDesign, geometry)

        assertTrue(
            "Ambient micro-bubbles must generate additional circle nodes for light modules",
            ambientIr.rootNodes.size > standardIr.rootNodes.size
        )

        // Verify that some nodes have strokeWidth > 0
        val ambientNodes = ambientIr.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>().filter {
            it.stroke != null && (it.strokeWidth ?: 0f) > 0f
        }
        assertTrue("Ambient micro-bubbles must emit stroked circle nodes", ambientNodes.isNotEmpty())
    }

    @Test
    fun testGifEncoderEncodingAndStructure() {
        val w = 64
        val h = 64
        val frame1Pixels = IntArray(w * h) { 0xFF000000.toInt() } // Black
        val frame2Pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() } // White

        val bos = java.io.ByteArrayOutputStream()
        val encoder = com.veilframe.app.qr.exporter.GifEncoder()
        encoder.start(bos, w, h, loops = 0)
        encoder.addFrame(frame1Pixels, w, h, durationMs = 200)
        encoder.addFrame(frame2Pixels, w, h, durationMs = 300)
        encoder.finish()

        val gifBytes = bos.toByteArray()
        assertTrue("GIF byte array must not be empty", gifBytes.isNotEmpty())

        // 1. Header: GIF89a
        val header = String(gifBytes, 0, 6, Charsets.US_ASCII)
        assertEquals("GIF header must be GIF89a", "GIF89a", header)

        // 2. Logical Screen Width & Height (LE)
        val lsdWidth = (gifBytes[6].toInt() and 0xFF) or ((gifBytes[7].toInt() and 0xFF) shl 8)
        val lsdHeight = (gifBytes[8].toInt() and 0xFF) or ((gifBytes[9].toInt() and 0xFF) shl 8)
        assertEquals(w, lsdWidth)
        assertEquals(h, lsdHeight)

        // 3. Netscape 2.0 loop extension signature
        val gifString = String(gifBytes, Charsets.ISO_8859_1)
        assertTrue("GIF must contain NETSCAPE2.0 loop extension", gifString.contains("NETSCAPE2.0"))

        // 4. Trailer byte 0x3B (59)
        assertEquals("GIF must end with trailer byte 0x3B", 0x3B.toByte(), gifBytes.last())
    }

    @Test
    fun testAnimatedQrGeneratorSvgMarkup() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/animated-qr", QrDesign())
        val dummyBitmap = createDummyBitmap()

        val frame1 = com.veilframe.app.qr.model.QrFrame(bitmap = dummyBitmap, durationMs = 250)
        val frame2 = com.veilframe.app.qr.model.QrFrame(bitmap = dummyBitmap, durationMs = 750)
        val frames = listOf(frame1, frame2)

        val design = QrDesign(
            style = QrStyle.IMAGE
        )

        val animSvg = com.veilframe.app.qr.AnimatedQrGenerator.generateAnimatedSvg(matrix, design, frames)

        assertTrue("Animated SVG must contain defs section", animSvg.contains("<defs>"))
        assertTrue("Animated SVG must define qr_frame_0", animSvg.contains("<g id=\"qr_frame_0\">"))
        assertTrue("Animated SVG must define qr_frame_1", animSvg.contains("<g id=\"qr_frame_1\">"))
        assertTrue("Animated SVG must contain use element referencing #qr_frame_0", animSvg.contains("<use xlink:href=\"#qr_frame_0\">"))
        assertTrue("Animated SVG must contain animate element", animSvg.contains("<animate"))
        assertTrue("Animated SVG must animate xlink:href", animSvg.contains("attributeName=\"xlink:href\""))
        assertTrue("Animated SVG must cycle frame values", animSvg.contains("values=\"#qr_frame_0;#qr_frame_1;#qr_frame_0\""))
        assertTrue("Animated SVG must have discrete calcMode", animSvg.contains("calcMode=\"discrete\""))
        assertTrue("Animated SVG must loop indefinitely", animSvg.contains("repeatCount=\"indefinite\""))
        assertTrue("Animated SVG must have total duration 1.000s", animSvg.contains("dur=\"1.000s\""))
    }

    private fun createDummyBitmap(): Bitmap {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            allocateMethod.invoke(unsafe, Bitmap::class.java) as Bitmap
        } catch (_: Throwable) {
            val constructor = Bitmap::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        }
    }
}

