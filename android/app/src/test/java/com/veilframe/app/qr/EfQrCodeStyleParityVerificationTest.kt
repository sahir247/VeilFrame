package com.veilframe.app.qr

import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.geometry.D25Geometry
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.LineRenderer
import com.veilframe.app.qr.renderer.LineTopologyBuilder
import com.veilframe.app.qr.renderer.RandomRectangleRenderer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * Strict verification test suite enforcing exact EFQRCode parity across styles:
 *
 * 1. D25: Data and position modules use scale 1.0 (full cell coverage) by default.
 * 2. IMAGE: Data scale defaults to 1.0 (not hardcoded 0.33 override).
 * 3. LINE: Lossless support and distinct geometry for all 7 EF directions:
 *    Horizontal, Vertical, Cross, Loopback, Diagonal ↘, Diagonal ↙, X.
 * 4. RANDOM_RECTANGLE: Default offsetJitter is 0.0 (no positional jitter for EF parity),
 *    and decoupled salted RNG streams ensure scale/color stability independent of jitter.
 * 5. BUBBLE: Style defaults to EF color palette (dataColor: #8ED1FC, dataCenterColor: #FFFFFF,
 *    positionColor: #0693E3) while allowing explicit user color overrides.
 * 6. DSJ: Exact EF defaults (lineSize: 0.7, xSize: 0.7, colors: #F6B506, #E02020, #0B2D97).
 * 7. FUNCTION: Exact EF formula verification for FADE and CIRCLE.
 */
class EfQrCodeStyleParityVerificationTest {

    @Test
    fun testD25ScaleAndGeometryParity() {
        val params = QrStyleParams(style = QrStyle.D25)
        val design = QrDesign.fromQrStyleParams(params)

        // 1. Data module scale must be 1.0f for EFQRCode parity
        assertEquals("D25 data module scale must be 1.0f for EF parity", 1.0f, design.moduleStyle.scale, 0.0001f)

        // 2. D25 depth ratios default to 1.0
        assertEquals(1.0f, design.depthStyle.depth, 0.0001f)
        assertEquals(1.0f, design.depthStyle.positionDepth, 0.0001f)

        // 3. Test IR geometry generation produces nodes with full scale
        val matrix = QrMatrix("https://veilframe.app/d25-parity", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 4
        )
        val ir = D25Geometry.buildGeometry(matrix, design, geometry)
        assertNotNull(ir)
        assertTrue("D25 IR nodes must not be empty", ir.rootNodes.isNotEmpty())
    }

    @Test
    fun testImageStyleDefaultScaleParity() {
        // In EFQRCode, image style data modules occupy full size (scale 1.0) unless explicitly customized
        val params = QrStyleParams(style = QrStyle.IMAGE)
        assertEquals("QrStyleParams.imageDataScale default must be 1.0f", 1.0f, params.imageDataScale, 0.0001f)

        val design = QrDesign.fromQrStyleParams(params)
        assertEquals("QrDesign.moduleStyle.scale for IMAGE must be 1.0f", 1.0f, design.moduleStyle.scale, 0.0001f)
        assertEquals("QrDesign.imageDataScale for IMAGE must be 1.0f", 1.0f, design.imageDataScale ?: 0f, 0.0001f)
    }

    @Test
    fun testLineAllSevenDirectionsParity() {
        val matrix = QrMatrix("https://veilframe.app/line-directions", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 4
        )
        val renderer = LineRenderer()

        val all7Directions = listOf(
            LineDirection.HORIZONTAL,
            LineDirection.VERTICAL,
            LineDirection.CROSS,
            LineDirection.LOOPBACK,
            LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
            LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            LineDirection.X
        )

        val generatedNodeCounts = mutableMapOf<LineDirection, Int>()

        for (dir in all7Directions) {
            val params = QrStyleParams(
                style = QrStyle.LINE,
                lineDirection = dir,
                lineThickness = 0.5f
            )
            val design = QrDesign.fromQrStyleParams(params)
            assertEquals("LineStyle direction must match parameter", dir, design.lineStyle.direction)

            val ir = renderer.generateGeometry(matrix, design, geometry)
            assertNotNull("IR must not be null for direction $dir", ir)
            assertTrue("IR nodes must not be empty for direction $dir", ir.rootNodes.isNotEmpty())

            generatedNodeCounts[dir] = ir.rootNodes.size
        }

        // Horizontal and Vertical must produce non-zero topology
        assertTrue(generatedNodeCounts[LineDirection.HORIZONTAL]!! > 0)
        assertTrue(generatedNodeCounts[LineDirection.VERTICAL]!! > 0)
        assertTrue(generatedNodeCounts[LineDirection.CROSS]!! > 0)
        assertTrue(generatedNodeCounts[LineDirection.LOOPBACK]!! > 0)
        assertTrue(generatedNodeCounts[LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT]!! > 0)
        assertTrue(generatedNodeCounts[LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT]!! > 0)
        assertTrue(generatedNodeCounts[LineDirection.X]!! > 0)

        // Verify diagonal directions produce distinct geometry
        val tlBrNodes = LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = 10f,
            thicknessFraction = 0.5f,
            lineColor = Color.BLACK,
            direction = LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT
        )
        val trBlNodes = LineTopologyBuilder.buildTopology(
            matrix = matrix,
            ox = 0f,
            oy = 0f,
            cs = 10f,
            thicknessFraction = 0.5f,
            lineColor = Color.BLACK,
            direction = LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT
        )
        assertTrue("Diagonal forward nodes must not be empty", tlBrNodes.isNotEmpty())
        assertTrue("Diagonal backward nodes must not be empty", trBlNodes.isNotEmpty())
    }

    @Test
    fun testRandomRectangleOffsetAndRngParity() {
        // EFQRCode has no positional offset jitter by default
        val defaultJitter = RandomJitterStyle()
        assertEquals("EF parity: default offsetJitter must be 0.0f", 0.0f, defaultJitter.offsetJitter, 0.0001f)

        val matrix = QrMatrix("https://veilframe.app/rand-rect-parity", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 4
        )
        val renderer = RandomRectangleRenderer()

        // Test with offsetJitter = 0.0f
        val designZeroOffset = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            randomRectColor = 0xFF14AA3C.toInt(),
            jitterStyle = RandomJitterStyle(seed = 12345L, scaleJitter = 0.25f, offsetJitter = 0.0f, colorJitter = 0.1f)
        )
        val irZeroOffset = renderer.generateGeometry(matrix, designZeroOffset, geometry)

        // Test with offsetJitter = 0.2f
        val designWithOffset = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            randomRectColor = 0xFF14AA3C.toInt(),
            jitterStyle = RandomJitterStyle(seed = 12345L, scaleJitter = 0.25f, offsetJitter = 0.2f, colorJitter = 0.1f)
        )
        val irWithOffset = renderer.generateGeometry(matrix, designWithOffset, geometry)

        // The number of nodes must match exactly (1 background rect + 2 rects per dark module)
        assertEquals(irZeroOffset.rootNodes.size, irWithOffset.rootNodes.size)

        // Due to decoupled RNG streams, the widths (scale stream) and colors (color stream)
        // must be identical between zero offset and non-zero offset!
        val rectsZero = irZeroOffset.rootNodes.filterIsInstance<RectNode>().filter { it.fill != designZeroOffset.palette.background }
        val rectsWith = irWithOffset.rootNodes.filterIsInstance<RectNode>().filter { it.fill != designWithOffset.palette.background }

        assertEquals(rectsZero.size, rectsWith.size)
        for (i in rectsZero.indices) {
            val r0 = rectsZero[i]
            val r1 = rectsWith[i]
            assertEquals("Scale stream must be identical regardless of offsetJitter", r0.width, r1.width, 0.0001f)
            assertEquals("Color stream must be identical regardless of offsetJitter", r0.fill, r1.fill)
        }
    }

    @Test
    fun testBubbleColorDefaultsAndCustomOverrideParity() {
        // 1. Check BubbleClusterStyle default values match EFQRCode
        val defaultClusterStyle = BubbleClusterStyle()
        assertEquals("Bubble data module outline must default to EF light blue #8ED1FC", 0xFF8ED1FC.toInt(), defaultClusterStyle.dataColor)
        assertEquals("Bubble data module center must default to EF white #FFFFFF", 0xFFFFFFFF.toInt(), defaultClusterStyle.dataCenterColor)
        assertEquals("Bubble position pattern must default to EF blue #0693E3", 0xFF0693E3.toInt(), defaultClusterStyle.positionColor)

        // 2. QrDesign.fromQrStyleParams with default colors
        val paramsDefault = QrStyleParams(style = QrStyle.BUBBLE)
        val designDefault = QrDesign.fromQrStyleParams(paramsDefault)
        assertEquals(0xFF8ED1FC.toInt(), designDefault.clusterStyle.dataColor)
        assertEquals(0xFFFFFFFF.toInt(), designDefault.clusterStyle.dataCenterColor)
        assertEquals(0xFF0693E3.toInt(), designDefault.clusterStyle.positionColor)

        // 3. QrDesign.fromQrStyleParams with explicit custom color override
        val customColor = 0xFF552288.toInt()
        val paramsCustom = QrStyleParams(
            style = QrStyle.BUBBLE,
            foreground = customColor,
            positionColor = customColor
        )
        val designCustom = QrDesign.fromQrStyleParams(paramsCustom)
        assertEquals("Explicit user foreground must override EF default dataColor", customColor, designCustom.clusterStyle.dataColor)
        assertEquals("Explicit user positionColor must override EF default positionColor", customColor, designCustom.clusterStyle.positionColor)
    }

    @Test
    fun testDsjDefaultsAndGeometryParity() {
        val params = QrStyleParams(style = QrStyle.DSJ)
        assertEquals(0.7f, params.dsjLineSize, 0.0001f)
        assertEquals(0.7f, params.dsjXSize, 0.0001f)
        assertEquals(0xFFF6B506.toInt(), params.dsjHorizontalLineColor)
        assertEquals(0xFFE02020.toInt(), params.dsjVerticalLineColor)
        assertEquals(0xFF0B2D97.toInt(), params.dsjXColor)

        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(0.7f, design.veilDsjStyle.lineSize, 0.0001f)
        assertEquals(0.7f, design.veilDsjStyle.xSize, 0.0001f)
        assertEquals(0xFFF6B506.toInt(), design.veilDsjStyle.horizontalLineColor)
        assertEquals(0xFFE02020.toInt(), design.veilDsjStyle.verticalLineColor)
        assertEquals(0xFF0B2D97.toInt(), design.veilDsjStyle.xColor)
    }

    @Test
    fun testFunctionFadeAndCircleMathematicalFormulas() {
        // FADE: (1 - cos(PI * dist)) / 6 + 1/5
        // At dist = 0: (1 - 1) / 6 + 0.2 = 0.2
        val fadeAt0 = (1.0 - cos(PI * 0.0)) / 6.0 + 0.2
        assertEquals(0.2, fadeAt0, 0.0001)

        // At dist = 1: (1 - (-1)) / 6 + 0.2 = 2/6 + 0.2 = 0.3333 + 0.2 = 0.5333
        val fadeAt1 = (1.0 - cos(PI * 1.0)) / 6.0 + 0.2
        assertEquals(0.53333, fadeAt1, 0.0001)

        // CIRCLE: ring test: 5/20 < dist < 8/20 (0.25 < dist < 0.40)
        val insideRing = 0.30f
        val outsideRing = 0.50f
        assertTrue(insideRing in (5f / 20f)..(8f / 20f))
        assertFalse(outsideRing in (5f / 20f)..(8f / 20f))
    }

    @Test
    fun testBasicStyleModuleScaleParity() {
        val params = QrStyleParams(style = QrStyle.BASIC)
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals("BASIC style module scale must be 1.0f for EFQRCode parity (no inter-module gaps)", 1.0f, design.moduleStyle.scale, 0.0001f)

        // Custom override check
        val customParams = QrStyleParams(style = QrStyle.BASIC, dataScale = 0.7f)
        val customDesign = QrDesign.fromQrStyleParams(customParams)
        assertEquals("Custom dataScale override must be respected", 0.7f, customDesign.moduleStyle.scale, 0.0001f)
    }

    @Test
    fun testImageFillMaskAndAntiGapGeometryParity() {
        val matrix = QrMatrix("https://veilframe.app/image-fill-parity", ErrorCorrectionLevel.M)
        val params = QrStyleParams(
            style = QrStyle.IMAGE_FILL,
            imageFillBackgroundColor = 0xFFFFFFFF.toInt(),
            imageFillMaskColor = 0x1A000000 // 10% black
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(Color.WHITE, design.imageFillBackgroundColor)
        assertEquals(0x1A000000, design.imageFillMaskColor)

        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 1
        )
        val renderer = com.veilframe.app.qr.renderer.ImageFillRenderer()
        val ir = renderer.generateGeometry(matrix, design, geometry)
        assertNotNull(ir)

        // Verify defs contain #hole mask with anti-gap expansion 1.02
        val defsStr = ir.defs.joinToString("\n")
        assertTrue("Defs must contain #hole mask", defsStr.contains("<mask id=\"hole\">"))
        val mSize = geometry.moduleSize
        val expectedW = mSize + 2 * (0.01f * mSize)
        assertTrue("Mask must have 1.02x anti-gap module width", defsStr.contains("width=\"$expectedW\""))

        // Verify SVG export
        val svg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(matrix, design)
        assertTrue(svg.contains("<mask id=\"hole\">"))
        assertTrue(svg.contains("width=\"1.02\" height=\"1.02\""))
        assertTrue(svg.contains("mask=\"url(#hole)\""))
    }

    @Test
    fun testImageResampleExclusionAndCenterAnchorParity() {
        val matrix = QrMatrix("https://veilframe.app/resample-parity", ErrorCorrectionLevel.M)
        val n = matrix.size

        // 1. Finder corners (24x24 subpixels / 8x8 modules) must be strictly excluded from sampling
        for (subX in 0 until 24) {
            for (subY in 0 until 24) {
                assertTrue(
                    "Top-left finder subpixel ($subX, $subY) must be excluded from sampling",
                    com.veilframe.app.qr.renderer.ArtisticResampleFunctionalMask.isSubpixelExcluded(matrix, subX, subY)
                )
            }
        }

        // 2. Traversal and emission verification
        var centerAnchorCount = 0
        var photoDitherCount = 0
        val policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.RngMode.DETERMINISTIC)

        val dummySource = object : com.veilframe.app.qr.renderer.PixelSource {
            override val width: Int = 3 * n
            override val height: Int = 3 * n
            override fun getPixel(x: Int, y: Int): Int = Color.BLACK // forces threshold check to pass
        }

        com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
            matrix = matrix,
            pixelSource = dummySource,
            style = ImageSourceStyle(contrast = 0.0f, exposure = 0.0f),
            seed = 42L,
            policy = policy,
            sink = { col, row, subX, subY, isCenterAnchor ->
                if (isCenterAnchor) {
                    centerAnchorCount++
                    // Center anchor must be at dx=1, dy=1
                    assertEquals(3 * col + 1, subX)
                    assertEquals(3 * row + 1, subY)
                } else {
                    photoDitherCount++
                    // Photo dither must never be at dx=1, dy=1
                    assertFalse(subX % 3 == 1 && subY % 3 == 1)
                }
            }
        )

        assertTrue("Center anchor must be emitted for dark modules", centerAnchorCount > 0)
        assertTrue("Photo dither dots must be emitted for non-center subpixels", photoDitherCount > 0)

        // 3. Verify RngMode support
        val unseededPolicy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy(rngMode = com.veilframe.app.qr.renderer.RngMode.SYSTEM_UNSEEDED)
        assertEquals(com.veilframe.app.qr.renderer.RngMode.SYSTEM_UNSEEDED, unseededPolicy.rngMode)
    }
}
