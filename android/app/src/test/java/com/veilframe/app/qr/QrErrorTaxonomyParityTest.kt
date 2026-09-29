package com.veilframe.app.qr

import com.google.zxing.WriterException
import com.veilframe.app.qr.encoder.engine.QRCodeType
import com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrOutputFormat
import com.veilframe.app.qr.model.QrOutputResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Validates VeilFrame's EFQRCode-grade typed error boundary and output format pipeline.
 *
 * Confirms:
 * 1. Complete domain taxonomy coverage across Input, Encoding, Design, Image, Rendering,
 *    Animation, Output, Platform, and Internal.
 * 2. Typed exception translation layer converting ZXing, OOM, I/O, and platform exceptions
 *    into structured [QrError] without collapsing into opaque strings.
 * 3. 100% backwards compatibility of [QrRenderResult.Failure] preserving both legacy .error
 *    string access and typed .qrError domain inspection.
 * 4. Output format abstraction ([QrOutputFormat]) and result boundary ([QrOutputResult]).
 * 5. Architectural independence between fatal errors ([QrError]) and diagnostic validation ([com.veilframe.app.qr.validation.ScanabilityReport]).
 */
class QrErrorTaxonomyParityTest {

    @Test
    fun testCompleteTaxonomyHierarchy() {
        val inputErr: QrError = QrError.Input.EmptyContent
        assertEquals("QR content must not be blank", inputErr.description)

        val invalidPayload: QrError = QrError.Input.InvalidPayload("Malformed WiFi SSID")
        assertTrue(invalidPayload.description.contains("WiFi SSID"))

        val capacityErr: QrError = QrError.Encoding.CapacityExceeded(actualBytes = 3500, limitBytes = 2953)
        assertTrue(capacityErr.description.contains("3500 bytes"))
        assertTrue(capacityErr.description.contains("2953 bytes"))
        assertEquals(3500, (capacityErr as QrError.Encoding.CapacityExceeded).actualBytes)
        assertEquals(2953, capacityErr.limitBytes)

        val oomErr: QrError = QrError.Rendering.BitmapAllocationFailed(width = 4096, height = 4096)
        assertTrue(oomErr.description.contains("4096x4096"))

        val videoErr: QrError = QrError.Output.VideoEncodingFailed(
            stage = AnimatedQrGenerator.VideoStage.FFMPEG_EXECUTION.name,
            exitCode = 1
        )
        assertTrue(videoErr.description.contains("FFMPEG_EXECUTION"))
        assertTrue(videoErr.description.contains("exit code: 1"))

        val permErr: QrError = QrError.Platform.PermissionDenied("android.permission.WRITE_EXTERNAL_STORAGE")
        assertTrue(permErr.description.contains("WRITE_EXTERNAL_STORAGE"))
    }

