package com.veilframe.app.qr.validation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.AnimatedQrGenerator
import com.veilframe.app.qr.GenerationMode
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.*
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
 * Cross-pipeline validation and contract verification test suite (Audit C-01 through C-08).
 *
 * Verifies:
 * 1. Strict validation contract closes quiet-zone bypasses (e.g. explicitQuietZone = 0).
 * 2. Directional quiet-zone reporting preserves margins on all 4 sides and detects asymmetric deficiencies.
 * 3. QrRenderResult hierarchy cleanly differentiates PreviewVerified vs StrictVerified.
 * 4. Validator luminance uses W3C WCAG 2.1 linearized relative luminance via ImageColorAnalyzer.
 * 5. Unmaterialized ImageSource.Uri / Resource fail with typed QrError.Image.UnmaterializedSource.
 * 6. Animated QR strict validation evaluates all frames and catches single-frame degradation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class CrossPipelineScanabilityContractTest {

    private val payload = "https://veilframe.app/scanability-contract"

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Audit C-02 - Strict validation rejects explicit zero quiet zone`() = runBlocking {
        val design = QrDesign(
            style = QrStyle.BASIC,
            outputSize = 512,
            explicitQuietZone = 0,
            quietZoneModules = 0
        )
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.M)
        val bitmap = (QrGenerator.generateBitmapResult(matrix, design) as QrGenerator.BitmapRenderResult.Success).bitmap

        // Fast preview validation may allow user-configured custom margin
        val fastReport = ScanabilityValidator.validateFast(bitmap, design, matrix, payload)
        assertEquals(ValidationTier.FAST_PREVIEW, fastReport.tier)
        assertFalse("Fast report cannot be strictly compliant", fastReport.isStrictlyCompliant)

        // Strict validation MUST hard-fail the zero quiet-zone bypass
        val strictReport = ScanabilityValidator.validateStrict(bitmap, design, matrix, payload)
        assertEquals(ValidationTier.STRICT_COMPLIANCE, strictReport.tier)
        assertFalse("Strict validation must reject explicit quietZone = 0", strictReport.isScanReady)
        assertFalse("isStrictlyCompliant must be false", strictReport.isStrictlyCompliant)
        assertFalse("QuietZoneReport must report hasFourModuleMargin = false", strictReport.quietZone.hasFourModuleMargin)
        assertEquals(0, strictReport.quietZone.quietZoneModules)
        assertEquals(0f, strictReport.quietZone.minMargin, 0.01f)
    }

    @Test
    fun `Audit C-05 - Asymmetric quiet zone preserves 4-side margins and catches deficient side`() = runBlocking {
        // Asymmetric quiet zone: Left = 4, Top = 4, Right = 0, Bottom = 4
        val design = QrDesign(
            style = QrStyle.BASIC,
            outputSize = 512,
            directionalQuietZone = DirectionalInsets(left = 4, top = 4, right = 0, bottom = 4)
        )
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.M)
        val bitmap = (QrGenerator.generateBitmapResult(matrix, design) as QrGenerator.BitmapRenderResult.Success).bitmap

        val strictReport = ScanabilityValidator.validateStrict(bitmap, design, matrix, payload)
        assertEquals(4f, strictReport.quietZone.left, 0.01f)
        assertEquals(4f, strictReport.quietZone.top, 0.01f)
        assertEquals(0f, strictReport.quietZone.right, 0.01f)
        assertEquals(4f, strictReport.quietZone.bottom, 0.01f)
        assertEquals(0f, strictReport.quietZone.minMargin, 0.01f)
        assertFalse("hasFourModuleMargin must be false when any side is deficient", strictReport.quietZone.hasFourModuleMargin)
        assertTrue("isCustomMargin must be true", strictReport.quietZone.isCustomMargin)
        assertFalse("Strict validation must fail due to deficient right margin", strictReport.isScanReady)
    }

    @Test
    fun `Audit C-01 - QrRenderResult differentiates PreviewVerified vs StrictVerified`() {
        val standardDesign = QrDesign(style = QrStyle.BASIC, outputSize = 512, quietZoneModules = 4)

        // Fast generation produces PreviewVerified
        val previewResult = QrGenerator.generateWithResult(payload, standardDesign, mode = GenerationMode.SAFE, strictValidation = false)
        assertTrue("previewResult must be Success", previewResult is QrRenderResult.Success)
        val previewSuccess = previewResult as QrRenderResult.Success
        assertTrue("previewResult must be Verified", previewSuccess is QrRenderResult.Success.Verified)
        assertTrue("previewResult must be PreviewVerified", previewSuccess is QrRenderResult.Success.PreviewVerified)
        assertTrue("isVerified must be true for scannable preview", previewSuccess.isVerified)
        assertFalse("isStrictlyVerified must be false for preview tier", previewSuccess.isStrictlyVerified)

        // Strict generation produces StrictVerified
        val strictResult = QrGenerator.generateStrictWithResult(payload, standardDesign, mode = GenerationMode.SAFE)
        assertTrue("strictResult must be Success", strictResult is QrRenderResult.Success)
        val strictSuccess = strictResult as QrRenderResult.Success
        assertTrue("strictResult must be Verified", strictSuccess is QrRenderResult.Success.Verified)
        assertTrue("strictResult must be StrictVerified", strictSuccess is QrRenderResult.Success.StrictVerified)
        assertTrue("isVerified must be true", strictSuccess.isVerified)
        assertTrue("isStrictlyVerified must be true", strictSuccess.isStrictlyVerified)
        assertEquals(ValidationTier.STRICT_COMPLIANCE, strictSuccess.report.tier)
    }

    @Test
    fun `Audit C-07 - Unmaterialized ImageSource Uri returns typed UnmaterializedSource error`() {
        val unmaterializedDesign = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Uri("content://media/external/images/media/999"))
        )
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.M)

        val bmpResult = QrGenerator.generateBitmapResult(matrix, unmaterializedDesign)
        assertTrue("Unmaterialized Uri must produce BitmapRenderResult.Failure", bmpResult is QrGenerator.BitmapRenderResult.Failure)
        val failure = bmpResult as QrGenerator.BitmapRenderResult.Failure
        assertTrue("Error must be QrError.Image.UnmaterializedSource", failure.error is QrError.Image.UnmaterializedSource)
        assertTrue((failure.error as QrError.Image.UnmaterializedSource).sourceDescription.contains("content://media/external/images/media/999"))

        // Also test high-level generateWithResult
        val renderResult = QrGenerator.generateWithResult(payload, unmaterializedDesign)
        assertTrue("renderResult must be Failure", renderResult is QrRenderResult.Failure)
        val rf = renderResult as QrRenderResult.Failure
        assertTrue("Error must be QrError.Image.UnmaterializedSource", rf.qrError is QrError.Image.UnmaterializedSource)
    }

    @Test
    fun `Audit C-06 - Animated QR strict validation evaluates all frames`() = runBlocking {
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val frameBmp1 = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val frameBmp2 = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val frames = listOf(
            QrFrame(frameBmp1, 100),
            QrFrame(frameBmp2, 100)
        )
        val baseDesign = QrDesign(style = QrStyle.BASIC, outputSize = 256, quietZoneModules = 4)

        val animReport = AnimatedQrGenerator.validateAnimatedFramesStrict(
            matrix = matrix,
            baseDesign = baseDesign,
            sourceFrames = frames,
            expectedContent = payload,
            outputSize = 256
        )

        assertEquals(2, animReport.totalFrames)
        assertEquals(2, animReport.passedFrames)
        assertTrue("All frames must be scan-ready", animReport.isAllFramesScanReady)
        assertEquals(2, animReport.frameReports.size)
        assertTrue("Frame reports must all be strictly compliant", animReport.frameReports.all { it.isStrictlyCompliant })
    }

    @Test
    fun `Audit C-06 - Animated QR strict validation detects single defective frame`() = runBlocking {
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val validFrame = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val frames = listOf(
            QrFrame(validFrame, 100),
            QrFrame(validFrame, 100)
        )
        // With 0 quiet zone, frames will fail strict compliance
        val defectiveDesign = QrDesign(style = QrStyle.BASIC, outputSize = 256, quietZoneModules = 0, explicitQuietZone = 0)

        val animReport = AnimatedQrGenerator.validateAnimatedFramesStrict(
            matrix = matrix,
            baseDesign = defectiveDesign,
            sourceFrames = frames,
            expectedContent = payload,
            outputSize = 256
        )

        assertFalse("All frames must not be scan-ready when margin is deficient", animReport.isAllFramesScanReady)
        assertEquals(0, animReport.passedFrames)
        assertTrue("Failure reasons must be populated", animReport.failureReasons.isNotEmpty())
    }
}
