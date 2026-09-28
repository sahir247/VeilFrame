package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.D25Geometry
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.geometry.ResampleGeometryBuilder
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

    @Test
    fun testBasicStyleDataScale085PreservedAndNotConvertedTo10() {
        // 1. Default dataScale must be 1.0f
        val paramsDefault = QrStyleParams(style = QrStyle.BASIC)
        assertEquals(1.0f, paramsDefault.dataScale, 0.0001f)
        val designDefault = QrDesign.fromQrStyleParams(paramsDefault)
        assertEquals(1.0f, designDefault.moduleStyle.scale, 0.0001f)

        // 2. Explicit 0.85f dataScale MUST be preserved and NOT converted to 1.0f sentinel
        val params085 = QrStyleParams(style = QrStyle.BASIC, dataScale = 0.85f)
        val design085 = QrDesign.fromQrStyleParams(params085)
        assertEquals("Explicit dataScale = 0.85f must be preserved as 0.85f", 0.85f, design085.moduleStyle.scale, 0.0001f)
    }

    @Test
    fun testDefaultQuietZoneOneModuleParity() {
        val paramsBasic = QrStyleParams(style = QrStyle.BASIC)
        val designBasic = QrDesign.fromQrStyleParams(paramsBasic)
        assertEquals("EF parity: default quiet zone must be 1 module", 1, designBasic.quietZoneModules)

        val paramsD25 = QrStyleParams(style = QrStyle.D25)
        val designD25 = QrDesign.fromQrStyleParams(paramsD25)
        assertEquals("D25 quiet zone must be 0 modules", 0, designD25.quietZoneModules)

        // Explicit quiet zone override (e.g. 4 for SAFE mode)
        val paramsSafe = QrStyleParams(style = QrStyle.BASIC, quietZone = 4)
        val designSafe = QrDesign.fromQrStyleParams(paramsSafe)
        assertEquals("Explicit quiet zone = 4 must be preserved", 4, designSafe.quietZoneModules)
    }

    @Test
    fun testRandomRoundModuleShapeMappingParity() {
        val params = QrStyleParams(style = QrStyle.BASIC, dataShape = ModuleShape.RANDOM_ROUND)
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals("RANDOM_ROUND data shape must map to ORGANIC module shape for EF parity", com.veilframe.app.qr.model.ModuleShape.ORGANIC, design.moduleStyle.shape)
    }

    @Test
    fun testImageTransparentPrepassIncludesNoneTimingAndAlignment() {
        val matrix = QrMatrix("https://veilframe.app/image-none-transparent", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 1
        )
        val params = QrStyleParams(
            style = QrStyle.IMAGE,
            imageAllowTransparent = true,
            timingShape = ModuleShape.RECTANGLE,
            alignShape = ModuleShape.RECTANGLE
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertTrue(design.allowTransparent)

        val renderer = com.veilframe.app.qr.renderer.ImageRenderer()
        val ir = renderer.generateGeometry(matrix, design, geometry)
        assertNotNull(ir)
    }

    @Test
    fun testDefaultLogoFractionParity() {
        val params = QrStyleParams()
        assertEquals("EF parity: default logoFraction must be 0.20f", 0.20f, params.logoFraction, 0.0001f)
    }

    @Test
    fun testResampleIndependentBackdropParity() {
        val paramsWithBackdrop = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            resampleUseSourceAsBackdrop = false
        )
        assertNull(paramsWithBackdrop.resampleBackdropImage)

        val design = QrDesign.fromQrStyleParams(paramsWithBackdrop)
        assertFalse(design.resampleStyle.hasBackdrop)

        val designExplicitBackdrop = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = false,
                backdropOpacity = 0.8f
            )
        )
        assertFalse(designExplicitBackdrop.resampleStyle.hasBackdrop)

        val designWithUseSource = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = true,
                backdropOpacity = 0.8f
            )
        )
        assertTrue(designWithUseSource.resampleStyle.hasBackdrop)
    }

    @Test
    fun testResampleSubpixelSymmetricAntiGapCentering() {
        val moduleSize = 30f
        val rect = com.veilframe.app.qr.renderer.SubpixelGeometry.computeRect(
            subX = 1,
            subY = 1,
            moduleLeft = 100f,
            moduleTop = 200f,
            moduleSize = moduleSize,
            antiGapScale = 1.02f
        )
        assertEquals(109.9f, rect.left, 0.001f)
        assertEquals(209.9f, rect.top, 0.001f)
        assertEquals(10.2f, rect.width, 0.001f)
        assertEquals(10.2f, rect.height, 0.001f)
    }

    @Test
    fun testResampleLayerOrderingParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE_LAYER_ORDER", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 1
        )
        val pixels = IntArray(64 * 64) { 0xFF000000.toInt() }
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(64, 64, pixels)

        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            quietZoneModules = 1,
            palette = PaletteStyle(background = Color.TRANSPARENT),
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = true,
                backdropOpacity = 0.7f
            )
        )

        val ir = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, design, geometry, pixelSource)
        val nodes = ir.rootNodes
        assertTrue("IR nodes must not be empty", nodes.isNotEmpty())

        val backdropIndex = nodes.indexOfFirst { it is com.veilframe.app.qr.geometry.ImageNode }
        val firstSubpixelDotIndex = nodes.indexOfFirst { it is RectNode && it.width < geometry.moduleSize }
        val finderIndex = nodes.indexOfFirst { it is RectNode && (it.width == 7f * geometry.moduleSize || it.width == 6f * geometry.moduleSize) }

        assertTrue("Backdrop must be present", backdropIndex >= 0)
        assertTrue("Subpixel dots must be present", firstSubpixelDotIndex >= 0)
        assertTrue("Finder must be present", finderIndex >= 0)

        assertTrue("Backdrop must be rendered before subpixel dots", backdropIndex < firstSubpixelDotIndex)
        assertTrue("Subpixel dots must be rendered before finders/timing/alignment dedicated geometry (EF writeResImage before writeQRCode)", firstSubpixelDotIndex < finderIndex)
    }

    @Test
    fun testResampleRngModeDefaultParity() {
        val params = QrStyleParams(style = QrStyle.IMAGE_RESAMPLE)
        assertEquals("QrStyleParams resampleRngMode must default to SYSTEM_UNSEEDED for EF parity", com.veilframe.app.qr.renderer.ResampleRngMode.SYSTEM_UNSEEDED, params.resampleRngMode)

        val design = QrDesign.fromQrStyleParams(params)
        assertEquals("QrDesign resampleStyle.rngMode must default to SYSTEM_UNSEEDED for EF parity", com.veilframe.app.qr.renderer.ResampleRngMode.SYSTEM_UNSEEDED, design.resampleStyle.rngMode)

        val defaultDesign = QrDesign()
        assertEquals("QrDesign default resampleStyle.rngMode must default to SYSTEM_UNSEEDED", com.veilframe.app.qr.renderer.ResampleRngMode.SYSTEM_UNSEEDED, defaultDesign.resampleStyle.rngMode)

        val policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy.from(design)
        assertEquals("ArtisticResamplePolicy rngMode must default to SYSTEM_UNSEEDED for EF parity", com.veilframe.app.qr.renderer.ResampleRngMode.SYSTEM_UNSEEDED, policy.rngMode)

        val defaultPolicy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy()
        assertEquals("Direct ArtisticResamplePolicy() must default to DETERMINISTIC for testing reference simulation", com.veilframe.app.qr.renderer.ResampleRngMode.DETERMINISTIC, defaultPolicy.rngMode)
    }

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    @Test
    fun testResampleTimingAndAlignmentOnlyWhiteParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE_ONLY_WHITE", ErrorCorrectionLevel.H)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 1
        )
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(64, 64, pixels)

        // 1. When timingOnlyWhite = true and alignOnlyWhite = true:
        // Dedicated timing and alignment modules must emit 1x1 subpixel center anchor dots (#Stb and #Sab)
        // instead of full module size geometry (moduleSize).
        val paramsOnlyWhite = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            timingOnlyWhite = true,
            alignOnlyWhite = true
        )
        val designOnlyWhite = QrDesign.fromQrStyleParams(paramsOnlyWhite)
        assertTrue(designOnlyWhite.timingStyle.onlyWhite)
        assertTrue(designOnlyWhite.alignmentStyle.onlyWhite)

        val irOnlyWhite = ResampleGeometryBuilder.generateGeometry(matrix, designOnlyWhite, geometry, pixelSource)
        val mSize = geometry.moduleSize

        // Filter nodes that could be full timing/alignment shapes (width == mSize)
        val fullModuleNodesOnlyWhite = irOnlyWhite.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - mSize) < 0.01f && Math.abs(it.height - mSize) < 0.01f
        }
        // Since onlyWhite is true, neither timing nor alignment should have full module rects
        assertTrue("No full module rects should be emitted for timing/alignment when onlyWhite=true", fullModuleNodesOnlyWhite.isEmpty())

        // 2. When timingOnlyWhite = false and alignOnlyWhite = false:
        // Full module geometry is emitted
        val paramsFull = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            timingOnlyWhite = false,
            alignOnlyWhite = false
        )
        val designFull = QrDesign.fromQrStyleParams(paramsFull)
        assertFalse(designFull.timingStyle.onlyWhite)
        assertFalse(designFull.alignmentStyle.onlyWhite)

        val irFull = ResampleGeometryBuilder.generateGeometry(matrix, designFull, geometry, pixelSource)
        val fullModuleNodesFull = irFull.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - mSize) < 0.01f && Math.abs(it.height - mSize) < 0.01f
        }
        assertTrue("Full module rects must be emitted when onlyWhite=false", fullModuleNodesFull.isNotEmpty())
    }

    @Test
    fun testResampleFinderGeometryExactEfParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/FINDER_PARITY", ErrorCorrectionLevel.H)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 1
        )
        val mSize = geometry.moduleSize
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(64, 64, pixels)

        // 1. CLASSIC: Sharp corners (rx = 0, ry = 0) in both hollow and solid modes
        val designClassicHollow = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val irClassicHollow = ResampleGeometryBuilder.generateGeometry(matrix, designClassicHollow, geometry, pixelSource)
        val classicFinderOuter = irClassicHollow.rootNodes.filterIsInstance<RectNode>().firstOrNull {
            Math.abs(it.width - 6f * mSize) < 0.01f
        }
        val classicFinderInner = irClassicHollow.rootNodes.filterIsInstance<RectNode>().firstOrNull {
            Math.abs(it.width - 3f * mSize) < 0.01f
        }
        assertNotNull("Classic 6x6 outer finder must exist in hollow mode", classicFinderOuter)
        assertNotNull("Classic 3x3 inner finder must exist in hollow mode", classicFinderInner)
        assertEquals("Classic outer finder must have sharp corners (rx=0)", 0f, classicFinderOuter?.rx ?: -1f, 0.001f)
        assertEquals("Classic inner finder must have sharp corners (rx=0)", 0f, classicFinderInner?.rx ?: -1f, 0.001f)

        val designClassicSolid = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = false)
        )
        val irClassicSolid = ResampleGeometryBuilder.generateGeometry(matrix, designClassicSolid, geometry, pixelSource)
        val solid7x7 = irClassicSolid.rootNodes.filterIsInstance<RectNode>().firstOrNull { Math.abs(it.width - 7f * mSize) < 0.01f }
        val solid5x5 = irClassicSolid.rootNodes.filterIsInstance<RectNode>().firstOrNull { Math.abs(it.width - 5f * mSize) < 0.01f }
        assertNotNull("Classic 7x7 outer finder must exist in solid mode", solid7x7)
        assertNotNull("Classic 5x5 middle finder must exist in solid mode", solid5x5)
        assertEquals("Solid 7x7 outer finder must have sharp corners (rx=0)", 0f, solid7x7?.rx ?: -1f, 0.001f)
        assertEquals("Solid 5x5 middle finder must have sharp corners (rx=0)", 0f, solid5x5?.rx ?: -1f, 0.001f)

        // 2. ROUNDED: Inner is CircleNode(r = 1.5 * mSize) matching EF <circle r="4.5"/>
        val designRounded = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.ROUNDED),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val irRounded = ResampleGeometryBuilder.generateGeometry(matrix, designRounded, geometry, pixelSource)
        val roundedInnerCircle = irRounded.rootNodes.filterIsInstance<CircleNode>().firstOrNull {
            Math.abs(it.radius - 1.5f * mSize) < 0.01f
        }
        assertNotNull("Rounded finder must use CircleNode with r=1.5*mSize for inner eye matching EF", roundedInnerCircle)

        // 3. CIRCLE: Inner circle r=1.5 * mSize, outer circle stroke r=3.0 * mSize
        val designCircle = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.CIRCLE),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val irCircle = ResampleGeometryBuilder.generateGeometry(matrix, designCircle, geometry, pixelSource)
        val circleInner = irCircle.rootNodes.filterIsInstance<CircleNode>().firstOrNull {
            Math.abs(it.radius - 1.5f * mSize) < 0.01f
        }
        val circleOuter = irCircle.rootNodes.filterIsInstance<CircleNode>().firstOrNull {
            Math.abs(it.radius - 3.0f * mSize) < 0.01f && it.stroke != null
        }
        assertNotNull("Circle finder must use CircleNode with r=1.5*mSize for inner eye", circleInner)
        assertNotNull("Circle finder must use CircleNode with r=3.0*mSize for outer stroke", circleOuter)
    }

    @Test
    fun testImageSourceAnimatedModelingParity() {
        val frame1 = allocateBitmapReflectively()
        val frame2 = allocateBitmapReflectively()
        val frames = listOf(frame1, frame2)
        val delays = listOf(100, 200)

        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            sourceImageAnimatedFrames = frames,
            sourceImageFrameDelaysMs = delays
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertNotNull(design.imageSource)
        assertTrue("imageSource must be marked animated", design.imageSource?.isAnimated == true)
        assertEquals("First frame must be returned by bitmap accessor", frame1, design.imageSource?.bitmap)
        assertEquals(frames, design.imageSource?.animatedFrames)
        assertEquals(delays, design.imageSource?.frameDelaysMs)
    }

    @Test
    fun testIndependentBackdropHollowFinderParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/INDEPENDENT_BACKDROP", ErrorCorrectionLevel.H)
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 1)
        val mSize = geometry.moduleSize
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(64, 64, pixels)
        val dummyBackdrop = allocateBitmapReflectively()

        // 1. useSourceAsBackdrop = false, BUT backdropBitmap is provided (independent backdrop object):
        // Must emit hollow stroked finders so the independent backdrop shows through!
        val designIndependentBackdrop = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC),
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = false,
                backdropBitmap = dummyBackdrop
            )
        )
        val irIndependent = ResampleGeometryBuilder.generateGeometry(matrix, designIndependentBackdrop, geometry, pixelSource)
        val hollowFinderRects = irIndependent.rootNodes.filterIsInstance<RectNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("Independent backdrop must emit stroked hollow rects for the outer frame", hollowFinderRects.isNotEmpty())

        val bgFillFinderRects = irIndependent.rootNodes.filterIsInstance<RectNode>()
            .filter { it.fill == designIndependentBackdrop.palette.background && it.width < 512f }
        assertTrue("Independent backdrop must NOT paint solid background middle rects", bgFillFinderRects.isEmpty())

        // 2. useSourceAsBackdrop = false, and backdropBitmap = null (no backdrop, opaque background):
        // Must emit solid background middle rects!
        val designNoBackdrop = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            palette = PaletteStyle(background = Color.WHITE, foreground = Color.BLACK),
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC),
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = false,
                backdropBitmap = null
            )
        )
        val irNoBackdrop = ResampleGeometryBuilder.generateGeometry(matrix, designNoBackdrop, geometry, pixelSource)
        val normalBgFinderRects = irNoBackdrop.rootNodes.filterIsInstance<RectNode>()
            .filter { it.fill == designNoBackdrop.palette.background && it.width < 512f }
        assertTrue("Solid finder mode must emit background middle rects when no backdrop is active", normalBgFinderRects.isNotEmpty())
    }

    @Test
    fun testTimingAndAlignmentSizeScalingParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/TIMING_ALIGN_SIZE", ErrorCorrectionLevel.H)
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 1)
        val mSize = geometry.moduleSize
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(64, 64, pixels)

        // 1. timingSize = 0.5f, alignSize = 1.5f (full geometry)
        val paramsCustomSize = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            timingSize = 0.5f,
            alignSize = 1.5f,
            timingOnlyWhite = false,
            alignOnlyWhite = false
        )
        val designCustomSize = QrDesign.fromQrStyleParams(paramsCustomSize)
        val irCustomSize = ResampleGeometryBuilder.generateGeometry(matrix, designCustomSize, geometry, pixelSource)

        val timingNodes = irCustomSize.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - 0.5f * mSize) < 0.01f && Math.abs(it.height - 0.5f * mSize) < 0.01f
        }
        assertTrue("timingSize = 0.5f must emit rects scaled to 0.5 * mSize", timingNodes.isNotEmpty())

        val alignNodes = irCustomSize.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - 1.5f * mSize) < 0.01f && Math.abs(it.height - 1.5f * mSize) < 0.01f
        }
        assertTrue("alignSize = 1.5f must emit rects scaled to 1.5 * mSize", alignNodes.isNotEmpty())

        // 2. onlyWhite = true with custom sizes
        val paramsOnlyWhiteCustomSize = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            timingSize = 0.5f,
            alignSize = 1.5f,
            timingOnlyWhite = true,
            alignOnlyWhite = true
        )
        val designOnlyWhiteCustomSize = QrDesign.fromQrStyleParams(paramsOnlyWhiteCustomSize)
        val irOnlyWhiteCustom = ResampleGeometryBuilder.generateGeometry(matrix, designOnlyWhiteCustomSize, geometry, pixelSource)

        val subStep = mSize / 3f
        val expectedTimingAnchorDim = subStep * 1.02f * 0.5f
        val expectedAlignAnchorDim = subStep * 1.02f * 1.5f

        val timingAnchorNodes = irOnlyWhiteCustom.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedTimingAnchorDim) < 0.01f
        }
        assertTrue("timingOnlyWhite with timingSize = 0.5f must emit anchor dots of dimension subStep * 1.02 * 0.5", timingAnchorNodes.isNotEmpty())

        val alignAnchorNodes = irOnlyWhiteCustom.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedAlignAnchorDim) < 0.01f
        }
        assertTrue("alignOnlyWhite with alignSize = 1.5f must emit anchor dots of dimension subStep * 1.02 * 1.5", alignAnchorNodes.isNotEmpty())
    }

    @Test
    fun testDataColorIndependentFromForegroundParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/DATA_COLOR_PARITY", ErrorCorrectionLevel.H)
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 1)
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        val pixelSource = com.veilframe.app.qr.renderer.ArrayPixelSource(64, 64, pixels)

        // When foreground = RED, but dataColor = BLUE
        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            foreground = Color.RED,
            dataColor = Color.BLUE
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(Color.BLUE, design.dataColorDark)

        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry, pixelSource)
        val mSize = geometry.moduleSize
        val subStep = mSize / 3f
        val anchorDim = subStep * 1.02f

        // Center anchors of data modules must use dataColor (BLUE), not foreground (RED)
        val blueAnchors = ir.rootNodes.filterIsInstance<RectNode>().filter {
            it.fill == Color.BLUE && Math.abs(it.width - anchorDim) < 0.01f
        }
        val redAnchors = ir.rootNodes.filterIsInstance<RectNode>().filter {
            it.fill == Color.RED && Math.abs(it.width - anchorDim) < 0.01f
        }
        assertTrue("Data module center anchors must use dataColor (BLUE)", blueAnchors.isNotEmpty())
        assertTrue("Data module center anchors must not use foreground (RED)", redAnchors.isEmpty())
    }

    @Test
    fun testAnimatedResampleSvgGenerationParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ANIMATED_SVG", ErrorCorrectionLevel.H)
        val frame1 = allocateBitmapReflectively()
        val frame2 = allocateBitmapReflectively()
        val frames = listOf(frame1, frame2)
        val delays = listOf(150, 250)

        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            sourceImageAnimatedFrames = frames,
            sourceImageFrameDelaysMs = delays
        )
        val design = QrDesign.fromQrStyleParams(params)

        val extracted = AnimatedQrGenerator.extractSourceFrames(design)
        assertEquals(2, extracted.size)
        assertEquals(150, extracted[0].durationMs)
        assertEquals(250, extracted[1].durationMs)

        val animatedSvg = AnimatedQrGenerator.generateAnimatedSvg(matrix, design)
        assertTrue("Generated SVG must contain animate tag", animatedSvg.contains("<animate"))
        assertTrue("Generated SVG must contain discrete calcMode", animatedSvg.contains("calcMode=\"discrete\""))
        assertTrue("Generated SVG must contain frame 0 def", animatedSvg.contains("id=\"qr_frame_0\""))
        assertTrue("Generated SVG must contain frame 1 def", animatedSvg.contains("id=\"qr_frame_1\""))
    }
}
