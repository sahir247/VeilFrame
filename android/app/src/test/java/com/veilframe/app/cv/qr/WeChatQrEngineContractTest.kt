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
}
