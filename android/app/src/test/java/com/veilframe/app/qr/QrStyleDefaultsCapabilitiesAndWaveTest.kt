package com.veilframe.app.qr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.D25Geometry
import com.veilframe.app.qr.geometry.PolygonNode
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.DepthStyle
import com.veilframe.app.qr.model.EyeStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionStyle
import com.veilframe.app.qr.model.FunctionType
import com.veilframe.app.qr.model.LineVariant
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.PaletteStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.registry.QrStyleRegistry
import com.veilframe.app.qr.renderer.StyleFunctionRenderer
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry
import com.veilframe.app.qr.ui.QrStudioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class QrStyleDefaultsCapabilitiesAndWaveTest {

    private fun testMatrix(): QrMatrix = QrMatrix("https://veilframe.app/style-audit", ErrorCorrectionLevel.M)

    @Test
    fun `switching styles updates default finderStyle and dataShape accurately`() {
        val vm = QrStudioViewModel(Application())

        vm.updateStyle(QrStyle.BUBBLE)
        assertEquals(FinderStyle.CIRCLE, vm.state.value.finderStyle)
        assertEquals(ModuleShape.CIRCLE, vm.state.value.dataShape)

        vm.updateStyle(QrStyle.DSJ)
        assertEquals(FinderStyle.DSJ, vm.state.value.finderStyle)
        assertEquals(ModuleShape.CONNECTED, vm.state.value.dataShape)

        vm.updateStyle(QrStyle.LINE)
        assertEquals(FinderStyle.PLANETS, vm.state.value.finderStyle)
        assertEquals(ModuleShape.LINE, vm.state.value.dataShape)

        vm.updateStyle(QrStyle.STYLE_FUNCTION)
        assertEquals(FinderStyle.ROUNDED, vm.state.value.finderStyle)
        assertEquals(ModuleShape.ROUNDED, vm.state.value.dataShape)

        vm.updateStyle(QrStyle.BASIC)
        assertEquals(FinderStyle.CLASSIC, vm.state.value.finderStyle)
        assertEquals(ModuleShape.SQUARE, vm.state.value.dataShape)

        // Switching Line variant to CIRCUIT when on CLASSIC upgrades finder to PLANETS
        vm.updateFinderStyle(FinderStyle.CLASSIC)
        vm.updateLineVariant(LineVariant.CIRCUIT)
        assertEquals(FinderStyle.PLANETS, vm.state.value.finderStyle)
    }

    @Test
    fun `style capability matrix gates motion and style options accurately`() {
        val imageStyles = setOf(QrStyle.IMAGE, QrStyle.IMAGE_FILL, QrStyle.IMAGE_RESAMPLE)
        for (style in QrStyle.values()) {
            val def = QrStyleRegistry.get(style)
            assertEquals(
                "supportsAnimatedSource must match image-capable styles for $style",
                style in imageStyles,
                def.supportsAnimatedSource
            )
            assertEquals(
                "supportsSourceImage must match image styles for $style",
                style in imageStyles,
                def.supportsSourceImage
            )
        }
        assertEquals(FinderStyle.PLANETS, QrStyleRegistry.get(QrStyle.LINE).defaultFinderStyle)
        assertEquals(FinderStyle.CIRCLE, QrStyleRegistry.get(QrStyle.BUBBLE).defaultFinderStyle)
        assertEquals(FinderStyle.DSJ, QrStyleRegistry.get(QrStyle.DSJ).defaultFinderStyle)
        assertTrue(QrStyleRegistry.get(QrStyle.BASIC).supportsGradients)
        assertTrue(QrStyleRegistry.get(QrStyle.STYLE_FUNCTION).supportsGradients)
        assertFalse(QrStyleRegistry.get(QrStyle.D25).supportsGradients)
        assertFalse(QrStyleRegistry.get(QrStyle.RANDOM_RECTANGLE).supportsFinderStyle)
    }

    @Test
    fun `all parametric FunctionType waves produce distinct geometry and preserve styleFuncGrad`() {
        val matrix = testMatrix()
        val geometry = QrGeometry(matrix.size, 512, 512, quietZoneModules = 2)
        val renderer = StyleFunctionRenderer()

        val signatures = mutableMapOf<FunctionType, List<Float>>()
        for (type in FunctionType.values()) {
            val design = QrDesign(
                style = QrStyle.STYLE_FUNCTION,
                palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE),
                functionStyle = FunctionStyle(type = type, frequency = 0.75f, amplitude = 0.65f, seed = 42L)
            )
            val ir = renderer.generateGeometry(matrix, design, geometry)
            assertTrue("Must define styleFuncGrad", ir.defs.any { it.contains("""id="styleFuncGrad"""") })

            val svg = SvgExporter.generateSvg(matrix, design)
            assertTrue("SVG must contain styleFuncGrad def for $type", svg.contains("""id="styleFuncGrad""""))
            assertTrue("SVG must reference url(#styleFuncGrad) for $type", svg.contains("""fill="url(#styleFuncGrad)""""))

            val metricVector = ir.rootNodes.mapNotNull { node ->
                when (node) {
                    is RectNode -> if (node.fillString == "url(#styleFuncGrad)") node.width + node.rx + node.opacity else null
                    is CircleNode -> if (node.fillString == "url(#styleFuncGrad)") node.radius * 2f + node.opacity else null
                    else -> null
                }
            }
            assertTrue("Wave $type must emit gradient-backed data modules", metricVector.size > 20)
            signatures[type] = metricVector.take(24)
        }

        // Verify distinctness across wave functions
        val uniqueSignatures = signatures.values.map { list -> list.map { (it * 100).toInt() } }.toSet()
        assertEquals(
            "Every FunctionType (${FunctionType.values().joinToString()}) must produce a distinct wave signature",
            FunctionType.values().size,
            uniqueSignatures.size
        )

        // Verify frequency and amplitude visibly alter WAVE geometry
        val lowAmpIr = renderer.generateGeometry(
            matrix,
            QrDesign(style = QrStyle.STYLE_FUNCTION, functionStyle = FunctionStyle(FunctionType.WAVE, frequency = 0.3f, amplitude = 0.1f)),
            geometry
        )
        val highAmpIr = renderer.generateGeometry(
            matrix,
            QrDesign(style = QrStyle.STYLE_FUNCTION, functionStyle = FunctionStyle(FunctionType.WAVE, frequency = 1.6f, amplitude = 0.9f)),
            geometry
        )
        val lowWidths = lowAmpIr.rootNodes.filterIsInstance<RectNode>().map { it.width }
        val highWidths = highAmpIr.rootNodes.filterIsInstance<RectNode>().map { it.width }
        assertNotEquals("Frequency & amplitude changes must change wave geometry", lowWidths, highWidths)
    }

    @Test
    fun `VeilPositionPatternGeometry supports SOFT and FRAME finders cleanly`() {
        val softNodes = VeilPositionPatternGeometry.toIrNodes(3, 3, 10f, 0f, 0f, FinderStyle.SOFT, 1.0f, Color.BLACK)
        assertTrue("SOFT outer rect must have rounded corners", softNodes.filterIsInstance<RectNode>().any { it.rx > 0f && it.stroke != null })
        assertTrue("SOFT inner core must emit circle", softNodes.filterIsInstance<CircleNode>().isNotEmpty())

        val frameNodes = VeilPositionPatternGeometry.toIrNodes(3, 3, 10f, 0f, 0f, FinderStyle.FRAME, 1.0f, Color.BLACK)
        assertTrue("FRAME must emit diamond PolygonNode", frameNodes.filterIsInstance<PolygonNode>().any { it.pointsList.size == 4 })
    }

    @Test
    fun `D25Geometry angleDegrees visibly changes projection coefficients`() {
        val defaultProj = D25Geometry.computeProjection(25, 512f, 512f, 1f, 1f, 1f, 1f, angleDegrees = 45f)
        val flatProj = D25Geometry.computeProjection(25, 512f, 512f, 1f, 1f, 1f, 1f, angleDegrees = 20f)
        val steepProj = D25Geometry.computeProjection(25, 512f, 512f, 1f, 1f, 1f, 1f, angleDegrees = 70f)

        assertEquals(D25Geometry.SQ3H, defaultProj.xCoeff, 0.0001f)
        assertEquals(0.5f, defaultProj.yCoeff, 0.0001f)
        assertTrue("Lower angle compresses yCoeff", flatProj.yCoeff < defaultProj.yCoeff)
        assertTrue("Steeper angle expands yCoeff", steepProj.yCoeff > defaultProj.yCoeff)
    }

    @Test
    fun `maxAnimationFrames customization and session termination clean state completely`() {
        val vm = QrStudioViewModel(Application())
        vm.updateContent("https://veilframe.app/reset-check")
        vm.updateStyle(QrStyle.LINE)
        vm.updateMaxAnimationFrames(240)
        assertEquals(240, vm.state.value.maxAnimationFrames)

        vm.updateMaxAnimationFrames(999)
        assertEquals(360, vm.state.value.maxAnimationFrames)

        vm.updateMaxAnimationFrames(2)
        assertEquals(12, vm.state.value.maxAnimationFrames)

        val bmp1 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        vm.updateAnimatedFrames(listOf(QrFrame(bmp1, 100), QrFrame(bmp2, 100)))
        assertFalse("LINE style does not activate source animation", vm.isStyleAnimationActive())
        vm.updateStyle(QrStyle.IMAGE)
        assertTrue("IMAGE style activates source animation", vm.isStyleAnimationActive())

        vm.terminateSession()
        assertEquals("", vm.state.value.content)
        assertEquals(QrStyle.BASIC, vm.state.value.style)
        assertEquals(FinderStyle.CLASSIC, vm.state.value.finderStyle)
        assertEquals(120, vm.state.value.maxAnimationFrames)
        assertTrue(vm.state.value.animatedFrames.isEmpty())
        assertTrue(vm.state.value.previewAnimatedFrames.isEmpty())
    }

    @Test
    fun `D25 does not false-positive when decoder fails and D25 UI defaults are non-occluding`() = runBlocking {
        val vm = QrStudioViewModel(Application())
        vm.updateStyle(QrStyle.D25)
        val s = vm.state.value
        assertEquals(0.40f, s.d25Depth, 0.001f)
        assertEquals(0.30f, s.d25PositionDepth, 0.001f)
        assertEquals(0x22000000, s.d25LeftColor)
        assertEquals(0x42000000.toInt(), s.d25RightColor)

        val d25Design = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(
                depth = 1.5f,
                positionDepth = 1.5f,
                leftColor = 0xCC000000.toInt(),
                rightColor = 0xEE000000.toInt()
            )
        )
        val matrix = testMatrix()
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            com.veilframe.app.qr.QrGenerator.renderToCanvas(matrix, d25Design, android.graphics.Canvas(bmp))
            val prevPrimary = com.veilframe.app.qr.validation.ScanabilityValidator.primaryDecoder
            try {
                com.veilframe.app.qr.validation.ScanabilityValidator.primaryDecoder = object : com.veilframe.app.qr.decoder.QrDecoder {
                    override val id: String = "FailingMock"
                    override suspend fun decode(bitmap: Bitmap) = com.veilframe.app.qr.decoder.DecodeResult(false, error = "Undecodable")
                }
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(bmp, d25Design, matrix, "https://veilframe.app/style-audit")
                assertFalse("D25 must never report verified false-positive when decoders fail", report.isScanReady)
            } finally {
                com.veilframe.app.qr.validation.ScanabilityValidator.primaryDecoder = prevPrimary
            }
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `Line Circuit defaults and Floyd-Steinberg V6 Dither QR reconstruct subjects on short text`() {
        val vm = QrStudioViewModel(Application())
        vm.updateStyle(QrStyle.LINE)
        assertEquals(FinderStyle.PLANETS, vm.state.value.finderStyle)
        assertEquals(LineVariant.CIRCUIT, vm.state.value.lineVariant)
        assertTrue(vm.state.value.lineCircuitBridges)
        assertTrue(vm.state.value.lineAccentRings)
        assertEquals(0.75f, vm.state.value.lineThickness, 0.001f)

        // Dither QR defaults to Version 6 + Floyd-Steinberg error diffusion for short text
        vm.updateStyle(QrStyle.IMAGE_RESAMPLE)
        assertEquals(com.veilframe.app.qr.model.ResampleDitherMode.FLOYD_STEINBERG, vm.state.value.resampleDitherMode)
        assertEquals(6, vm.state.value.resampleMinVersion)

        val shortMatrix = com.veilframe.app.qr.QrGenerator.generateMatrix(
            "Hi",
            QrDesign(
                style = QrStyle.IMAGE_RESAMPLE,
                resampleStyle = com.veilframe.app.qr.model.ResampleStyle(
                    ditherMode = com.veilframe.app.qr.model.ResampleDitherMode.FLOYD_STEINBERG,
                    minVersion = 6
                )
            )
        )
        assertEquals("Min Version 6 on short text 'Hi' must expand to 41x41 modules", 41, shortMatrix.size)

        // Half-black / half-white test image: verify top half emits far more dark subpixels than bottom half
        val splitBmp = Bitmap.createBitmap(123, 123, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(splitBmp)
        val p = android.graphics.Paint()
        p.color = Color.BLACK
        c.drawRect(0f, 0f, 123f, 61f, p)
        p.color = Color.WHITE
        c.drawRect(0f, 61f, 123f, 123f, p)

        var topDots = 0
        var bottomDots = 0
        com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
            matrix = shortMatrix,
            source = splitBmp,
            style = com.veilframe.app.qr.model.ImageSourceStyle(source = com.veilframe.app.qr.model.ImageSource.Memory(splitBmp)),
            ditherMode = com.veilframe.app.qr.model.ResampleDitherMode.FLOYD_STEINBERG,
            includeCenterAnchors = false
        ) { _, row, _, _, isCenter ->
            if (!isCenter) {
                if (row < 16) topDots++ else if (row > 24) bottomDots++
            }
        }
        splitBmp.recycle()
        assertTrue("Dark top subject region must produce substantially more halftone subpixels than light bottom ($topDots vs $bottomDots)", topDots > bottomDots * 3)
    }
}

