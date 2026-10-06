package com.veilframe.app.cv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM policy tests for the CV memory architecture: size classes, resolution
 * tiers and admission control. No Mat allocation happens in these tests.
 */
class CvMemoryPolicyTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `byte classes are powers of two and clamp at bounds`() {
        assertEquals(SizeClass.MIN_BYTE_CLASS, SizeClass.byteClassFor(0))
        assertEquals(SizeClass.MIN_BYTE_CLASS, SizeClass.byteClassFor(64 * 1024))
        assertEquals(128 * 1024, SizeClass.byteClassFor(64 * 1024 + 1))
        assertEquals(SizeClass.MAX_BYTE_CLASS, SizeClass.byteClassFor(SizeClass.MAX_BYTE_CLASS.toLong() * 4))
    }

    @Test
    fun `six GB conservative tier stays at 1280 working resolution`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 6 * gb, available = 512L * 1024 * 1024))
        assertEquals(MemoryTier.CONSERVATIVE, memory.tier())
        val tier = memory.workingResolution(6000, 4000, ResolutionPurpose.STANDARD)
        assertEquals(ResolutionTier.WORKING_1280, tier)
    }

    @Test
    fun `eight GB normal tier uses 1080p for standard work`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 8 * gb, available = 2 * gb))
        assertEquals(MemoryTier.NORMAL, memory.tier())
        assertEquals(ResolutionTier.NORMAL, memory.workingResolution(6000, 4000, ResolutionPurpose.STANDARD))
    }

    @Test
    fun `sixteen GB high tier keeps more detail`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 16 * gb, available = 8 * gb))
        assertEquals(MemoryTier.HIGH, memory.tier())
        assertEquals(ResolutionTier.HIGH, memory.workingResolution(6000, 4000, ResolutionPurpose.STANDARD))
    }

    @Test
    fun `preview purpose never exceeds 720p`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 16 * gb, available = 8 * gb))
        assertEquals(ResolutionTier.PREVIEW, memory.workingResolution(4000, 3000, ResolutionPurpose.PREVIEW))
    }

    @Test
    fun `scaled size preserves aspect and never exceeds tier edge`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 6 * gb, available = 1 * gb))
        val (w, h) = memory.scaledSize(6000, 4000, ResolutionTier.WORKING_1280)
        assertEquals(1280, maxOf(w, h))
        assertTrue(w * 4000 == h * 6000 || kotlin.math.abs(w.toDouble() / h - 1.5) < 0.05)
    }

    @Test
    fun `admission rejects oversized jobs with a lower tier suggestion`() {
        val memory = CvMemoryManager(
            ManualMemoryProbe(total = 6 * gb, available = 512L * 1024 * 1024),
            safetyFactor = 0.25,
        )
        val decision = memory.admit(costBytes = 2L * 1024 * 1024 * 1024)
        assertTrue(decision is AdmissionDecision.Rejected)
        val rejected = decision as AdmissionDecision.Rejected
        assertTrue(rejected.budgetBytes < rejected.costBytes)
        assertEquals(ResolutionTier.TILED, rejected.suggestedTier)
    }

    @Test
    fun `admission accepts jobs within the safety budget`() {
        val memory = CvMemoryManager(
            ManualMemoryProbe(total = 16 * gb, available = 8 * gb),
            safetyFactor = 0.25,
        )
        assertTrue(memory.admit(costBytes = 512L * 1024 * 1024) is AdmissionDecision.Admitted)
    }

    @Test
    fun `memory estimate accounts for element size not just channels`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 8 * gb, available = 2 * gb))
        // 1920x1080 CV_32FC2 is 16.6MB per buffer, NOT the 4.1MB a
        // channels-only estimate would give.
        val flowBytes = memory.estimateBytes(1920, 1080, org.opencv.core.CvType.CV_32FC2, intermediateBuffers = 1)
        assertTrue(flowBytes > 30L * 1024 * 1024)
        val wrong = 1920L * 1080 * 2 // old channels-only formula
        assertTrue(flowBytes > wrong)
    }

    @Test
    fun `three channel 6000x4000 job cost estimate exceeds 96 MB`() {
        val memory = CvMemoryManager(ManualMemoryProbe(total = 8 * gb, available = 2 * gb))
        val estimate = memory.estimateBytes(6000, 4000, org.opencv.core.CvType.CV_8UC4, intermediateBuffers = 3)
        assertTrue(estimate > 96L * 1024 * 1024)
    }

    @Test
    fun `dispatcher config rejects an empty interactive lane`() {
        var threw = false
        try {
            DispatcherConfig(interactiveSlots = 0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(DispatcherConfig().interactiveSlots == 0)
    }
}
