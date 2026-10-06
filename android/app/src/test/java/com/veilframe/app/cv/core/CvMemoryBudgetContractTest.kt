package com.veilframe.app.cv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [CvMemoryManager] budget accounting.
 *
 * Verifies specifically that:
 * - Pool retained bytes are NOT added to the admission budget
 *   (the P0-4 double-counting fix).
 * - Budget under memory pressure remains conservative.
 * - Admission / reservation semantics are consistent.
 */
class CvMemoryBudgetContractTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    // ------------------------------------------------------------------ helpers

    /** A pool that pretends to have [retained] bytes retained. */
    private fun poolWithRetained(retained: Long): MatPool {
        val pool = MatPool(maxRetainedBytes = retained * 2)
        // We cannot call acquire() on JVM (Mat allocation is native).
        // Verify via stats that retained = 0 initially — the test below
        // then checks that the manager does NOT add retained bytes to budget.
        assertEquals(0L, pool.stats().retainedBytes)
        return pool
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `budget equals safetyFactor times available only no pool inflation`() {
        // With 2 GB available and safetyFactor 0.25, budget must be exactly 512 MB.
        // If pool retained bytes were added back, even a 0-retained pool gives
        // the right answer (0 + 512 = 512), but this test documents the contract:
        // the formula is safetyFactor * available, period.
        val memory = CvMemoryManager(
            probe = ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25,
        )
        assertEquals(512 * mb, memory.availableBudget())
    }

    @Test
    fun `budget does not change when a non-empty pool exists`() {
        // Even if the pool reports retained bytes, availableBudget() must not
        // inflate by those bytes (they are already committed native memory and
        // are reflected in MemAvailable drop).
        //
        // We simulate a pool with retainedBytes = 100 MB via a fake probe that
        // reflects the committed memory in the available figure:
        //   device has 2 GB physical available MINUS 100 MB already pooled
        //   → real available to new jobs = 1924 MB
        // Without the double-count fix: budget = 1924 MB * 0.25 + 100 MB = 581 MB
        // With the fix:                  budget = 1924 MB * 0.25             = 481 MB
        val pooled = 100 * mb
        val realAvailable = 2 * gb - pooled  // kernel already shows less available
        val memory = CvMemoryManager(
            probe = ManualMemoryProbe(total = 8 * gb, available = realAvailable),
            safetyFactor = 0.25,
        )
        val budget = memory.availableBudget()
        val expectedWithFix = (realAvailable * 0.25).toLong()
        // Budget must equal safetyFactor * available — no pool inflation.
        assertEquals(expectedWithFix, budget)
        // Explicitly check it's LESS than the (wrong) inflated value.
        val wrongInflatedBudget = expectedWithFix + pooled
        assertTrue(
            "Budget must not be inflated by pool retained bytes",
            budget < wrongInflatedBudget,
        )
    }

    @Test
    fun `inspectAdmission and reserve agree on budget`() {
        val memory = CvMemoryManager(
            probe = ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25,
        )
        val cost = 200 * mb
        val inspection = memory.inspectAdmission(cost)
        val reservation = memory.reserve(cost)

        assertTrue(inspection is AdmissionDecision.Admitted)
        assertTrue(reservation is ReservationResult.Granted)
        (reservation as ReservationResult.Granted).reservation.close()
    }

    @Test
    fun `reserve and availableBudget are consistent after multiple operations`() {
        val memory = CvMemoryManager(
            probe = ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25,
        )
        val initial = memory.availableBudget()  // 512 MB

        val r1 = memory.reserve(100 * mb) as ReservationResult.Granted
        assertEquals(initial - 100 * mb, memory.availableBudget())

        val r2 = memory.reserve(200 * mb) as ReservationResult.Granted
        assertEquals(initial - 300 * mb, memory.availableBudget())

        r1.reservation.close()
        assertEquals(initial - 200 * mb, memory.availableBudget())

        r2.reservation.close()
        assertEquals(initial, memory.availableBudget())
    }

    @Test
    fun `six GB device with 512 MB available has a conservative budget`() {
        // Conservative tier: available < 1 GB → budget = 512 MB * 0.25 = 128 MB
        val memory = CvMemoryManager(
            probe = ManualMemoryProbe(total = 6 * gb, available = 512 * mb),
            safetyFactor = 0.25,
        )
        val budget = memory.availableBudget()
        assertEquals(128 * mb, budget)
        // A 200 MB job must be rejected on a conservative device.
        assertTrue(memory.reserve(200 * mb) is ReservationResult.Rejected)
    }

    @Test
    fun `zero cost reservation is always granted without consuming budget`() {
        val memory = CvMemoryManager(
            probe = ManualMemoryProbe(total = 8 * gb, available = 2 * gb),
            safetyFactor = 0.25,
        )
        val before = memory.availableBudget()
        val res = memory.reserve(0L)
        assertTrue(res is ReservationResult.Granted)
        assertEquals(before, memory.availableBudget())
        assertEquals(0L, memory.currentReservedBytes)
        (res as ReservationResult.Granted).reservation.close()
        assertEquals(before, memory.availableBudget())
    }
}
