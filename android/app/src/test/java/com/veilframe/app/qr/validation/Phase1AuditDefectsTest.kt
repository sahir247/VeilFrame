package com.veilframe.app.qr.validation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.GenerationMode
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.decoder.DecodeResult
import com.veilframe.app.qr.decoder.ZxingQrDecoder
import com.veilframe.app.qr.encoder.engine.QRCodeModel
import com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel
import com.veilframe.app.qr.encoder.engine.VeilQrEncoder
import com.veilframe.app.qr.exporter.GifEncoder
import com.veilframe.app.qr.geometry.VeilIconPipeline
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.ui.QrStudioViewModel
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
 * Regression test suite covering Phase 1 audit defect resolutions:
 * 1. P0 ML Kit deadlock prevention via background executor & suspend generation
 * 2. P1 UTF-8 ECI mode in VeilQrEncoder / QRCodeModel
 * 3. P1 Strict generateSafe contract & quiet zone enforcement
 * 4. P1 Asymmetric quiet zone module units & D25 margin
 * 5. P1 AutoRepair H->Q preservation & quiet zone clear
 * 6. P1 Logo scaleFraction == 0 numerical safety
 * 7. P1 GIF disposal method 1 & alpha transparency preservation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class Phase1AuditDefectsTest {

    private val asciiPayload = "https://veilframe.app/verify"
    private val utf8Payload = "VeilFrame café こんにちは 🚀"
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = Application()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createDummyBitmap(w: Int = 32, h: Int = 32, color: Int = Color.BLACK): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    // 1. P1 Veil encoder UTF-8 ECI
    @Test
    fun testVeilEncoderUtf8EciForNonAsciiPayload() {
        // Non-ASCII payload with writeEci = true must emit ECI
        val nonAsciiEncoded = VeilQrEncoder.encode(utf8Payload, VeilCorrectionLevel.H, writeEci = true)
        assertTrue("Model for non-ASCII payload must have writeEci enabled", nonAsciiEncoded.model.writeEci)

        // Matrix must decode cleanly via ZXing with exact string match
        val size = 512
        val design = QrDesign()
        val geometry = QrGeometry.fromDesign(nonAsciiEncoded.matrix.size, size, size, design)
        val bitmap = QrGenerator.generateBitmap(nonAsciiEncoded.matrix, design, geometry)
        assertNotNull("Bitmap must render", bitmap)

        val zxing = ZxingQrDecoder()
        val decodeResult = runBlocking { zxing.decode(bitmap!!) }
        assertTrue("ZXing must decode UTF-8 payload with ECI: ${decodeResult.error}", decodeResult.success)
        assertEquals("Decoded text must match original UTF-8 payload", utf8Payload, decodeResult.text)

        // QrGenerator in ARTISTIC_ENGINE automatically enables writeEci for non-ASCII
        val artisticMatrix = QrGenerator.generateMatrix(utf8Payload, design, mode = GenerationMode.ARTISTIC_ENGINE)
        val artisticBitmap = QrGenerator.generateBitmap(artisticMatrix, design, QrGeometry.fromDesign(artisticMatrix.size, size, size, design))
        val artisticDecode = runBlocking { zxing.decode(artisticBitmap!!) }
        assertTrue("ZXing must decode ARTISTIC_ENGINE non-ASCII QR: ${artisticDecode.error}", artisticDecode.success)
        assertEquals("Decoded text must match original UTF-8 payload", utf8Payload, artisticDecode.text)

        // PARITY_EF mode disables ECI to preserve exact EFQRCode 7.0.3 / QRCodeSwift 2.3.1 oracle bits
        val parityMatrix = QrGenerator.generateMatrix(utf8Payload, design, mode = GenerationMode.PARITY_EF)
        assertNotNull("Parity matrix should generate", parityMatrix)

        // Pure ASCII payload or default encode must not emit ECI (maintaining 100% byte & oracle parity)
        val asciiEncoded = VeilQrEncoder.encode(asciiPayload, VeilCorrectionLevel.H)
        assertFalse("Model for pure ASCII must not emit ECI", asciiEncoded.model.writeEci)
    }

    // 2. P1 generateSafe contract
    @Test
    fun testGenerateSafeEnforcesQuietZoneAndStrictValidation() {
        // User passes aggressive design with 0 margin
        val zeroMarginDesign = QrDesign(
            quietZoneModules = 0,
            explicitQuietZone = 0,
            directionalQuietZone = DirectionalInsets(0f, 0f, 0f, 0f),
            backdropStyle = BackdropStyle(fractionalQuietZone = FractionalInsets(0f, 0f, 0f, 0f))
        )

        val result = QrGenerator.generateSafe(asciiPayload, zeroMarginDesign)
        assertTrue("generateSafe must succeed on standard payload: $result", result is QrRenderResult.Success)

        val success = result as QrRenderResult.Success
        // Contract guarantees:
        // 1. 4-module quiet zone forcibly enforced
        assertTrue("Design quietZoneModules must be at least 4", success.design.quietZoneModules >= 4)
        assertNull("directionalQuietZone must be cleared", success.design.directionalQuietZone)
        assertNull("fractionalQuietZone must be cleared", success.design.backdropStyle.fractionalQuietZone)
        assertTrue("QuietZoneReport must have 4 modules", success.report.quietZone.hasFourModuleMargin)
        assertTrue("Min margin must be >= 3.99f", success.report.quietZone.minMargin >= 3.99f)

        // 2. Strict compliance tier
        assertEquals(ValidationTier.STRICT_COMPLIANCE, success.report.tier)
        assertTrue("Must be strictly compliant", success.report.isStrictlyCompliant)
    }

    // 3. P1 Asymmetric quiet zone module units & D25 margin
    @Test
    fun testAsymmetricQuietZoneUsesModuleUnitsNotFractional() {
        val vm = QrStudioViewModel(app)
        vm.updateAsymmetricQuietZone(enabled = true, left = 4f, top = 6f, right = 4f, bottom = 8f)

        val state = vm.state.value
        assertTrue(state.useAsymmetricQuietZone)
        assertEquals(DirectionalInsets(4f, 6f, 4f, 8f), state.directionalQuietZone)
        assertNull("fractionalQuietZone must be null so it does not multiply by matrixSize", state.fractionalQuietZone)

        val currentDesign = vm.currentDesign()
        assertEquals(DirectionalInsets(4f, 6f, 4f, 8f), currentDesign.directionalQuietZone)
        assertNull(currentDesign.backdropStyle.fractionalQuietZone)

        // Resolve geometry for a 21x21 matrix
        val geom = QrGeometry.fromDesign(matrixSize = 21, outputWidth = 512, outputHeight = 512, design = currentDesign)
        assertEquals("Left quiet zone must be 4 modules, not 4*21", 4f, geom.quietZoneLeftFloat, 0.001f)
        assertEquals("Top quiet zone must be 6 modules, not 6*21", 6f, geom.quietZoneTopFloat, 0.001f)
        assertEquals("Right quiet zone must be 4 modules, not 4*21", 4f, geom.quietZoneRightFloat, 0.001f)
        assertEquals("Bottom quiet zone must be 8 modules, not 8*21", 8f, geom.quietZoneBottomFloat, 0.001f)

        // Verify D25 defaults to 0 modules to preserve EFQRCode 7.0.3 canonical 2n x 2n viewBox
        val d25Default = QrGeometry.resolveDefaultQuietZone(QrStyle.D25)
        assertEquals("D25 must default to 0 module margin to preserve EF 7.0.3 canonical viewBox", 0, d25Default)
        assertEquals(1, QrGeometry.resolveDefaultQuietZone(QrStyle.BASIC))
    }

    // 4. P1 AutoRepair H->Q preservation & quiet zone restoration
    @Test
    fun testAutoRepairPreservesErrorCorrectionLevelH() {
        val hDesign = QrDesign(
            correction = ErrorCorrectionChoice.H,
            quietZoneModules = 4
        )

        val mockReport = ScanabilityReport(
            isScanReady = false,
            quietZone = QuietZoneReport(hasFourModuleMargin = true, quietZoneModules = 4),
            contrast = ContrastReport(0f, 0f, 1f, 1f, 1f, isContrastAdequate = true),
            finders = FinderIntegrityReport(findersIntact = true, separatorsClear = true),
            logo = LogoOcclusionReport(hasProtectedOverlap = false, affectedDataModules = 0, affectedDataFraction = 0f, isWithinErrorCorrectionCapacity = true),
            decodeResult = DecodeResult(success = false, error = "Low module scale", decoderId = "MLKit"),
            errorCorrection = ErrorCorrectionLevel.H,
            warnings = listOf("Decoder failed"),
            repairSuggestions = listOf(RepairReason.ELEVATE_ERROR_CORRECTION, RepairReason.INCREASE_MODULE_SCALE)
        )

        val repairResult = AutoRepairEngine.repair(hDesign, mockReport, asciiPayload)
        // Error correction must remain H, never downgraded to Q
        assertEquals("Error correction must remain H", ErrorCorrectionChoice.H, repairResult.repairedDesign.correction)
        assertFalse("Changes must not claim downgrading to Q", repairResult.changesApplied.any { it.contains("Q") })
    }

    @Test
    fun testAutoRepairRestoresQuietZoneClearsDirectionalAndFractionalBypasses() {
        val badMarginDesign = QrDesign(
            quietZoneModules = 0,
            directionalQuietZone = DirectionalInsets(0f, 0f, 0f, 0f),
            backdropStyle = BackdropStyle(fractionalQuietZone = FractionalInsets(0f, 0f, 0f, 0f))
        )

        val mockReport = ScanabilityReport(
            isScanReady = false,
            quietZone = QuietZoneReport(hasFourModuleMargin = false, quietZoneModules = 0, minMargin = 0f),
            contrast = ContrastReport(0f, 0f, 1f, 1f, 1f, isContrastAdequate = true),
            finders = FinderIntegrityReport(findersIntact = true, separatorsClear = true),
            logo = LogoOcclusionReport(hasProtectedOverlap = false, affectedDataModules = 0, affectedDataFraction = 0f, isWithinErrorCorrectionCapacity = true),
            decodeResult = DecodeResult(success = false, error = "Quiet zone missing", decoderId = "MLKit"),
            errorCorrection = ErrorCorrectionLevel.M,
            warnings = listOf("Quiet zone is less than standard"),
            repairSuggestions = listOf(RepairReason.RESTORE_QUIET_ZONE)
        )

        val repairResult = AutoRepairEngine.repair(badMarginDesign, mockReport, asciiPayload)
        assertEquals(4, repairResult.repairedDesign.quietZoneModules)
        assertEquals(4, repairResult.repairedDesign.explicitQuietZone)
        assertNull("directionalQuietZone must be cleared", repairResult.repairedDesign.directionalQuietZone)
        assertNull("fractionalQuietZone must be cleared", repairResult.repairedDesign.backdropStyle.fractionalQuietZone)

        val geom = QrGeometry.fromDesign(matrixSize = 21, outputWidth = 512, outputHeight = 512, design = repairResult.repairedDesign)
        assertEquals(4f, geom.quietZoneLeftFloat, 0.001f)
        assertEquals(4f, geom.quietZoneTopFloat, 0.001f)
    }

    // 5. P1 Logo scaleFraction == 0 numerical safety
    @Test
    fun testLogoScaleZeroSafety() {
        val dummyLogo = createDummyBitmap(64, 64)
        val designWithZeroLogo = QrDesign(
            logo = LogoStyle(bitmap = dummyLogo, scaleFraction = 0.0f)
        )

        val geom = QrGeometry.fromDesign(matrixSize = 21, outputWidth = 512, outputHeight = 512, design = designWithZeroLogo)
        val logoRect = geom.computeLogoRect(0.0f)
        assertEquals(0f, logoRect.width(), 0.001f)
        assertEquals(0f, logoRect.height(), 0.001f)

        val nodes = mutableListOf<com.veilframe.app.qr.geometry.QrGeometryNode>()
        val defs = mutableListOf<String>()
        // Must return safely without division by zero or Infinity stroke width
        VeilIconPipeline.appendIconNodes(nodes, defs, designWithZeroLogo, 0f, 0f, 512f)
        assertTrue("No icon nodes should be added when scale is 0", nodes.isEmpty())
    }

    // 6. P1 GIF disposal method 1 & transparency preservation
    @Test
    fun testGifEncoderDisposalMethodAndTransparency() {
        val w = 16
        val h = 16
        // Frame 1: half transparent (0x00000000), half opaque red (0xFFFF0000)
        val frame1Pixels = IntArray(w * h) { i ->
            if (i < (w * h) / 2) 0x00000000 else 0xFFFF0000.toInt()
        }
        // Frame 2: all opaque blue (0xFF0000FF)
        val frame2Pixels = IntArray(w * h) { 0xFF0000FF.toInt() }

        val bos = java.io.ByteArrayOutputStream()
        val encoder = GifEncoder()
        encoder.start(bos, w, h, loops = 0)
        encoder.addFrame(frame1Pixels, w, h, durationMs = 100)
        encoder.addFrame(frame2Pixels, w, h, durationMs = 100)
        encoder.finish()

        val gifBytes = bos.toByteArray()
        assertTrue(gifBytes.isNotEmpty())

        // Find Graphic Control Extension blocks (0x21, 0xF9)
        val gceIndices = mutableListOf<Int>()
        for (i in 0 until gifBytes.size - 4) {
            if (gifBytes[i] == 0x21.toByte() && gifBytes[i + 1] == 0xF9.toByte()) {
                gceIndices.add(i)
            }
        }
        assertEquals("Must have 2 GCE blocks for 2 frames", 2, gceIndices.size)

        // First frame had transparency:
        // Packed byte = Disposal 1 (0x04) or Transparent Flag (0x01) = 0x05
        val frame1Packed = gifBytes[gceIndices[0] + 3].toInt() and 0xFF
        val frame1Disposal = (frame1Packed ushr 2) and 0x07
        val frame1HasTrans = (frame1Packed and 0x01) == 0x01
        assertEquals("Disposal method for frame 1 must be 1 (do not dispose)", 1, frame1Disposal)
        assertTrue("Frame 1 must flag transparent color", frame1HasTrans)

        // Second frame was fully opaque:
        // Packed byte = Disposal 1 (0x04), no transparent flag
        val frame2Packed = gifBytes[gceIndices[1] + 3].toInt() and 0xFF
        val frame2Disposal = (frame2Packed ushr 2) and 0x07
        val frame2HasTrans = (frame2Packed and 0x01) == 0x01
        assertEquals("Disposal method for frame 2 must be 1 (do not dispose)", 1, frame2Disposal)
        assertFalse("Frame 2 must not flag transparent color", frame2HasTrans)
    }

    // 7. P0 Suspend generation executes without blocking
    @Test
    fun testSuspendGeneration() = runBlocking {
        val result = QrGenerator.generateWithResultSuspend(asciiPayload, QrDesign(), strictValidation = true)
        assertTrue("Suspend generation must produce success", result is QrRenderResult.Success)
        val success = result as QrRenderResult.Success
        assertNotNull(success.bitmap)
        assertTrue(success.report.isStrictlyCompliant)
    }
}
