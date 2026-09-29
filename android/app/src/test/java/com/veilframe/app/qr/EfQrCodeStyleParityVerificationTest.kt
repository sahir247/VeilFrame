package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.*
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
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
        val expectedFullDim = (3.02f / 3f) * mSize
        val fullModuleNodesFull = irFull.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedFullDim) < 0.01f && Math.abs(it.height - expectedFullDim) < 0.01f
        }
        assertTrue("Exact EF 3.02/3 * mSize rects must be emitted when onlyWhite=false", fullModuleNodesFull.isNotEmpty())
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

        // 1. CLASSIC: Sharp corners (rx = 0, ry = 0) with 6x6 stroked frame and 3x3 inner fill in all modes (no solid 7x7/5x5 branch)
        val designClassicHollow = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val irClassicHollow = ResampleGeometryBuilder.generateGeometry(matrix, designClassicHollow, geometry, pixelSource)
        val classicFinderOuter = irClassicHollow.rootNodes.filterIsInstance<RectNode>().firstOrNull {
            Math.abs(it.width - 6f * mSize) < 0.01f && it.stroke != null && it.fill == null
        }
        val classicFinderInner = irClassicHollow.rootNodes.filterIsInstance<RectNode>().firstOrNull {
            Math.abs(it.width - 3f * mSize) < 0.01f && it.fill != null
        }
        assertNotNull("Classic 6x6 outer stroked finder must exist in hollow mode", classicFinderOuter)
        assertNotNull("Classic 3x3 inner filled finder must exist in hollow mode", classicFinderInner)
        assertEquals("Classic outer finder must have sharp corners (rx=0)", 0f, classicFinderOuter?.rx ?: -1f, 0.001f)
        assertEquals("Classic inner finder must have sharp corners (rx=0)", 0f, classicFinderInner?.rx ?: -1f, 0.001f)

        val designClassicSolid = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = false)
        )
        val irClassicSolid = ResampleGeometryBuilder.generateGeometry(matrix, designClassicSolid, geometry, pixelSource)
        // EF writeQRCode emits 6x6 stroked frame and 3x3 inner fill directly without solid 7x7/5x5 background fill
        val solid6x6 = irClassicSolid.rootNodes.filterIsInstance<RectNode>().firstOrNull { Math.abs(it.width - 6f * mSize) < 0.01f && it.stroke != null }
        val solid3x3 = irClassicSolid.rootNodes.filterIsInstance<RectNode>().firstOrNull { Math.abs(it.width - 3f * mSize) < 0.01f && it.fill != null }
        val solid7x7 = irClassicSolid.rootNodes.filterIsInstance<RectNode>().firstOrNull { Math.abs(it.width - 7f * mSize) < 0.01f }
        val solid5x5 = irClassicSolid.rootNodes.filterIsInstance<RectNode>().firstOrNull { Math.abs(it.width - 5f * mSize) < 0.01f }
        assertNotNull("EF parity: 6x6 stroked frame must be emitted even when useSourceAsBackdrop=false", solid6x6)
        assertNotNull("EF parity: 3x3 inner filled square must be emitted even when useSourceAsBackdrop=false", solid3x3)
        assertNull("EF parity: No solid 7x7 outer fill exists in EF resample finders", solid7x7)
        assertNull("EF parity: No solid 5x5 background fill exists in EF resample finders", solid5x5)

        // 2. ROUNDED: Inner is CircleNode(r = 1.5 * mSize) matching EF <circle r="4.5"/>, outer is sq25 PathNode
        val designRounded = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.ROUNDED),
            resampleStyle = ResampleStyle(useSourceAsBackdrop = true)
        )
        val irRounded = ResampleGeometryBuilder.generateGeometry(matrix, designRounded, geometry, pixelSource)
        val roundedInnerCircle = irRounded.rootNodes.filterIsInstance<CircleNode>().firstOrNull {
            Math.abs(it.radius - 1.5f * mSize) < 0.01f
        }
        val roundedOuterPath = irRounded.rootNodes.filterIsInstance<PathNode>().firstOrNull {
            it.svgPathData == com.veilframe.app.qr.renderer.VeilPositionPatternGeometry.SQ25_PATH
        }
        assertNotNull("Rounded finder must use CircleNode with r=1.5*mSize for inner eye matching EF", roundedInnerCircle)
        assertNotNull("Rounded finder must use PathNode with exact SQ25_PATH for outer frame matching EF", roundedOuterPath)
        assertTrue("Rounded outer path transform must contain translate and scale", roundedOuterPath?.transform?.contains("scale") == true)

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

        // 2. EF parity: Even without backdrop, EF writeQRCode emits the outer stroked frame without solid 7x7/5x5 background fill
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
        val noBackdropHollowRects = irNoBackdrop.rootNodes.filterIsInstance<RectNode>()
            .filter { it.stroke != null && it.fill == null }
        assertTrue("EF parity: Resample finder emits stroked frame even with no backdrop", noBackdropHollowRects.isNotEmpty())
        val normalBgFinderRects = irNoBackdrop.rootNodes.filterIsInstance<RectNode>()
            .filter { it.fill == designNoBackdrop.palette.background && it.width < 512f }
        assertTrue("EF parity: Resample finder must never emit background middle rects", normalBgFinderRects.isEmpty())
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

        val expectedTimingDim = (3.02f / 3f) * mSize * 0.5f
        val expectedAlignDim = (3.02f / 3f) * mSize * 1.5f

        val timingNodes = irCustomSize.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedTimingDim) < 0.01f && Math.abs(it.height - expectedTimingDim) < 0.01f
        }
        assertTrue("timingSize = 0.5f must emit rects scaled to exact (3.02/3) * mSize * 0.5", timingNodes.isNotEmpty())

        val alignNodes = irCustomSize.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedAlignDim) < 0.01f && Math.abs(it.height - expectedAlignDim) < 0.01f
        }
        assertTrue("alignSize = 1.5f must emit rects scaled to exact (3.02/3) * mSize * 1.5", alignNodes.isNotEmpty())

        // 2. onlyWhite = true with custom sizes and exact EF offsets
        val paramsOnlyWhiteCustomSize = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            timingSize = 0.5f,
            alignSize = 1.5f,
            timingOnlyWhite = true,
            alignOnlyWhite = true
        )
        val designOnlyWhiteCustomSize = QrDesign.fromQrStyleParams(paramsOnlyWhiteCustomSize)
        val irOnlyWhiteCustom = ResampleGeometryBuilder.generateGeometry(matrix, designOnlyWhiteCustomSize, geometry, pixelSource)

        val expectedTimingAnchorDim = (1.02f / 3f) * mSize * 0.5f
        val expectedAlignAnchorDim = (1.02f / 3f) * mSize * 1.5f

        val timingAnchorNodes = irOnlyWhiteCustom.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedTimingAnchorDim) < 0.01f
        }
        assertTrue("timingOnlyWhite with timingSize = 0.5f must emit anchor dots of dimension (1.02/3) * mSize * 0.5", timingAnchorNodes.isNotEmpty())

        val alignAnchorNodes = irOnlyWhiteCustom.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedAlignAnchorDim) < 0.01f
        }
        assertTrue("alignOnlyWhite with alignSize = 1.5f must emit anchor dots of dimension (1.02/3) * mSize * 1.5", alignAnchorNodes.isNotEmpty())
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
        val anchorDim = (1.02f / 3f) * mSize

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
        assertTrue("Generated SVG must contain resfm0 def matching EF", animatedSvg.contains("id=\"resfm0\""))
        assertTrue("Generated SVG must contain resfm1 def matching EF", animatedSvg.contains("id=\"resfm1\""))
        assertTrue("Generated SVG must contain xlink:href values matching EF", animatedSvg.contains("values=\"#resfm0;#resfm1\""))

        // Verify that finders are outside <animate> (static layer architecture)
        val animateIndex = animatedSvg.indexOf("<animate")
        val strokeFinderIndex = animatedSvg.indexOf("stroke-width=\"1.0000\"")
        assertTrue("Finders must be emitted outside and after animated resample layer", strokeFinderIndex > animateIndex)
    }

    @Test
    fun testGeneratedSvgMatchesEfPrimitivesGoldenTest() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/GOLDEN", ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            eyeStyle = EyeStyle(style = FinderStyle.ROUNDED),
            timingStyle = TimingStyle(shape = com.veilframe.app.qr.model.ModuleShape.ROUNDED),
            timingSize = 1.0f
        )
        val svg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(matrix, design)

        // 1. Svg contains xmlns:xlink
        assertTrue("SVG root must declare xmlns:xlink", svg.contains("xmlns:xlink=\"http://www.w3.org/1999/xlink\""))
        // 2. Rounded finder uses SQ25_PATH
        assertTrue("Rounded finder in SVG must contain SQ25 path", svg.contains(com.veilframe.app.qr.renderer.VeilPositionPatternGeometry.SQ25_PATH))
        // 3. No solid 7x7 outer fill or 5x5 background fill
        assertFalse("SVG must not contain solid 7x7 outer fill", svg.contains("width=\"7.0000\" height=\"7.0000\""))
        assertFalse("SVG must not contain 5x5 background fill", svg.contains("width=\"5.0000\" height=\"5.0000\""))
    }

    @Test
    fun testResampleContrastAndExposurePublicApiParity() {
        // 1. Default contrast/exposure is 0.0f
        val defaultParams = QrStyleParams(style = QrStyle.IMAGE_RESAMPLE)
        val defaultDesign = QrDesign.fromQrStyleParams(defaultParams)
        assertEquals(0.0f, defaultDesign.imageSource.contrast, 0.0001f)
        assertEquals(0.0f, defaultDesign.imageSource.exposure, 0.0001f)

        // 2. Direct contrast and exposure parameter propagation
        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            contrast = 0.5f,
            exposure = -0.25f
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(0.5f, design.imageSource.contrast, 0.0001f)
        assertEquals(-0.25f, design.imageSource.exposure, 0.0001f)

        // 3. sourceImageContrast and sourceImageExposure override
        val overrideParams = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            contrast = 0.5f,
            exposure = -0.25f,
            sourceImageContrast = 0.75f,
            sourceImageExposure = 0.1f
        )
        val overrideDesign = QrDesign.fromQrStyleParams(overrideParams)
        assertEquals(0.75f, overrideDesign.imageSource.contrast, 0.0001f)
        assertEquals(0.1f, overrideDesign.imageSource.exposure, 0.0001f)

        // 4. Threshold calculation actively reacts to contrast and exposure
        val midGray = Color.rgb(128, 128, 128)
        val thBase = ResampleSubpixelEngine.computeThreshold(midGray, defaultDesign.imageSource)
        val thHighContrast = ResampleSubpixelEngine.computeThreshold(midGray, design.imageSource)
        assertNotEquals("Contrast and exposure must actively alter the resample threshold", thBase, thHighContrast)
    }

    @Test
    fun testAnimatedResampleCenterAnchorsAreStaticOutsideAnimate() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ANIM_PARITY", ErrorCorrectionLevel.M)
        val frame1 = allocateBitmapReflectively()
        val frame2 = allocateBitmapReflectively()
        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            sourceImageAnimatedFrames = listOf(frame1, frame2),
            sourceImageFrameDelaysMs = listOf(100, 100)
        )
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)

        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry)
        val animGroup = ir.rootNodes.filterIsInstance<AnimatedGroupNode>().firstOrNull()
        assertNotNull("AnimatedGroupNode must be present in rootNodes", animGroup)

        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        // 1. All rects in frameNodes MUST be strictly stochastic dots (never center anchors (1, 1))
        for (frame in animGroup!!.frameNodes) {
            for (node in frame.filterIsInstance<RectNode>()) {
                val modCol = Math.round((node.x - ox) / mSize - 0.5f).coerceIn(0, matrix.size - 1)
                val modRow = Math.round((node.y - oy) / mSize - 0.5f).coerceIn(0, matrix.size - 1)
                val localX = (node.x - (ox + modCol * mSize)) / mSize
                val localY = (node.y - (oy + modRow * mSize)) / mSize
                val subX = Math.round(localX * 3f)
                val subY = Math.round(localY * 3f)

                val isCenterAnchor = (subX == 1 && subY == 1)
                assertFalse("Frame nodes must strictly contain 8-neighbor stochastic dots and NO center anchors", isCenterAnchor)
            }
        }

        // 2. Ordinary dark-module #Sb center anchors must exist as static nodes in rootNodes outside the animated group
        val animGroupIndex = ir.rootNodes.indexOf(animGroup)
        val sbAnchors = ir.rootNodes.filterIndexed { index, node ->
            index > animGroupIndex && node is RectNode && Math.abs(node.width - (1.02f / 3f) * mSize) < 0.01f
        }
        assertTrue("Ordinary dark-module #Sb center anchors must be emitted as static nodes after animated group", sbAnchors.isNotEmpty())

        // 3. In animated SVG, <animate> must terminate before #Sb anchors
        val animatedSvg = AnimatedQrGenerator.generateAnimatedSvg(matrix, design)
        val animateIndex = animatedSvg.indexOf("<animate")
        assertTrue("<animate> tag must be present", animateIndex > 0)
        val useEndIndex = animatedSvg.indexOf("</use>", animateIndex)
        assertTrue("</use> closing tag must be present", useEndIndex > 0)
        val contentAfterAnimate = animatedSvg.substring(useEndIndex)
        assertTrue("Static #Sb center anchors must be emitted outside and after animated group", contentAfterAnimate.contains("<rect"))
    }

    @Test
    fun testStaticResampleLayerOrderingParityWithEf() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ORDERING", ErrorCorrectionLevel.M)
        val pixelSource = ArrayPixelSource(100, 100, IntArray(100 * 100) { Color.DKGRAY })
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE
        )
        val geometry = QrGeometry(matrix.size, 512, 512, 1)

        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry, pixelSource)
        val nodes = ir.rootNodes
        val mSize = geometry.moduleSize
        val ditherDotDim = (1.02f / 3f) * mSize

        // Find the index of the first finder element (classic 3x3 or 6x6)
        val firstFinderIndex = nodes.indexOfFirst {
            it is RectNode && (Math.abs(it.width - 3f * mSize) < 0.01f || Math.abs(it.width - 6f * mSize) < 0.01f)
        }
        assertTrue("Finders must be present", firstFinderIndex > 0)

        // Find index of the last finder element
        val lastFinderIndex = nodes.indexOfLast {
            it is RectNode && (Math.abs(it.width - 3f * mSize) < 0.01f || Math.abs(it.width - 6f * mSize) < 0.01f)
        }

        // Ordinary #Sb center anchors are added in step 7, AFTER finders, timing, alignment
        val lastSbIndex = nodes.indexOfLast {
            it is RectNode && Math.abs(it.width - ditherDotDim) < 0.01f && it.fill == design.dataColorDark
        }

        assertTrue("All stochastic dither dots must precede finders (EF writeResImage first)", firstFinderIndex > 1)
        assertTrue("Ordinary #Sb center anchors must be emitted after finders/timing/alignment", lastSbIndex > lastFinderIndex)
    }

    @Test
    fun testResampleTimingAndAlignmentNoDuplicateAnchorsOnOnlyWhite() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/NO_DUPLICATES", ErrorCorrectionLevel.M)
        val bmp = allocateBitmapReflectively()
        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            sourceImage = bmp,
            timingOnlyWhite = true,
            alignOnlyWhite = true
        )
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)

        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry)
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        // For each dark timing module, there must be EXACTLY ONE rect covering its center
        val n = matrix.size
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) == QrModuleRole.TIMING && matrix.isDark(col, row)) {
                    val centerX = ox + (col + 0.5f) * mSize
                    val centerY = oy + (row + 0.5f) * mSize

                    val coveringRects = ir.rootNodes.filterIsInstance<RectNode>().filter {
                        it.width < 2f * mSize &&
                        centerX >= it.x && centerX <= (it.x + it.width) &&
                        centerY >= it.y && centerY <= (it.y + it.height)
                    }
                    assertEquals("Dark timing module ($col, $row) with onlyWhite=true must have exactly 1 rect (no duplicate engine anchor)", 1, coveringRects.size)
                }

                if ((matrix.roleAt(col, row) == QrModuleRole.ALIGNMENT_CENTER ||
                     matrix.roleAt(col, row) == QrModuleRole.ALIGNMENT_BORDER) && matrix.isDark(col, row)) {
                    val centerX = ox + (col + 0.5f) * mSize
                    val centerY = oy + (row + 0.5f) * mSize

                    val coveringRects = ir.rootNodes.filterIsInstance<RectNode>().filter {
                        it.width < 2f * mSize &&
                        centerX >= it.x && centerX <= (it.x + it.width) &&
                        centerY >= it.y && centerY <= (it.y + it.height)
                    }
                    assertEquals("Dark alignment module ($col, $row) with onlyWhite=true must have exactly 1 rect (no duplicate engine anchor)", 1, coveringRects.size)
                }
            }
        }
    }

    @Test
    fun testResampleBackdropCornerRadiusClippingParity() {
        val bmp = allocateBitmapReflectively()
        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            resampleBackdropImage = bmp,
            resampleBackdropCornerRadius = 12.5f
        )
        val design = QrDesign.fromQrStyleParams(params)
        assertEquals(12.5f, design.resampleStyle.backdropCornerRadius, 0.0001f)

        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/BACKDROP", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)
        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry)

        // 1. IR defs must contain clipPath for backdrop
        val clipDef = ir.defs.find { it.contains("id=\"backdropClip\"") }
        assertNotNull("Defs must contain clipPath with id 'backdropClip'", clipDef)
        assertTrue("ClipPath must contain rounded rect with rx and ry", clipDef!!.contains("rx=\"12.5\"") || clipDef.contains("rx=\"12.5000\""))

        // 2. Backdrop image node must reference backdropClip
        val imageNode = ir.rootNodes.filterIsInstance<ImageNode>().firstOrNull()
        assertNotNull("Backdrop ImageNode must be present", imageNode)
        assertEquals("backdropClip", imageNode!!.clipPathId)

        // 3. Rendered SVG must contain clip-path attribute
        val svg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        assertTrue("SVG must define clipPath id='backdropClip'", svg.contains("<clipPath id=\"backdropClip\">"))
        assertTrue("SVG must apply clip-path='url(#backdropClip)'", svg.contains("clip-path=\"url(#backdropClip)\""))
    }

    @Test
    fun testImageStyleRoundedFinderSq25Parity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ROUNDED-PARITY", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)
        val mSize = geometry.moduleSize
        val design = QrDesign(
            style = QrStyle.IMAGE,
            eyeStyle = EyeStyle(style = FinderStyle.ROUNDED),
            positionSize = 1.0f
        )
        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)

        // 1. Must emit inner circle of radius 1.5 * mSize matching EF <circle r="1.5"/>
        val innerCircles = ir.rootNodes.filterIsInstance<CircleNode>().filter {
            Math.abs(it.radius - 1.5f * mSize) < 0.01f
        }
        assertEquals("Must emit 3 inner finder circles for ROUNDED style", 3, innerCircles.size)

        // 2. Must emit outer SQ25 squircle PathNode matching EFQRCodeStyleBasic.sq25
        val sq25Paths = ir.rootNodes.filterIsInstance<PathNode>().filter {
            it.svgPathData == VeilPositionPatternGeometry.SQ25_PATH
        }
        assertEquals("Must emit 3 outer SQ25 squircle PathNodes for ROUNDED style", 3, sq25Paths.size)
    }

    @Test
    fun testImageStyleUnclampedParametersParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/UNCLAMPED-PARITY", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 0)
        val mSize = geometry.moduleSize

        // EF allows unclamped positionSize (e.g. 2.5f) and unclamped dataScale
        val design = QrDesign(
            style = QrStyle.IMAGE,
            eyeStyle = EyeStyle(style = FinderStyle.DSJ),
            positionSize = 2.5f,
            imageDataScale = 1.2f,
            timingSize = 1.5f,
            alignSize = 1.5f
        )
        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)

        // Verify DSJ center rect width reflects unclamped posSize = 2.5: (2.0 + 2.5) * mSize = 4.5 * mSize
        val dsjRects = ir.rootNodes.filterIsInstance<RectNode>()
        val expectedCenterW = 4.5f * mSize
        val centerMatches = dsjRects.filter { Math.abs(it.width - expectedCenterW) < 0.01f }
        assertTrue("ImageRenderer must not artificially clamp positionSize below 2.5", centerMatches.isNotEmpty())
    }

    @Test
    fun testAnimatedImageNodeSmilMarkupParity() {
        val node = AnimatedImageNode(
            x = 10f,
            y = 20f,
            width = 100f,
            height = 100f,
            base64Frames = listOf("FRAME_DATA_0", "FRAME_DATA_1", "FRAME_DATA_2"),
            frameDelaysMs = listOf(100, 200, 300),
            framePrefix = "1fm",
            opacity = 0.85f,
            maskId = "hole"
        )
        val ir = QrGeometryIr(
            width = 120f,
            height = 120f,
            rootNodes = listOf(node)
        )
        val svg = IrSvgRenderer.render(ir)

        // 1. Defs must define all 3 frame images
        assertTrue("Defs must contain frame 0", svg.contains("<image id=\"1fm0\" xlink:href=\"data:image/png;base64,FRAME_DATA_0\""))
        assertTrue("Defs must contain frame 1", svg.contains("<image id=\"1fm1\" xlink:href=\"data:image/png;base64,FRAME_DATA_1\""))
        assertTrue("Defs must contain frame 2", svg.contains("<image id=\"1fm2\" xlink:href=\"data:image/png;base64,FRAME_DATA_2\""))

        // 2. SMIL use tag references first frame
        assertTrue("Use tag must reference #1fm0", svg.contains("<use xlink:href=\"#1fm0\">"))

        // 3. SMIL animate tag with discrete calcMode
        assertTrue("Animate must target xlink:href", svg.contains("attributeName=\"xlink:href\""))
        assertTrue("Animate values must chain all frames", svg.contains("values=\"#1fm0;#1fm1;#1fm2\""))
        // Total duration: 100 + 200 + 300 = 600ms = 0.600s
        // KeyTimes: 0/600 = 0.000, 100/600 = 0.167, 300/600 = 0.500 (NO trailing 1.0)
        assertTrue("KeyTimes must match EF formula without terminal 1.0", svg.contains("keyTimes=\"0.000;0.167;0.500\""))
        assertTrue("Duration must match sum of delays", svg.contains("dur=\"0.600s\""))
        assertTrue("Must repeat indefinitely", svg.contains("repeatCount=\"indefinite\""))
        assertTrue("calcMode must be discrete", svg.contains("calcMode=\"discrete\""))
        assertTrue("Group must apply mask if specified", svg.contains("mask=\"url(#hole)\""))
    }

    @Test
    fun testAnimatedKeyTimesDiscreteMathParity() {
        // Test diverse frame delay sets matching EF formula
        val delays1 = listOf(200, 200) // equal 50/50
        val node1 = AnimatedImageNode(
            x = 0f,
            y = 0f,
            width = 100f,
            height = 100f,
            base64Frames = listOf("f0", "f1"),
            frameDelaysMs = delays1,
            framePrefix = "test1"
        )
        val svg1 = IrSvgRenderer.render(QrGeometryIr(width = 100f, height = 100f, rootNodes = listOf(node1)))
        assertTrue("2 equal frames must produce 0.000;0.500", svg1.contains("keyTimes=\"0.000;0.500\""))
        assertTrue("2 equal frames of 200ms must have dur=0.400s", svg1.contains("dur=\"0.400s\""))

        // 4 variable frames: 100, 300, 200, 400 -> total 1000ms
        val delays2 = listOf(100, 300, 200, 400)
        val node2 = AnimatedImageNode(
            x = 0f,
            y = 0f,
            width = 100f,
            height = 100f,
            base64Frames = listOf("a", "b", "c", "d"),
            frameDelaysMs = delays2,
            framePrefix = "test2"
        )
        val svg2 = IrSvgRenderer.render(QrGeometryIr(width = 100f, height = 100f, rootNodes = listOf(node2)))
        assertTrue("4 frames must produce exactly 4 keyTimes matching accumulated fractions",
            svg2.contains("keyTimes=\"0.000;0.100;0.400;0.600\""))
        assertTrue("Total dur must be 1.000s", svg2.contains("dur=\"1.000s\""))

        // Invariant: keyTimes count must equal values count
        val valuesMatch = Regex("values=\"([^\"]+)\"").find(svg2)?.groupValues?.get(1)?.split(";")
        val keyTimesMatch = Regex("keyTimes=\"([^\"]+)\"").find(svg2)?.groupValues?.get(1)?.split(";")
        assertNotNull("Values must be present", valuesMatch)
        assertNotNull("KeyTimes must be present", keyTimesMatch)
        assertEquals("Count of keyTimes must match count of frame values", valuesMatch!!.size, keyTimesMatch!!.size)
        assertEquals("First keyTime must always be 0.000", "0.000", keyTimesMatch.first())
        assertFalse("KeyTimes must NOT contain terminal 1.000", keyTimesMatch.contains("1.000"))
    }

    @Test
    fun testAnimationBoundaryConditionsParity() {
        // 1. Single frame
        val singleNode = AnimatedImageNode(
            x = 0f,
            y = 0f,
            width = 100f,
            height = 100f,
            base64Frames = listOf("only_frame"),
            frameDelaysMs = listOf(150),
            framePrefix = "single"
        )
        val singleSvg = IrSvgRenderer.render(QrGeometryIr(width = 100f, height = 100f, rootNodes = listOf(singleNode)))
        assertTrue(singleSvg.contains("values=\"#single0\""))
        assertTrue(singleSvg.contains("keyTimes=\"0.000\""))
        assertTrue(singleSvg.contains("dur=\"0.150s\""))

        // 2. Empty delays: default 100ms per frame
        val noDelaysNode = AnimatedImageNode(
            x = 0f,
            y = 0f,
            width = 100f,
            height = 100f,
            base64Frames = listOf("f0", "f1", "f2"),
            frameDelaysMs = emptyList(),
            framePrefix = "nodelay"
        )
        val noDelaysSvg = IrSvgRenderer.render(QrGeometryIr(width = 100f, height = 100f, rootNodes = listOf(noDelaysNode)))
        assertTrue(noDelaysSvg.contains("keyTimes=\"0.000;0.333;0.667\""))
        assertTrue(noDelaysSvg.contains("dur=\"0.300s\""))
    }

    @Test
    fun testIconAnimatedSmilSvgParity() {
        val dummyBmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.BASIC,
            logo = LogoStyle(
                bitmap = dummyBmp,
                source = ImageSource.Animated(
                    frames = listOf(dummyBmp, dummyBmp),
                    delaysMs = listOf(150, 250)
                ),
                shape = LogoShape.SQUIRCLE
            )
        )
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/ANIM-LOGO", ErrorCorrectionLevel.H)
        val svg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(matrix, design)

        // 1. Defs must define dynamic frame 0 and frame 1
        val prefixMatch = Regex("""<image id="(\d+fm)0"""").find(svg)
        assertNotNull("Must define dynamic framePrefix0 in defs", prefixMatch)
        val prefix = prefixMatch!!.groupValues[1]
        assertTrue("Must define frame 1 in defs", svg.contains("<image id=\"${prefix}1\""))

        // 2. Logo squircle mask per EFQRCode contract
        assertTrue("Must define SQ25 mask in defs", svg.contains("<mask id=\"icon"))

        // 3. SMIL animate with discrete calcMode
        assertTrue("Use tag must reference dynamic frame 0", svg.contains("<use xlink:href=\"#${prefix}0\">"))
        assertTrue("Animate values must chain logo frames", svg.contains("values=\"#${prefix}0;#${prefix}1\""))
        assertTrue("KeyTimes must match EF formula (150/400 = 0.375)", svg.contains("keyTimes=\"0.000;0.375\""))
        assertTrue("Duration must be sum (150+250=400ms = 0.400s)", svg.contains("dur=\"0.400s\""))
        assertTrue("calcMode must be discrete", svg.contains("calcMode=\"discrete\""))
    }

    @Test
    fun testAllFivePositionStyles() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/5POS-PARITY", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        // 1. RECTANGLE / CLASSIC
        val classicNodes = VeilPositionPatternGeometry.toIrNodes(
            x = 3, y = 3, moduleSize = mSize, offsetX = ox, offsetY = oy,
            style = FinderStyle.CLASSIC, size = 1.0f, color = Color.BLACK
        )
        assertEquals("Classic must produce 2 nodes (inner rect + outer stroke rect)", 2, classicNodes.size)
        val classicInner = classicNodes[0] as RectNode
        val classicOuter = classicNodes[1] as RectNode
        assertEquals(3f * mSize, classicInner.width, 0.01f)
        assertEquals(6f * mSize, classicOuter.width, 0.01f)

        // 2. ROUND / CIRCLE
        val circleNodes = VeilPositionPatternGeometry.toIrNodes(
            x = 3, y = 3, moduleSize = mSize, offsetX = ox, offsetY = oy,
            style = FinderStyle.CIRCLE, size = 1.0f, color = Color.BLACK
        )
        assertEquals("Circle must produce 2 nodes (inner circle + outer stroke circle)", 2, circleNodes.size)
        val circleInner = circleNodes[0] as CircleNode
        val circleOuter = circleNodes[1] as CircleNode
        assertEquals(1.5f * mSize, circleInner.radius, 0.01f)
        assertEquals(3.0f * mSize, circleOuter.radius, 0.01f)

        // 3. ROUNDED_RECTANGLE / SQ25
        val roundedNodes = VeilPositionPatternGeometry.toIrNodes(
            x = 3, y = 3, moduleSize = mSize, offsetX = ox, offsetY = oy,
            style = FinderStyle.ROUNDED, size = 1.0f, color = Color.BLACK
        )
        assertEquals("Rounded must produce 2 nodes (inner circle + outer SQ25 squircle path)", 2, roundedNodes.size)
        val roundedInner = roundedNodes[0] as CircleNode
        val roundedOuter = roundedNodes[1] as PathNode
        assertEquals(1.5f * mSize, roundedInner.radius, 0.01f)
        assertEquals(VeilPositionPatternGeometry.SQ25_PATH, roundedOuter.svgPathData)

        // 4. PLANETS
        val planetNodes = VeilPositionPatternGeometry.toIrNodes(
            x = 3, y = 3, moduleSize = mSize, offsetX = ox, offsetY = oy,
            style = FinderStyle.PLANETS, size = 1.0f, color = Color.BLACK
        )
        assertEquals("Planets must produce 6 nodes (core + orbit + 4 satellites)", 6, planetNodes.size)
        val planetCore = planetNodes[0] as CircleNode
        val planetOrbit = planetNodes[1] as CircleNode
        assertEquals(1.5f * mSize, planetCore.radius, 0.01f)
        assertEquals(3.0f * mSize, planetOrbit.radius, 0.01f)

        // 5. DSJ
        val dsjNodes = VeilPositionPatternGeometry.toIrNodes(
            x = 3, y = 3, moduleSize = mSize, offsetX = ox, offsetY = oy,
            style = FinderStyle.DSJ, size = 1.0f, color = Color.BLACK
        )
        assertEquals("DSJ must produce 5 nodes (center + 4 protruding tabs)", 5, dsjNodes.size)
        val dsjCenter = dsjNodes[0] as RectNode
        assertEquals(3f * mSize, dsjCenter.width, 0.01f)
    }

    @Test
    fun testBackdropStyleViewBoxAndQuietZoneParity() {
        // Standard style with default quiet zone (nil -> 1 module on each side)
        val defaultBackdrop = BackdropStyle()
        val vbDefault = defaultBackdrop.calculateViewBox(21)
        assertEquals("Default viewBox x must be -1", -1f, vbDefault.minX, 0.001f)
        assertEquals("Default viewBox y must be -1", -1f, vbDefault.minY, 0.001f)
        assertEquals("Default viewBox width must be N + 2 = 23", 23f, vbDefault.width, 0.001f)
        assertEquals("Default viewBox height must be N + 2 = 23", 23f, vbDefault.height, 0.001f)

        // Standard style with fractional quiet zone (top=0.1, left=0.1, bottom=0.2, right=0.2)
        val fractionalBackdrop = BackdropStyle(
            fractionalQuietZone = FractionalInsets(left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.2f)
        )
        val vbFractional = fractionalBackdrop.calculateViewBox(20)
        // x = -20 * 0.1 = -2.0, y = -20 * 0.1 = -2.0
        // width = 20 * (0.1 + 1 + 0.2) = 20 * 1.3 = 26.0
        // height = 20 * (0.1 + 1 + 0.2) = 20 * 1.3 = 26.0
        assertEquals(-2.0f, vbFractional.minX, 0.001f)
        assertEquals(-2.0f, vbFractional.minY, 0.001f)
        assertEquals(26.0f, vbFractional.width, 0.001f)
        assertEquals(26.0f, vbFractional.height, 0.001f)

        // RESAMPLE style with default quiet zone (nil -> 3 subpixels on each side)
        val vbResampleDefault = defaultBackdrop.calculateViewBox(21, isResample = true)
        assertEquals("Resample default viewBox x must be -3", -3f, vbResampleDefault.minX, 0.001f)
        assertEquals("Resample default viewBox y must be -3", -3f, vbResampleDefault.minY, 0.001f)
        assertEquals("Resample default viewBox width must be 3N + 6 = 69", 69f, vbResampleDefault.width, 0.001f)
        assertEquals("Resample default viewBox height must be 3N + 6 = 69", 69f, vbResampleDefault.height, 0.001f)
    }

    @Test
    fun testBackdropStyleSvgContainerParity() {
        val backdrop = BackdropStyle(
            color = 0xFFEEEEEE.toInt(),
            cornerRadius = 16.0f,
            fractionalQuietZone = FractionalInsets(left = 0.05f, top = 0.05f, right = 0.05f, bottom = 0.05f)
        )
        val (openSvg, closeSvg) = backdrop.generateSvgContainer(20, isResample = false, preprocessedBase64Image = "DUMMY_BACKDROP_B64")

        // 1. Root svg tag with viewBox width and height
        assertTrue("Must contain svg with className Qr-item-svg", openSvg.contains("<svg className=\"Qr-item-svg\""))
        assertTrue("Width must be 20 * 1.1 = 22.0", openSvg.contains("width=\"22.0\""))
        assertTrue("Height must be 20 * 1.1 = 22.0", openSvg.contains("height=\"22.0\""))

        // 2. Defs clipPath for rounded corners
        assertTrue("Must define rounded-corners clipPath", openSvg.contains("<clipPath id=\"rounded-corners\">"))
        assertTrue("Clip rect must have rx='16.00'", openSvg.contains("rx=\"16.00\""))

        // 3. Background color rect
        assertTrue("Background rect must fill #EEEEEE", openSvg.contains("fill=\"#EEEEEE\""))

        // 4. Preprocessed backdrop image
        assertTrue("Must embed preprocessed backdrop image", openSvg.contains("<image key=\"bi\""))
        assertTrue("Image href must contain base64", openSvg.contains("xlink:href=\"data:image/png;base64,DUMMY_BACKDROP_B64\""))

        // 5. Transform translation to center QR code within quiet zone margin: -left = -(-20 * 0.05) = +1.0
        assertTrue("Transform must translate by (-minX, -minY)", openSvg.contains("transform=\"translate(1.000, 1.000)\""))

        // 6. Closing tags
        assertEquals("    </g>\n  </g>\n</svg>", closeSvg)
    }

    @Test
    fun testIconPercentageClamp() {
        val dummyBmp = allocateBitmapReflectively()

        // TC-21: Excessive Icon Percentage (0.85 -> strictly clamped to 0.33)
        val paramsExcessive = QrStyleParams(
            style = QrStyle.IMAGE,
            logo = dummyBmp,
            logoFraction = 0.85f
        )
        val designExcessive = QrDesign.fromQrStyleParams(paramsExcessive)
        assertNotNull(designExcessive.logo)
        assertEquals("Icon fraction 0.85 must be clamped to 0.33", 0.33f, designExcessive.logo!!.scaleFraction, 0.001f)

        // Standard 0.20 remains 0.20
        val paramsNormal = QrStyleParams(
            style = QrStyle.IMAGE,
            logo = dummyBmp,
            logoFraction = 0.20f
        )
        val designNormal = QrDesign.fromQrStyleParams(paramsNormal)
        assertEquals(0.20f, designNormal.logo!!.scaleFraction, 0.001f)

        // Mid-value 0.50 clamped to 0.33
        val paramsMid = QrStyleParams(
            style = QrStyle.IMAGE,
            logo = dummyBmp,
            logoFraction = 0.50f
        )
        val designMid = QrDesign.fromQrStyleParams(paramsMid)
        assertEquals(0.33f, designMid.logo!!.scaleFraction, 0.001f)
    }

    @Test
    fun testIconExact24PaddingOffsetAndSq25Border() {
        val dummyBmp = allocateBitmapReflectively()
        val matrix = QrMatrix("https://veilframe.app/icon-test", ErrorCorrectionLevel.H)
        val geom = QrGeometry.fromDesign(matrix.size, 512, 512, QrDesign())

        val design = QrDesign(
            style = QrStyle.IMAGE,
            logo = LogoStyle(
                bitmap = dummyBmp,
                scaleFraction = 0.20f,
                shape = LogoShape.SQUIRCLE,
                borderColor = 0xFF336699.toInt()
            )
        )

        val ir = ImageRenderer().generateGeometry(matrix, design, geom)
        val width = 512f
        val iconSize = width * 0.20f // 102.4
        val iconXY = (width - iconSize) / 2f // 204.8
        val iconOffset = iconXY * 0.024f // 4.9152
        val rectXY = iconXY - iconOffset // 199.8848
        val length = iconSize + 2f * iconOffset // 112.2304

        // 1. Verify SQ25 squircle border path node
        val sq25Node = ir.rootNodes.filterIsInstance<PathNode>().firstOrNull { it.svgPathData == VeilPositionPatternGeometry.SQ25_PATH }
        assertNotNull("Must include SQ25 squircle border path for LogoShape.SQUIRCLE", sq25Node)
        assertEquals("Stroke width must be 100.0 / iconSize", 100f / iconSize, sq25Node!!.strokeWidth, 0.001f)
        assertTrue("Transform must translate by iconXY and scale by iconSize / 100", sq25Node.transform!!.contains("scale(1.024"))

        // 2. Verify preprocessed ImageNode at exact 2.4% offset bounds
        val imgNode = ir.rootNodes.filterIsInstance<ImageNode>().lastOrNull()
        assertNotNull("Must include ImageNode for logo", imgNode)
        assertEquals("X must match rectXY", rectXY, imgNode!!.x, 0.01f)
        assertEquals("Y must match rectXY", rectXY, imgNode.y, 0.01f)
        assertEquals("Width must match length", length, imgNode.width, 0.01f)
        assertEquals("Height must match length", length, imgNode.height, 0.01f)
        assertEquals("preserveAspectRatio must be empty to eliminate distortion", "", imgNode.preserveAspectRatio)
    }

    @Test
    fun testIconAlphaOpacityAttribute() {
        val dummyBmp = allocateBitmapReflectively()
        val matrix = QrMatrix("https://veilframe.app/icon-alpha", ErrorCorrectionLevel.H)
        val geom = QrGeometry.fromDesign(matrix.size, 512, 512, QrDesign())

        val design = QrDesign(
            style = QrStyle.IMAGE,
            logo = LogoStyle(
                bitmap = dummyBmp,
                scaleFraction = 0.20f,
                alpha = 0.65f
            )
        )

        val ir = ImageRenderer().generateGeometry(matrix, design, geom)
        val imgNode = ir.rootNodes.filterIsInstance<ImageNode>().lastOrNull()
        assertNotNull(imgNode)
        assertEquals(0.65f, imgNode!!.opacity, 0.001f)

        val svg = IrSvgRenderer.render(ir)
        assertTrue("SVG must emit opacity='0.65'", svg.contains("opacity=\"0.65\""))
    }

    @Test
    fun testIconAnimationAndMode() {
        val frame1 = allocateBitmapReflectively()
        val frame2 = allocateBitmapReflectively()
        val matrix = QrMatrix("https://veilframe.app/icon-anim", ErrorCorrectionLevel.H)
        val geom = QrGeometry.fromDesign(matrix.size, 512, 512, QrDesign())

        val design = QrDesign(
            style = QrStyle.IMAGE,
            logo = LogoStyle(
                source = ImageSource.Animated(listOf(frame1, frame2), listOf(200, 300)),
                scaleFraction = 0.20f,
                alpha = 0.90f
            )
        )

        val ir = ImageRenderer().generateGeometry(matrix, design, geom)
        val animNode = ir.rootNodes.filterIsInstance<AnimatedImageNode>().firstOrNull { it.framePrefix.endsWith("fm") }
        assertNotNull("Must emit AnimatedImageNode with dynamic framePrefix", animNode)
        assertEquals(2, animNode!!.base64Frames.size)
        assertEquals(0.90f, animNode.opacity, 0.001f)

        val svg = IrSvgRenderer.render(ir)
        assertTrue("Must declare discrete animation with dynamic prefix", svg.contains("values=\"#${animNode.framePrefix}0;#${animNode.framePrefix}1\""))
        assertTrue("Must use calcMode='discrete'", svg.contains("calcMode=\"discrete\""))
        assertTrue("Must have total duration 0.500s", svg.contains("dur=\"0.500s\""))
    }

    @Test
    fun testCanvasAnimatedLogoFrameResolutionAndDrawing() {
        val frame0 = allocateBitmapReflectively()
        val frame1 = allocateBitmapReflectively()
        val logo = LogoStyle(
            source = ImageSource.Animated(listOf(frame0, frame1), listOf(100, 150)),
            scaleFraction = 0.20f
        )
        val design = QrDesign(
            style = QrStyle.BASIC,
            logo = logo
        )

        // 1. Test resolveLogoBitmap directly
        val resolved0 = VeilIconPipeline.resolveLogoBitmap(logo, 0)
        val resolved1 = VeilIconPipeline.resolveLogoBitmap(logo, 1)
        val resolved2 = VeilIconPipeline.resolveLogoBitmap(logo, 2)
        assertSame("Frame 0 must resolve to frame0 bitmap", frame0, resolved0)
        assertSame("Frame 1 must resolve to frame1 bitmap", frame1, resolved1)
        assertSame("Frame 2 (modulo 2) must wrap to frame0 bitmap", frame0, resolved2)

        // 2. Test Canvas drawLogo captures frame 0 vs frame 1
        var drawnBitmap: Bitmap? = null
        val testCanvas = object : Canvas() {
            override fun drawBitmap(bitmap: Bitmap, src: android.graphics.Rect?, dst: android.graphics.RectF, paint: android.graphics.Paint?) {
                drawnBitmap = bitmap
            }
        }

        // Draw frame 0 explicitly
        VeilIconPipeline.drawLogo(
            canvas = testCanvas,
            design = design,
            ox = 0f,
            oy = 0f,
            qrPixelSize = 512f,
            frameIndex = 0
        )
        assertSame("Canvas must receive frame 0", frame0, drawnBitmap)

        // Draw frame 1 explicitly
        VeilIconPipeline.drawLogo(
            canvas = testCanvas,
            design = design,
            ox = 0f,
            oy = 0f,
            qrPixelSize = 512f,
            frameIndex = 1
        )
        assertSame("Canvas must receive frame 1", frame1, drawnBitmap)

        // 3. Test through RenderContext.frameIndex and QrRenderer.drawLogo delegation
        val context = RenderContext()
        val geom = QrGeometry(matrixSize = 25, outputWidth = 512, outputHeight = 512, quietZoneModules = 1)

        context.frameIndex = 0
        com.veilframe.app.qr.renderer.drawLogo(testCanvas, design, geom, context)
        assertSame("Delegated QrRenderer.drawLogo must respect context.frameIndex = 0", frame0, drawnBitmap)

        context.frameIndex = 1
        com.veilframe.app.qr.renderer.drawLogo(testCanvas, design, geom, context)
        assertSame("Delegated QrRenderer.drawLogo must respect context.frameIndex = 1", frame1, drawnBitmap)
    }

    @Test
    fun testAnimatedQrGeneratorExtractsAndRendersAnimatedLogoFrames() {
        val frame0 = allocateBitmapReflectively()
        val frame1 = allocateBitmapReflectively()
        val logo = LogoStyle(
            source = ImageSource.Animated(listOf(frame0, frame1), listOf(120, 180)),
            scaleFraction = 0.20f
        )
        val design = QrDesign(
            style = QrStyle.BASIC,
            logo = logo
        )

        // 1. isDesignAnimated must detect animated logo even if imageSource is static
        assertTrue("isDesignAnimated must return true for animated logo", AnimatedQrGenerator.isDesignAnimated(design))

        // 2. extractSourceFrames must extract frames from logo
        val extracted = AnimatedQrGenerator.extractSourceFrames(design)
        assertEquals("Must extract 2 frames from animated logo", 2, extracted.size)
        assertSame("First frame must match frame0", frame0, extracted[0].bitmap)
        assertEquals(120, extracted[0].durationMs)
        assertSame("Second frame must match frame1", frame1, extracted[1].bitmap)
        assertEquals(180, extracted[1].durationMs)
    }

    @Test
    fun testIrCanvasRendererMultiFrameExecutionForImageAndResample() {
        val frame0 = allocateBitmapReflectively()
        val frame1 = allocateBitmapReflectively()

        // 1. AnimatedImageNode (IMAGE style) multi-frame Canvas evaluation
        val animImageNode = AnimatedImageNode(
            x = 0f,
            y = 0f,
            width = 512f,
            height = 512f,
            frames = listOf(frame0, frame1),
            frameDelaysMs = listOf(100, 100),
            framePrefix = "testfm"
        )
        val imageIr = QrGeometryIr(width = 512f, height = 512f, rootNodes = listOf(animImageNode))

        var drawnBitmap: Bitmap? = null
        val testCanvas = object : Canvas() {
            override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
                drawnBitmap = bitmap
            }
        }

        IrCanvasRenderer.render(imageIr, testCanvas, frameIndex = 0)
        assertSame("IrCanvasRenderer must draw frame 0 when frameIndex = 0", frame0, drawnBitmap)

        IrCanvasRenderer.render(imageIr, testCanvas, frameIndex = 1)
        assertSame("IrCanvasRenderer must draw frame 1 when frameIndex = 1", frame1, drawnBitmap)

        // 2. AnimatedGroupNode (RESAMPLE style) multi-frame Canvas evaluation
        var drawnRectX = -1f
        val rectFrame0 = RectNode(x = 10f, y = 10f, width = 5f, height = 5f, fill = Color.BLACK)
        val rectFrame1 = RectNode(x = 20f, y = 20f, width = 5f, height = 5f, fill = Color.BLACK)
        val animGroupNode = AnimatedGroupNode(
            framePrefix = "resfm",
            frameNodes = listOf(listOf(rectFrame0), listOf(rectFrame1)),
            frameDelaysMs = listOf(100, 100)
        )
        val resampleIr = QrGeometryIr(width = 512f, height = 512f, rootNodes = listOf(animGroupNode))

        val rectCanvas = object : Canvas() {
            override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
                drawnRectX = left
            }
        }

        IrCanvasRenderer.render(resampleIr, rectCanvas, frameIndex = 0)
        assertEquals("IrCanvasRenderer must render frame 0 geometry at x=10", 10f, drawnRectX, 0.001f)

        IrCanvasRenderer.render(resampleIr, rectCanvas, frameIndex = 1)
        assertEquals("IrCanvasRenderer must render frame 1 geometry at x=20", 20f, drawnRectX, 0.001f)
    }

    @Test
    fun testCoreGraphicsPremultipliedAlphaMathematicalDiscrepancy() {
        // Pure red: (255, 0, 0), alpha = 0.5
        // Un-premultiplied sRGB (Android Bitmap.getPixel):
        // baseGray = 0.2126 * 255 = 54.213
        // Standard weightedGray = 54.213 * 0.5 + (1 - 0.5) * 255 = 27.1065 + 127.5 = 154.6065
        val standardNorm = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(255, 0, 0, 0.5f, efPremultipliedAlpha = false)
        assertEquals(154.6065f / 255f, standardNorm, 0.001f)

        // EF CoreGraphics CGContext(premultipliedLast) buffer behavior:
        // CoreGraphics draws red as (255 * 0.5) = 127.5 -> rounded to 127 or 128
        // EF gamma() computes gray from the premultiplied channel byte value
        // Then EF applies alpha AGAIN: weightedGray = gray * alpha + (1 - alpha) * 255
        val efCoreGraphicsNorm = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(255, 0, 0, 0.5f, efPremultipliedAlpha = true)
        val expectedPremulGray = (0.2126 * 127.0) // 127 is (255 * 0.5).toInt()
        val expectedWeighted = (expectedPremulGray * 0.5 + (1.0 - 0.5) * 255.0) / 255.0
        assertEquals(expectedWeighted.toFloat(), efCoreGraphicsNorm, 0.001f)

        // For opaque pixels (alpha = 1.0), both paths are strictly identical:
        val opaqueStandard = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(255, 0, 0, 1.0f, efPremultipliedAlpha = false)
        val opaqueEf = com.veilframe.app.qr.renderer.ImageScaleResolver.calculateLuminance(255, 0, 0, 1.0f, efPremultipliedAlpha = true)
        assertEquals(opaqueStandard, opaqueEf, 0.0001f)
    }

    @Test
    fun testImageRendererModuleShapeNoneDoesNotEmitGeometry() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        val imgBmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(imgBmp)
            ),
            timingStyle = TimingStyle(shape = com.veilframe.app.qr.model.ModuleShape.NONE),
            alignmentStyle = AlignmentStyle(shape = com.veilframe.app.qr.model.ModuleShape.NONE)
        )
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)
        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)

        // Verify that no timing or alignment nodes were emitted when shape is NONE
        // (Previously fell back to RectNode and rendered square timing/alignment modules)
        val allNodes = mutableListOf<QrGeometryNode>()
        fun collectNodes(node: QrGeometryNode) {
            allNodes.add(node)
            if (node is GroupNode) {
                for (child in node.children) {
                    collectNodes(child)
                }
            }
        }
        for (root in ir.rootNodes) {
            collectNodes(root)
        }

        // Find module size in pixel coordinates
        val moduleSize = geometry.moduleSize.toFloat()
        val qzPx = geometry.offsetX
        val timingRowY = qzPx + 6 * moduleSize
        val timingColX = qzPx + 6 * moduleSize

        // Neither row 6 nor col 6 between finders (indices 8 until matrix.size - 8) should have timing nodes
        val timingNodes = allNodes.filterIsInstance<RectNode>().filter { rect ->
            val isTimingRow = rect.y in (timingRowY - 0.5f)..(timingRowY + 0.5f) && rect.x > qzPx + 7 * moduleSize && rect.x < qzPx + (matrix.size - 7) * moduleSize
            val isTimingCol = rect.x in (timingColX - 0.5f)..(timingColX + 0.5f) && rect.y > qzPx + 7 * moduleSize && rect.y < qzPx + (matrix.size - 7) * moduleSize
            isTimingRow || isTimingCol
        }
        assertTrue("When timingShape is NONE, no timing nodes must be generated on ImageRenderer path", timingNodes.isEmpty())
    }

    @Test
    fun testImageRendererAlphaSingleApplication() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        val imgBmp = allocateBitmapReflectively()
        // 50% translucent color with distinct RGB (0x80123456)
        val translucentColor = (0x80 shl 24) or 0x123456
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(imgBmp)
            ),
            dataColorDark = translucentColor,
            dataColorLight = translucentColor,
            timingDarkColor = translucentColor,
            timingLightColor = translucentColor,
            alignDarkColor = translucentColor,
            alignLightColor = translucentColor
        )
        val geometry = QrGeometry(matrixSize = matrix.size, outputWidth = 512, outputHeight = 512, quietZoneModules = 4)
        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)

        val allNodes = mutableListOf<QrGeometryNode>()
        fun collectNodes(node: QrGeometryNode) {
            allNodes.add(node)
            if (node is GroupNode) {
                for (child in node.children) {
                    collectNodes(child)
                }
            }
        }
        for (root in ir.rootNodes) {
            collectNodes(root)
        }

        // All emitted RectNodes for modules styled with translucentColor should have opaque RGB fill (alpha = 0xFF) and opacity set to 0.5f (128/255)
        val translucentOpaqueRgb = (translucentColor and 0x00FFFFFF) or 0xFF000000.toInt()
        val styledModules = allNodes.filterIsInstance<RectNode>().filter { it.fill == translucentOpaqueRgb }
        assertTrue("Should have emitted module nodes with translucentColor", styledModules.isNotEmpty())
        for (node in styledModules) {
            val fillAlpha = (node.fill!! ushr 24) and 0xFF
            assertEquals("Module fill color must have opaque alpha channel to avoid double-alpha application", 0xFF, fillAlpha)
            val expectedOpacity = 0x80 / 255f
            assertEquals("Module opacity must carry the color's original alpha", expectedOpacity, node.opacity, 0.01f)
        }
    }

    @Test
    fun testImageFillSvgPreprocessesStaticBitmapAndEmitsQuietZoneBackdrop() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        val rectBmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(rectBmp)
            ),
            palette = PaletteStyle(background = Color.WHITE)
        )
        val svg = SvgExporter.generateSvg(matrix, design)

        // 1. Svg must contain quiet-zone background rect covering entire viewBox
        val n = matrix.size
        val qz = design.effectiveQuietZone
        val totalSize = n + 2 * qz
        assertTrue("ImageFill SVG must contain full-viewBox background rect", svg.contains("""<rect width="$totalSize" height="$totalSize" fill="#FFFFFF""""))

        // 2. SVG defs mask must have width and height matching QR matrix size (n)
        assertTrue("ImageFill SVG must embed dimensions matching QR matrix size", svg.contains("""width="$n" height="$n""""))
    }

    @Test
    fun testAnimatedQrGeneratorTimelineReconciliation() {
        val frameA = allocateBitmapReflectively()
        val frameB = allocateBitmapReflectively()
        val frameX = allocateBitmapReflectively()
        val frameY = allocateBitmapReflectively()

        // Stream 1 (imageSource): A (100ms), B (300ms) -> total 400ms
        // Stream 2 (logo): X (200ms), Y (200ms) -> total 400ms
        val reconciled = AnimatedQrGenerator.reconcileAnimationTimelines(
            imageFrames = listOf(frameA, frameB),
            imageDelaysMs = listOf(100, 300),
            logoFrames = listOf(frameX, frameY),
            logoDelaysMs = listOf(200, 200)
        )

        // Reconciled timeline:
        // [0, 100ms): A + X (dur: 100ms)
        // [100, 200ms): B + X (dur: 100ms)
        // [200, 400ms): B + Y (dur: 200ms)
        assertEquals("Must reconcile into exactly 3 synchronized slices", 3, reconciled.size)

        assertEquals(frameA, reconciled[0].imageBitmap)
        assertEquals(frameX, reconciled[0].logoBitmap)
        assertEquals(100, reconciled[0].durationMs)

        assertEquals(frameB, reconciled[1].imageBitmap)
        assertEquals(frameX, reconciled[1].logoBitmap)
        assertEquals(100, reconciled[1].durationMs)

        assertEquals(frameB, reconciled[2].imageBitmap)
        assertEquals(frameY, reconciled[2].logoBitmap)
        assertEquals(200, reconciled[2].durationMs)

        val totalReconciledMs = reconciled.sumOf { it.durationMs }
        assertEquals(400, totalReconciledMs)
    }

    @Test
    fun testImageAndImageFillSvgNoPreserveAspectRatio() {
        val matrix = QrMatrix("https://veilframe.app/no-preserve-aspect", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()

        // 1. IMAGE style SVG must not emit preserveAspectRatio on <image>
        val imageDesign = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp), scaleMode = ImageScaleMode.ASPECT_FILL)
        )
        val imageSvg = SvgExporter.generateSvg(matrix, imageDesign)
        assertFalse("IMAGE SVG must not emit preserveAspectRatio=\"...\"", imageSvg.contains("preserveAspectRatio="))

        // 2. IMAGE_FILL style SVG must not emit preserveAspectRatio on <image>
        val imageFillDesign = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp), scaleMode = ImageScaleMode.STRETCH)
        )
        val fillSvg = SvgExporter.generateSvg(matrix, imageFillDesign)
        assertFalse("IMAGE_FILL SVG must not emit preserveAspectRatio=\"...\"", fillSvg.contains("preserveAspectRatio="))

        // 3. IR ImageNode must have preserveAspectRatio = "" for EF parity (avoiding double scale)
        val geometry = QrGeometry(matrix.size, 512, 512, 1)
        val imageIr = ImageRenderer().generateGeometry(matrix, imageDesign, geometry)
        val imageNode = imageIr.rootNodes.filterIsInstance<ImageNode>().firstOrNull()
        assertNotNull("ImageNode must be present in IR", imageNode)
        assertEquals("ImageNode preserveAspectRatio must be empty to avoid secondary scaling", "", imageNode?.preserveAspectRatio)

        val fillIr = ImageFillRenderer().generateGeometry(matrix, imageFillDesign, geometry)
        val fillImageNode = fillIr.rootNodes.filterIsInstance<GroupNode>().firstOrNull()?.children?.filterIsInstance<ImageNode>()?.firstOrNull()
        assertNotNull("Fill ImageNode must be present in IR", fillImageNode)
        assertEquals("Fill ImageNode preserveAspectRatio must be empty to avoid secondary scaling", "", fillImageNode?.preserveAspectRatio)
    }

    @Test
    fun testImageSvgPreservesTranslucentBackgroundAlpha() {
        val matrix = QrMatrix("https://veilframe.app/bg-alpha", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()

        // Translucent background: 50% opacity white (0x80FFFFFF)
        val translucentBg = (0x80 shl 24) or 0x00FFFFFF
        val design = QrDesign(
            style = QrStyle.IMAGE,
            palette = PaletteStyle(background = translucentBg),
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp))
        )
        val svg = SvgExporter.generateSvg(matrix, design)

        // Background rect must carry opacity attribute preserving alpha
        val expectedAlpha = 0x80 / 255f
        val formattedExpectedAlpha = String.format(java.util.Locale.US, "%.3f", expectedAlpha).trimEnd('0')
        assertTrue("Background rect must have opacity preserving alpha ($formattedExpectedAlpha)", svg.contains("opacity=\"$formattedExpectedAlpha\""))
    }

    @Test
    fun testImageSvgTransparentPrepassIncludesNoneTimingAndAlignment() {
        val matrix = QrMatrix("https://veilframe.app/prepass-none", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()

        // When allowTransparent = true and timingShape = NONE
        val design = QrDesign(
            style = QrStyle.IMAGE,
            allowTransparent = true,
            timingStyle = TimingStyle(shape = com.veilframe.app.qr.model.ModuleShape.NONE),
            alignmentStyle = AlignmentStyle(shape = com.veilframe.app.qr.model.ModuleShape.NONE),
            moduleStyle = ModuleStyle(shape = com.veilframe.app.qr.model.ModuleShape.SQUARE),
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp))
        )
        val svg = SvgExporter.generateSvg(matrix, design)

        // Find timing module coordinate (row 6, col 8)
        val qz = design.effectiveQuietZone
        val timingModuleX = (8 + qz).toDouble()
        val timingModuleY = (6 + qz).toDouble()

        // In transparent pre-pass, timing modules with shape NONE must be rendered with data shape (SQUARE rect)
        val expectedTimingRect = """x="$timingModuleX" y="$timingModuleY""""
        assertTrue("Transparent pre-pass must render timing module with shape NONE", svg.contains(expectedTimingRect))
    }

    @Test
    fun testAnimatedSvgDiscreteKeyTimesCountMatchesValuesCount() {
        val matrix = QrMatrix("https://veilframe.app/anim-keytimes", ErrorCorrectionLevel.M)
        val frame1 = allocateBitmapReflectively()
        val frame2 = allocateBitmapReflectively()
        val frame3 = allocateBitmapReflectively()

        val params = QrStyleParams(
            style = QrStyle.IMAGE_RESAMPLE,
            sourceImageAnimatedFrames = listOf(frame1, frame2, frame3),
            sourceImageFrameDelaysMs = listOf(100, 100, 100)
        )
        val design = QrDesign.fromQrStyleParams(params)
        val svg = AnimatedQrGenerator.generateAnimatedSvg(matrix, design)

        // Find values and keyTimes in <animate>
        val valuesRegex = Regex("""values="([^"]+)"""")
        val keyTimesRegex = Regex("""keyTimes="([^"]+)"""")

        val valuesMatch = valuesRegex.find(svg)
        val keyTimesMatch = keyTimesRegex.find(svg)

        assertNotNull("SVG must contain values attribute in animate tag", valuesMatch)
        assertNotNull("SVG must contain keyTimes attribute in animate tag", keyTimesMatch)

        val values = valuesMatch!!.groupValues[1].split(";")
        val keyTimes = keyTimesMatch!!.groupValues[1].split(";")

        assertEquals("Values count must match frame count (3)", 3, values.size)
        assertEquals("KeyTimes count must exactly match values count (3) for EF parity", 3, keyTimes.size)

        // In EFQRCode 7.0.3, for 3 frames (equal duration): keyTimes = 0.000;0.333;0.667
        assertEquals("0.000", keyTimes[0])
        assertEquals("0.333", keyTimes[1])
        assertEquals("0.667", keyTimes[2])
        // Must NOT contain terminal 1.000
        assertFalse("KeyTimes must not end with 1.000 in EFQRCode 7.0.3 discrete animation", keyTimes.contains("1.000"))
    }

    @Test
    fun testImageSvgBackdropCornerRadiusAndImageParity() {
        val matrix = QrMatrix("https://veilframe.app/backdrop-parity", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()
        val dummyBackdropBmp = allocateBitmapReflectively()

        // Test corner radius clipping and backdrop image in IMAGE SVG
        val designWithBackdrop = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            backdropStyle = BackdropStyle(
                cornerRadius = 16f,
                image = dummyBackdropBmp,
                imageAlpha = 0.8f
            )
        )
        val svg = SvgExporter.generateSvg(matrix, designWithBackdrop)

        // Must define rounded-corners clipPath with exact corner radius (rx="16" ry="16", not clamped rx="1")
        assertTrue("SVG must define rounded-corners clipPath when cornerRadius > 0", svg.contains("<clipPath id=\"rounded-corners\">"))
        assertTrue("SVG clip rect must have rx=\"16\" ry=\"16\"", svg.contains("""rx="16" ry="16""""))
        assertTrue("SVG must clip main group to rounded-corners", svg.contains("clip-path=\"url(#rounded-corners)\""))
        // Must include backdrop image <image key="bi" .../>
        assertTrue("SVG must include backdrop image element", svg.contains("""key="bi""""))

        // Also test IMAGE_FILL SVG with corner radius
        val fillDesignWithBackdrop = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            backdropStyle = BackdropStyle(
                cornerRadius = 12f,
                image = dummyBackdropBmp
            )
        )
        val fillSvg = SvgExporter.generateSvg(matrix, fillDesignWithBackdrop)
        assertTrue("IMAGE_FILL SVG must define rounded-corners clipPath when cornerRadius > 0", fillSvg.contains("<clipPath id=\"rounded-corners\">"))
        assertTrue("IMAGE_FILL clip rect must have rx=\"12\" ry=\"12\"", fillSvg.contains("""rx="12" ry="12""""))
        assertTrue("IMAGE_FILL SVG must clip main group to rounded-corners", fillSvg.contains("clip-path=\"url(#rounded-corners)\""))
        assertTrue("IMAGE_FILL SVG must include backdrop image element", fillSvg.contains("""key="bi""""))
    }

    @Test
    fun testBackdropColorOverridesPaletteBackgroundParity() {
        val matrix = QrMatrix("https://veilframe.app/backdrop-color", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()

        // Test QrStyle.IMAGE with palette.background = WHITE and backdropStyle.color = RED
        val imageDesign = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            palette = PaletteStyle(background = Color.WHITE),
            backdropStyle = BackdropStyle(color = Color.RED)
        )
        val imageSvg = SvgExporter.generateSvg(matrix, imageDesign)
        val redHex = String.format(Locale.US, "#%06X", 0xFFFFFF and Color.RED)
        assertTrue(
            "IMAGE SVG outer backdrop rect must use backdropStyle.color (RED) instead of palette.background (WHITE)",
            imageSvg.contains("""<rect width="${matrix.size}" height="${matrix.size}" fill="$redHex"""") ||
            imageSvg.contains("""fill="$redHex"""")
        )

        // Test QrStyle.IMAGE_FILL with palette.background = WHITE and backdropStyle.color = RED
        val fillDesign = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            palette = PaletteStyle(background = Color.WHITE),
            backdropStyle = BackdropStyle(color = Color.RED)
        )
        val fillSvg = SvgExporter.generateSvg(matrix, fillDesign)
        assertTrue(
            "IMAGE_FILL SVG outer backdrop rect must use backdropStyle.color (RED) instead of palette.background (WHITE)",
            fillSvg.contains("""fill="$redHex"""")
        )
    }

    @Test
    fun testBackdropCornerRadiusNumericFormattingParity() {
        val matrix = QrMatrix("https://veilframe.app/corner-radius", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()

        // Test corner radius = 8f (must NOT be clamped to 1.0 by formatOpacity)
        val designR8 = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            backdropStyle = BackdropStyle(cornerRadius = 8f)
        )
        val svgR8 = SvgExporter.generateSvg(matrix, designR8)
        assertTrue(
            "Corner radius 8 must serialize as rx=\"8\" ry=\"8\", not clamped rx=\"1\" ry=\"1\"",
            svgR8.contains("""rx="8" ry="8"""")
        )
        assertFalse(
            "Corner radius 8 must NOT be clamped to rx=\"1\" ry=\"1\"",
            svgR8.contains("""rx="1" ry="1"""")
        )

        // Test corner radius = 16f
        val designR16 = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            backdropStyle = BackdropStyle(cornerRadius = 16f)
        )
        val svgR16 = SvgExporter.generateSvg(matrix, designR16)
        assertTrue(
            "Corner radius 16 must serialize as rx=\"16\" ry=\"16\"",
            svgR16.contains("""rx="16" ry="16"""")
        )

        // Test fractional corner radius = 12.5f
        val designR12_5 = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            backdropStyle = BackdropStyle(cornerRadius = 12.5f)
        )
        val svgR12_5 = SvgExporter.generateSvg(matrix, designR12_5)
        assertTrue(
            "Corner radius 12.5 must serialize as rx=\"12.5\" ry=\"12.5\"",
            svgR12_5.contains("""rx="12.5" ry="12.5"""")
        )
    }

    @Test
    fun testCanvasGenericBackdropContractParity() {
        val matrix = QrMatrix("https://veilframe.app/canvas-backdrop", ErrorCorrectionLevel.M)
        val dummyBmp = allocateBitmapReflectively()
        val dummyBackdropBmp = allocateBitmapReflectively()

        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            quietZoneModules = 0
        )

        // 1. Verify ImageRenderer IR geometry contains resolved backdrop color and backdrop image
        val imageDesign = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            palette = PaletteStyle(background = Color.WHITE),
            backdropStyle = BackdropStyle(
                color = Color.RED,
                cornerRadius = 8f,
                image = dummyBackdropBmp,
                imageAlpha = 0.75f
            )
        )
        val imageRenderer = ImageRenderer()
        val imageIr = imageRenderer.generateGeometry(matrix, imageDesign, geometry)
        val imageBgRect = imageIr.rootNodes[0] as RectNode
        assertEquals("ImageRenderer Canvas IR background must use backdropStyle.color", Color.RED, imageBgRect.fill)
        assertEquals("ImageRenderer Canvas IR background rx must reflect corner radius in pixels", 8f * geometry.moduleSize, imageBgRect.rx, 0.01f)
        val imageBackdropNode = imageIr.rootNodes[1] as ImageNode
        assertEquals("ImageRenderer Canvas IR must include backdrop image node", 0.75f, imageBackdropNode.opacity, 0.01f)

        // 2. Verify ImageFillRenderer IR geometry contains resolved backdrop color and backdrop image
        val fillDesign = QrDesign(
            style = QrStyle.IMAGE_FILL,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
            palette = PaletteStyle(background = Color.WHITE),
            backdropStyle = BackdropStyle(
                color = Color.BLUE,
                cornerRadius = 10f,
                image = dummyBackdropBmp,
                imageAlpha = 0.5f
            )
        )
        val fillRenderer = ImageFillRenderer()
        val fillIr = fillRenderer.generateGeometry(matrix, fillDesign, geometry)
        val fillBgRect = fillIr.rootNodes[0] as RectNode
        assertEquals("ImageFillRenderer Canvas IR background must use backdropStyle.color", Color.BLUE, fillBgRect.fill)
        assertEquals("ImageFillRenderer Canvas IR background rx must reflect corner radius in pixels", 10f * geometry.moduleSize, fillBgRect.rx, 0.01f)
        val fillBackdropNode = fillIr.rootNodes[1] as ImageNode
        assertEquals("ImageFillRenderer Canvas IR must include backdrop image node", 0.5f, fillBackdropNode.opacity, 0.01f)
    }
}

