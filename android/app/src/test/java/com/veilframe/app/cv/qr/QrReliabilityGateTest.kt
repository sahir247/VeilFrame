package com.veilframe.app.cv.qr

import com.veilframe.app.cv.qr.QrReliabilityGate.ConditionOutcome
import com.veilframe.app.cv.qr.QrReliabilityGate.Level
import com.veilframe.app.cv.qr.QrStressMatrix.StressCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM policy tests for the QR release gate.
 *
 * These assert the GRADED verdict model: WeChatQRCode is authoritative,
 * ML Kit fallback can only ever produce fallback coverage, and the pristine
 * render failing is a hard FAIL.
 */
class QrReliabilityGateTest {

    private fun wechatPass(condition: StressCondition) =
        ConditionOutcome(condition, QrDecodeSource.WECHAT, true)

    private fun mlKitPass(condition: StressCondition) =
        ConditionOutcome(condition, QrDecodeSource.ML_KIT, true)

    private fun fail(condition: StressCondition) =
        ConditionOutcome(condition, null, false)

    private val allConditions = StressCondition.entries.toList()

    @Test
    fun `all stress conditions decoded by WeChat is PASS and release-approved`() {
        val outcomes = allConditions.map { wechatPass(it) }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertEquals(Level.PASS, report.level)
        assertTrue(report.releaseApproved)
        assertEquals("${allConditions.size}/${allConditions.size}", report.wechatCoverage)
        assertEquals(1.0, report.wechatMatchRate, 1e-9)
    }

    @Test
    fun `single ML Kit fallback with WeChat otherwise perfect is PASS_WITH_MARGIN`() {
        val outcomes = allConditions.mapIndexed { index, condition ->
            if (index == 1) mlKitPass(condition) else wechatPass(condition)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertEquals(Level.PASS_WITH_MARGIN, report.level)
        assertTrue(report.releaseApproved)
        assertTrue(report.reasons.any { it.contains("ML Kit fallback") })
    }

    @Test
    fun `two fallback-only conditions are not PASS_WITH_MARGIN`() {
        val outcomes = allConditions.mapIndexed { index, condition ->
            if (index in setOf(1, 2)) mlKitPass(condition) else wechatPass(condition)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertEquals(Level.WEAK, report.level)
        assertFalse(report.releaseApproved)
    }

    @Test
    fun `low WeChat rate with good fallback coverage is WEAK not PASS`() {
        // WeChat only decodes the pristine + easy conditions; ML Kit saves most others.
        val outcomes = allConditions.mapIndexed { index, condition ->
            when {
                index < 3 -> wechatPass(condition)
                index < allConditions.size - 1 -> mlKitPass(condition)
                else -> fail(condition)
            }
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertEquals(Level.WEAK, report.level)
        assertFalse("ML Kit fallback must not upgrade WEAK to PASS", report.releaseApproved)
    }

    @Test
    fun `pristine render failing is a hard FAIL regardless of other conditions`() {
        val outcomes = allConditions.map { condition ->
            if (condition == StressCondition.ORIGINAL) fail(condition) else wechatPass(condition)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertEquals(Level.FAIL, report.level)
        assertFalse(report.releaseApproved)
        assertTrue(report.reasons.first().contains("Pristine render failed"))
    }

    @Test
    fun `pristine render passing only via ML Kit is flagged but not a hard fail`() {
        val outcomes = allConditions.map { condition ->
            if (condition == StressCondition.ORIGINAL) mlKitPass(condition) else wechatPass(condition)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertTrue(report.releaseApproved)
        assertTrue(report.reasons.any { it.contains("not WeChatQRCode") })
    }

    @Test
    fun `majority undecodable is FAIL`() {
        val outcomes = allConditions.map { condition ->
            if (isEasyCondition(condition)) wechatPass(condition) else fail(condition)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")

        assertEquals(Level.FAIL, report.level)
        assertFalse(report.releaseApproved)
    }

    private fun isEasyCondition(condition: StressCondition): Boolean =
        condition == StressCondition.ORIGINAL || condition == StressCondition.SCALE_75

    @Test
    fun `wrong payload never counts as a match`() {
        val outcomes = allConditions.map { condition ->
            ConditionOutcome(condition, QrDecodeSource.WECHAT, false)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "expected")

        assertEquals(Level.FAIL, report.level)
        assertEquals(0.0, report.totalMatchRate, 1e-9)
    }

    @Test
    fun `QrDecodeReport distinguishes clean no-QR from engine failure`() {
        val cleanNoQr = QrDecodeReport(emptyList(), null)
        assertTrue(cleanNoQr.engineHealthy)
        assertTrue(cleanNoQr.detections.isEmpty())

        val engineFault = QrDecodeReport(emptyList(), IllegalStateException("WeChat QR engine unavailable"))
        assertFalse(engineFault.engineHealthy)
        assertEquals("WeChat QR engine unavailable", engineFault.engineError?.message)
    }

    @Test
    fun `uninstalled WeChatQrEngine reports unavailable status`() {
        WeChatQrEngine.resetForTests()
        val engine = WeChatQrEngine.get()
        assertFalse(engine.isAvailable)
        assertEquals(WeChatQrEngine.Availability.UNAVAILABLE, engine.availability)
    }
}
