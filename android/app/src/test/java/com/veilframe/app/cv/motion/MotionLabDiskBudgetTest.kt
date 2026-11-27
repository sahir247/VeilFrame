package com.veilframe.app.cv.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class MotionLabDiskBudgetTest {
    @Test fun monitorCancelsLongRunningWorkWhenReserveDisappears() = runTest {
        var checks = 0
        var retired = false
        try {
            MotionLabDiskBudget.monitor(check = {
                if (++checks == 2) error("storage exhausted")
            }, intervalMs = 10) {
                try { delay(1000) } finally { retired = true }
            }
            throw AssertionError("Expected storage failure")
        } catch (expected: IllegalStateException) {
            org.junit.Assert.assertEquals("storage exhausted", expected.message)
        }
        assertTrue(retired)
    }

    @Test fun monitorStopsAfterSuccessfulWork() = runTest {
        var checks = 0
        val value = MotionLabDiskBudget.monitor(check = { checks++ }, intervalMs = 10) { 42 }
        org.junit.Assert.assertEquals(42, value)
        org.junit.Assert.assertEquals(2, checks)
    }

    @Test fun reserveIsNonNegotiableAndOverflowSafe() {
        val reserve = MotionLabDiskBudget.RESERVED_FREE_BYTES
        assertFalse(MotionLabDiskBudget.hasHeadroom(-1))
        assertFalse(MotionLabDiskBudget.hasHeadroom(0))
        assertFalse(MotionLabDiskBudget.hasHeadroom(reserve - 1))
        assertTrue(MotionLabDiskBudget.hasHeadroom(reserve))
        assertTrue(MotionLabDiskBudget.hasHeadroom(Long.MAX_VALUE))
        assertFalse(MotionLabDiskBudget.hasHeadroom(reserve, 1))
        assertTrue(MotionLabDiskBudget.hasHeadroom(reserve + 1, 1))
        assertFalse(MotionLabDiskBudget.hasHeadroom(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
