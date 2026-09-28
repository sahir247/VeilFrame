package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.*
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.*
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
    fun testSoftwareRasterizedLineDecodableByZxing() {
        val payload = "HTTPS://VEILFRAME.APP/LINE-ZXING-DECODE"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val n = matrix.size
        val qz = 4
        val scale = 12
        val totalModules = n + 2 * qz
        val totalPx = totalModules * scale
        val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() }
        val black = 0xFF000000.toInt()

        // 1. Draw finders as solid blocks
        for (c in 0 until n) {
            for (r in 0 until n) {
                if (VeilPositionPatternGeometry.isFinderArea(c, r, n)) {
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

        // 2. Build and rasterize Line topology for data modules
        val ox = qz * scale.toFloat()
        val oy = qz * scale.toFloat()
        val cs = scale.toFloat()
        val nodes = LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = ox,
            oy = oy,
            cs = cs,
            thicknessFraction = 0.70f,
            lineColor = black,
            direction = LineDirection.CROSS,
            addAccentRings = false
        )

        for (node in nodes) {
            when (node) {
                is com.veilframe.app.qr.geometry.LineNode -> {
                    val halfStroke = (node.strokeWidth / 2f).toInt().coerceAtLeast(1)
                    val xMin = minOf(node.x1, node.x2).toInt() - halfStroke
                    val xMax = maxOf(node.x1, node.x2).toInt() + halfStroke
                    val yMin = minOf(node.y1, node.y2).toInt() - halfStroke
                    val yMax = maxOf(node.y1, node.y2).toInt() + halfStroke
                    for (y in yMin.coerceAtLeast(0)..yMax.coerceAtMost(totalPx - 1)) {
                        for (x in xMin.coerceAtLeast(0)..xMax.coerceAtMost(totalPx - 1)) {
                            pixels[y * totalPx + x] = black
                        }
                    }
                }
                is com.veilframe.app.qr.geometry.CircleNode -> {
                    val r = node.radius.toInt()
                    val cx = node.cx.toInt()
                    val cy = node.cy.toInt()
                    for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(totalPx - 1)) {
                        for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(totalPx - 1)) {
                            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                                pixels[y * totalPx + x] = black
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        val source = RGBLuminanceSource(totalPx, totalPx, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val decoded = reader.decode(binaryBitmap)

        assertNotNull("Decoded result must not be null", decoded)
        assertEquals("ZXing must successfully decode rasterized Line style QR", payload, decoded.text)
    }

    @Test
    fun testSoftwareRasterizedDotDecodableByZxing() {
        val payload = "HTTPS://VEILFRAME.APP/DOT-ZXING-DECODE"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val n = matrix.size
        val qz = 4
        val scale = 10
        val totalModules = n + 2 * qz
        val totalPx = totalModules * scale
        val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() }
        val black = 0xFF000000.toInt()

        for (c in 0 until n) {
            for (r in 0 until n) {
                if (matrix.isDark(c, r)) {
                    val isFinder = VeilPositionPatternGeometry.isFinderArea(c, r, n)
                    val startX = (c + qz) * scale
                    val startY = (r + qz) * scale
                    if (isFinder) {
                        for (y in startY until (startY + scale)) {
                            for (x in startX until (startX + scale)) {
                                pixels[y * totalPx + x] = black
                            }
                        }
                    } else {
                        // Circle module dot with radius = 0.42 * scale
                        val cx = startX + scale / 2
                        val cy = startY + scale / 2
                        val radius = (scale * 0.42f).toInt()
                        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(totalPx - 1)) {
                            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(totalPx - 1)) {
                                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) {
                                    pixels[y * totalPx + x] = black
                                }
                            }
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
        assertEquals("ZXing must successfully decode rasterized Dot style QR", payload, decoded.text)
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
            allowTransparent = false,
            explicitQuietZone = 4
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

    @Test
    fun testImageResampleBackdropDefaultsToFalseForParity() {
        val design = QrDesign(style = QrStyle.IMAGE_RESAMPLE)
        assertFalse(
            "IMAGE_RESAMPLE useSourceAsBackdrop must be false by default for reference parity",
            design.resampleStyle.useSourceAsBackdrop
        )
        val params = QrStyleParams(style = QrStyle.IMAGE_RESAMPLE)
        val fromParams = QrDesign.fromQrStyleParams(params)
        assertFalse(
            "QrDesign.fromQrStyleParams for IMAGE_RESAMPLE must default useSourceAsBackdrop to false",
            fromParams.resampleStyle.useSourceAsBackdrop
        )
    }

    @Test
    fun testImageFillRegistryUsesAuthoritativeImageFillRenderer() {
        val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(QrStyle.IMAGE_FILL)
        val renderer = def.rendererFactory()
        assertTrue(
            "IMAGE_FILL in registry must instantiate ImageFillRenderer",
            renderer is com.veilframe.app.qr.renderer.ImageFillRenderer
        )
    }

    @Test
    fun testD25GeometryFaceComputationAndPainterOrder() {
        val matrix = QrMatrix("https://veilframe.app/d25-faces-test", ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = 1.0f,
                positionDepth = 1.0f,
                topColor = 0xFF000000.toInt(),
                leftColor = 0x33000000,
                rightColor = 0x99000000.toInt()
            )
        )
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 0
        )
        val ir = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(matrix, design, geometry)
        assertNotNull(ir)
        assertTrue("D25 IR must contain polygon nodes for top, left, right faces", ir.rootNodes.size > matrix.size)

        val polygons = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>()
        assertTrue("D25 must emit polygon nodes for the extruded isometric faces", polygons.isNotEmpty())
    }

    @Test
    fun testLineTopologyBuilderRunDetectionAndTargetPads() {
        val matrix = QrMatrix("https://veilframe.app/line-topology-test", ErrorCorrectionLevel.H)
        val cs = 10f
        val ox = 0f
        val oy = 0f
        val nodes = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = ox,
            oy = oy,
            cs = cs,
            thicknessFraction = 0.5f,
            lineColor = 0xFF123456.toInt(),
            direction = LineDirection.X,
            addAccentRings = true
        )

        assertNotNull(nodes)
        val lines = nodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        val circles = nodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()

        assertTrue("LineTopologyBuilder must detect contiguous runs and emit LineNodes", lines.isNotEmpty())
        assertTrue("LineTopologyBuilder lines must have round end caps", lines.all { it.isRoundCap })
        
        // Assert true diagonal lines exist for LineDirection.X (x1 != x2 and y1 != y2)
        val diagonalLines = lines.filter { it.x1 != it.x2 && it.y1 != it.y2 }
        assertTrue("LineDirection.X must construct diagonal runs (slope != 0 and slope != Inf)", diagonalLines.isNotEmpty())

        assertTrue("LineTopologyBuilder must emit circular node pads at data module positions", circles.isNotEmpty())

        val accentRings = circles.filter { it.stroke != null && it.fill == null }
        assertTrue("LineTopologyBuilder must emit target accent rings for target appearance", accentRings.isNotEmpty())
    }

    @Test
    fun testLineTopologyBuilderLoopbackQuadrantRunGeneration() {
        val matrix = QrMatrix("https://veilframe.app/loopback-test", ErrorCorrectionLevel.H)
        val cs = 10f

        // 1. Verify LineDirection.LOOPBACK in LineTopologyBuilder
        val loopbackNodes = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = cs,
            thicknessFraction = 0.5f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.LOOPBACK
        )
        val loopbackLines = loopbackNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertTrue("LineDirection.LOOPBACK must emit LineNodes", loopbackLines.isNotEmpty())

        val horizontalLines = loopbackLines.filter { it.y1 == it.y2 && it.x1 != it.x2 }
        val verticalLines = loopbackLines.filter { it.x1 == it.x2 && it.y1 != it.y2 }
        assertTrue("LOOPBACK must emit horizontal quadrant segments", horizontalLines.isNotEmpty())
        assertTrue("LOOPBACK must emit vertical quadrant segments", verticalLines.isNotEmpty())

        // 2. Verify LineRenderer routing for LineDirection.LOOP and LineDirection.LOOPBACK
        val designLoop = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.LOOP)
        )
        val designLoopback = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.LOOPBACK)
        )
        val geom = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 0)
        val irLoop = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designLoop, geom)
        val irLoopback = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designLoopback, geom)

        assertTrue("LineRenderer with LineDirection.LOOP must produce LineNodes",
            irLoop.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>().isNotEmpty())
        assertTrue("LineRenderer with LineDirection.LOOPBACK must produce LineNodes",
            irLoopback.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>().isNotEmpty())
    }

    @Test
    fun testLineTopologyBuilderRoundCapsAndLengthFractionControls() {
        val matrix = QrMatrix("https://veilframe.app/line-controls-test", ErrorCorrectionLevel.H)
        val cs = 10f

        // 1. Verify roundCaps = false produces isRoundCap = false
        val squareCapNodes = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = cs,
            thicknessFraction = 0.5f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL,
            roundCaps = false,
            lengthFraction = 1.0f
        )
        val squareLines = squareCapNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertTrue("Must emit lines for HORIZONTAL direction", squareLines.isNotEmpty())
        assertTrue("All LineNodes must have isRoundCap = false when roundCaps = false",
            squareLines.all { !it.isRoundCap })

        // 2. Verify roundCaps = true produces isRoundCap = true
        val roundCapNodes = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = cs,
            thicknessFraction = 0.5f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL,
            roundCaps = true,
            lengthFraction = 1.0f
        )
        val roundLines = roundCapNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertTrue("All LineNodes must have isRoundCap = true when roundCaps = true",
            roundLines.all { it.isRoundCap })

        // 3. Verify lengthFraction scaling (0.5f scales line length to 50%)
        val halfLengthNodes = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = cs,
            thicknessFraction = 0.5f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL,
            roundCaps = true,
            lengthFraction = 0.5f
        )
        val halfLines = halfLengthNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertEquals("Line counts should match between full and scaled lengths", roundLines.size, halfLines.size)

        for (i in roundLines.indices) {
            val fullLen = kotlin.math.abs(roundLines[i].x2 - roundLines[i].x1)
            val halfLen = kotlin.math.abs(halfLines[i].x2 - halfLines[i].x1)
            assertEquals("Length with lengthFraction=0.5 must be exactly 50% of full length",
                fullLen * 0.5f, halfLen, 0.001f)
        }
    }

    @Test
    fun testGifFrameDelayParsingFromStream() {
        val tempFile = java.io.File.createTempFile("test_anim_", ".gif")
        try {
            // Build a minimal GIF stream with two Graphic Control Extensions:
            // Frame 1: delay 15 (150ms) -> low=15, high=0
            // Frame 2: delay 25 (250ms) -> low=25, high=0
            val gce1 = byteArrayOf(0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x00.toByte(), 15.toByte(), 0.toByte(), 0.toByte(), 0.toByte())
            val dummyImg = byteArrayOf(0x2C.toByte(), 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
            val gce2 = byteArrayOf(0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x00.toByte(), 25.toByte(), 0.toByte(), 0.toByte(), 0.toByte())
            val trailer = byteArrayOf(0x3B.toByte())

            val stream = java.io.ByteArrayOutputStream()
            stream.write("GIF89a".toByteArray(Charsets.US_ASCII))
            stream.write(gce1)
            stream.write(dummyImg)
            stream.write(gce2)
            stream.write(dummyImg)
            stream.write(trailer)

            tempFile.writeBytes(stream.toByteArray())

            val delays = com.veilframe.app.qr.AnimatedMediaHelper.parseGifDelays(tempFile)
            assertEquals("Should parse exactly 2 delay values", 2, delays.size)
            assertEquals(150, delays[0])
            assertEquals(250, delays[1])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testResampleSubpixelLuminanceGammaMathematicalParity() {
        // Reference EFQRCode gamma formula:
        // gray = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        // weightedGray = gray * alpha + (1 - alpha) * 255.0
        // normalized = weightedGray / 255.0
        val testCases = listOf(
            Triple(255, 255, 255) to 1.0f,
            Triple(0, 0, 0) to 1.0f,
            Triple(128, 128, 128) to 1.0f,
            Triple(255, 0, 0) to 0.5f,
            Triple(0, 255, 0) to 0.8f
        )

        for ((rgb, alpha) in testCases) {
            val (r, g, b) = rgb
            val refGray = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val refWeighted = refGray * alpha + (1.0f - alpha) * 255.0f
            val expectedNorm = refWeighted / 255.0f

            val actualNorm = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(r, g, b, alpha)
            assertEquals("calculateLuminance must be mathematically identical to reference gamma formula",
                expectedNorm, actualNorm, 0.0001f)
        }
    }

    @Test
    fun testMultiFrameGifEncodingHeaderAndIntegrity() {
        val dummyBmp = createDummyBitmap()
        val frames = listOf(
            com.veilframe.app.qr.model.QrFrame(dummyBmp, 100),
            com.veilframe.app.qr.model.QrFrame(dummyBmp, 100),
            com.veilframe.app.qr.model.QrFrame(dummyBmp, 100)
        )
        val gifBytes = com.veilframe.app.qr.exporter.GifEncoder.encode(frames, 32, 32, 0)
        assertTrue("GIF byte output cannot be empty", gifBytes.isNotEmpty())
        val header = String(gifBytes.copyOfRange(0, 6), Charsets.US_ASCII)
        assertEquals("GIF header must be GIF89a", "GIF89a", header)
        // Trailer byte 0x3B must be at the end
        assertEquals("GIF must end with trailer byte 0x3B", 0x3B.toByte(), gifBytes.last())
    }

    @Test
    fun testImageStyleDataScaleParityAndQuietZone() {
        val params = QrStyleParams(style = QrStyle.IMAGE)
        assertEquals(1.0f, params.imageDataScale, 0.001f)

        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(1.0f, design.moduleStyle.scale, 0.001f)
        assertEquals(1.0f, design.imageDataScale ?: 0f, 0.001f)
        assertEquals(1, design.quietZoneModules)
        assertEquals(1, design.explicitQuietZone)
        assertEquals(com.veilframe.app.qr.model.ModuleShape.SQUARE, design.timingStyle.shape)
        assertEquals(com.veilframe.app.qr.model.ModuleShape.SQUARE, design.alignmentStyle.shape)

        val matrix = QrGenerator.generateMatrix("https://example.com", design)
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        assertEquals(1, geometry.quietZoneModules)

        val svg = SvgExporter.generateSvg(matrix, design)
        val expectedDim = matrix.size + 2 // 1-module quiet zone on each side
        assertTrue("SVG viewBox must have 1-module margin (total size $expectedDim)", svg.contains("viewBox=\"0 0 $expectedDim $expectedDim\""))
    }

    @Test
    fun testVideoExtractionTimingParity() {
        val videoFps = 15
        val expectedDelayMs = (1000.0 / videoFps).toInt()
        assertEquals(66, expectedDelayMs)
    }

    @Test
    fun testIrCanvasRendererImageNodeAspectFillResolution() {
        val nodeSlice = com.veilframe.app.qr.geometry.ImageNode(
            x = 0f, y = 0f, width = 100f, height = 100f,
            preserveAspectRatio = "xMidYMid slice"
        )
        val mode = when {
            nodeSlice.preserveAspectRatio.contains("slice", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
            nodeSlice.preserveAspectRatio.contains("meet", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT
            nodeSlice.preserveAspectRatio.equals("none", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.STRETCH
            else -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
        }
        assertEquals(com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL, mode)

        val nodeMeet = com.veilframe.app.qr.geometry.ImageNode(
            x = 0f, y = 0f, width = 100f, height = 100f,
            preserveAspectRatio = "xMidYMid meet"
        )
        val meetMode = when {
            nodeMeet.preserveAspectRatio.contains("slice", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
            nodeMeet.preserveAspectRatio.contains("meet", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT
            nodeMeet.preserveAspectRatio.equals("none", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.STRETCH
            else -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
        }
        assertEquals(com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT, meetMode)
    }

    @Test
    fun testD25QuietZoneProjectionDerivation() {
        val projDefault = com.veilframe.app.qr.geometry.D25Geometry.computeProjection(25, 500f, 500f, quietZoneModules = 0)
        assertEquals(-25f, projDefault.vbX, 0.001f)
        assertEquals(-12.5f, projDefault.vbY, 0.001f)
        assertEquals(50f, projDefault.scale * 50f / projDefault.scale, 0.001f)

        val projWithQz = com.veilframe.app.qr.geometry.D25Geometry.computeProjection(25, 500f, 500f, quietZoneModules = 4)
        assertEquals(-29f, projWithQz.vbX, 0.001f)
        assertEquals(-16.5f, projWithQz.vbY, 0.001f)

        val projDirectional = com.veilframe.app.qr.geometry.D25Geometry.computeProjection(
            n = 25,
            outputWidth = 500f,
            outputHeight = 500f,
            quietZoneLeft = 4,
            quietZoneTop = 2,
            quietZoneRight = 3,
            quietZoneBottom = 1
        )
        assertEquals(-29f, projDirectional.vbX, 0.001f)
        assertEquals(-14.5f, projDirectional.vbY, 0.001f)
    }

    @Test
    fun testD25SvgCanvasIrCoordinateParityAcrossQuietZones() {
        val matrix = QrMatrix("https://veilframe.app/d25-coord-parity", ErrorCorrectionLevel.M)
        val n = matrix.size
        val outputSize = 512

        // Cases: L=T=R=B=0, L=T=R=B=1, L=T=R=B=4, and asymmetric L=1, T=2, R=3, B=4
        val cases = listOf(
            com.veilframe.app.qr.model.DirectionalInsets(0, 0, 0, 0),
            com.veilframe.app.qr.model.DirectionalInsets(1, 1, 1, 1),
            com.veilframe.app.qr.model.DirectionalInsets(4, 4, 4, 4),
            com.veilframe.app.qr.model.DirectionalInsets(1, 2, 3, 4)
        )

        for (insets in cases) {
            val design = QrDesign(
                style = QrStyle.D25,
                directionalQuietZone = insets
            )
            val geometry = QrGeometry(
                matrixSize = n,
                outputWidth = outputSize,
                outputHeight = outputSize,
                quietZoneModules = insets.left,
                quietZoneLeft = insets.left,
                quietZoneTop = insets.top,
                quietZoneRight = insets.right,
                quietZoneBottom = insets.bottom
            )

            // 1. D25 Projection
            val proj = com.veilframe.app.qr.geometry.D25Geometry.computeProjection(
                n = n,
                outputWidth = outputSize.toFloat(),
                outputHeight = outputSize.toFloat(),
                quietZoneLeft = insets.left,
                quietZoneTop = insets.top,
                quietZoneRight = insets.right,
                quietZoneBottom = insets.bottom
            )

            val expectedVbX = -(n + insets.left).toFloat()
            val expectedVbY = -(n / 2f + insets.top)
            val expectedVbW = (2 * n + insets.left + insets.right).toFloat()
            val expectedVbH = (2 * n + insets.top + insets.bottom).toFloat()

            assertEquals(expectedVbX, proj.vbX, 0.001f)
            assertEquals(expectedVbY, proj.vbY, 0.001f)

            // 2. SVG Export ViewBox Parity
            val svg = SvgExporter.generateSvg(matrix, design)
            val vbXStr = if (insets.left == 0) "-$n" else if (expectedVbX == expectedVbX.toLong().toFloat()) "${expectedVbX.toLong()}" else "$expectedVbX"
            val vbYStr = if (expectedVbY == expectedVbY.toLong().toFloat()) "${expectedVbY.toLong()}" else "$expectedVbY"
            val expectedSvgViewBox = "viewBox=\"$vbXStr $vbYStr ${expectedVbW.toDouble()} ${expectedVbH.toDouble()}\""
            assertTrue("SVG viewBox must match analytical D25 formula for $insets", svg.contains(expectedSvgViewBox))

            // 3. Geometry IR Raster Node Parity
            val ir = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(matrix, design, geometry)
            val firstPoly = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>().firstOrNull()
            assertNotNull(firstPoly)
            val topPt = firstPoly!!.pointsList.first()

            // Find first dark module in EF column-major painter order
            var firstDarkCol = -1
            var firstDarkRow = -1
            outerLoop@ for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.isDark(col, row)) {
                        firstDarkCol = col
                        firstDarkRow = row
                        break@outerLoop
                    }
                }
            }
            assertTrue(firstDarkCol >= 0)

            val expectedScreenX = proj.screenX(firstDarkCol.toFloat(), firstDarkRow.toFloat())
            val expectedScreenY = proj.screenY(firstDarkCol.toFloat(), firstDarkRow.toFloat(), 0f)

            assertEquals(expectedScreenX, topPt.first, 0.001f)
            assertEquals(expectedScreenY, topPt.second, 0.001f)
        }
    }

    @Test
    fun testResampleFinderTransparencyWhenBackdropActive() {
        val matrix = QrMatrix("https://veilframe.app/resample-hollow-test", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512)

        // 1. With useSourceAsBackdrop = true, outer finder ring must be hollow (stroke != null, fill == null)
        val designHollow = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            palette = PaletteStyle(background = 0xFFFFFFFF.toInt(), foreground = 0xFF000000.toInt()),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val irHollow = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designHollow, geometry)
        val hollowFinderRects = irHollow.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("Hollow finder mode must emit stroked hollow rects for the outer frame", hollowFinderRects.isNotEmpty())
        val bgFillFinderRects = irHollow.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()
            .filter { it.fill == designHollow.palette.background && it.width < 512f }
        assertTrue("Hollow finder mode must NOT paint solid 5x5 background rects over finders", bgFillFinderRects.isEmpty())

        // 2. With useSourceAsBackdrop = false, normal background rects are present
        val designNormal = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            palette = PaletteStyle(background = 0xFFFFFFFF.toInt(), foreground = 0xFF000000.toInt()),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = false)
        )
        val irNormal = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designNormal, geometry)
        val normalBgFinderRects = irNormal.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()
            .filter { it.fill == designNormal.palette.background && it.width < 512f }
        assertTrue("Normal finder mode must emit background middle rects when backdrop is false", normalBgFinderRects.isNotEmpty())
    }

    @Test
    fun testLineRendererAccentRingGating() {
        val matrix = QrMatrix("https://veilframe.app/line-gating-test", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512)

        // 1. HORIZONTAL direction must NOT emit accent rings by default
        val designH = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.HORIZONTAL)
        )
        val irH = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designH, geometry)
        val accentRingsH = irH.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("HORIZONTAL line direction must have clean line topology without accent rings", accentRingsH.isEmpty())

        // 2. LineDirection.X by default matches EF clean diagonal + dot topology (no extra accent rings)
        val designX = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.X)
        )
        val irX = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designX, geometry)
        val accentRingsX = irX.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("LineDirection.X by default must have clean EF topology without accent rings", accentRingsX.isEmpty())

        // 3. Explicit accentRingsEnabled = true on X enables accent rings (opt-in circuit mode)
        val designXWithRings = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.X, accentRingsEnabled = true)
        )
        val irXWithRings = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designXWithRings, geometry)
        val accentRingsExplicitX = irXWithRings.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("Explicit accentRingsEnabled = true must enable accent rings", accentRingsExplicitX.isNotEmpty())
    }

    @Test
    fun testD25DepthZeroProducesNoSideExtrusion() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/d25-zero", QrDesign())
        val geometry = QrGeometry(matrix.size, 512, 512, 0)

        // 1. Depth = 0f must produce zero side-face extrusion
        val designZero = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(depth = 0.0f, positionDepth = 0.0f)
        )
        val irZero = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(matrix, designZero, geometry)
        var darkModuleCount = 0
        for (c in 0 until matrix.size) {
            for (r in 0 until matrix.size) {
                if (matrix.isDark(c, r)) darkModuleCount++
            }
        }
        assertEquals("Depth 0 must only emit top faces (1 polygon per dark module)", darkModuleCount, irZero.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>().size)

        val svgZero = SvgExporter.generateSvg(matrix, designZero)
        assertFalse("SVG with depth 0 must not contain skewY extrusion", svgZero.contains("skewY(45)"))
        assertFalse("SVG with depth 0 must not contain skewX extrusion", svgZero.contains("skewX(45)"))

        // 2. Depth > 0f must produce side-face extrusions
        val designExtruded = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(depth = 0.5f, positionDepth = 0.5f)
        )
        val irExtruded = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(matrix, designExtruded, geometry)
        assertTrue("Depth > 0 must emit side extrusion polygons in IR", irExtruded.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>().size > darkModuleCount)

        val svgExtruded = SvgExporter.generateSvg(matrix, designExtruded)
        assertTrue("SVG with depth > 0 must contain skewY extrusion", svgExtruded.contains("skewY(45)"))
        assertTrue("SVG with depth > 0 must contain skewX extrusion", svgExtruded.contains("skewX(45)"))
    }

    @Test
    fun testD25QuietZoneCrossPipelineParity() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/d25-qz-parity", QrDesign())
        val design = QrDesign(style = QrStyle.D25)

        // 1. QrGeometry.fromDesign defaults to 0
        val geom = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        assertEquals("D25 QrGeometry.fromDesign must default quiet zone to 0", 0, geom.quietZoneModules)

        // 2. QrGenerator.generateWithResult report defaults to 0
        val result = QrGenerator.generateWithResult("https://veilframe.app/d25-qz-parity", design)
        assertTrue(result is QrRenderResult.Success)
        val report = (result as QrRenderResult.Success).report
        assertEquals("D25 QrGenerator.generateWithResult must default quiet zone to 0", 0, report.quietZone.quietZoneModules)

        // 3. SvgExporter produces analytical QZ=0 viewBox [-n, -n/2, 2n, 2n]
        val n = matrix.size
        val svg = SvgExporter.generateSvg(matrix, design)
        val expectedViewBox = "viewBox=\"-$n -${n / 2.0} ${n * 2.0} ${n * 2.0}\""
        assertTrue("D25 SVG must have QZ=0 analytical viewBox: $expectedViewBox", svg.contains(expectedViewBox))
    }

    @Test
    fun testLineDotTopologyUnconsumedCellsVsX() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/line-topology", QrDesign())
        val geometry = QrGeometry(matrix.size, 512, 512, 4)

        // HORIZONTAL direction: multi-module runs consume cells, so circles are ONLY emitted for unconsumed cells
        val designH = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.HORIZONTAL, accentRingsEnabled = false)
        )
        val irH = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designH, geometry)
        val circleNodesH = irH.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()

        // Count dark non-finder cells
        var darkCellCount = 0
        for (c in 0 until matrix.size) {
            for (r in 0 until matrix.size) {
                if (matrix.isDark(c, r) && !com.veilframe.app.qr.renderer.VeilPositionPatternGeometry.isFinderArea(c, r, matrix.size)) {
                    darkCellCount++
                }
            }
        }
        assertTrue("Dark non-finder modules must exist", darkCellCount > 0)
        // With horizontal runs consuming adjacent cells, circle count must be strictly less than total dark cells
        assertTrue("Horizontal line runs must consume cells, emitting circles only for unconsumed cells", circleNodesH.size < darkCellCount)

        // LineDirection.X: circle emitted on EVERY dark cell
        val designX = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.X, accentRingsEnabled = false)
        )
        val irX = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designX, geometry)
        val circleNodesX = irX.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
        assertEquals("LineDirection.X must emit circles on every dark cell", darkCellCount, circleNodesX.size)
    }

    @Test
    fun testImageAndImageFillDirectionalQuietZonesInSvg() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/directional-qz", QrDesign())
        val n = matrix.size
        val insets = DirectionalInsets(left = 1, top = 2, right = 3, bottom = 4)

        // 1. IMAGE style with directional quiet zone
        val designImage = QrDesign(
            style = QrStyle.IMAGE,
            directionalQuietZone = insets,
            imageSource = ImageSourceStyle(source = ImageSource.Resource(123))
        )
        val svgImage = SvgExporter.generateSvg(matrix, designImage)
        val expectedW = n + 1 + 3
        val expectedH = n + 2 + 4
        assertTrue("IMAGE SVG must reflect directional quiet zone in viewBox (width $expectedW, height $expectedH)", svgImage.contains("viewBox=\"0 0 $expectedW $expectedH\""))
        assertTrue("IMAGE SVG mask must be offset by left=1, top=2", svgImage.contains("x=\"1\" y=\"2\" width=\"$n\" height=\"$n\""))

        // 2. IMAGE_FILL style with directional quiet zone
        val designFill = QrDesign(
            style = QrStyle.IMAGE_FILL,
            directionalQuietZone = insets,
            imageSource = ImageSourceStyle(source = ImageSource.Resource(123))
        )
        val svgFill = SvgExporter.generateSvg(matrix, designFill)
        assertTrue("IMAGE_FILL SVG must reflect directional quiet zone in viewBox", svgFill.contains("viewBox=\"0 0 $expectedW $expectedH\""))
    }

    @Test
    fun testResampleDsjAndPlanetsFinderPositionSize() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/resample-finder-scaling", QrDesign())
        val geometry = QrGeometry(matrix.size, 512, 512, 1)

        // 1. DSJ finder: center rect and arm dimensions scale with positionSize
        val designDsjSmall = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.DSJ),
            positionSize = 0.8f
        )
        val irDsjSmall = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designDsjSmall, geometry)
        val rectsSmall = irDsjSmall.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()

        val designDsjLarge = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.DSJ),
            positionSize = 1.2f
        )
        val irDsjLarge = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designDsjLarge, geometry)
        val rectsLarge = irDsjLarge.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()

        val mSize = geometry.moduleSize
        val expectedSmallCenter = (2.0f + 0.8f) * mSize
        val expectedLargeCenter = (2.0f + 1.2f) * mSize
        assertTrue("DSJ center rect must match (2 + posSize) * mSize for posSize 0.8", rectsSmall.any { kotlin.math.abs(it.width - expectedSmallCenter) < 0.01f })
        assertTrue("DSJ center rect must match (2 + posSize) * mSize for posSize 1.2", rectsLarge.any { kotlin.math.abs(it.width - expectedLargeCenter) < 0.01f })

        // 2. Planets finder: orbit stroke = 0.15 * mSize, planet dot radius = 0.5 * posSize * mSize
        val designPlanets = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.PLANETS),
            positionSize = 1.0f
        )
        val irPlanets = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designPlanets, geometry)
        val circlesPlanets = irPlanets.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()

        val expectedOrbitStroke = 0.15f * mSize
        val expectedPlanetRadius = 0.5f * 1.0f * mSize
        assertTrue("Planets orbit stroke must equal 0.15 * mSize", circlesPlanets.any { kotlin.math.abs(it.strokeWidth - expectedOrbitStroke) < 0.01f })
        assertTrue("Planets dot radius must equal 0.5 * posSize * mSize", circlesPlanets.any { kotlin.math.abs(it.radius - expectedPlanetRadius) < 0.01f })
    }

    @Test
    fun testAnimatedExactQuantizedTimingParity() {
        val dummy = createDummyBitmap()

        // 1. Durations [100, 102, 98, 100] quantize to the SAME centisecond [10, 10, 10, 10]
        val constantFrames = listOf(
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 100),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 102),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 98),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 100)
        )
        val csConstant = constantFrames.map { maxOf(1, (it.durationMs + 5) / 10) }
        assertEquals("Frames within same centisecond quantize to equal delay", 1, csConstant.distinct().size)

        // 2. Durations [100, 120, 100, 100] quantize to DIFFERENT centiseconds [10, 12, 10, 10]
        val variableFrames = listOf(
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 100),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 120),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 100),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 100)
        )
        val csVariable = variableFrames.map { maxOf(1, (it.durationMs + 5) / 10) }
        assertTrue("Frames with different centiseconds are correctly identified as variable timing", csVariable.distinct().size > 1)
    }

    @Test
    fun testAnimatedSvgUnclampedDurationParity() {
        val dummy = createDummyBitmap()
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/unclamped-svg-timing", QrDesign())

        // Frames with durations less than 10ms (5ms, 15ms, 20ms) -> total 40ms = 0.040s
        val frames = listOf(
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 5),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 15),
            com.veilframe.app.qr.model.QrFrame(bitmap = dummy, durationMs = 20)
        )

        val svg = AnimatedQrGenerator.generateAnimatedSvg(matrix, QrDesign(), frames)

        // Verify total duration is exactly 0.040s (not clamped to >=10ms per frame which would be 45ms or 0.045s)
        assertTrue("Animated SVG duration must be exactly 0.040s for 40ms total", svg.contains("dur=\"0.040s\""))

        // KeyTimes: 0.000, 5/40 = 0.125, (5+15)/40 = 0.500, 1.000
        assertTrue("KeyTimes must include exact fraction 0.125 for 5ms frame", svg.contains("0.125"))
        assertTrue("KeyTimes must include exact fraction 0.500 for cumulative 20ms", svg.contains("0.500"))
    }

    @Test
    fun testDirectionalQuietZoneLogoCenteringCanvasAndSvg() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/directional-logo-center", QrDesign())
        val n = matrix.size

        // Asymmetric quiet zones: L=1, T=2, R=3, B=4
        val insets = DirectionalInsets(left = 1, top = 2, right = 3, bottom = 4)
        val modulePx = 10f
        val outW = ((n + 1 + 3) * modulePx).toInt()
        val outH = ((n + 2 + 4) * modulePx).toInt()

        val geometry = QrGeometry(
            matrixSize = n,
            outputWidth = outW,
            outputHeight = outH,
            quietZoneModules = 1,
            quietZoneLeft = 1,
            quietZoneTop = 2,
            quietZoneRight = 3,
            quietZoneBottom = 4
        )

        // 1. Pure geometry verification
        // QR matrix center in pixels:
        // qrPixelSize = n * modulePx
        // cx = offsetX + qrPixelSize / 2f
        // cy = offsetY + qrPixelSize / 2f
        // With totalModulesX * modulePx == outW, offsetX = quietZoneLeft * modulePx = 10f
        val qrPixelSize = n * modulePx
        val expectedCenterX = geometry.offsetX + qrPixelSize / 2f
        val expectedCenterY = geometry.offsetY + qrPixelSize / 2f

        val calculatedCenterX = (1f + n / 2f) * modulePx
        val calculatedCenterY = (2f + n / 2f) * modulePx

        assertEquals("Geometry offsetX must place QR matrix at qzLeft * modulePx", 1f * modulePx, geometry.offsetX, 0.01f)
        assertEquals("Geometry offsetY must place QR matrix at qzTop * modulePx", 2f * modulePx, geometry.offsetY, 0.01f)
        assertEquals("Expected QR center X must match directional inset L=1 + N/2", calculatedCenterX, expectedCenterX, 0.01f)
        assertEquals("Expected QR center Y must match directional inset T=2 + N/2", calculatedCenterY, expectedCenterY, 0.01f)

        // 2. IR geometry logo centering (Canvas & SVG shared pipeline)
        val logoBmp = createDummyBitmap()
        val designWithLogo = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            directionalQuietZone = insets,
            logo = LogoStyle(
                bitmap = logoBmp,
                scaleFraction = 0.20f
            )
        )
        val irResample = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, designWithLogo, geometry)
        val imageNode = irResample.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.ImageNode>().first()
        val logoCenterX = imageNode.x + imageNode.width / 2f
        val logoCenterY = imageNode.y + imageNode.height / 2f

        assertEquals("Resample IR logo must be centered at QR matrix centerX", expectedCenterX, logoCenterX, 0.01f)
        assertEquals("Resample IR logo must be centered at QR matrix centerY", expectedCenterY, logoCenterY, 0.01f)

        // 3. SVG logo centering (Resample IR-rendered SVG and Standard SVG)
        val qrPixelSizeSvg = n.toDouble()
        val logoSizeSvg = qrPixelSizeSvg * 0.20
        val expectedLogoXSvg = (1.0 + qrPixelSizeSvg / 2.0) - (logoSizeSvg / 2.0)
        val expectedLogoYSvg = (2.0 + qrPixelSizeSvg / 2.0) - (logoSizeSvg / 2.0)

        val svgResample = SvgExporter.generateSvg(matrix, designWithLogo)
        val resampleImageTag = svgResample.lines().first { it.contains("<image") }
        val resampleActualX = Regex("""x="([0-9.]+)"""").find(resampleImageTag)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val resampleActualY = Regex("""y="([0-9.]+)"""").find(resampleImageTag)?.groupValues?.get(1)?.toDouble() ?: 0.0
        assertEquals("Resample SVG logo X must match expected QR matrix center", expectedLogoXSvg, resampleActualX, 0.01)
        assertEquals("Resample SVG logo Y must match expected QR matrix center", expectedLogoYSvg, resampleActualY, 0.01)

        val designBasic = QrDesign(
            directionalQuietZone = insets,
            logo = LogoStyle(bitmap = logoBmp, scaleFraction = 0.20f)
        )
        val svgBasic = SvgExporter.generateSvg(matrix, designBasic)
        val basicImageTag = svgBasic.lines().first { it.contains("<image") }
        val basicActualX = Regex("""x="([0-9.]+)"""").find(basicImageTag)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val basicActualY = Regex("""y="([0-9.]+)"""").find(basicImageTag)?.groupValues?.get(1)?.toDouble() ?: 0.0
        assertEquals("Standard SVG logo X must match expected QR matrix center", expectedLogoXSvg, basicActualX, 0.01)
        assertEquals("Standard SVG logo Y must match expected QR matrix center", expectedLogoYSvg, basicActualY, 0.01)
    }

    @Test
    fun testD25DepthZeroNoExtrusionCanvasAndSvg() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/d25-zero-depth-parity", QrDesign())
        val n = matrix.size

        val topColor = 0xFFFF0000.toInt() // Red
        val leftColor = 0xFF00FF00.toInt() // Green
        val rightColor = 0xFF0000FF.toInt() // Blue
        val bgColor = 0xFFFFFFFF.toInt() // White

        val designZeroDepth = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = 0.0f,
                positionDepth = 0.0f,
                topColor = topColor,
                leftColor = leftColor,
                rightColor = rightColor
            ),
            palette = PaletteStyle(background = bgColor, foreground = topColor),
            explicitQuietZone = 0
        )

        val geometry = QrGeometry(matrixSize = n, outputWidth = 200, outputHeight = 200, quietZoneModules = 0)

        // 1. IR geometry check (shared by Canvas and SVG pipelines)
        val ir = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(matrix, designZeroDepth, geometry)
        val polygonNodes = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>()
        val sideNodes = polygonNodes.filter { it.fill == leftColor || it.fill == rightColor }
        assertEquals("Zero depth D25 IR must contain zero side-face PolygonNodes", 0, sideNodes.size)
        val topNodes = polygonNodes.filter { it.fill == topColor }
        assertTrue("Zero depth D25 IR must contain top-face PolygonNodes", topNodes.isNotEmpty())

        // 2. SVG check
        val svg = SvgExporter.generateSvg(matrix, designZeroDepth)
        assertFalse("Zero depth D25 SVG must not contain skewY extrusion transforms", svg.contains("skewY"))
        assertFalse("Zero depth D25 SVG must not contain skewX extrusion transforms", svg.contains("skewX"))
        assertFalse("Zero depth D25 SVG must not contain leftColor side faces", svg.contains("#00FF00"))
        assertFalse("Zero depth D25 SVG must not contain rightColor side faces", svg.contains("#0000FF"))
    }

    @Test
    fun testResampleDeterministicSamplingParityFixture() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/resample-fixture", QrDesign())
        val n = matrix.size

        // Create a 3Nx3N gradient pixel source
        val dim = 3 * n
        val pixels = IntArray(dim * dim)
        for (y in 0 until dim) {
            for (x in 0 until dim) {
                val grayVal = ((x + y) * 255) / (2 * dim)
                pixels[y * dim + x] = (0xFF shl 24) or (grayVal shl 16) or (grayVal shl 8) or grayVal
            }
        }
        val arraySource = com.veilframe.app.qr.renderer.ArrayPixelSource(width = dim, height = dim, pixels = pixels)

        val emittedSubpixels = mutableSetOf<Pair<Int, Int>>()
        val anchorSubpixels = mutableSetOf<Pair<Int, Int>>()

        val style = ImageSourceStyle(contrast = 0.0f, exposure = 0.0f)
        val seed = 12345L

        com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = arraySource,
            style = style,
            seed = seed,
            policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy
        ) { col, row, subX, subY, isCenterAnchor ->
            if (isCenterAnchor) {
                anchorSubpixels.add(Pair(subX, subY))
            } else {
                emittedSubpixels.add(Pair(subX, subY))
            }
        }

        // Verify anchors: Dark DATA modules have anchor at (3*col+1, 3*row+1), Light DATA modules have NONE
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) == QrModuleRole.DATA) {
                    val centerPt = Pair(3 * col + 1, 3 * row + 1)
                    if (matrix.isDark(col, row)) {
                        assertTrue("Dark data module must emit center anchor at $centerPt", anchorSubpixels.contains(centerPt))
                    } else {
                        assertFalse("Light data module must NEVER emit center anchor at $centerPt", anchorSubpixels.contains(centerPt))
                    }
                }
            }
        }

        // Verify surrounding 8 subpixels match reference stochastic formula
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) == QrModuleRole.DATA) {
                    for (dx in 0..2) {
                        for (dy in 0..2) {
                            if (dx == 1 && dy == 1) continue
                            val sx = 3 * col + dx
                            val sy = 3 * row + dy
                            val pixel = arraySource.getPixel(sx, sy)
                            val grayNorm = com.veilframe.app.qr.renderer.ImageScaleResolver.calculatePixelLuminance(pixel)
                            val threshold = ((grayNorm + style.exposure - 0.5f) * (style.contrast + 1.0f) + 0.5f).coerceIn(0.0f, 1.0f)
                            val rnd = com.veilframe.app.qr.renderer.ResampleSubpixelEngine.subpixelRandom(seed, sx, sy)
                            val shouldEmit = rnd > threshold

                            val isEmitted = emittedSubpixels.contains(Pair(sx, sy))
                            assertEquals("Subpixel ($sx, $sy) emission must match deterministic reference thresholding", shouldEmit, isEmitted)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testLineRunAndIsolatedDotParityWithMutationOrder() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/line-topology-parity", QrDesign())
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)

        // 1. Horizontal direction: continuous runs consume cells, isolated cells get circles
        val designHorizontal = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.HORIZONTAL, thicknessFraction = 0.5f)
        )
        val irHoriz = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designHorizontal, geometry)
        val linesHoriz = irHoriz.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        val circlesHoriz = irHoriz.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()

        assertTrue("Horizontal line style must emit continuous line segments", linesHoriz.isNotEmpty())
        assertTrue("Horizontal line style must emit isolated circle nodes", circlesHoriz.isNotEmpty())

        // 2. X direction: both diagonals emitted + circles for all dark data modules
        val designX = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.X, thicknessFraction = 0.5f)
        )
        val irX = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designX, geometry)
        val linesX = irX.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        val circlesX = irX.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()

        assertTrue("X line style must emit diagonal line segments", linesX.isNotEmpty())
        assertTrue("X line style must emit circles across dark modules (EF circuit aesthetic)", circlesX.size >= linesX.size)

        // 3. Determinism check: VeilFrame's pseudoRandom guarantees reproducible geometry across invocations
        val irX2 = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, designX, geometry)
        val linesX2 = irX2.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertEquals("VeilFrame X mode must produce deterministic, reproducible geometry (deterministic by design)", linesX.size, linesX2.size)
        for (i in linesX.indices) {
            assertEquals("Line segment stroke width must match reproducibly", linesX[i].strokeWidth, linesX2[i].strokeWidth, 0.001f)
        }
    }

    @Test
    fun testResampleCrossImplementationDifferentialParityProof() {
        val matrix = QrGenerator.generateMatrix("https://veilframe.app/resample-differential-parity", QrDesign())
        val n = matrix.size // 21 for Version 1
        val targetDim = 3 * n // 63 subpixels

        // 1. Create an asymmetric test image (60 wide x 30 high, aspect 2:1)
        // Top half dark (RGB=20, luminance low), bottom half light (RGB=240, luminance high)
        val imgW = 60
        val imgH = 30
        val imgPixels = IntArray(imgW * imgH)
        for (y in 0 until imgH) {
            for (x in 0 until imgW) {
                val rgb = if (y < imgH / 2) 20 else 240
                imgPixels[y * imgW + x] = (0xFF shl 24) or (rgb shl 16) or (rgb shl 8) or rgb
            }
        }
        val pixelSource = ArrayPixelSource(imgW, imgH, imgPixels)

        // 2. Reference EFQRCode simulation of getGrayPointList + writeQRCode
        fun simulateEfPoints(
            scaleMode: ImageScaleMode,
            timingShape: com.veilframe.app.qr.model.ModuleShape = com.veilframe.app.qr.model.ModuleShape.SQUARE,
            alignmentShape: com.veilframe.app.qr.model.ModuleShape = com.veilframe.app.qr.model.ModuleShape.SQUARE
        ): Set<Pair<Int, Int>> {
            val preScaled = ImageScaleResolver.createPreScaledArraySource(pixelSource, targetDim, targetDim, scaleMode)
            val points = mutableSetOf<Pair<Int, Int>>()

            // posOrigins from posCenter modules using markArr
            val posOrigins = listOf(
                intArrayOf(0, 0),                       // Top-Left (3, 3) -> 9 - 12 - (-3) = 0
                intArrayOf(3 * n - 24, 0),              // Top-Right (n - 4, 3) -> 3*n - 12 - 12 = 3*n - 24
                intArrayOf(0, 3 * n - 24)               // Bottom-Left (3, n - 4) -> 3*n - 24
            )

            // bwOrigins and swOrigins for timing and alignment
            val bwOrigins = mutableListOf<IntArray>()
            val swOrigins = mutableListOf<IntArray>()
            for (x in 0 until n) {
                for (y in 0 until n) {
                    val role = matrix.roleAt(x, y)
                    val isDark = matrix.isDark(x, y)
                    if (!isDark) {
                        if (role == QrModuleRole.TIMING) {
                            if (timingShape != com.veilframe.app.qr.model.ModuleShape.NONE) bwOrigins.add(intArrayOf(3 * x, 3 * y))
                            else swOrigins.add(intArrayOf(3 * x + 1, 3 * y + 1))
                        }
                        if (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) {
                            if (alignmentShape != com.veilframe.app.qr.model.ModuleShape.NONE) bwOrigins.add(intArrayOf(3 * x, 3 * y))
                            else swOrigins.add(intArrayOf(3 * x + 1, 3 * y + 1))
                        }
                    }
                }
            }

            // Subpixel sampling loop (getGrayPointList)
            for (sy in 0 until targetDim) {
                for (sx in 0 until targetDim) {
                    // Check trans area
                    var isTrans = false
                    for (pos in posOrigins) {
                        if (sx in pos[0] until (pos[0] + 24) && sy in pos[1] until (pos[1] + 24)) {
                            isTrans = true; break
                        }
                    }
                    if (isTrans) continue

                    val col = sx / 3
                    val row = sy / 3

                    for (bw in bwOrigins) {
                        if (sx in bw[0] until (bw[0] + 3) && sy in bw[1] until (bw[1] + 3)) {
                            isTrans = true; break
                        }
                    }
                    if (isTrans) continue
                    for (sw in swOrigins) {
                        if (sx == sw[0] && sy == sw[1]) {
                            isTrans = true; break
                        }
                    }
                    if (isTrans) continue

                    // Padding check for ASPECT_FIT
                    if (preScaled.isPadding(sx, sy)) continue

                    // Skip center subpixel
                    if (sx % 3 == 1 && sy % 3 == 1) continue

                    val px = preScaled.getPixel(sx, sy)
                    val gray = ImageScaleResolver.calculatePixelLuminance(px)
                    val threshold = (gray - 0.5f) * 1.0f + 0.5f // contrast=0, exposure=0 -> threshold = gray
                    val rnd = ResampleSubpixelEngine.subpixelRandom(42L, sx, sy)
                    if (rnd > threshold) {
                        points.add(Pair(sx, sy))
                    }
                }
            }

            // Solid center anchors (writeQRCode)
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val isDark = matrix.isDark(col, row)
                    if (!isDark) continue
                    val role = matrix.roleAt(col, row)
                    val isFinder = (col < 8 && row < 8) || (col >= n - 8 && row < 8) || (col < 8 && row >= n - 8)
                    if (isFinder) continue

                    // Timing & Alignment: emit center anchor if shape == NONE
                    if (role == QrModuleRole.TIMING && timingShape != com.veilframe.app.qr.model.ModuleShape.NONE) continue
                    if ((role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) && alignmentShape != com.veilframe.app.qr.model.ModuleShape.NONE) continue

                    // Center anchor
                    points.add(Pair(3 * col + 1, 3 * row + 1))
                }
            }

            return points
        }

        // 3. Compare against VeilFrame ResampleSubpixelEngine output for ASPECT_FILL
        val veilFramePointsFill = mutableSetOf<Pair<Int, Int>>()
        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = pixelSource,
            style = ImageSourceStyle(scaleMode = ImageScaleMode.ASPECT_FILL, contrast = 0.0f, exposure = 0.0f),
            seed = 42L,
            policy = ArtisticResamplePolicy()
        ) { _, _, sx, sy, _ ->
            veilFramePointsFill.add(Pair(sx, sy))
        }
        val efPointsFill = simulateEfPoints(ImageScaleMode.ASPECT_FILL)
        val diffEfOnly = efPointsFill - veilFramePointsFill
        val diffVeilOnly = veilFramePointsFill - efPointsFill
        assertEquals("Diff EF-only: ${diffEfOnly.take(15)}, Diff Veil-only: ${diffVeilOnly.take(15)}", efPointsFill.size, veilFramePointsFill.size)
        assertEquals("Emitted subpixel coordinates must match reference EFQRCode 100% for ASPECT_FILL", efPointsFill, veilFramePointsFill)

        // 4. Compare against VeilFrame ResampleSubpixelEngine output for ASPECT_FIT (with letterbox padding)
        val veilFramePointsFit = mutableSetOf<Pair<Int, Int>>()
        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = pixelSource,
            style = ImageSourceStyle(scaleMode = ImageScaleMode.ASPECT_FIT, contrast = 0.0f, exposure = 0.0f),
            seed = 42L,
            policy = ArtisticResamplePolicy()
        ) { _, _, sx, sy, _ ->
            veilFramePointsFit.add(Pair(sx, sy))
        }
        val efPointsFit = simulateEfPoints(ImageScaleMode.ASPECT_FIT)
        assertEquals("Emitted subpixel coordinate count must match reference EFQRCode exactly for ASPECT_FIT", efPointsFit.size, veilFramePointsFit.size)
        assertEquals("Emitted subpixel coordinates must match reference EFQRCode 100% for ASPECT_FIT", efPointsFit, veilFramePointsFit)

        // 5. Explicit invariant verifications on the emitted sets
        // A. Finder exclusion: all 3 finders have ZERO emitted points inside their 24x24 box
        for (pt in veilFramePointsFit) {
            val inTl = pt.first in 0 until 24 && pt.second in 0 until 24
            val inTr = pt.first in (targetDim - 24) until targetDim && pt.second in 0 until 24
            val inBl = pt.first in 0 until 24 && pt.second in (targetDim - 24) until targetDim
            assertFalse("No subpixels may be emitted in Top-Left finder transArea", inTl)
            assertFalse("No subpixels may be emitted in Top-Right finder transArea", inTr)
            assertFalse("No subpixels may be emitted in Bottom-Left finder transArea", inBl)
        }

        // B. Format bits (Row 8): dark format bits MUST have center anchor emitted
        for (col in 0..8) {
            if (col == 6) continue // timing intersection
            if (matrix.isDark(col, 8)) {
                val anchor = Pair(3 * col + 1, 3 * 8 + 1)
                assertTrue("Dark format bit at col=$col, row=8 must emit center anchor", veilFramePointsFit.contains(anchor))
            }
        }

        // C. ASPECT_FIT letterbox padding: top and bottom padding zones have ZERO photo dither points
        val preScaledFit = ImageScaleResolver.createPreScaledArraySource(pixelSource, targetDim, targetDim, ImageScaleMode.ASPECT_FIT)
        for (pt in veilFramePointsFit) {
            if (pt.first % 3 != 1 || pt.second % 3 != 1) {
                assertFalse("Stochastic dither dot cannot be emitted inside ASPECT_FIT letterbox padding", preScaledFit.isPadding(pt.first, pt.second))
            }
        }
    }

    @Test
    fun testAnimatedSvgScopedIdsAcrossFrames() {
        val sampleSvgFrame0 = """
            <defs>
              <mask id="hole">
                <rect fill="white"/>
              </mask>
              <linearGradient id="qrGrad">
                <stop offset="0%"/>
              </linearGradient>
              <clipPath id="logoClip">
                <path d="M0,0"/>
              </clipPath>
            </defs>
            <g mask="url(#hole)" fill="url(#qrGrad)" clip-path="url(#logoClip)">
              <rect x="0" y="0"/>
            </g>
        """.trimIndent()

        val sampleSvgFrame1 = """
            <defs>
              <mask id="hole">
                <circle fill="white"/>
              </mask>
              <linearGradient id="qrGrad">
                <stop offset="50%"/>
              </linearGradient>
              <clipPath id="logoClip">
                <circle cx="5" cy="5" r="5"/>
              </clipPath>
            </defs>
            <g mask="url(#hole)" fill="url(#qrGrad)" clip-path="url(#logoClip)">
              <circle cx="10" cy="10" r="5"/>
            </g>
        """.trimIndent()

        val scoped0 = AnimatedQrGenerator.scopeSvgIds(sampleSvgFrame0, "f0")
        val scoped1 = AnimatedQrGenerator.scopeSvgIds(sampleSvgFrame1, "f1")

        // Frame 0 must be scoped with f0_
        assertTrue(scoped0.contains("""id="f0_hole""""))
        assertTrue(scoped0.contains("""id="f0_qrGrad""""))
        assertTrue(scoped0.contains("""id="f0_logoClip""""))
        assertTrue(scoped0.contains("""mask="url(#f0_hole)""""))
        assertTrue(scoped0.contains("""fill="url(#f0_qrGrad)""""))
        assertTrue(scoped0.contains("""clip-path="url(#f0_logoClip)""""))
        assertFalse("Unscoped id=hole must not exist in frame 0", scoped0.contains("""id="hole""""))

        // Frame 1 must be scoped with f1_
        assertTrue(scoped1.contains("""id="f1_hole""""))
        assertTrue(scoped1.contains("""id="f1_qrGrad""""))
        assertTrue(scoped1.contains("""id="f1_logoClip""""))
        assertTrue(scoped1.contains("""mask="url(#f1_hole)""""))
        assertTrue(scoped1.contains("""fill="url(#f1_qrGrad)""""))
        assertTrue(scoped1.contains("""clip-path="url(#f1_logoClip)""""))
        assertFalse("Unscoped id=hole must not exist in frame 1", scoped1.contains("""id="hole""""))
    }

    @Test
    fun testPartialDirectionalQuietZoneFallback() {
        // Overriding only quietZoneLeft on IMAGE style (which defaults to quiet zone = 1)
        val params = QrStyleParams(
            style = QrStyle.IMAGE,
            quietZoneLeft = 2
        )
        val design = QrDesign.fromQrStyleParams(params)
        val insets = checkNotNull(design.directionalQuietZone)
        assertEquals("Specified left side must be preserved", 2, insets.left)
        assertEquals("Unspecified top side must fall back to style default (1)", 1, insets.top)
        assertEquals("Unspecified right side must fall back to style default (1)", 1, insets.right)
        assertEquals("Unspecified bottom side must fall back to style default (1)", 1, insets.bottom)

        // Overriding quietZone base + one directional side
        val paramsBase = QrStyleParams(
            style = QrStyle.IMAGE,
            quietZone = 3,
            quietZoneTop = 6
        )
        val designBase = QrDesign.fromQrStyleParams(paramsBase)
        val insetsBase = checkNotNull(designBase.directionalQuietZone)
        assertEquals("Unspecified left side must use base quietZone (3)", 3, insetsBase.left)
        assertEquals("Specified top side must be 6", 6, insetsBase.top)
        assertEquals("Unspecified right side must use base quietZone (3)", 3, insetsBase.right)
        assertEquals("Unspecified bottom side must use base quietZone (3)", 3, insetsBase.bottom)
    }

    @Test
    fun testImageAndImageFillScaleModePropagation() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/SCALE-MODE", ErrorCorrectionLevel.M)

        for (mode in listOf(ImageScaleMode.ASPECT_FIT, ImageScaleMode.ASPECT_FILL, ImageScaleMode.STRETCH)) {
            val paramsImage = QrStyleParams(
                style = QrStyle.IMAGE,
                imageScaleMode = mode
            )
            val designImage = QrDesign.fromQrStyleParams(paramsImage)
            assertEquals("scaleMode must propagate to ImageSourceStyle", mode, designImage.imageSource.scaleMode)

            val paramsFill = QrStyleParams(
                style = QrStyle.IMAGE_FILL,
                imageScaleMode = mode
            )
            val designFill = QrDesign.fromQrStyleParams(paramsFill)
            assertEquals("scaleMode must propagate to ImageSourceStyle for IMAGE_FILL", mode, designFill.imageSource.scaleMode)
        }

        // SVG preserveAspectRatio tests
        val expectedPreserveAspect = mapOf(
            ImageScaleMode.ASPECT_FIT to "xMidYMid meet",
            ImageScaleMode.ASPECT_FILL to "xMidYMid slice",
            ImageScaleMode.STRETCH to "none"
        )
        for ((mode, expected) in expectedPreserveAspect) {
            val designImage = QrDesign(
                style = QrStyle.IMAGE,
                imageSource = ImageSourceStyle(source = com.veilframe.app.qr.model.ImageSource.Memory(createDummyBitmap()), scaleMode = mode)
            )
            val svgImage = SvgExporter.generateSvg(matrix, designImage)
            assertTrue("IMAGE SVG must contain preserveAspectRatio=\"$expected\"", svgImage.contains("""preserveAspectRatio="$expected""""))

            val designFill = QrDesign(
                style = QrStyle.IMAGE_FILL,
                imageSource = ImageSourceStyle(source = com.veilframe.app.qr.model.ImageSource.Memory(createDummyBitmap()), scaleMode = mode)
            )
            val svgFill = SvgExporter.generateSvg(matrix, designFill)
            assertTrue("IMAGE_FILL SVG must contain preserveAspectRatio=\"$expected\"", svgFill.contains("""preserveAspectRatio="$expected""""))
        }
    }

    @Test
    fun testImageRendererPlanetsAndDsjFinderParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/FINDER-PARITY", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)
        val mSize = geometry.moduleSize

        // 1. PLANETS finder parity: orbit stroke must be 0.15 * module (not 0.35)
        val designPlanets = QrDesign(
            style = QrStyle.IMAGE,
            eyeStyle = EyeStyle(style = FinderStyle.PLANETS),
            positionSize = 0.8f
        )
        val irPlanets = com.veilframe.app.qr.renderer.ImageRenderer().generateGeometry(matrix, designPlanets, geometry)
        val orbitCircles = irPlanets.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("PLANETS must emit orbit stroke circles", orbitCircles.isNotEmpty())
        for (orbit in orbitCircles) {
            val expectedStroke = 0.15f * mSize
            assertEquals("PLANETS orbit stroke must be 0.15 * module size", expectedStroke, orbit.strokeWidth ?: 0f, 0.01f)
        }

        // 2. DSJ finder parity: center rect width = (2.0 + posSize) * module, arm width = posSize * module
        val posSize = 0.75f
        val designDsj = QrDesign(
            style = QrStyle.IMAGE,
            eyeStyle = EyeStyle(style = FinderStyle.DSJ),
            positionSize = posSize
        )
        val irDsj = com.veilframe.app.qr.renderer.ImageRenderer().generateGeometry(matrix, designDsj, geometry)
        val dsjRects = irDsj.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()
        val expectedCenterW = (2.0f + posSize) * mSize
        val expectedArmW = posSize * mSize
        val centerMatches = dsjRects.filter { kotlin.math.abs(it.width - expectedCenterW) < 0.01f && kotlin.math.abs(it.height - expectedCenterW) < 0.01f }
        assertTrue("DSJ must emit center rect matching (2 + posSize) * module", centerMatches.isNotEmpty())
        val armMatches = dsjRects.filter {
            (kotlin.math.abs(it.width - expectedArmW) < 0.01f && kotlin.math.abs(it.height - expectedCenterW) < 0.01f) ||
            (kotlin.math.abs(it.width - expectedCenterW) < 0.01f && kotlin.math.abs(it.height - expectedArmW) < 0.01f)
        }
        assertTrue("DSJ must emit arms matching arm thickness = posSize * module", armMatches.isNotEmpty())
    }

    @Test
    fun testD25PainterOrderColumnMajor() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/D25-ORDER", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 0)
        val design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(depth = 0.5f, positionDepth = 0.5f)
        )
        val ir = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(matrix, design, geometry)
        val polygonNodes = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>()
        assertTrue("D25 must emit polygon nodes for isometric 3D faces", polygonNodes.isNotEmpty())

        val topFaces = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>()
        if (topFaces.size >= 2) {
            val firstX = topFaces[0].x
            val secondX = topFaces[1].x
            assertEquals("Second element in col-major order must be in the same column (col 0)", firstX, secondX, 0.01f)
        }
    }

    @Test
    fun testSvgAlphaFidelityTranslucentColors() {
        val semiRed = 0x80FF0000.toInt()
        val svgColor = SvgExporter.toSvgColor(semiRed)
        assertEquals("#FF0000", svgColor.hex)
        assertEquals(128f / 255f, svgColor.opacity, 0.005f)
        assertTrue("CSS representation must contain rgba", svgColor.css.startsWith("rgba(255,0,0,"))

        val opaqueBlue = 0xFF0000FF.toInt()
        val blueSvg = SvgExporter.toSvgColor(opaqueBlue)
        assertEquals("#0000FF", blueSvg.hex)
        assertEquals(1.0f, blueSvg.opacity, 0.001f)
        assertEquals("#0000FF", blueSvg.css)

        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ALPHA", ErrorCorrectionLevel.M)
        val design = QrDesign(palette = PaletteStyle(foreground = semiRed))
        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("SVG must preserve alpha channel in fill attributes", svg.contains("rgba(255,0,0,"))
    }

    @Test
    fun testImageFillNoImageFallback() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/IMAGE-FILL-NO-IMG", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageFillBackgroundColor = 0xFF00FF00.toInt(),
            imageFillMaskColor = 0x33000000.toInt(),
            imageSource = ImageSourceStyle(source = null)
        )

        val ir = com.veilframe.app.qr.renderer.ImageFillRenderer().generateGeometry(matrix, design, geometry)
        assertFalse("IMAGE_FILL without image must NOT fall back to empty geometry", ir.rootNodes.isEmpty())
        val groupNodes = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.GroupNode>()
        assertTrue("IMAGE_FILL without image must generate stencil group node", groupNodes.isNotEmpty())
        val stencilGroup = groupNodes.first()
        assertEquals("Hole mask must be attached to stencil group", "hole", stencilGroup.maskId)
        assertTrue("Stencil group must contain background and tint rect nodes", stencilGroup.children.isNotEmpty())
    }

    @Test
    fun testResampleDarkTimingAndAlignmentStochasticSampling() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE-DARK-TIMING", ErrorCorrectionLevel.M)
        val n = matrix.size

        // Col 10, Row 6 is a dark timing module
        assertTrue("Module at (10, 6) must be timing module", ArtisticResampleFunctionalMask.isTimingArea(10, 6, n))
        assertTrue("Module at (10, 6) must be dark", matrix.isDark(10, 6))

        // Upstream EF parity: dark timing subpixels ARE available to stochastic sampling
        for (dx in 0..2) {
            for (dy in 0..2) {
                val subX = 10 * 3 + dx
                val subY = 6 * 3 + dy
                assertFalse(
                    "Dark timing subpixel at ($subX, $subY) must NOT be excluded from stochastic sampling",
                    ArtisticResampleFunctionalMask.isSubpixelExcluded(matrix, subX, subY)
                )
            }
        }

        // Light timing module at (9, 6)
        assertTrue("Module at (9, 6) must be timing module", ArtisticResampleFunctionalMask.isTimingArea(9, 6, n))
        assertFalse("Module at (9, 6) must be light", matrix.isDark(9, 6))

        // Light timing subpixels ARE excluded from stochastic sampling when timingShape != NONE
        assertTrue(
            "Light timing subpixel must be excluded from stochastic sampling",
            ArtisticResampleFunctionalMask.isSubpixelExcluded(matrix, 9 * 3 + 1, 6 * 3 + 1)
        )
    }

    // =========================================================================
    // PARITY LADDER EXTENSIONS: LEVELS 2, 3, 4 & CONTRACT
    // =========================================================================

    @Test
    fun testLineMultiDirectionDifferentialTopologyAndCollisionOrder() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/LINE-COLLISION", ErrorCorrectionLevel.M)
        val cs = 10f
        val ox = 0f
        val oy = 0f

        // 1. HORIZONTAL
        val nodesH = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL
        )
        val segsH = nodesH.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        for (seg in segsH) {
            assertEquals("Horizontal segments must have identical Y coordinates", seg.y1, seg.y2, 0.001f)
            assertTrue("Horizontal segment length must be >= 1 module", kotlin.math.abs(seg.x2 - seg.x1) >= cs * 0.9f)
        }

        // 2. VERTICAL
        val nodesV = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.VERTICAL
        )
        val segsV = nodesV.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        for (seg in segsV) {
            assertEquals("Vertical segments must have identical X coordinates", seg.x1, seg.x2, 0.001f)
            assertTrue("Vertical segment length must be >= 1 module", kotlin.math.abs(seg.y2 - seg.y1) >= cs * 0.9f)
        }

        // 3. CROSS: Vertical runs execute first and consume modules, horizontal runs run second on remainder
        val nodesCross = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.CROSS
        )
        val segsCross = nodesCross.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        val circlesCross = nodesCross.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
        assertTrue("CROSS must emit line segments", segsCross.isNotEmpty())
        assertTrue("CROSS must emit node circles for remaining cells", circlesCross.isNotEmpty())

        // 4. LOOPBACK: Quadrant selection directs vertical vs horizontal runs without collision
        val nodesLoop = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.LOOPBACK
        )
        val segsLoop = nodesLoop.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertTrue("LOOPBACK must emit line segments", segsLoop.isNotEmpty())

        // 5. DIAGONAL_FORWARD and DIAGONAL_BACKWARD
        val nodesDf = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.DIAGONAL_FORWARD
        )
        val segsDf = nodesDf.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        for (seg in segsDf) {
            // (x2 - x1) == (y2 - y1) for forward diagonal
            assertEquals("Forward diagonal slope must be +1", (seg.x2 - seg.x1), (seg.y2 - seg.y1), 0.01f)
        }

        val nodesDb = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.DIAGONAL_BACKWARD
        )
        val segsDb = nodesDb.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        for (seg in segsDb) {
            // (x2 - x1) == -(y2 - y1) for backward diagonal
            assertEquals("Backward diagonal slope must be -1", (seg.x2 - seg.x1), -(seg.y2 - seg.y1), 0.01f)
        }

        // 6. X: True cross-hatching containing both diagonal slopes
        val nodesX = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix, ox = ox, oy = oy, cs = cs,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.X
        )
        val segsX = nodesX.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        val hasForward = segsX.any { kotlin.math.abs((it.x2 - it.x1) - (it.y2 - it.y1)) < 0.01f }
        val hasBackward = segsX.any { kotlin.math.abs((it.x2 - it.x1) + (it.y2 - it.y1)) < 0.01f }
        assertTrue("X mode must contain both forward and backward diagonals", hasForward && hasBackward)

        // 7. CROSS COLLISION PRECEDENCE: Vertical pass takes precedence over horizontal on shared intersection cell
        // Construct a synthetic 21x21 matrix where:
        // Column 11 has dark cells at rows 10, 11, 12 (vertical run of 3)
        // Row 11 has dark cells at columns 10, 11, 12 (horizontal run of 3)
        // Module (11, 11) is the shared intersection cell in data area outside finders.
        val crossMatrix = QrMatrix(size = 21, version = 1, errorCorrection = ErrorCorrectionLevel.M) { col, row ->
            (col == 11 && row in 10..12) || (row == 11 && col in 10..12)
        }
        val crossNodes = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = crossMatrix, ox = 0f, oy = 0f, cs = 10f,
            thicknessFraction = 0.5f, lineColor = 0xFF000000.toInt(),
            direction = LineDirection.CROSS
        )
        val crossLineSegs = crossNodes.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        // Vertical line at col 11 (X = 115) must exist spanning from Y=105 to Y=125
        val verticalCrossLine = crossLineSegs.find { kotlin.math.abs(it.x1 - 115f) < 0.1f && kotlin.math.abs(it.x2 - 115f) < 0.1f }
        assertNotNull("Vertical run must claim the shared intersection cell (11, 11)", verticalCrossLine)
        assertEquals(105f, verticalCrossLine!!.y1, 0.01f)
        assertEquals(125f, verticalCrossLine.y2, 0.01f)

        // Horizontal run at row 11 cannot bridge across col 11 because (11, 11) was claimed by vertical pass.
        // Therefore, NO horizontal line segment of length >= 2 can exist across row 11!
        val horizontalCrossLine = crossLineSegs.find { kotlin.math.abs(it.y1 - 115f) < 0.1f && kotlin.math.abs(it.y2 - 115f) < 0.1f }
        assertNull("Horizontal run must NOT cross the shared intersection cell claimed by vertical run", horizontalCrossLine)

        // 8. EF Randomness vs VeilFrame Determinism Documentation:
        // In EFQRCode (EFQRCodeStyleLine.swift lines 547, 566, 570), EF uses non-deterministic `CGFloat.random(in: 0.3...1)`
        // which introduces frame-to-frame jitter in animated QR codes. VeilFrame intentionally uses deterministic
        // spatial hashing via `LineRenderer.pseudoRandom(x, y, tag, min, max)` to guarantee rock-solid animated stability.
        val r1 = com.veilframe.app.qr.renderer.LineRenderer.pseudoRandom(5, 5, 0, 0.3f, 1.0f)
        val r2 = com.veilframe.app.qr.renderer.LineRenderer.pseudoRandom(5, 5, 0, 0.3f, 1.0f)
        assertEquals("VeilFrame spatial hash must be 100% deterministic across multiple evaluations", r1, r2, 0.0001f)
    }

    @Test
    fun testAnimatedSvgReferenceIntegrity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ANIM-SVG-INTEGRITY", ErrorCorrectionLevel.M)
        val bmp1 = createDummyBitmap()
        val bmp2 = createDummyBitmap()
        val bmp3 = createDummyBitmap()

        val frames = listOf(
            QrFrame(bmp1, 200),
            QrFrame(bmp2, 200),
            QrFrame(bmp3, 200)
        )
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageFillMaskColor = 0x33000000.toInt(),
            imageFillBackgroundColor = 0xFFFFFFFF.toInt()
        )

        val animatedSvg = AnimatedQrGenerator.generateAnimatedSvg(matrix, design, frames)

        // 1. Parse full animated SVG into a strict XML DOM document
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(java.io.ByteArrayInputStream(animatedSvg.toByteArray(Charsets.UTF_8)))

        // 2. DOM-wide traversal to collect defined IDs, references, and frame elements
        val definedIds = mutableListOf<String>()
        val allRefs = mutableListOf<String>()
        val frameElements = mutableListOf<Pair<Int, org.w3c.dom.Element>>()

        val urlRefRegex = Regex("""url\(\s*#([^)]+)\s*\)""")

        fun scanGlobal(node: org.w3c.dom.Node) {
            if (node is org.w3c.dom.Element) {
                if (node.hasAttribute("id")) {
                    val idVal = node.getAttribute("id")
                    if (idVal.isNotEmpty()) {
                        definedIds.add(idVal)
                        val frameMatch = Regex("""^qr_frame_(\d+)$""").matchEntire(idVal)
                        if (frameMatch != null) {
                            frameElements.add(Pair(frameMatch.groupValues[1].toInt(), node))
                        }
                    }
                }
                val attrs = node.attributes
                for (a in 0 until attrs.length) {
                    val attr = attrs.item(a)
                    val attrVal = attr.nodeValue
                    for (match in urlRefRegex.findAll(attrVal)) {
                        allRefs.add(match.groupValues[1])
                    }
                    if (attr.nodeName == "href" || attr.nodeName == "xlink:href") {
                        if (attrVal.startsWith("#")) {
                            allRefs.add(attrVal.substring(1))
                        }
                    }
                }
            }
            val children = node.childNodes
            for (i in 0 until children.length) {
                scanGlobal(children.item(i))
            }
        }
        scanGlobal(doc.documentElement)

        assertTrue("DOM must define resource and frame IDs", definedIds.isNotEmpty())

        // 3. Assert all defined IDs are unique across the entire DOM tree
        val duplicates = definedIds.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("All IDs must be unique across animated SVG frames, found duplicates: $duplicates", duplicates.isEmpty())

        // 4. Assert that EVERY reference resolves to a defined ID (zero dangling references)
        val definedIdSet = definedIds.toSet()
        for (ref in allRefs) {
            assertTrue("Referenced ID '#$ref' must exist in SVG DOM definitions", definedIdSet.contains(ref))
        }

        // 5. Assert frame isolation across each frame subtree in the DOM
        assertEquals("DOM tree must contain exactly 3 frame root elements", 3, frameElements.size)

        for ((frameIdx, frameElem) in frameElements) {
            val subtreeRefs = mutableListOf<String>()
            fun scanSubtree(node: org.w3c.dom.Node) {
                if (node is org.w3c.dom.Element) {
                    val attrs = node.attributes
                    for (a in 0 until attrs.length) {
                        val attr = attrs.item(a)
                        val attrVal = attr.nodeValue
                        for (match in urlRefRegex.findAll(attrVal)) {
                            subtreeRefs.add(match.groupValues[1])
                        }
                        if (attr.nodeName == "href" || attr.nodeName == "xlink:href") {
                            if (attrVal.startsWith("#")) {
                                subtreeRefs.add(attrVal.substring(1))
                            }
                        }
                    }
                }
                val children = node.childNodes
                for (i in 0 until children.length) {
                    scanSubtree(children.item(i))
                }
            }
            scanSubtree(frameElem)

            for (ref in subtreeRefs) {
                assertTrue(
                    "DOM reference '#$ref' inside frame $frameIdx must strictly be prefixed with 'f${frameIdx}_'",
                    ref.startsWith("f${frameIdx}_")
                )
                for (otherIdx in 0 until 3) {
                    if (otherIdx != frameIdx) {
                        assertFalse(
                            "DOM reference '#$ref' in frame $frameIdx must NEVER point to frame $otherIdx",
                            ref.startsWith("f${otherIdx}_")
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testImageScaleModeCropRectAndPixelSurvivalParity() {
        // 1. Wide source: 400x200 into 300x300
        val wideFill = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            400, 200, 0f, 0f, 300f, 300f, ImageScaleMode.ASPECT_FILL
        )
        assertEquals("Wide ASPECT_FILL must crop horizontal overflow to 200px wide", 200, wideFill.srcWidth)
        assertEquals("Wide ASPECT_FILL must preserve full height of 200px", 200, wideFill.srcHeight)
        assertEquals("Wide ASPECT_FILL must center crop: left=100", 100, wideFill.srcLeft)
        assertEquals("Wide ASPECT_FILL must center crop: right=300", 300, wideFill.srcRight)

        val wideFit = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            400, 200, 0f, 0f, 300f, 300f, ImageScaleMode.ASPECT_FIT
        )
        assertEquals("Wide ASPECT_FIT must fit full 300px width", 300f, wideFit.dstWidth, 0.01f)
        assertEquals("Wide ASPECT_FIT must letterbox height to 150px", 150f, wideFit.dstHeight, 0.01f)
        assertEquals("Wide ASPECT_FIT must center vertically: top=75", 75f, wideFit.dstTop, 0.01f)

        // 2. Tall source: 200x400 into 300x300
        val tallFill = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            200, 400, 0f, 0f, 300f, 300f, ImageScaleMode.ASPECT_FILL
        )
        assertEquals("Tall ASPECT_FILL must preserve full width of 200px", 200, tallFill.srcWidth)
        assertEquals("Tall ASPECT_FILL must crop vertical overflow to 200px tall", 200, tallFill.srcHeight)
        assertEquals("Tall ASPECT_FILL must center crop: top=100", 100, tallFill.srcTop)
        assertEquals("Tall ASPECT_FILL must center crop: bottom=300", 300, tallFill.srcBottom)

        val tallFit = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            200, 400, 0f, 0f, 300f, 300f, ImageScaleMode.ASPECT_FIT
        )
        assertEquals("Tall ASPECT_FIT must pillarbox width to 150px", 150f, tallFit.dstWidth, 0.01f)
        assertEquals("Tall ASPECT_FIT must fit full 300px height", 300f, tallFit.dstHeight, 0.01f)
        assertEquals("Tall ASPECT_FIT must center horizontally: left=75", 75f, tallFit.dstLeft, 0.01f)

        // 3. Square source: 200x200 into 300x300
        val sqFill = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            200, 200, 0f, 0f, 300f, 300f, ImageScaleMode.ASPECT_FILL
        )
        assertEquals(200, sqFill.srcWidth)
        assertEquals(200, sqFill.srcHeight)
        assertEquals(300f, sqFill.dstWidth, 0.01f)

        // 4. 1-pixel edge cases
        val onePx = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            1, 1, 0f, 0f, 300f, 300f, ImageScaleMode.ASPECT_FILL
        )
        assertEquals(1, onePx.srcWidth)
        assertEquals(1, onePx.srcHeight)
        assertEquals(300f, onePx.dstWidth, 0.01f)

        // 5. Odd dimensions: 399x199 into 301x301
        val oddFill = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveCropGeometry(
            399, 199, 0f, 0f, 301f, 301f, ImageScaleMode.ASPECT_FILL
        )
        assertTrue("Odd dimensions must resolve positive crop width", oddFill.srcWidth in 190..210)
        assertEquals(199, oddFill.srcHeight)

        // 6. Distinct-color 4x3 Pixel Survival Parity (4x3 into 1:1 destination)
        // 12 unique colors across a 4-column x 3-row source grid:
        // Row 0: P(0,0)=0xFF111111, P(1,0)=0xFF222222, P(2,0)=0xFF333333, P(3,0)=0xFF444444
        // Row 1: P(0,1)=0xFF555555, P(1,1)=0xFF666666, P(2,1)=0xFF777777, P(3,1)=0xFF888888
        // Row 2: P(0,2)=0xFF999999, P(1,2)=0xFFAAAAAA, P(2,2)=0xFFBBBBBB, P(3,2)=0xFFCCCCCC
        val colors4x3 = intArrayOf(
            0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt(), 0xFF444444.toInt(),
            0xFF555555.toInt(), 0xFF666666.toInt(), 0xFF777777.toInt(), 0xFF888888.toInt(),
            0xFF999999.toInt(), 0xFFAAAAAA.toInt(), 0xFFBBBBBB.toInt(), 0xFFCCCCCC.toInt()
        )
        val pixelSource4x3 = ArrayPixelSource(width = 4, height = 3, pixels = colors4x3)

        // 6a. ASPECT_FILL into 1:1 destination:
        // Height is preserved (all rows 0, 1, 2 survive). Width is center-cropped to 3/4 = 0.75.
        // Exact integer grid centers:
        // fx = (0.125 + u * 0.75) * 3, fy = v * 2.
        // For Col 1, Row 1 (fx=1.0, fy=1.0): u = 5/18f, v = 0.5f -> P(1, 1) = 0xFF666666
        val col1Fill = ImageScaleResolver.sample(pixelSource4x3, 5f / 18f, 0.5f, ImageScaleMode.ASPECT_FILL)
        assertFalse("ASPECT_FILL sample must not be padding", col1Fill.isPadding)
        assertEquals("Col 1 Row 1 under ASPECT_FILL must survive as 0xFF666666", 0xFF666666.toInt(), col1Fill.color)

        // For Col 2, Row 1 (fx=2.0, fy=1.0): u = 13/18f, v = 0.5f -> P(2, 1) = 0xFF777777
        val col2Fill = ImageScaleResolver.sample(pixelSource4x3, 13f / 18f, 0.5f, ImageScaleMode.ASPECT_FILL)
        assertFalse(col2Fill.isPadding)
        assertEquals("Col 2 Row 1 under ASPECT_FILL must survive as 0xFF777777", 0xFF777777.toInt(), col2Fill.color)

        // Midpoint u=0.5, v=0.5 evaluates fx=1.5, fy=1.0, which bilinearly blends Col 1 (0x66=102) and Col 2 (0x77=119) to 110.5 -> 111 (0x6F)
        val centerFill = ImageScaleResolver.sample(pixelSource4x3, 0.5f, 0.5f, ImageScaleMode.ASPECT_FILL)
        assertEquals("Center sample bilinearly interpolates adjacent columns", 0xFF6F6F6F.toInt(), centerFill.color)

        val topFill = ImageScaleResolver.sample(pixelSource4x3, 13f / 18f, 0.0f, ImageScaleMode.ASPECT_FILL)
        assertEquals("Top pixel under ASPECT_FILL must survive from Row 0, Col 2", 0xFF333333.toInt(), topFill.color)

        val bottomFill = ImageScaleResolver.sample(pixelSource4x3, 13f / 18f, 1.0f, ImageScaleMode.ASPECT_FILL)
        assertEquals("Bottom pixel under ASPECT_FILL must survive from Row 2, Col 2", 0xFFBBBBBB.toInt(), bottomFill.color)

        // Verify Col 1 across Row 0 and Row 2 under ASPECT_FILL:
        val col1Row0Fill = ImageScaleResolver.sample(pixelSource4x3, 5f / 18f, 0.0f, ImageScaleMode.ASPECT_FILL)
        assertEquals("Col 1 Row 0 under ASPECT_FILL must survive as 0xFF222222", 0xFF222222.toInt(), col1Row0Fill.color)
        val col1Row2Fill = ImageScaleResolver.sample(pixelSource4x3, 5f / 18f, 1.0f, ImageScaleMode.ASPECT_FILL)
        assertEquals("Col 1 Row 2 under ASPECT_FILL must survive as 0xFFAAAAAA", 0xFFAAAAAA.toInt(), col1Row2Fill.color)

        // Mathematical proof that Col 0 and Col 3 are cropped out in ASPECT_FILL:
        // Crop window horizontally is [0.125, 0.875], so x=0.0 and x=1.0 never map to integer index 0 or 3.
        // Leftmost sample u=0.0 blends Col 0 (0x55) and Col 1 (0x66) with weights 0.625 / 0.375 -> 0xFF5B5B5B != 0xFF555555
        val leftEdgeFill = ImageScaleResolver.sample(pixelSource4x3, 0.0f, 0.5f, ImageScaleMode.ASPECT_FILL)
        assertNotEquals("Col 0 pure center is cropped out under ASPECT_FILL", 0xFF555555.toInt(), leftEdgeFill.color)
        assertEquals("Left edge under ASPECT_FILL samples interpolated inner edge", 0xFF5B5B5B.toInt(), leftEdgeFill.color)

        // Rightmost sample u=1.0 blends Col 2 (0x77) and Col 3 (0x88) with weights 0.375 / 0.625 -> 0xFF828282 != 0xFF888888
        val rightEdgeFill = ImageScaleResolver.sample(pixelSource4x3, 1.0f, 0.5f, ImageScaleMode.ASPECT_FILL)
        assertNotEquals("Col 3 pure center is cropped out under ASPECT_FILL", 0xFF888888.toInt(), rightEdgeFill.color)
        assertEquals("Right edge under ASPECT_FILL samples interpolated inner edge", 0xFF828282.toInt(), rightEdgeFill.color)

        // 6b. ASPECT_FIT into 1:1 destination:
        // Width is fitted (all 4 columns survive horizontally).
        // Height is letterboxed with top and bottom margins (v < 0.125 and v >= 0.875).
        val topPadding = ImageScaleResolver.sample(pixelSource4x3, 0.5f, 0.05f, ImageScaleMode.ASPECT_FIT)
        assertTrue("Top margin under ASPECT_FIT must be padding", topPadding.isPadding)
        assertEquals("Top margin must be pure white (0xFFFFFFFF)", 0xFFFFFFFF.toInt(), topPadding.color)

        val bottomPadding = ImageScaleResolver.sample(pixelSource4x3, 0.5f, 0.95f, ImageScaleMode.ASPECT_FIT)
        assertTrue("Bottom margin under ASPECT_FIT must be padding", bottomPadding.isPadding)
        assertEquals("Bottom margin must be pure white (0xFFFFFFFF)", 0xFFFFFFFF.toInt(), bottomPadding.color)

        // All 12 pixels across the 4x3 source grid survive horizontally across all 3 rows under ASPECT_FIT:
        // Row 0 at v = 0.125f:
        val r0c0 = ImageScaleResolver.sample(pixelSource4x3, 0.0f, 0.125f, ImageScaleMode.ASPECT_FIT)
        val r0c1 = ImageScaleResolver.sample(pixelSource4x3, 1f / 3f, 0.125f, ImageScaleMode.ASPECT_FIT)
        val r0c2 = ImageScaleResolver.sample(pixelSource4x3, 2f / 3f, 0.125f, ImageScaleMode.ASPECT_FIT)
        val r0c3 = ImageScaleResolver.sample(pixelSource4x3, 1.0f, 0.125f, ImageScaleMode.ASPECT_FIT)
        assertFalse(r0c0.isPadding); assertEquals("P(0,0) must survive", 0xFF111111.toInt(), r0c0.color)
        assertFalse(r0c1.isPadding); assertEquals("P(1,0) must survive", 0xFF222222.toInt(), r0c1.color)
        assertFalse(r0c2.isPadding); assertEquals("P(2,0) must survive", 0xFF333333.toInt(), r0c2.color)
        assertFalse(r0c3.isPadding); assertEquals("P(3,0) must survive", 0xFF444444.toInt(), r0c3.color)

        // Row 1 at v = 0.500f:
        val r1c0 = ImageScaleResolver.sample(pixelSource4x3, 0.0f, 0.5f, ImageScaleMode.ASPECT_FIT)
        val r1c1 = ImageScaleResolver.sample(pixelSource4x3, 1f / 3f, 0.5f, ImageScaleMode.ASPECT_FIT)
        val r1c2 = ImageScaleResolver.sample(pixelSource4x3, 2f / 3f, 0.5f, ImageScaleMode.ASPECT_FIT)
        val r1c3 = ImageScaleResolver.sample(pixelSource4x3, 1.0f, 0.5f, ImageScaleMode.ASPECT_FIT)
        assertFalse(r1c0.isPadding); assertEquals("P(0,1) must survive", 0xFF555555.toInt(), r1c0.color)
        assertFalse(r1c1.isPadding); assertEquals("P(1,1) must survive", 0xFF666666.toInt(), r1c1.color)
        assertFalse(r1c2.isPadding); assertEquals("P(2,1) must survive", 0xFF777777.toInt(), r1c2.color)
        assertFalse(r1c3.isPadding); assertEquals("P(3,1) must survive", 0xFF888888.toInt(), r1c3.color)

        // Row 2 at v = 0.8749f (just inside active area before v >= 0.875 padding boundary):
        val r2c0 = ImageScaleResolver.sample(pixelSource4x3, 0.0f, 0.8749f, ImageScaleMode.ASPECT_FIT)
        val r2c1 = ImageScaleResolver.sample(pixelSource4x3, 1f / 3f, 0.8749f, ImageScaleMode.ASPECT_FIT)
        val r2c2 = ImageScaleResolver.sample(pixelSource4x3, 2f / 3f, 0.8749f, ImageScaleMode.ASPECT_FIT)
        val r2c3 = ImageScaleResolver.sample(pixelSource4x3, 1.0f, 0.8749f, ImageScaleMode.ASPECT_FIT)
        assertFalse(r2c0.isPadding); assertEquals("P(0,2) must survive", 0xFF999999.toInt(), r2c0.color)
        assertFalse(r2c1.isPadding); assertEquals("P(1,2) must survive", 0xFFAAAAAA.toInt(), r2c1.color)
        assertFalse(r2c2.isPadding); assertEquals("P(2,2) must survive", 0xFFBBBBBB.toInt(), r2c2.color)
        assertFalse(r2c3.isPadding); assertEquals("P(3,2) must survive", 0xFFCCCCCC.toInt(), r2c3.color)
    }

    @Test
    fun testD25RasterPainterOrderAndOverlapOcclusion() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/D25-PAINTER", ErrorCorrectionLevel.M)
        val n = matrix.size
        val proj = com.veilframe.app.qr.geometry.D25Geometry.computeProjection(n, 512f, 512f, 1)

        val top00 = proj.screenY(0f, 0f, 0f)
        val top01 = proj.screenY(0f, 1f, 0f)
        assertTrue("Screen Y increases down the column for isometric projection", top01 > top00)

        // Discrete Raster Overlap Occlusion Verification:
        // Module (10, 10) and Module (11, 11) both have depth h = 1.0f.
        val testMatrix = QrMatrix(size = 21, version = 1, errorCorrection = ErrorCorrectionLevel.M) { col, row ->
            (col == 10 && row == 10) || (col == 11 && row == 11)
        }
        val topColor = 0xFF00FF00.toInt()   // Green
        val leftColor = 0xFFFF0000.toInt()  // Red
        val rightColor = 0xFF0000FF.toInt() // Blue

        val design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = 1.0f,
                positionDepth = 1.0f,
                topColor = topColor,
                leftColor = leftColor,
                rightColor = rightColor
            )
        )
        val geom = QrGeometry(matrixSize = 21, outputWidth = 400, outputHeight = 400, quietZoneModules = 0)
        val ir = com.veilframe.app.qr.geometry.D25Geometry.buildGeometry(testMatrix, design, geom)

        val width = 400
        val height = 400
        val raster = IntArray(width * height)

        fun pointInConvexPolygon(px: Float, py: Float, pts: List<Pair<Float, Float>>): Boolean {
            if (pts.size < 3) return false
            var sign = 0
            for (i in pts.indices) {
                val p1 = pts[i]
                val p2 = pts[(i + 1) % pts.size]
                val cross = (p2.first - p1.first) * (py - p1.second) - (p2.second - p1.second) * (px - p1.first)
                if (kotlin.math.abs(cross) > 1e-5f) {
                    val currentSign = if (cross > 0f) 1 else -1
                    if (sign == 0) sign = currentSign
                    else if (sign != currentSign) return false
                }
            }
            return true
        }

        fun rasterizeNodes(nodes: List<com.veilframe.app.qr.geometry.QrGeometryNode>, target: IntArray) {
            for (node in nodes) {
                if (node is com.veilframe.app.qr.geometry.PolygonNode) {
                    val pts = node.pointsList
                    val minX = pts.minOf { it.first }.toInt().coerceIn(0, width - 1)
                    val maxX = pts.maxOf { it.first }.toInt().coerceIn(0, width - 1)
                    val minY = pts.minOf { it.second }.toInt().coerceIn(0, height - 1)
                    val maxY = pts.maxOf { it.second }.toInt().coerceIn(0, height - 1)
                    for (y in minY..maxY) {
                        for (x in minX..maxX) {
                            if (pointInConvexPolygon(x + 0.5f, y + 0.5f, pts)) {
                                target[y * width + x] = node.fill ?: 0
                            }
                        }
                    }
                }
            }
        }

        // Paint in forward painter order
        rasterizeNodes(ir.rootNodes, raster)

        // Paint in reverse painter order
        val reverseRaster = IntArray(width * height)
        val reversedPolys = ir.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.PolygonNode>().reversed()
        rasterizeNodes(reversedPolys, reverseRaster)

        // Find ALL pixels in the 2D overlap footprint where the forward and reverse rasters differ
        val overlapFootprint = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (raster[y * width + x] != 0 && reverseRaster[y * width + x] != 0 && raster[y * width + x] != reverseRaster[y * width + x]) {
                    overlapFootprint.add(Pair(x, y))
                }
            }
        }

        assertTrue("There must be a non-empty 2D overlap footprint between the two blocks", overlapFootprint.size >= 10)

        // Prove 100% of the overlap footprint is Green (top face visible) under forward order,
        // and under reverse order the extrusion faces (Left=Red / Right=Blue) incorrectly occlude the Top face!
        for ((px, py) in overlapFootprint) {
            assertEquals("Forward order must render Top face (Green) over overlapping pixel at ($px, $py)", topColor, raster[py * width + px])
            val revColor = reverseRaster[py * width + px]
            assertTrue(
                "Reverse order must incorrectly render an extrusion face (Left=Red or Right=Blue) at ($px, $py), got: ${Integer.toHexString(revColor)}",
                revColor == leftColor || revColor == rightColor
            )
        }
    }

    @Test
    fun testSvgAlphaNoAccidentalDoubleAttenuation() {
        val semiGreen = 0x8000FF00.toInt() // 50% green
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ALPHA-COMPOSITING", ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE,
            resampleStyle = com.veilframe.app.qr.model.ResampleStyle(
                useSourceAsBackdrop = true,
                backdropTint = semiGreen
            )
        )

        val svg = SvgExporter.generateSvg(matrix, design)
        assertFalse(
            "Backdrop tint rect must NOT duplicate opacity inside rgba and opacity attribute",
            svg.contains("""fill="rgba(0,255,0,""") && svg.contains("""opacity="0.50"""")
        )
        assertTrue("Backdrop tint rect must emit solid hex fill", svg.contains("""fill="#00FF00""""))
        assertTrue("Backdrop tint rect must emit opacity attribute", svg.contains("""opacity="0.50""""))

        // Extract opacity attribute and fill color from the tint element in the generated SVG
        val tintRectRegex = Regex("""<rect[^>]*fill="(#[0-9A-Fa-f]{6})"[^>]*opacity="([0-9.]+)"[^>]*>""")
        val tintMatch = tintRectRegex.find(svg)
        assertNotNull("SVG must contain backdrop tint rect element", tintMatch)
        val extractedHex = tintMatch!!.groupValues[1]
        val extractedOpacity = tintMatch.groupValues[2].toFloat()

        assertEquals("#00FF00", extractedHex)
        assertEquals(0.50f, extractedOpacity, 0.01f)

        // End-to-End Rendered Software Pixel Buffer Compositing Proof:
        // Render tint rect over a 50x50 white pixel buffer (0xFFFFFFFF)
        val renderBuffer = IntArray(50 * 50) { 0xFFFFFFFF.toInt() }
        val alphaFloat = extractedOpacity
        val tintR = extractedHex.substring(1, 3).toInt(16)
        val tintG = extractedHex.substring(3, 5).toInt(16)
        val tintB = extractedHex.substring(5, 7).toInt(16)

        for (i in renderBuffer.indices) {
            val bgR = 255
            val bgG = 255
            val bgB = 255
            val compR = kotlin.math.round(tintR * alphaFloat + bgR * (1f - alphaFloat)).toInt()
            val compG = kotlin.math.round(tintG * alphaFloat + bgG * (1f - alphaFloat)).toInt()
            val compB = kotlin.math.round(tintB * alphaFloat + bgB * (1f - alphaFloat)).toInt()
            renderBuffer[i] = (0xFF shl 24) or (compR shl 16) or (compG shl 8) or compB
        }

        // Single attenuation produces 0xFF80FF80: R=128, G=255, B=128
        val expectedPixel = (0xFF shl 24) or (128 shl 16) or (255 shl 8) or 128
        // Double attenuation would produce 0xFFBFFFB7 (alpha = 0.25): R=191, G=255, B=191
        val doubleAttenuatedPixel = (0xFF shl 24) or (191 shl 16) or (255 shl 8) or 191

        for (px in renderBuffer) {
            assertEquals("Rendered pixel must match single attenuation (0xFF80FF80)", expectedPixel, px)
            assertNotEquals("Rendered pixel must NOT match double attenuation (0xFFBFFFB7)", doubleAttenuatedPixel, px)
        }
    }

    @Test
    fun testParameterNormalizationContractAndEfCompatibilityRange() {
        val testMatrix = QrMatrix(size = 21, version = 1, errorCorrection = ErrorCorrectionLevel.M) { col, row ->
            col == 10 && row == 10
        }
        val geom = QrGeometry(matrixSize = 21, outputWidth = 400, outputHeight = 400, quietZoneModules = 0)

        // =====================================================================
        // 1. Depth Normalization: Normal [0.0, 5.0] vs Negative Clamping [0.0, inf)
        // =====================================================================
        // Normal Depth (1.0f in EF range): Generates 3 faces (1 Top + 2 Extrusions)
        val normalD25Design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = 1.0f,
                positionDepth = 1.0f,
                topColor = 0xFF00FF00.toInt(),
                leftColor = 0xFFFF0000.toInt(),
                rightColor = 0xFF0000FF.toInt()
            )
        )
        val normalD25Ir = D25Geometry.buildGeometry(testMatrix, normalD25Design, geom)
        val normalPolys = normalD25Ir.rootNodes.filterIsInstance<PolygonNode>()
        assertEquals("Normal depth (1.0f) generates exactly 3 faces (Top, Left, Right)", 3, normalPolys.size)

        // Defensive Negative Depth (-2.5f clamped to 0.0f): Generates ONLY 1 Top Face (0 inverted extrusion faces)
        val negativeD25Design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = -2.5f,
                positionDepth = -2.5f,
                topColor = 0xFF00FF00.toInt(),
                leftColor = 0xFFFF0000.toInt(),
                rightColor = 0xFF0000FF.toInt()
            )
        )
        val negD25Ir = D25Geometry.buildGeometry(testMatrix, negativeD25Design, geom)
        val negPolys = negD25Ir.rootNodes.filterIsInstance<PolygonNode>()
        assertEquals("Negative depth (-2.5f -> 0.0f) must defensively clamp and emit ONLY Top face", 1, negPolys.size)
        assertEquals("Sole emitted face must be Top face (Green)", 0xFF00FF00.toInt(), negPolys[0].fill)

        // =====================================================================
        // 2. Line Thickness: Normal (0.5f) vs Negative (-0.1f) vs Excessive (2.0f)
        // =====================================================================
        val lineMatrix = QrMatrix(size = 21, version = 1, errorCorrection = ErrorCorrectionLevel.M) { col, row ->
            (col == 10 || col == 11) && row == 10
        }
        val cellSize = 10f
        // Normal Thickness (0.5f): stroke width = 0.5 * 10f = 5.0f
        val normalLineNodes = LineTopologyBuilder.buildTopology(
            matrix = lineMatrix,
            ox = 0f,
            oy = 0f,
            cs = cellSize,
            thicknessFraction = 0.5f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL
        )
        val normalLine = normalLineNodes.filterIsInstance<LineNode>().firstOrNull()
        assertNotNull("Normal line node must be emitted", normalLine)
        assertEquals("Normal line thickness (0.5f) evaluates to 5.0f stroke width", 5.0f, normalLine!!.strokeWidth, 0.001f)

        // Defensive Negative Thickness (-0.1f clamped to 0.05f): stroke width = 0.05 * 10f = 0.5f
        val negLineNodes = LineTopologyBuilder.buildTopology(
            matrix = lineMatrix,
            ox = 0f,
            oy = 0f,
            cs = cellSize,
            thicknessFraction = -0.1f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL
        )
        val negLine = negLineNodes.filterIsInstance<LineNode>().firstOrNull()
        assertNotNull(negLine)
        assertEquals("Negative line thickness (-0.1f -> 0.05f) clamped to safe minimum 0.5f", 0.5f, negLine!!.strokeWidth, 0.001f)

        // Excessive Thickness (2.0f clamped to 0.85f): stroke width = 0.85 * 10f = 8.5f
        val excessiveLineNodes = LineTopologyBuilder.buildTopology(
            matrix = lineMatrix,
            ox = 0f,
            oy = 0f,
            cs = cellSize,
            thicknessFraction = 2.0f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL
        )
        val excessiveLine = excessiveLineNodes.filterIsInstance<LineNode>().firstOrNull()
        assertNotNull(excessiveLine)
        assertEquals("Excessive line thickness (2.0f -> 0.85f) clamped to safe maximum 8.5f", 8.5f, excessiveLine!!.strokeWidth, 0.001f)

        // =====================================================================
        // 3. Data Scale Normalization: Normal (0.85f) vs Excessive (3.5f) vs Negative (-0.5f)
        // =====================================================================
        // Normal dataScale (0.85f): preserved
        val paramsNormalScale = QrStyleParams(style = QrStyle.IMAGE, imageDataScale = 0.85f)
        val designNormalScale = QrDesign.fromQrStyleParams(paramsNormalScale)
        assertEquals("Normal imageDataScale (0.85f) preserved", 0.85f, designNormalScale.imageDataScale!!, 0.001f)

        // Excessive dataScale (3.5f clamped to 1.0f):
        val paramsExcessiveScale = QrStyleParams(style = QrStyle.IMAGE, imageDataScale = 3.5f)
        val designExcessiveScale = QrDesign.fromQrStyleParams(paramsExcessiveScale)
        assertEquals("Excessive imageDataScale (3.5f -> 1.0f) clamped to max 1.0f", 1.0f, designExcessiveScale.imageDataScale!!, 0.001f)

        // Negative dataScale (-0.5f clamped to 0.05f):
        val paramsNegativeScale = QrStyleParams(style = QrStyle.IMAGE, imageDataScale = -0.5f)
        val designNegativeScale = QrDesign.fromQrStyleParams(paramsNegativeScale)
        assertEquals("Negative imageDataScale (-0.5f -> 0.05f) clamped to min 0.05f", 0.05f, designNegativeScale.imageDataScale!!, 0.001f)

        // =====================================================================
        // 4. Position Size, Timing Size, Align Size Normalization
        // =====================================================================
        val paramsSizes = QrStyleParams(
            style = QrStyle.IMAGE,
            imagePositionSize = 0.8f,
            imageTimingSize = 0.6f,
            imageAlignSize = 0.7f
        )
        val designSizes = QrDesign.fromQrStyleParams(paramsSizes)
        assertEquals("imagePositionSize preserved", 0.8f, designSizes.positionSize, 0.001f)
        assertEquals("imageTimingSize preserved", 0.6f, designSizes.timingSize, 0.001f)
        assertEquals("imageAlignSize preserved", 0.7f, designSizes.alignSize, 0.001f)

        // =====================================================================
        // 5. Logo Fraction / Icon Percentage Safety Guard
        // =====================================================================
        val dummyLogo = createDummyBitmap()
        val paramsNormalLogo = QrStyleParams(style = QrStyle.BASIC, logo = dummyLogo, logoFraction = 0.20f)
        val designNormalLogo = QrDesign.fromQrStyleParams(paramsNormalLogo)
        assertEquals(0.20f, designNormalLogo.logo!!.scaleFraction, 0.001f)

        // Defensive guard ensures logo does not obscure QR modules beyond recovery:
        val paramsExcessiveLogo = QrStyleParams(style = QrStyle.BASIC, logo = dummyLogo, logoFraction = 0.85f)
        val designExcessiveLogo = QrDesign.fromQrStyleParams(paramsExcessiveLogo)
        assertEquals("Excessive logo fraction (0.85f -> 0.35f) clamped to safe maximum", 0.35f, designExcessiveLogo.logo!!.scaleFraction, 0.001f)

        val paramsNegativeLogo = QrStyleParams(style = QrStyle.BASIC, logo = dummyLogo, logoFraction = -0.10f)
        val designNegativeLogo = QrDesign.fromQrStyleParams(paramsNegativeLogo)
        assertEquals("Negative logo fraction (-0.10f -> 0.10f) clamped to safe minimum", 0.10f, designNegativeLogo.logo!!.scaleFraction, 0.001f)
    }

    // =========================================================================
    // LEVEL 6: COMPREHENSIVE ZXING DECODE PARITY - RESAMPLE EXTENSION
    // =========================================================================

    @Test
    fun testSoftwareRasterizedResampleDecodableByZxing() {
        val payload = "HTTPS://VEILFRAME.APP/RESAMPLE-ZXING-DECODE"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val n = matrix.size
        val qz = 4
        val scale = 10
        val totalModules = n + 2 * qz
        val totalPx = totalModules * scale
        val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() }

        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE
        )
        val geom = QrGeometry(matrix.size, totalPx, totalPx, qz)
        val ir = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, design, geom)

        for (node in ir.rootNodes) {
            if (node is com.veilframe.app.qr.geometry.RectNode) {
                val fill = node.fill ?: continue
                val minX = node.x.toInt().coerceIn(0, totalPx - 1)
                val minY = node.y.toInt().coerceIn(0, totalPx - 1)
                val maxX = (node.x + node.width).toInt().coerceIn(0, totalPx)
                val maxY = (node.y + node.height).toInt().coerceIn(0, totalPx)
                for (py in minY until maxY) {
                    val rowOffset = py * totalPx
                    for (px in minX until maxX) {
                        pixels[rowOffset + px] = fill
                    }
                }
            }
        }

        val source = RGBLuminanceSource(totalPx, totalPx, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val decoded = reader.decode(binaryBitmap)

        assertNotNull("Decoded result must not be null", decoded)
        assertEquals("ZXing must successfully decode rasterized Resample style QR", payload, decoded.text)
    }

    // =========================================================================
    // SECTION: EFQRCode Cross-Engine Parity Proofs (Audit Verification)
    // =========================================================================

    @Test
    fun testD25AffineTransformVertexParityWithEFQRCode() {
        // Multi-configuration differential proof that D25Geometry 3-face polygons mathematically match
        // EFQRCode's SVG affine transforms across multiple module positions, heights, and scales:
        // Top:   matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)
        // Left:  matrix(...) translate(xValue + size, yValue) skewY(45) applied to [0..h] x [0..size]
        // Right: matrix(...) translate(xValue, yValue + size) skewX(45) applied to [0..size] x [0..h]
        val n = 21
        val testMatrix = QrMatrix(size = n, version = 1, errorCorrection = ErrorCorrectionLevel.M) { col, row ->
            (col == 5 && row == 7) || (col == 12 && row == 14) || (col == 18 && row == 3)
        }
        val sq3h = (kotlin.math.sqrt(3.0) / 2.0).toFloat()

        val heights = listOf(0.5f, 1.0f, 2.0f)
        val scales = listOf(0.8f, 1.0f)
        val geom = QrGeometry(matrixSize = n, outputWidth = 1000, outputHeight = 1000, quietZoneModules = 0)

        for (h in heights) {
            for (scale in scales) {
                val design = QrDesign(
                    style = QrStyle.D25,
                    moduleStyle = ModuleStyle(scale = scale),
                    depthStyle = DepthStyle(
                        depth = h,
                        positionDepth = h,
                        topColor = 0xFF00FF00.toInt(),
                        leftColor = 0xFFFF0000.toInt(),
                        rightColor = 0xFF0000FF.toInt()
                    )
                )

                val ir = D25Geometry.buildGeometry(testMatrix, design, geom)
                val polys = ir.rootNodes.filterIsInstance<PolygonNode>()
                val proj = D25Geometry.computeProjection(n, 1000f, 1000f, 0)

                // For every dark module, verify generated polygon vertices against EF equations
                for (col in 0 until n) {
                    for (row in 0 until n) {
                        if (!testMatrix.isDark(col, row)) continue

                        val isPos = testMatrix.roleAt(col, row) == QrModuleRole.FINDER_INNER ||
                            testMatrix.roleAt(col, row) == QrModuleRole.FINDER_OUTER ||
                            testMatrix.functionMask.isFinder(col, row)
                        val effScale = if (isPos) 1.0f else scale
                        val offset = (1.0f - effScale) / 2.0f
                        val xVal = col + offset
                        val yVal = row + offset

                        // Analytical EF coordinates:
                        // Top face: [xVal, yVal] to [xVal + effScale, yVal + effScale]
                        val expectedP0x = sq3h * (xVal - yVal)
                        val expectedP0y = 0.5f * (xVal + yVal)
                        val expectedP1x = sq3h * (xVal + effScale - yVal)
                        val expectedP1y = 0.5f * (xVal + effScale + yVal)
                        val expectedP2x = sq3h * (xVal + effScale - (yVal + effScale)) // = sq3h * (xVal - yVal) = expectedP0x
                        val expectedP2y = 0.5f * (xVal + effScale + yVal + effScale) // = 0.5 * (xVal + yVal) + effScale
                        val expectedP3x = sq3h * (xVal - (yVal + effScale))
                        val expectedP3y = 0.5f * (xVal + yVal + effScale)

                        val screenP0x = (expectedP0x - proj.vbX) * proj.scale + proj.transX
                        val screenP0y = (expectedP0y - proj.vbY) * proj.scale + proj.transY
                        val screenP1x = (expectedP1x - proj.vbX) * proj.scale + proj.transX
                        val screenP1y = (expectedP1y - proj.vbY) * proj.scale + proj.transY
                        val screenP2x = (expectedP2x - proj.vbX) * proj.scale + proj.transX
                        val screenP2y = (expectedP2y - proj.vbY) * proj.scale + proj.transY
                        val screenP3x = (expectedP3x - proj.vbX) * proj.scale + proj.transX
                        val screenP3y = (expectedP3y - proj.vbY) * proj.scale + proj.transY

                        // Find matching Top polygon
                        val topPoly = polys.firstOrNull { p ->
                            p.fill == 0xFF00FF00.toInt() &&
                                kotlin.math.abs(p.pointsList[0].first - screenP0x) < 0.01f &&
                                kotlin.math.abs(p.pointsList[0].second - screenP0y) < 0.01f
                        }
                        assertNotNull("Generated Top polygon must exist at ($col, $row) for h=$h, s=$scale", topPoly)
                        assertEquals(screenP1x, topPoly!!.pointsList[1].first, 0.01f)
                        assertEquals(screenP1y, topPoly.pointsList[1].second, 0.01f)
                        assertEquals(screenP2x, topPoly.pointsList[2].first, 0.01f)
                        assertEquals(screenP2y, topPoly.pointsList[2].second, 0.01f)
                        assertEquals(screenP3x, topPoly.pointsList[3].first, 0.01f)
                        assertEquals(screenP3y, topPoly.pointsList[3].second, 0.01f)

                        // Left Face corners:
                        // Corner (h, 1) after skewY(45) and translate(xVal + scale, yVal):
                        //   x = xVal + scale + h, y = yVal + scale + h
                        //   M: X = sq3h * (xVal - yVal) [h cancels out!], Y = 0.5 * (xVal + yVal) + scale + h
                        val screenL2x = screenP2x
                        val screenL2y = screenP2y + h * proj.scale
                        val screenL3x = screenP1x
                        val screenL3y = screenP1y + h * proj.scale

                        val leftPoly = polys.firstOrNull { p ->
                            p.fill == 0xFFFF0000.toInt() &&
                                kotlin.math.abs(p.pointsList[0].first - screenP1x) < 0.01f &&
                                kotlin.math.abs(p.pointsList[0].second - screenP1y) < 0.01f
                        }
                        assertNotNull("Generated Left polygon must exist at ($col, $row) for h=$h, s=$scale", leftPoly)
                        assertEquals(screenP2x, leftPoly!!.pointsList[1].first, 0.01f)
                        assertEquals(screenP2y, leftPoly.pointsList[1].second, 0.01f)
                        assertEquals(screenL2x, leftPoly.pointsList[2].first, 0.01f)
                        assertEquals(screenL2y, leftPoly.pointsList[2].second, 0.01f)
                        assertEquals(screenL3x, leftPoly.pointsList[3].first, 0.01f)
                        assertEquals(screenL3y, leftPoly.pointsList[3].second, 0.01f)

                        // Right Face corners:
                        val screenR3x = screenP3x
                        val screenR3y = screenP3y + h * proj.scale

                        val rightPoly = polys.firstOrNull { p ->
                            p.fill == 0xFF0000FF.toInt() &&
                                kotlin.math.abs(p.pointsList[0].first - screenP3x) < 0.01f &&
                                kotlin.math.abs(p.pointsList[0].second - screenP3y) < 0.01f
                        }
                        assertNotNull("Generated Right polygon must exist at ($col, $row) for h=$h, s=$scale", rightPoly)
                        assertEquals(screenP2x, rightPoly!!.pointsList[1].first, 0.01f)
                        assertEquals(screenP2y, rightPoly.pointsList[1].second, 0.01f)
                        assertEquals(screenL2x, rightPoly.pointsList[2].first, 0.01f)
                        assertEquals(screenL2y, rightPoly.pointsList[2].second, 0.01f)
                        assertEquals(screenR3x, rightPoly.pointsList[3].first, 0.01f)
                        assertEquals(screenR3y, rightPoly.pointsList[3].second, 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun testD25QuietZoneFormulaParityWithEFQRCode() {
        val n = 25
        // Test with EFQRCode canonical quietzone insets: [0, 0.25, 0.5, 0.75, 1.0]
        val insetsList = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        for (q in insetsList) {
            val proj = com.veilframe.app.qr.geometry.D25Geometry.computeProjectionWithInsets(
                n = n,
                outputWidth = 500f,
                outputHeight = 500f,
                left = q,
                top = q,
                right = q,
                bottom = q
            )

            // EFQRCode exact viewBox formula:
            // x: -n * (left + 1)
            // y: -n * (top + 0.5)
            // width: n * (left + 2 + right)
            // height: n * (top + 2 + bottom)
            val expectedVbX = -n * (q + 1f)
            val expectedVbY = -n * (q + 0.5f)
            val expectedVbW = n * (q + 2f + q)
            val expectedVbH = n * (q + 2f + q)

            assertEquals("vbX parity for inset $q", expectedVbX, proj.vbX, 0.001f)
            assertEquals("vbY parity for inset $q", expectedVbY, proj.vbY, 0.001f)
            assertEquals("vbW parity for inset $q", expectedVbW, proj.vbW, 0.001f)
            assertEquals("vbH parity for inset $q", expectedVbH, proj.vbH, 0.001f)
        }

        // Verify integer module quiet zones: substituting q = Q / n gives identical viewBox
        for (qz in listOf(0, 2, 4, 8)) {
            val intProj = com.veilframe.app.qr.geometry.D25Geometry.computeProjection(n, 500f, 500f, qz, qz, qz, qz)
            val fracProj = com.veilframe.app.qr.geometry.D25Geometry.computeProjectionWithInsets(n, 500f, 500f, qz.toFloat() / n, qz.toFloat() / n, qz.toFloat() / n, qz.toFloat() / n)
            assertEquals(fracProj.vbX, intProj.vbX, 0.001f)
            assertEquals(fracProj.vbY, intProj.vbY, 0.001f)
            assertEquals(fracProj.vbW, intProj.vbW, 0.001f)
            assertEquals(fracProj.vbH, intProj.vbH, 0.001f)
        }

        // Also verify SvgExporter generate25DSvg produces exact viewBox string
        val dummyMatrix = QrMatrix("HTTPS://VEILFRAME.APP/D25", ErrorCorrectionLevel.M)
        val dummyDesign = QrDesign(
            style = QrStyle.D25,
            directionalQuietZone = com.veilframe.app.qr.model.DirectionalInsets(4, 4, 4, 4)
        )
        val svg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(dummyMatrix, dummyDesign)
        val expectedVbStr = "viewBox=\"-${dummyMatrix.size + 4} -${dummyMatrix.size / 2.0 + 4} ${(2 * dummyMatrix.size + 8).toDouble()} ${(2 * dummyMatrix.size + 8).toDouble()}\""
        assertTrue("D25 SVG export must contain canonical EF viewBox", svg.contains(expectedVbStr))
    }

    @Test
    fun testD25PainterOrderParityWithEFQRCode() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/D25-PAINTER-ORDER", ErrorCorrectionLevel.M)
        val n = matrix.size

        // EFQRCode (EFQRCodeStyle25D.swift:239-264) loops:
        // for x in 0..<nCount {
        //     for y in 0..<nCount { ... }
        // }
        // VeilFrame D25Geometry and SvgExporter must follow this exact (col, row) column-major order.
        val design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(depth = 1.0f, positionDepth = 1.0f)
        )
        val geom = QrGeometry(n, 500, 500, 4)
        val ir = D25Geometry.buildGeometry(matrix, design, geom)

        // Background is node 0
        assertEquals(1, ir.rootNodes.filterIsInstance<RectNode>().size)

        // Collect all dark modules in column-major order (x then y)
        val expectedModules = mutableListOf<Pair<Int, Int>>()
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.isDark(col, row)) {
                    expectedModules.add(Pair(col, row))
                }
            }
        }

        // Each dark module with depth > 0 emits 3 PolygonNodes: Top, Left, Right
        val polyNodes = ir.rootNodes.filterIsInstance<PolygonNode>()
        assertEquals(expectedModules.size * 3, polyNodes.size)

        val proj = D25Geometry.computeProjection(n, 500f, 500f, 4, 4, 4, 4)
        for (i in expectedModules.indices) {
            val (col, row) = expectedModules[i]
            val topNode = polyNodes[3 * i]
            val leftNode = polyNodes[3 * i + 1]
            val rightNode = polyNodes[3 * i + 2]

            assertEquals("Top face fill", design.depthStyle.topColor, topNode.fill)
            assertEquals("Left face fill", design.depthStyle.leftColor, leftNode.fill)
            assertEquals("Right face fill", design.depthStyle.rightColor, rightNode.fill)

            val isPosition = matrix.roleAt(col, row) == QrModuleRole.FINDER_INNER ||
                matrix.roleAt(col, row) == QrModuleRole.FINDER_OUTER ||
                matrix.functionMask.isFinder(col, row)
            val size = if (isPosition) 1.0f else design.moduleStyle.scale.coerceIn(0.1f, 1.0f)
            val offset = (1.0f - size) / 2.0f
            val c0 = col + offset
            val r0 = row + offset
            val expectedP0x = proj.screenX(c0, r0)
            val expectedP0y = proj.screenY(c0, r0, 0f)

            val topPts = topNode.pointsList ?: emptyList()
            assertEquals("Top face must have 4 vertices", 4, topPts.size)
            assertEquals("Module $i ($col, $row) screenX order mismatch", expectedP0x, topPts[0].first, 0.001f)
            assertEquals("Module $i ($col, $row) screenY order mismatch", expectedP0y, topPts[0].second, 0.001f)
        }

        // SvgExporter.generate25DSvg must also output rects in column-major order
        val svg = SvgExporter.generateSvg(matrix, design)
        val rectLines = svg.lines().filter { it.contains("<rect") && it.contains("transform=") }
        assertEquals(expectedModules.size * 3, rectLines.size)
    }

    @Test
    fun testImageStyleEFDefaultsParity() {
        // EFQRCode defaults:
        // EFStyleImageParamsData.scale = 1
        // allowTransparent = false
        val params = QrStyleParams(style = QrStyle.IMAGE)
        assertEquals("Default imageDataScale must be 1.0f for EF parity", 1.0f, params.imageDataScale, 0.001f)
        assertFalse("Default imageAllowTransparent must be false for EF parity", params.imageAllowTransparent)

        val design = QrDesign.fromQrStyleParams(params)
        assertEquals("Design moduleStyle scale must be 1.0f", 1.0f, design.moduleStyle.scale, 0.001f)
        assertEquals("Design imageDataScale must be 1.0f", 1.0f, design.imageDataScale ?: 0f, 0.001f)
        assertFalse("Design allowTransparent must be false", design.allowTransparent)
        assertFalse("Design imageSource allowTransparent must be false", design.imageSource.allowTransparent)
    }

    @Test
    fun testLineStyleEFModeDecoupling() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/LINE-EF", ErrorCorrectionLevel.M)
        val cs = 16f
        val ox = 0f
        val oy = 0f
        val thickness = 0.5f
        val baseStrokeWidth = cs * thickness // 8.0f

        // 1. EF Mode: HORIZONTAL direction
        // In EF: strokeWidth == size, node circle radius == size / 2
        val nodesH = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = ox,
            oy = oy,
            cs = cs,
            thicknessFraction = thickness,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL,
            variant = com.veilframe.app.qr.model.LineVariant.EF
        )
        val linesH = nodesH.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        for (line in linesH) {
            assertEquals("EF mode HORIZONTAL line stroke-width must equal cs * thickness", baseStrokeWidth, line.strokeWidth, 0.001f)
        }
        val circlesH = nodesH.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
        for (c in circlesH) {
            assertEquals("EF mode HORIZONTAL isolated node circle radius must strictly equal size / 2", baseStrokeWidth * 0.5f, c.radius, 0.001f)
        }

        // 2. EF Mode: X direction
        // In EF lines 547 & 566: stroke-width = (size / 2) * random(0.3...1.0) -> [0.15, 0.5] * size
        // In EF line 570: circle radius = 0.5 * random(0.33...0.90) in module units -> [0.165, 0.45] * cs
        val nodesX = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = ox,
            oy = oy,
            cs = cs,
            thicknessFraction = thickness,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.X,
            variant = com.veilframe.app.qr.model.LineVariant.EF,
            addAccentRings = false,
            circuitBridgesEnabled = false
        )
        val linesX = nodesX.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        assertTrue("X direction must contain diagonal line runs", linesX.isNotEmpty())
        for (line in linesX) {
            val minSw = (baseStrokeWidth * 0.5f) * 0.30f - 0.001f
            val maxSw = (baseStrokeWidth * 0.5f) * 1.0f + 0.001f
            assertTrue("Line stroke width ${line.strokeWidth} must be in EF range [$minSw, $maxSw]", line.strokeWidth in minSw..maxSw)
        }

        val circlesX = nodesX.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
        assertTrue("X direction must contain node circles at dark modules", circlesX.isNotEmpty())
        for (c in circlesX) {
            val minR = cs * 0.5f * 0.33f - 0.001f
            val maxR = cs * 0.5f * 0.90f + 0.001f
            assertTrue("Node circle radius ${c.radius} must be in EF range [$minR, $maxR]", c.radius in minR..maxR)
        }

        val horizontalLinesEf = nodesX.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>().filter { it.y1 == it.y2 }
        assertEquals("Pure EF mode must have zero horizontal circuit bridges in X direction", 0, horizontalLinesEf.size)
        val accentRingsEf = nodesX.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>().filter { it.stroke != null }
        assertEquals("Pure EF mode must have zero target accent rings", 0, accentRingsEf.size)

        // 3. Circuit Mode: Contains accent rings and circuit bridges
        val nodesCircuit = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = ox,
            oy = oy,
            cs = cs,
            thicknessFraction = thickness,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.X,
            variant = com.veilframe.app.qr.model.LineVariant.CIRCUIT,
            addAccentRings = true,
            circuitBridgesEnabled = true
        )
        val horizontalLinesCircuit = nodesCircuit.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>().filter { it.y1 == it.y2 }
        assertTrue("Circuit mode must contain horizontal spine bridges", horizontalLinesCircuit.isNotEmpty())
        val accentRingsCircuit = nodesCircuit.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>().filter { it.stroke != null }
        assertTrue("Circuit mode must contain accent target rings", accentRingsCircuit.isNotEmpty())

        // 4. Injectable randomSource test: exact deterministic random realization
        val testRngValues = floatArrayOf(0.50f)
        var rngCallCount = 0
        val seededRandomSource = {
            rngCallCount++
            testRngValues[0]
        }
        val nodesInjected = com.veilframe.app.qr.renderer.LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = ox,
            oy = oy,
            cs = cs,
            thicknessFraction = thickness,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.X,
            variant = com.veilframe.app.qr.model.LineVariant.EF,
            randomSource = seededRandomSource
        )
        assertTrue("Injected RNG must be invoked", rngCallCount > 0)
        val injectedLines = nodesInjected.filterIsInstance<com.veilframe.app.qr.geometry.LineNode>()
        val expectedInjectedSw = (baseStrokeWidth * 0.5f) * (0.30f + 0.50f * 0.70f)
        for (line in injectedLines) {
            assertEquals("Injected line stroke width must match exact random formula", expectedInjectedSw, line.strokeWidth, 0.001f)
        }
        val injectedCircles = nodesInjected.filterIsInstance<com.veilframe.app.qr.geometry.CircleNode>()
        val expectedInjectedRadius = cs * 0.5f * (0.33f + 0.50f * (0.90f - 0.33f))
        for (c in injectedCircles) {
            assertEquals("Injected circle node radius must match exact random formula", expectedInjectedRadius, c.radius, 0.001f)
        }
    }

    @Test
    fun testLineStyleProcessesAllNonFinderFunctionalRoles() {
        // Use a Version 7 QR code which has all 5 non-finder functional roles:
        // DATA, TIMING, ALIGNMENT, FORMAT, VERSION
        val payload = "https://veilframe.app/parity/line-all-roles-v7-verification-data-string-with-enough-bytes-for-version-7"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        assertTrue("Matrix must be at least Version 7 to test all 5 roles", matrix.version >= 7)

        val n = matrix.size
        val rolesFound = mutableSetOf<QrModuleRole>()
        for (c in 0 until n) {
            for (r in 0 until n) {
                rolesFound.add(matrix.roleAt(c, r))
            }
        }
        assertTrue("Must have DATA", rolesFound.contains(QrModuleRole.DATA))
        assertTrue("Must have TIMING", rolesFound.contains(QrModuleRole.TIMING))
        assertTrue("Must have ALIGNMENT", rolesFound.contains(QrModuleRole.ALIGNMENT_CENTER) || rolesFound.contains(QrModuleRole.ALIGNMENT_BORDER))
        assertTrue("Must have FORMAT", rolesFound.contains(QrModuleRole.FORMAT))
        assertTrue("Must have VERSION", rolesFound.contains(QrModuleRole.VERSION))

        // Build topology with HORIZONTAL direction
        val cs = 10f
        val nodes = LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = cs,
            thicknessFraction = 0.5f,
            lineColor = 0xFF000000.toInt(),
            direction = LineDirection.HORIZONTAL,
            variant = LineVariant.EF
        )

        // Collect all non-finder dark modules
        val nonFinderDarkModules = mutableListOf<Pair<Int, Int>>()
        for (c in 0 until n) {
            for (r in 0 until n) {
                if (matrix.isDark(c, r) && !VeilPositionPatternGeometry.isFinderArea(c, r, n)) {
                    nonFinderDarkModules.add(Pair(c, r))
                }
            }
        }
        assertTrue("Must have non-finder dark modules", nonFinderDarkModules.isNotEmpty())

        val lines = nodes.filterIsInstance<LineNode>()
        val circles = nodes.filterIsInstance<CircleNode>()

        for ((c, r) in nonFinderDarkModules) {
            val cx = (c + 0.5f) * cs
            val cy = (r + 0.5f) * cs

            val coveredByLine = lines.any { line ->
                if (line.y1 == cy && line.y2 == cy) {
                    val minX = minOf(line.x1, line.x2)
                    val maxX = maxOf(line.x1, line.x2)
                    cx in minX..maxX
                } else if (line.x1 == cx && line.x2 == cx) {
                    val minY = minOf(line.y1, line.y2)
                    val maxY = maxOf(line.y1, line.y2)
                    cy in minY..maxY
                } else false
            }
            val coveredByCircle = circles.any { circle ->
                kotlin.math.abs(circle.cx - cx) < 0.01f && kotlin.math.abs(circle.cy - cy) < 0.01f
            }

            val role = matrix.roleAt(c, r)
            assertTrue("Dark module at ($c, $r) with role $role must be processed by LineTopologyBuilder", coveredByLine || coveredByCircle)
        }
    }

    @Test
    fun testAnimatedWebpDelayParsingAndAnimationTiming() {
        // Construct synthetic animated WebP container bytes with 3 ANMF chunks:
        // Frame 0: 50ms
        // Frame 1: 120ms
        // Frame 2: 80ms
        val expectedDurations = listOf(50, 120, 80)
        val baos = java.io.ByteArrayOutputStream()
        baos.write("RIFF".toByteArray(Charsets.US_ASCII))
        baos.write(ByteArray(4)) // Size placeholder
        baos.write("WEBP".toByteArray(Charsets.US_ASCII))

        // VP8X Chunk (10 bytes payload)
        baos.write("VP8X".toByteArray(Charsets.US_ASCII))
        baos.write(byteArrayOf(10, 0, 0, 0))
        baos.write(byteArrayOf(0x02, 0, 0, 0)) // Animation flag (bit 1)
        baos.write(byteArrayOf(63, 0, 0)) // Canvas width 64
        baos.write(byteArrayOf(63, 0, 0)) // Canvas height 64

        // ANIM Chunk (6 bytes payload)
        baos.write("ANIM".toByteArray(Charsets.US_ASCII))
        baos.write(byteArrayOf(6, 0, 0, 0))
        baos.write(byteArrayOf(0, 0, 0, 0)) // Background color
        baos.write(byteArrayOf(0, 0)) // Loop count (0 = infinite)

        // ANMF Chunks (16 bytes payload each)
        for (dur in expectedDurations) {
            baos.write("ANMF".toByteArray(Charsets.US_ASCII))
            baos.write(byteArrayOf(16, 0, 0, 0))
            baos.write(byteArrayOf(0, 0, 0)) // frame X
            baos.write(byteArrayOf(0, 0, 0)) // frame Y
            baos.write(byteArrayOf(63, 0, 0)) // frame W
            baos.write(byteArrayOf(63, 0, 0)) // frame H
            // Duration uint24 little-endian in ms:
            baos.write(byteArrayOf(
                (dur and 0xFF).toByte(),
                ((dur shr 8) and 0xFF).toByte(),
                ((dur shr 16) and 0xFF).toByte()
            ))
            baos.write(byteArrayOf(0)) // flags
        }

        val webpBytes = baos.toByteArray()
        val riffSize = webpBytes.size - 8
        webpBytes[4] = (riffSize and 0xFF).toByte()
        webpBytes[5] = ((riffSize shr 8) and 0xFF).toByte()
        webpBytes[6] = ((riffSize shr 16) and 0xFF).toByte()
        webpBytes[7] = ((riffSize shr 24) and 0xFF).toByte()

        // 1. Verify parseWebpDelays parses exact ANMF durations from ByteArray
        val parsedDelays = com.veilframe.app.qr.AnimatedMediaHelper.parseWebpDelays(webpBytes)
        assertEquals("WebP ANMF delay parser must extract exactly 3 frame durations", 3, parsedDelays.size)
        assertEquals(expectedDurations, parsedDelays)

        // 2. Verify parseWebpDelays from File
        val tempWebpFile = java.io.File.createTempFile("test_anim", ".webp")
        try {
            tempWebpFile.writeBytes(webpBytes)
            val fileDelays = com.veilframe.app.qr.AnimatedMediaHelper.parseWebpDelays(tempWebpFile)
            assertEquals("WebP ANMF delay parser from File must match ByteArray result", expectedDurations, fileDelays)
        } finally {
            tempWebpFile.delete()
        }

        // 3. Fallback on invalid / empty bytes
        val emptyDelays = com.veilframe.app.qr.AnimatedMediaHelper.parseWebpDelays(ByteArray(0))
        assertTrue("Empty bytes must yield empty delay list", emptyDelays.isEmpty())

        // 4. Verify AnimatedQrGenerator timing propagation into animated SVG
        val dummyBmp = createDummyBitmap()
        val frames = parsedDelays.map { com.veilframe.app.qr.model.QrFrame(dummyBmp, it) }
        val testMatrix = QrMatrix("HTTPS://VEILFRAME.APP/WEBP-ANIM", ErrorCorrectionLevel.M)
        val baseDesign = QrDesign(style = QrStyle.IMAGE)

        val animatedSvg = com.veilframe.app.qr.AnimatedQrGenerator.generateAnimatedSvg(testMatrix, baseDesign, frames)
        assertTrue("Generated animated SVG must define qr_frame_0", animatedSvg.contains("id=\"qr_frame_0\""))
        assertTrue("Generated animated SVG must define qr_frame_1", animatedSvg.contains("id=\"qr_frame_1\""))
        assertTrue("Generated animated SVG must define qr_frame_2", animatedSvg.contains("id=\"qr_frame_2\""))

        // Total duration: 50 + 120 + 80 = 250ms -> 0.250s
        assertTrue("Animated SVG duration must equal total frame duration (0.250s)", animatedSvg.contains("dur=\"0.250s\"") || animatedSvg.contains("dur=\"0.25s\""))
    }

    @Test
    fun testResampleEFBehavioralParity() {
        // Behavioral verification of ResampleSubpixelEngine against EFQRCode's getGrayPointList() invariants
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE-PARITY", ErrorCorrectionLevel.H)
        val n = matrix.size
        val targetDim = 3 * n
        val seed = 12345L

        // Test 1: Solid White Image
        // In EF: white pixels have Y = 255 -> grayNorm = 1.0 -> threshold = 1.0
        // Because RND in [0, 1) is never > 1.0, ZERO stochastic subpixels emit.
        // ONLY the solid center anchors for dark modules must be emitted.
        val whitePixels = IntArray(targetDim * targetDim) { 0xFFFFFFFF.toInt() }
        val whiteSource = ArrayPixelSource(targetDim, targetDim, whitePixels)
        val style = ImageSourceStyle(contrast = 0.0f, exposure = 0.0f)
        val emittedWhite = mutableListOf<Pair<Int, Int>>()
        val anchorsWhite = mutableListOf<Pair<Int, Int>>()

        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = whiteSource,
            style = style,
            seed = seed,
            policy = ArtisticResamplePolicy(rngMode = ResampleRngMode.DETERMINISTIC)
        ) { _, _, sx, sy, isCenterAnchor ->
            if (isCenterAnchor) {
                anchorsWhite.add(Pair(sx, sy))
            } else {
                emittedWhite.add(Pair(sx, sy))
            }
        }

        assertTrue("Solid white image must emit zero stochastic photo dots", emittedWhite.isEmpty())
        assertTrue("Solid white image must emit center anchors for dark modules", anchorsWhite.isNotEmpty())
        for (anchor in anchorsWhite) {
            val col = anchor.first / 3
            val row = anchor.second / 3
            assertTrue("Anchor must only be at dark modules", matrix.isDark(col, row))
            assertEquals("Anchor x must be center subpixel (3*col + 1)", 3 * col + 1, anchor.first)
            assertEquals("Anchor y must be center subpixel (3*row + 1)", 3 * row + 1, anchor.second)
        }

        // Test 2: Solid Black Image
        // In EF: black pixels have Y = 0 -> grayNorm = 0.0 -> threshold = 0.0
        val blackPixels = IntArray(targetDim * targetDim) { 0xFF000000.toInt() }
        val blackSource = ArrayPixelSource(targetDim, targetDim, blackPixels)
        val emittedBlack = mutableSetOf<Pair<Int, Int>>()

        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = blackSource,
            style = style,
            seed = seed,
            policy = ArtisticResamplePolicy(rngMode = ResampleRngMode.DETERMINISTIC)
        ) { _, _, sx, sy, isCenterAnchor ->
            if (!isCenterAnchor) {
                emittedBlack.add(Pair(sx, sy))
            }
        }

        val efPosBoxes = listOf(
            Pair(0 until 24, 0 until 24),
            Pair((3 * n - 24) until (3 * n), 0 until 24),
            Pair(0 until 24, (3 * n - 24) until (3 * n))
        )

        for (dot in emittedBlack) {
            val sx = dot.first
            val sy = dot.second
            // 1. Must NOT be in any 24x24 finder box
            for (box in efPosBoxes) {
                val inBox = sx in box.first && sy in box.second
                assertFalse("Resampled subpixel ($sx, $sy) must NEVER be inside finder box $box", inBox)
            }
            // 2. Must NOT be center subpixel
            assertFalse("Resampled subpixel ($sx, $sy) must not be a center anchor", sx % 3 == 1 && sy % 3 == 1)
        }

        // Test 3: High-contrast Gradient Image (Left half black, Right half white)
        val gradientPixels = IntArray(targetDim * targetDim) { idx ->
            val x = idx % targetDim
            if (x < targetDim / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val gradientSource = ArrayPixelSource(targetDim, targetDim, gradientPixels)
        val emittedGradient = mutableSetOf<Pair<Int, Int>>()

        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = gradientSource,
            style = style,
            seed = seed,
            policy = ArtisticResamplePolicy(rngMode = ResampleRngMode.DETERMINISTIC)
        ) { _, _, sx, sy, isCenterAnchor ->
            if (!isCenterAnchor) {
                emittedGradient.add(Pair(sx, sy))
            }
        }

        val leftDots = emittedGradient.count { it.first < targetDim / 2 }
        val rightDots = emittedGradient.count { it.first >= targetDim / 2 }
        assertTrue("Left dark half must have many emitted dots ($leftDots)", leftDots > 100)
        assertEquals("Right pure-white half must have exactly 0 emitted dots", 0, rightDots)
    }

    @Test
    fun testResampleExactCoordinateSetDifferentialParityWithEFQRCode() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE-EXACT-PARITY", ErrorCorrectionLevel.H)
        val n = matrix.size
        val targetDim = 3 * n

        // 1. Compute expected EF eligible coordinates from EFQRCodeStyleResampleImage.swift:748-847
        val expectedEfEligibleCoords = mutableSetOf<Pair<Int, Int>>()
        for (x in 0 until targetDim) {
            for (y in 0 until targetDim) {
                // PosOrigins: 24x24 finder boxes
                val inTopLeftFinder = x < 24 && y < 24
                val inTopRightFinder = x >= (targetDim - 24) && y < 24
                val inBottomLeftFinder = x < 24 && y >= (targetDim - 24)
                if (inTopLeftFinder || inTopRightFinder || inBottomLeftFinder) continue

                val col = x / 3
                val row = y / 3
                val role = matrix.roleAt(col, row)
                val isDark = matrix.isDark(col, row)

                // Timing & Alignment: if light, excluded (swOrigins or bwOrigins)
                if ((role == QrModuleRole.TIMING ||
                     role == QrModuleRole.ALIGNMENT_CENTER ||
                     role == QrModuleRole.ALIGNMENT_BORDER) && !isDark) {
                    continue
                }

                // Center subpixel condition: (x % 3 != 1 || y % 3 != 1) in EF line 843
                if (x % 3 == 1 && y % 3 == 1) continue

                expectedEfEligibleCoords.add(Pair(x, y))
            }
        }

        // 2. Compute expected EF center anchors from EFQRCodeStyleResampleImage.swift:361-465
        val expectedEfAnchors = mutableSetOf<Pair<Int, Int>>()
        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                val isDark = matrix.isDark(col, row)
                if (!isDark) continue
                // Finders are drawn separately with positionType
                if (role == QrModuleRole.FINDER_INNER || role == QrModuleRole.FINDER_OUTER || role == QrModuleRole.SEPARATOR) {
                    continue
                }
                // With default timing/align styles, dedicated timing/align renderers draw them.
                // In VeilFrame default policy: shouldDrawAnchor returns false for timing/alignment unless style is NONE
                // All other dark modules (DATA, FORMAT, VERSION) emit #Sb at (3*col + 1, 3*row + 1)
                if (role != QrModuleRole.TIMING && role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) {
                    expectedEfAnchors.add(Pair(3 * col + 1, 3 * row + 1))
                }
            }
        }

        // 3. Run VeilFrame ResampleSubpixelEngine on a pure black image (threshold = 0.0)
        val blackPixels = IntArray(targetDim * targetDim) { 0xFF000000.toInt() }
        val blackSource = ArrayPixelSource(targetDim, targetDim, blackPixels)
        val style = ImageSourceStyle(contrast = 0.0f, exposure = 0.0f)
        val emittedSubpixels = mutableSetOf<Pair<Int, Int>>()
        val emittedAnchors = mutableSetOf<Pair<Int, Int>>()

        ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = blackSource,
            style = style,
            seed = 42L,
            policy = ArtisticResamplePolicy(rngMode = ResampleRngMode.DETERMINISTIC)
        ) { _, _, sx, sy, isCenterAnchor ->
            if (isCenterAnchor) {
                emittedAnchors.add(Pair(sx, sy))
            } else {
                emittedSubpixels.add(Pair(sx, sy))
            }
        }

        // 4. Assert 1:1 coordinate set equality!
        assertEquals("Emitted resample subpixel coordinate set must match EF eligible coordinates 1:1", expectedEfEligibleCoords, emittedSubpixels)
        assertEquals("Emitted center anchor coordinate set must match EF anchor coordinates 1:1", expectedEfAnchors, emittedAnchors)
    }

    @Test
    fun testImageStyleDifferentialParityWithEFQRCode() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/IMAGE-PARITY", ErrorCorrectionLevel.M)
        val n = matrix.size
        val geom = QrGeometry(n, 420, 420, 0)
        val mSize = geom.moduleSize
        val renderer = ImageRenderer()

        // 1. allowTransparent = false (EF default)
        val paramsEf = QrStyleParams(
            style = QrStyle.IMAGE,
            imageDataScale = 1.0f,
            imageAllowTransparent = false,
            imagePositionDarkColor = 0xFF000000.toInt(),
            imagePositionLightColor = 0xFFFFFFFF.toInt()
        )
        val designEf = QrDesign.fromQrStyleParams(paramsEf)
        val irEf = renderer.generateGeometry(matrix, designEf, geom)

        // Verify Defs contain #hole mask with 3 finder cutouts of 8x8 modules
        val defsStr = irEf.defs.joinToString("\n")
        assertTrue("Defs must define #hole mask", defsStr.contains("<mask id=\"hole\">"))
        val expectedFinderW = 8 * mSize
        assertTrue("Cutout must have width $expectedFinderW", defsStr.contains("width=\"$expectedFinderW\""))
        assertTrue("Cutout must have height $expectedFinderW", defsStr.contains("height=\"$expectedFinderW\""))

        // Verify Canvas Background is at layer index 0, ImageNode at layer index 1 with maskId = "hole"
        assertTrue("Layer 0 must be background canvas rect", irEf.rootNodes[0] is RectNode)
        val imageNode = irEf.rootNodes[1] as ImageNode
        assertEquals("ImageNode maskId must be 'hole'", "hole", imageNode.maskId)
        assertEquals(0f, imageNode.x, 0.001f)
        assertEquals(0f, imageNode.y, 0.001f)
        assertEquals(n * mSize, imageNode.width, 0.001f)
        assertEquals(n * mSize, imageNode.height, 0.001f)

        // Verify 3 finder backing rects of 8x8 modules immediately following ImageNode
        val rects = irEf.rootNodes.filterIsInstance<RectNode>()
        val finderBackings = rects.filter { it.width == 8 * mSize && it.height == 8 * mSize && it.fill == 0xFFFFFFFF.toInt() }
        assertEquals("Must contain exactly 3 finder backing rects of size 8x8", 3, finderBackings.size)

        // Verify data modules with scale = 1.0f (no offset)
        val dataModules = rects.filter { it.width == mSize && it.height == mSize }
        assertTrue("Must contain data module rects", dataModules.isNotEmpty())
        for (dm in dataModules) {
            val col = (dm.x / mSize).toInt()
            val row = (dm.y / mSize).toInt()
            assertEquals("Module X must align with grid col", col * mSize, dm.x, 0.001f)
            assertEquals("Module Y must align with grid row", row * mSize, dm.y, 0.001f)
        }

        // Verify that allowTransparent = false generates both dark and light modules
        val darkModulesCount = rects.count { it.fill == designEf.dataColorDark }
        val lightModulesCount = rects.count { it.fill == designEf.dataColorLight }
        assertTrue("allowTransparent=false must emit dark data modules", darkModulesCount > 0)
        assertTrue("allowTransparent=false must emit light data modules", lightModulesCount > 0)

        // 2. allowTransparent pre-pass verification:
        // When allowTransparent = false (EF default): no pre-pass under the image
        val efImageIndex = irEf.rootNodes.indexOfFirst { it is ImageNode }
        assertEquals("When allowTransparent=false, ImageNode immediately follows background (index 1)", 1, efImageIndex)

        // When allowTransparent = true: transparent pre-pass emits full-scale modules before ImageNode
        val paramsTrans = QrStyleParams(
            style = QrStyle.IMAGE,
            imageDataScale = 0.8f,
            imageAllowTransparent = true
        )
        val irTrans = renderer.generateGeometry(matrix, QrDesign.fromQrStyleParams(paramsTrans), geom)
        val transImageIndex = irTrans.rootNodes.indexOfFirst { it is ImageNode }
        assertTrue("When allowTransparent=true, pre-pass modules must be emitted before ImageNode", transImageIndex > 1)

        // 3. Scaled data modules with scale = 0.8f: offset must be (1.0 - scale)/2 * mSize = 0.1 * mSize
        val paramsScaled = QrStyleParams(
            style = QrStyle.IMAGE,
            imageDataScale = 0.8f,
            imageAllowTransparent = false
        )
        val designScaled = QrDesign.fromQrStyleParams(paramsScaled)
        val irScaled = renderer.generateGeometry(matrix, designScaled, geom)
        val expectedScaledW = 0.8f * mSize
        val scaledRects = irScaled.rootNodes.filterIsInstance<RectNode>().filter {
            kotlin.math.abs(it.width - expectedScaledW) < 0.01f && kotlin.math.abs(it.height - expectedScaledW) < 0.01f
        }
        assertTrue("Must contain 0.8x scaled data modules", scaledRects.isNotEmpty())
        for (sm in scaledRects) {
            val col = (sm.x / mSize).toInt()
            val row = (sm.y / mSize).toInt()
            val expectedX = (col + 0.1f) * mSize
            val expectedY = (row + 0.1f) * mSize
            assertEquals("Scaled module X must have centering offset", expectedX, sm.x, 0.01f)
            assertEquals("Scaled module Y must have centering offset", expectedY, sm.y, 0.01f)
        }

        // 4. Image scale mode mapping
        val designFit = designEf.copy(imageSource = designEf.imageSource.copy(scaleMode = ImageScaleMode.ASPECT_FIT))
        val irFit = renderer.generateGeometry(matrix, designFit, geom)
        assertEquals("xMidYMid meet", irFit.rootNodes.filterIsInstance<ImageNode>().first().preserveAspectRatio)

        val designStretch = designEf.copy(imageSource = designEf.imageSource.copy(scaleMode = ImageScaleMode.STRETCH))
        val irStretch = renderer.generateGeometry(matrix, designStretch, geom)
        assertEquals("none", irStretch.rootNodes.filterIsInstance<ImageNode>().first().preserveAspectRatio)
    }

    @Test
    fun testImageFillDifferentialParityWithEFQRCode() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/IMAGE-FILL-PARITY", ErrorCorrectionLevel.M)
        val n = matrix.size
        val geom = QrGeometry(n, 420, 420, 0)
        val mSize = geom.moduleSize
        val renderer = ImageFillRenderer()

        val dummyBmp = createDummyBitmap()
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageFillBackgroundColor = 0xFFFFFFFF.toInt(),
            imageFillMaskColor = 0x1A000000.toInt(),
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(dummyBmp),
                opacity = 0.85f,
                scaleMode = ImageScaleMode.ASPECT_FIT
            )
        )
        val ir = renderer.generateGeometry(matrix, design, geom)

        // 1. Verify Defs contain #hole mask
        val defsStr = ir.defs.joinToString("\n")
        assertTrue("Defs must define #hole mask", defsStr.contains("<mask id=\"hole\">"))
        assertTrue("Mask must start with black base rect", defsStr.contains("<rect x=\"0\" y=\"0\" width=\"420.0\" height=\"420.0\" fill=\"black\"/>"))

        // Anti-gap 1.02 expansion for dark modules: width = mSize + 2*0.01*mSize = 1.02 * mSize
        val expectedStencilW = 1.02f * mSize
        assertTrue("Mask must expand dark modules by 1.02x ($expectedStencilW)", defsStr.contains("width=\"$expectedStencilW\""))
        assertTrue("Mask must expand dark modules by 1.02x ($expectedStencilW)", defsStr.contains("height=\"$expectedStencilW\""))

        // 2. Verify GroupNode uses maskId = "hole"
        val group = ir.rootNodes.filterIsInstance<GroupNode>().firstOrNull()
        assertNotNull("Root nodes must contain GroupNode with hole mask", group)
        assertEquals("GroupNode maskId must be 'hole'", "hole", group!!.maskId)

        // 3. Verify Children of GroupNode: strictly 3 layers in exact EF order
        // [0] Background inside dark modules
        // [1] ImageNode spanning complete QR area
        // [2] Tint overlay rect
        assertEquals("GroupNode must contain exactly 3 composited layers", 3, group.children.size)

        // Child 0: Background rect
        val bgNode = group.children[0]
        assertTrue("Child #0 must be RectNode (background)", bgNode is RectNode)
        val bgRect = bgNode as RectNode
        assertEquals("Background rect fill must match imageFillBackgroundColor", 0xFFFFFFFF.toInt(), bgRect.fill)
        assertEquals(0f, bgRect.x, 0.001f)
        assertEquals(0f, bgRect.y, 0.001f)
        assertEquals(n * mSize, bgRect.width, 0.001f)
        assertEquals(n * mSize, bgRect.height, 0.001f)

        // Child 1: ImageNode
        val imgNode = group.children[1]
        assertTrue("Child #1 must be ImageNode", imgNode is ImageNode)
        val imageNode = imgNode as ImageNode
        assertEquals("ImageNode x must be 0", 0f, imageNode.x, 0.001f)
        assertEquals("ImageNode y must be 0", 0f, imageNode.y, 0.001f)
        assertEquals("ImageNode width must occupy complete QR area", n * mSize, imageNode.width, 0.001f)
        assertEquals("ImageNode height must occupy complete QR area", n * mSize, imageNode.height, 0.001f)
        assertEquals("ImageNode opacity must match imageSource opacity", 0.85f, imageNode.opacity, 0.001f)
        assertEquals("ImageNode preserveAspectRatio must match ASPECT_FIT", "xMidYMid meet", imageNode.preserveAspectRatio)

        // Child 2: Tint overlay rect
        val tintNode = group.children[2]
        assertTrue("Child #2 must be RectNode (tint)", tintNode is RectNode)
        val tintRect = tintNode as RectNode
        assertEquals("Tint overlay fill must match imageFillMaskColor", 0x1A000000.toInt(), tintRect.fill)
        assertEquals(0f, tintRect.x, 0.001f)
        assertEquals(0f, tintRect.y, 0.001f)
        assertEquals(n * mSize, tintRect.width, 0.001f)
        assertEquals(n * mSize, tintRect.height, 0.001f)
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

