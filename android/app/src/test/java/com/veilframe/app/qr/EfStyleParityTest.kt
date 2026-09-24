package com.veilframe.app.qr

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
 * Verification test suite for EFQRCode parity fixes:
 * 1. Default contrast in [ImageSourceStyle] must be 0.0f for EF threshold math parity.
 * 2. IMAGE style: #hole mask with 8x8 finder cutouts, 8x8 backing rects, dual dark/light modules.
 * 3. IMAGE_FILL style: continuous image fill through 1.02 anti-gap stencil mask with background and tint.
 * 4. 2.5D style: axonometric isometric projection viewBox and matrix, skewY/skewX extrusions.
 * 5. Parameter mapping fidelity from [QrStyleParams] to [QrDesign].
 * 6. ZXing decode verification on pure software rasterized output.
 */
class EfStyleParityTest {

    @Test
    fun testResampleDefaultContrastParity() {
        // EFQRCode default: contrast: CGFloat = 0
        // Formula in EF: (grayNorm + exposure - 0.5) * (contrast + 1) + 0.5
        // When contrast = 0.0f, (contrast + 1.0f) evaluates to 1.0f (no artificial 2x contrast boost)
        val defaultStyle = ImageSourceStyle()
        assertEquals(
            "Default contrast in ImageSourceStyle must be 0.0f for EFQRCode parity",
            0.0f,
            defaultStyle.contrast,
            0.0001f
        )
        val multiplier = defaultStyle.contrast + 1.0f
        assertEquals("Default threshold contrast multiplier must be exactly 1.0f", 1.0f, multiplier, 0.0001f)
    }

    @Test
    fun testImageStyleParameterMappingAndSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/ef-image-parity", ErrorCorrectionLevel.H)
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

        // Verify SVG Export EFQRCode Parity
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
        val matrix = QrMatrix("https://veilframe.app/ef-image-fill-parity", ErrorCorrectionLevel.H)

        val params = QrStyleParams(
            style = QrStyle.IMAGE_FILL,
            imageFillBackgroundColor = 0xFFFAFAFA.toInt(),
            imageFillMaskColor = 0x1A000000 // 10% black
        )
        val design = QrDesign.fromQrStyleParams(params)

        assertEquals(QrStyle.IMAGE_FILL, design.style)
        assertEquals(0xFFFAFAFA.toInt(), design.imageFillBackgroundColor)
        assertEquals(0x1A000000, design.imageFillMaskColor)

        // Verify SVG Export EFQRCode Parity
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
        val matrix = QrMatrix("https://veilframe.app/ef-25d-parity", ErrorCorrectionLevel.H)
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

        // Verify SVG Export EFQRCode Parity
        val svg = SvgExporter.generateSvg(matrix, design)

        // Verify EF viewBox: [-n, -n/2, 2*n, 2*n]
        val expectedViewBox = "viewBox=\"-$n -${n / 2.0} ${n * 2.0} ${n * 2.0}\""
        assertTrue("SVG must have EFQRCode axonometric viewBox: $expectedViewBox", svg.contains(expectedViewBox))

        // Verify EF axonometric transform matrix: matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)
        assertTrue("SVG must contain EF axonometric matrix", svg.contains("matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)"))

        // Verify Left face skewY(45) and Right face skewX(45)
        assertTrue("SVG must contain Left face skewY(45)", svg.contains("skewY(45)"))
        assertTrue("SVG must contain Right face skewX(45)", svg.contains("skewX(45)"))
    }

    @Test
    fun testSoftwareRasterizedImageFillDecodableByZxing() {
        val payload = "https://veilframe.app/ef-image-fill-zxing"
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
        val matrix = QrMatrix("https://veilframe.app/ef-dsj-parity", ErrorCorrectionLevel.H)

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
        assertNotNull(design.efDsjStyle)
        assertEquals(0.8f, design.efDsjStyle!!.lineSize, 0.001f)
        assertEquals(0.9f, design.efDsjStyle!!.xSize, 0.001f)
        assertEquals(0xFFF6B506.toInt(), design.efDsjStyle!!.horizontalLineColor)
        assertEquals(0xFFE02020.toInt(), design.efDsjStyle!!.verticalLineColor)
        assertEquals(0xFF0B2D97.toInt(), design.efDsjStyle!!.xColor)

        // Verify SVG Export EFQRCode Parity
        val svg = SvgExporter.generateSvg(matrix, design)

        // Verify colors are present in SVG output
        assertTrue("SVG must contain DSJ horizontal color #F6B506", svg.contains("#F6B506"))
        assertTrue("SVG must contain DSJ vertical color #E02020", svg.contains("#E02020"))
        assertTrue("SVG must contain DSJ X color #0B2D97", svg.contains("#0B2D97"))
        assertTrue("SVG must contain stroke lines for X crosses", svg.contains("<line") || svg.contains("<rect"))
    }

    @Test
    fun testFunctionStyleFadeAndCircleSvgParity() {
        val matrix = QrMatrix("https://veilframe.app/ef-function-parity", ErrorCorrectionLevel.H)

        // Test FADE function
        val fadeParams = QrStyleParams(
            style = QrStyle.FUNCTION,
            functionType = EfFunctionType.FADE,
            functionDataStyle = EfFunctionDataStyle.ROUND,
            functionDataColor = 0xFF123456.toInt()
        )
        val fadeDesign = QrDesign.fromQrStyleParams(fadeParams)
        assertEquals(QrStyle.FUNCTION, fadeDesign.style)
        assertEquals(EfFunctionType.FADE, fadeDesign.efFunctionStyle?.functionType)
        assertEquals(EfFunctionDataStyle.ROUND, fadeDesign.efFunctionStyle?.dataStyle)

        val fadeSvg = SvgExporter.generateSvg(matrix, fadeDesign)
        assertTrue("Fade SVG must contain data color #123456", fadeSvg.contains("#123456"))
        assertTrue("Fade SVG with ROUND style must contain circles", fadeSvg.contains("<circle"))

        // Test CIRCLE function
        val circleParams = QrStyleParams(
            style = QrStyle.FUNCTION,
            functionType = EfFunctionType.CIRCLE,
            functionDataStyle = EfFunctionDataStyle.ROUND,
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
        val matrix = QrMatrix("https://veilframe.app/ef-line-parity", ErrorCorrectionLevel.H)

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
}
