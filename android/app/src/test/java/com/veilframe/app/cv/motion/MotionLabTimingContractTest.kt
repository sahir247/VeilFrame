package com.veilframe.app.cv.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MotionLabTimingContractTest {
    private fun pts(count: Int, periodUs: Long): List<Long> =
        (0 until count).map { it * periodUs }

    @Test fun twentyFpsToSixtyUsesThreeWayInterpolation() {
        val plan = MotionLabTimingContract.plan(pts(40, 50_000), 2_000, true, 2)
        assertEquals(3, plan.multiplier)
        assertEquals(60.0, plan.outputFps, 0.01)
        assertEquals(120, plan.expectedOutputFrames)
    }

    @Test fun fifteenFpsToSixtyUsesFourWayInterpolation() {
        val plan = MotionLabTimingContract.plan(pts(30, 66_667), 2_000, true, 2)
        assertEquals(4, plan.multiplier)
        assertEquals(120, plan.expectedOutputFrames)
    }

    @Test fun explicitTwoTimesUsesPresentationTimebase() {
        val plan = MotionLabTimingContract.plan(pts(90, 33_367), 3_003, false, 2)
        assertEquals(180, plan.expectedOutputFrames)
        assertEquals(59.94, plan.outputFps, 0.1)
    }

    @Test fun rejectsSixtySecondTruncation() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(40, 50_000), 60_001, false, 2)
        }
    }

    @Test fun rejectsVariableFrameRate() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(listOf(0, 33_333, 66_667, 166_667), 200, false, 2)
        }
    }

    @Test fun rejectsDuplicateOrInconsistentPresentationTimes() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(listOf(0, 0, 33_333), 100, false, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(30, 33_333), 20_000, false, 2)
        }
    }

    @Test fun incompatibleSourceCadenceMustNotBeCalledSixtyFps() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(48, 41_667), 2_000, true, 2)
        }
    }

    @Test fun sourceFrameBudgetIsStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(1801, 33_333), 60_000, false, 2)
        }
    }
}
