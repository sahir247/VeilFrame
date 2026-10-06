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

    @Test
    fun `reservation atomically tracks reserved bytes and restores budget on close`() {
        val memory = CvMemoryManager(
            ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25, // budget = 512 MB
        )
        val initialBudget = memory.availableBudget()
        assertEquals(512L * 1024 * 1024, initialBudget)

        val resResult = memory.reserve(200L * 1024 * 1024)
        assertTrue(resResult is ReservationResult.Granted)
        val granted = resResult as ReservationResult.Granted
        assertEquals(200L * 1024 * 1024, granted.reservation.reservedBytes)
        assertEquals(200L * 1024 * 1024, memory.currentReservedBytes)
        assertEquals(312L * 1024 * 1024, memory.availableBudget())

        // Closing the reservation releases the reserved bytes
        granted.reservation.close()
        assertTrue(granted.reservation.isClosed)
        assertEquals(0L, memory.currentReservedBytes)
        assertEquals(initialBudget, memory.availableBudget())
    }

    @Test
    fun `concurrent reservation rejects second job when combined cost exceeds budget`() {
        val memory = CvMemoryManager(
            ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25, // budget = 512 MB
        )
        // Job 1 reserves 300 MB out of 512 MB -> succeeds
        val job1 = memory.reserve(300L * 1024 * 1024)
        assertTrue(job1 is ReservationResult.Granted)
        val granted1 = job1 as ReservationResult.Granted
        assertEquals(212L * 1024 * 1024, memory.availableBudget())

        // Job 2 requests 300 MB -> exceeds remaining 212 MB -> rejected
        val job2 = memory.reserve(300L * 1024 * 1024)
        assertTrue(job2 is ReservationResult.Rejected)
        val rejected = job2 as ReservationResult.Rejected
        assertEquals(212L * 1024 * 1024, rejected.budgetBytes)
        assertEquals(300L * 1024 * 1024, rejected.costBytes)

        // Job 1 finishes and releases
        granted1.reservation.close()
        assertEquals(512L * 1024 * 1024, memory.availableBudget())

        // Now Job 2 can be reserved
        val retryJob2 = memory.reserve(300L * 1024 * 1024)
        assertTrue(retryJob2 is ReservationResult.Granted)
        (retryJob2 as ReservationResult.Granted).reservation.close()
    }

    @Test
    fun `double close on reservation is idempotent`() {
        val memory = CvMemoryManager(
            ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25,
        )
        val res = memory.reserve(100L * 1024 * 1024) as ReservationResult.Granted
        assertEquals(100L * 1024 * 1024, memory.currentReservedBytes)

        res.reservation.close()
        assertEquals(0L, memory.currentReservedBytes)

        // Second close must not subtract again into negative numbers
        res.reservation.close()
        assertEquals(0L, memory.currentReservedBytes)
    }

    @Test
    fun `engine submit rejects job when concurrent in-flight reservation exhausts budget`() {
        val memory = CvMemoryManager(
            ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25, // 512 MB budget
        )
        val engine = CvEngine(memory)

        // First job reserves 400 MB
        val job1 = engine.submit("job1", CvPriority.INTERACTIVE, 400L * 1024 * 1024) {
            Thread.sleep(80)
            "done1"
        }

        // Second parallel job tries to reserve 200 MB (400 + 200 > 512 MB) -> rejected immediately
        val job2 = engine.submit("job2", CvPriority.INTERACTIVE, 200L * 1024 * 1024) {
            "done2"
        }

        assertEquals(CvJobState.FAILED, job2.state)
        val result2 = job2.tryGetNow()
        assertTrue(result2 is CvResult.Err)
        val err = result2 as CvResult.Err
        assertEquals(CvErrorCode.OUT_OF_MEMORY, err.code)
        assertTrue(err.message.contains("exceeds unreserved budget"))

        engine.shutdown()
    }
}
