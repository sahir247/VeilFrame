package com.veilframe.app.cv.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the stress-suite contract (the part that doesn't need natives):
 * the matrix must stay complete and stable — it is a release gate.
 */
class QrStressMatrixContractTest {

    @Test
    fun `matrix covers every required condition from the CV plan`() {
        val expected = listOf(
            "Original", "75%", "50%", "25%", "Downscaled", "JPEG Q70",
            "Gaussian blur", "Contrast changed", "Brightness changed",
            "Rotation", "Perspective", "Noisy",
        )
        assertEquals(expected, QrStressMatrix.ALL.map { it.label })
    }

    @Test
    fun `matrix order is stable and starts with the pristine render`() {
        assertEquals(QrStressMatrix.StressCondition.ORIGINAL, QrStressMatrix.ALL.first())
        assertEquals(QrStressMatrix.StressCondition.entries.size, QrStressMatrix.ALL.size)
    }

    @Test
    fun `gate formatting reports every condition and the verdict block`() {
        val outcomes = QrStressMatrix.ALL.map {
            QrReliabilityGate.ConditionOutcome(it, QrDecodeSource.WECHAT, true)
        }
        val report = QrReliabilityGate.evaluate(outcomes, "payload")
        val text = QrReliabilityGate.format(report)

        QrStressMatrix.ALL.forEach { condition ->
            assertTrue("missing ${condition.label}", text.contains(condition.label))
        }
        assertTrue(text.contains("LEVEL"))
        assertTrue(text.contains("PASS"))
        assertTrue(text.contains("RELEASE"))
        assertTrue(text.contains("APPROVED"))
    }

    @Test
    fun `conditions are uniquely labelled`() {
        val labels = QrStressMatrix.ALL.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
