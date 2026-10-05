package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.decoder.ZxingQrDecoder
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.ImageGeometryBuilder
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.validation.ScanabilityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression and scanability test suite for EF Image Sample Preset.
 *
 * Covers:
 * - Phase 0 Step 0.1: Freezes and records the baseline failure with transparent light modules.
 * - Phase 3 Steps 5 & 6: Tests the fixed preset (cyan + white, 35% scale) across multi-resolution outputs
 *   (256, 512, 1024, 2048) and records scanability, finder safety, and contrast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class EfImagePresetScanabilityRegressionTest {

    private lateinit var mikuPhoto: Bitmap
    private val expectedPayload = "https://veilframe.app/miku-sample"
    private val zxingDecoder = ZxingQrDecoder()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val stream = javaClass.classLoader?.getResourceAsStream("miku_reference.png")
            ?: javaClass.getResourceAsStream("/miku_reference.png")
        assertNotNull("miku_reference.png must exist in test resources", stream)
        mikuPhoto = BitmapFactory.decodeStream(stream)
        assertNotNull("mikuPhoto must decode successfully", mikuPhoto)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Phase 0 Step 0_1 - Baseline failure recorded for transparent light modules`() = runBlocking {
        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)

        // Baseline configuration: cyan 0xFF39C5BC, transparent light modules, 35% scale
        val baselineDesign = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 512,
            quietZoneModules = 1,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(mikuPhoto)),
            imageDataScale = 0.35f,
            dataColorDark = 0xFF39C5BC.toInt(),
            dataColorLight = Color.TRANSPARENT,
            allowTransparent = true,
            positionDarkColor = 0xFF39C5BC.toInt(),
            positionLightColor = Color.WHITE
        )

        // 1. Geometry verification: cyan data nodes present, light nodes absent
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, baselineDesign)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, baselineDesign, geometry)
        val expectedDataSize = 0.35f * geometry.moduleSize
        val topDataModules = ir.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedDataSize) < 0.05f
        }
        assertTrue("Cyan dark modules must be emitted", topDataModules.isNotEmpty())
        for (node in topDataModules) {
            assertEquals("All data modules must be cyan", 0xFF39C5BC.toInt(), node.fill)
        }
        val transparentDataNodes = ir.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedDataSize) < 0.05f &&
            (Color.alpha(it.fill ?: 0) == 0 || it.fill == Color.TRANSPARENT)
        }
        assertEquals("Light modules are completely omitted (transparent)", 0, transparentDataNodes.size)

        // 2. SVG verification
        val svg = SvgExporter.generateSvg(matrix, baselineDesign)
        assertTrue("SVG contains hole mask", svg.contains("""<mask id="hole">"""))
        assertTrue("SVG contains cyan fill", svg.contains("#39C5BC") || svg.contains("#39c5bc"))

        // 3. Bitmap & Decode verification: Baseline fails decoding due to lack of light modules
        val renderResult = QrGenerator.generateBitmapResult(matrix, baselineDesign)
        assertTrue("Render must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
        assertNotNull("Bitmap must not be null", bitmap)

        val zxResult = zxingDecoder.decode(bitmap)
        assertFalse("Baseline with transparent light modules must FAIL decode in ZXing", zxResult.success)

        val report = ScanabilityValidator.validateStrict(bitmap, baselineDesign, matrix, expectedPayload)
        assertFalse("Baseline must fail strict scanability validation", report.isScanReady)
        assertFalse("Baseline decode must not match expected payload", report.decodeResult.success)
    }

    @Test
    fun `Phase 3 Step 5 and 6 - Fixed preset with white light modules across multi-resolution matrix`() = runBlocking {
        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val resolutions = listOf(256, 512, 1024, 2048)

        for (res in resolutions) {
            val fixedDesign = QrDesign(
                style = QrStyle.IMAGE,
                outputSize = res,
                quietZoneModules = 1,
                imageSource = ImageSourceStyle(source = ImageSource.Memory(mikuPhoto)),
                imageDataScale = 0.35f,
                dataColorDark = 0xFF39C5BC.toInt(),
                dataColorLight = Color.WHITE,
                allowTransparent = true,
                positionDarkColor = 0xFF39C5BC.toInt(),
                positionLightColor = Color.WHITE
            )

            val renderResult = QrGenerator.generateBitmapResult(matrix, fixedDesign)
            assertTrue("Render at ${res}px must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
            val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
            assertNotNull(bitmap)
            assertEquals(res, bitmap.width)
            assertEquals(res, bitmap.height)

            // Strict Scanability Validation
            val report = ScanabilityValidator.validateStrict(bitmap, fixedDesign, matrix, expectedPayload)
            val zxResult = zxingDecoder.decode(bitmap)

            System.err.println("Fixed Preset [${res}px]: zxSuccess=${zxResult.success}, zxText=${zxResult.text}, validatorScanReady=${report.isScanReady}, validatorDecode=${report.decodeResult.success}, separation=${report.contrast.separation}, findersIntact=${report.finders.findersIntact}, quietZone=${report.quietZone.quietZoneModules}, suggestions=${report.repairSuggestions}")

            // Record & verify decodability
            assertTrue("Fixed preset at ${res}px must be decodable by ZXing", zxResult.success || report.decodeResult.success)
            val decodedText = if (zxResult.success) zxResult.text else report.decodeResult.text
            assertEquals("Decoded payload at ${res}px must match expected", expectedPayload, decodedText)
            assertTrue("Quiet zone must be compliant at ${res}px", report.quietZone.quietZoneModules >= 1)
        }
    }
}
