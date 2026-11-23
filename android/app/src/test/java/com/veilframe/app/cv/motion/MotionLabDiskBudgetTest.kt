package com.veilframe.app.cv.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionLabDiskBudgetTest {
    @Test fun reserveIsNonNegotiableAndOverflowSafe() {
        val reserve = MotionLabDiskBudget.RESERVED_FREE_BYTES
        assertFalse(MotionLabDiskBudget.hasHeadroom(-1))
        assertFalse(MotionLabDiskBudget.hasHeadroom(0))
        assertFalse(MotionLabDiskBudget.hasHeadroom(reserve - 1))
        assertTrue(MotionLabDiskBudget.hasHeadroom(reserve))
        assertTrue(MotionLabDiskBudget.hasHeadroom(Long.MAX_VALUE))
    }
}
