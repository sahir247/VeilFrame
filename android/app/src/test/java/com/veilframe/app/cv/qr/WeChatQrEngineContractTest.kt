package com.veilframe.app.cv.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for [WeChatQrEngine] and [QrDecodeReport].
 * Asserts fail-closed behavior when native engine is unavailable in JVM test environment,
 * preventing silent suppression of engine errors.
 */
class WeChatQrEngineContractTest {

    @Before
    fun setUp() {
        WeChatQrEngine.resetForTests()
    }

    @Test
    fun `uninstalled engine reports UNAVAILABLE and not available`() {
        val engine = WeChatQrEngine.get()
        assertFalse("Engine must not report available when uninstalled", engine.isAvailable)
        assertEquals(WeChatQrEngine.Availability.UNAVAILABLE, engine.availability)
    }

    @Test
    fun `decodeReport accurately reflects healthy versus failed decode states`() {
        val healthyReport = QrDecodeReport(
            detections = listOf(
                QrCvDetection(
                    rawValue = "https://veilframe.local",
                    corners = null,
                    source = QrDecodeSource.WECHAT,
                ),
            ),
            engineError = null,
        )
        assertTrue(healthyReport.engineHealthy)
        assertEquals(1, healthyReport.detections.size)
        assertNull(healthyReport.engineError)

        val failedReport = QrDecodeReport(
            detections = emptyList(),
            engineError = IllegalStateException("Native model load failure"),
        )
        assertFalse(failedReport.engineHealthy)
        assertTrue(failedReport.detections.isEmpty())
        assertEquals("Native model load failure", failedReport.engineError?.message)
    }

    @Test
    fun `EXPECTED_MODEL_HASHES covers all four WeChat QR models with valid SHA256 format`() {
        val requiredModels = setOf(
            "detect.caffemodel",
            "detect.prototxt",
            "sr.caffemodel",
            "sr.prototxt",
        )
        assertEquals(requiredModels, WeChatQrEngine.EXPECTED_MODEL_HASHES.keys)
        for ((model, hash) in WeChatQrEngine.EXPECTED_MODEL_HASHES) {
            assertEquals("Hash for $model must be 64-char hex", 64, hash.length)
            assertTrue("Hash for $model must be lowercase hex", hash.matches(Regex("^[0-9a-f]{64}$")))
        }
    }

    @Test
    fun `sha256Of correctly hashes test file`() {
        val temp = java.io.File.createTempFile("test_sha", ".txt")
        try {
            temp.writeText("hello world\n")
            val hash = WeChatQrEngine.sha256Of(temp)
            assertNotNull(hash)
            assertEquals(64, hash?.length)
        } finally {
            temp.delete()
        }
    }
}