    @Test
    fun testExceptionTranslationLayer() {
        // ZXing capacity exception
        val zxingEx = WriterException("Data length (3000 bytes) exceeds maximum capacity (2953 bytes)")
        val translatedZxing = QrError.fromThrowable(zxingEx)
        assertTrue(
            "ZXing WriterException with capacity text must map to CapacityExceeded",
            translatedZxing is QrError.Encoding.CapacityExceeded
        )
        assertEquals(3000, (translatedZxing as QrError.Encoding.CapacityExceeded).actualBytes)
        assertEquals(2953, translatedZxing.limitBytes)

        // OutOfMemoryError
        val oomEx = OutOfMemoryError("Failed to allocate 67108864 byte bitmap")
        val translatedOom = QrError.fromThrowable(oomEx)
        assertTrue("OOM must map to BitmapAllocationFailed", translatedOom is QrError.Rendering.BitmapAllocationFailed)

        // Blank content IllegalArgumentException
        val blankEx = IllegalArgumentException("QR content must not be blank")
        val translatedBlank = QrError.fromThrowable(blankEx)
        assertTrue("Blank argument must map to EmptyContent", translatedBlank is QrError.Input.EmptyContent)

        // Security exception
        val secEx = SecurityException("Permission denied to open MediaStore URI")
        val translatedSec = QrError.fromThrowable(secEx)
        assertTrue("SecurityException must map to PermissionDenied", translatedSec is QrError.Platform.PermissionDenied)

        // IO exception
        val ioEx = IOException("Disk full")
        val translatedIo = QrError.fromThrowable(ioEx)
        assertTrue("IOException must map to StorageFailed", translatedIo is QrError.Platform.StorageFailed)

        // Coroutine CancellationException must NOT be caught/swallowed
        var caughtCancellation = false
        try {
            QrError.fromThrowable(CancellationException("Scope cancelled"))
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("CancellationException must propagate out of translation layer", caughtCancellation)
    }

    @Test
    fun testQrRenderResultFailureBackwardsCompatibility() {
        // Constructed via new typed QrError
        val typedFailure = QrRenderResult.Failure(QrError.Input.EmptyContent)
        assertEquals("QR content must not be blank", typedFailure.error)
        assertEquals(QrError.Input.EmptyContent, typedFailure.qrError)
        assertNull(typedFailure.throwable)

        // Constructed via legacy string + throwable
        val rootCause = RuntimeException("Underlying driver failure")
        val legacyFailure = QrRenderResult.Failure("Custom legacy error message", rootCause)
        assertEquals("Custom legacy error message", legacyFailure.error)
        assertSame(rootCause, legacyFailure.throwable)
        assertTrue(legacyFailure.qrError is QrError.Internal)
        assertEquals("Custom legacy error message", legacyFailure.qrError.description)
        assertSame(rootCause, legacyFailure.qrError.cause)
    }

    @Test
    fun testQrGeneratorBlankContentReturnsTypedFailure() {
        val result = QrGenerator.generateWithResult("   ")
        assertTrue("Blank content must return Failure", result is QrRenderResult.Failure)
        val failure = result as QrRenderResult.Failure
        assertEquals("QR content must not be blank", failure.error)
        assertEquals(QrError.Input.EmptyContent, failure.qrError)
    }

    @Test
    fun testQrGeneratorGenerateMatrixBlankThrowsEmptyContent() {
        try {
            QrGenerator.generateMatrix("")
            fail("Blank content in generateMatrix must throw")
        } catch (e: Throwable) {
            assertTrue("Exception must be QrError.Input.EmptyContent", e is QrError.Input.EmptyContent)
        }
    }

    @Test
    fun testQRCodeTypeExceededThrowsCapacityExceeded() {
        try {
            // Error correction L (offset 0) max capacity is 2953 bytes
            QRCodeType.typeNumber(3000, VeilCorrectionLevel.L)
            fail("Exceeding capacity must throw")
        } catch (e: Throwable) {
            assertTrue("Exception must be QrError.Encoding.CapacityExceeded", e is QrError.Encoding.CapacityExceeded)
            val cap = e as QrError.Encoding.CapacityExceeded
            assertEquals(3000, cap.actualBytes)
            assertEquals(2953, cap.limitBytes)
        }
    }

    @Test
    fun testQrOutputFormatSpecifications() {
        val png = QrOutputFormat.Png
        assertEquals("image/png", png.mimeType)
        assertEquals("png", png.extension)

        val jpeg = QrOutputFormat.Jpeg(quality = 85)
        assertEquals("image/jpeg", jpeg.mimeType)
        assertEquals("jpg", jpeg.extension)
        assertEquals(85, jpeg.quality)

        val svg = QrOutputFormat.Svg
        assertEquals("image/svg+xml", svg.mimeType)
        assertEquals("svg", svg.extension)

        val gif = QrOutputFormat.Gif(loopCount = 0)
        assertEquals("image/gif", gif.mimeType)
        assertEquals("gif", gif.extension)
        assertEquals(0, gif.loopCount)

        val mp4 = QrOutputFormat.Video(fps = 30, isMov = false)
        assertEquals("video/mp4", mp4.mimeType)
        assertEquals("mp4", mp4.extension)
        assertEquals(30, mp4.fps)

        val mov = QrOutputFormat.Video(fps = 24, isMov = true)
        assertEquals("video/quicktime", mov.mimeType)
        assertEquals("mov", mov.extension)
    }

    @Test
    fun testQrOutputFormatValidationBounds() {
        try {
            QrOutputFormat.Jpeg(quality = 0)
            fail("JPEG quality 0 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("quality") == true)
        }

        try {
            QrOutputFormat.Jpeg(quality = 101)
            fail("JPEG quality 101 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("quality") == true)
        }

        try {
            QrOutputFormat.Video(fps = 0)
            fail("Video FPS 0 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("FPS") == true)
        }
    }

    @Test
    fun testAnimatedQrGeneratorEmptyFramesReturnsTypedFailure() {
        val result = AnimatedQrGenerator.encodeToVideoResult(
            renderedFrames = emptyList(),
            outputFile = File("nonexistent.mp4")
        )
        assertTrue("Empty frames must return Failure", result is QrOutputResult.Failure)
        val failure = result as QrOutputResult.Failure
        assertEquals(QrError.Animation.EmptyFrames, failure.error)
    }

    @Test
    fun testVideoStageValuesCompleteness() {
        val stages = AnimatedQrGenerator.VideoStage.values().map { it.name }
        assertTrue(stages.contains("VALIDATION"))
        assertTrue(stages.contains("FRAME_WRITE"))
        assertTrue(stages.contains("CONCAT_MANIFEST"))
        assertTrue(stages.contains("FFMPEG_EXECUTION"))
        assertTrue(stages.contains("FINALIZATION"))
    }

    @Test
    fun testScanabilityReportRemainsIndependentQualityModel() {
        // ScanabilityReport must remain an independent diagnostic model for visual auto-repair,
        // rather than being conflated with fatal generation exceptions.
        val report = com.veilframe.app.qr.validation.ScanabilityReport(
            isScanReady = false,
            validationSkipped = false,
            quietZone = com.veilframe.app.qr.validation.QuietZoneReport(hasFourModuleMargin = false, quietZoneModules = 1),
            contrast = com.veilframe.app.qr.validation.ContrastReport(0.1f, 0.2f, 0.5f, 0.1f, 0.8f, isContrastAdequate = false),
            finders = com.veilframe.app.qr.validation.FinderIntegrityReport(findersIntact = false, separatorsClear = true),
            logo = com.veilframe.app.qr.validation.LogoOcclusionReport(
                hasProtectedOverlap = false,
                affectedDataModules = 0,
                affectedDataFraction = 0f,
                isWithinErrorCorrectionCapacity = true
            ),
            decodeResult = com.veilframe.app.qr.decoder.DecodeResult(success = false, text = null, error = "Decode failed"),
            errorCorrection = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H,
            warnings = listOf("Quiet zone is less than 4 modules"),
            repairSuggestions = listOf(com.veilframe.app.qr.validation.RepairReason.RESTORE_QUIET_ZONE)
        )

        assertFalse(report.isScanReady)
        assertEquals(1, report.warnings.size)
        assertNotNull(report.repairSuggestions)
    }
}
