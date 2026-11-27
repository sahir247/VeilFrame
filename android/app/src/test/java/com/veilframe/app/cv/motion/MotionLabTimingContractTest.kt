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

    @Test fun variableFrameRateUsesRealIntervalFractions() {
        val plan = MotionLabTimingContract.plan(listOf(0,33_333,66_667,166_667),200,false,2)
        org.junit.Assert.assertTrue(plan.variableCadence)
        assertEquals(8,plan.expectedOutputFrames)
        assertEquals(40.0,plan.outputFps,0.001)
        assertEquals(2,plan.pairFractions[0].size)
        assertEquals(0.5,plan.pairFractions[0][1],0.001)
        assertEquals(2,plan.pairFractions[2].size)
        assertEquals(2,plan.tailFrames)
        assertEquals(listOf(0L,16_666L,33_333L,50_000L,66_667L,116_667L,166_667L,183_333L),
            plan.preservedPresentationUs)
        assertEquals(200_000L,plan.preservedDurationUs)
        assertEquals(plan.expectedOutputFrames,plan.pairFractions.sumOf { it.size }+plan.tailFrames)
    }

    @Test fun sourceStartOffsetDoesNotChangeInterpolation() {
        val pts = listOf(0L,40_000L,100_000L,130_000L)
        val a = MotionLabTimingContract.plan(pts,160,true,2)
        val b = MotionLabTimingContract.plan(pts.map { it+700_000 },160,true,2)
        assertEquals(a,b)
        assertEquals(10,a.expectedOutputFrames)
    }

    @Test fun rejectsExcessiveGapsAndInconsistentVideoDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(listOf(0,1_100_000),1200,true,2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(30,33_333),1000,true,2,500_000)
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

    @Test fun twentyFourFpsToSixtyUsesTimestampGridNotIntegerMultiplier() {
        val plan = MotionLabTimingContract.plan(pts(48,41_667),2000,true,2)
        assertEquals(60.0,plan.outputFps,0.0)
        assertEquals(120,plan.expectedOutputFrames)
        assertEquals(0.4,plan.pairFractions[0][1],0.001)
        assertEquals(0.8,plan.pairFractions[0][2],0.001)
    }

    @Test fun quantizedCfrAtOneHundredTwentyFpsOutputBoundary() {
        val thirty = MotionLabTimingContract.plan(pts(60,33_333),2000,false,4)
        assertEquals(120.0,thirty.outputFps,0.0)
        assertEquals(240,thirty.expectedOutputFrames)
        val sixty = MotionLabTimingContract.plan(pts(120,16_666),2000,false,2)
        assertEquals(120.0,sixty.outputFps,0.0)
        assertEquals(240,sixty.expectedOutputFrames)
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(62,32_258),2000,false,4)
        }
    }

    @Test fun acceptsQuantizationAtMinimumSourceCadence() {
        val plan = MotionLabTimingContract.plan(pts(20,100_001),2000,false,2)
        assertEquals(10.0,plan.sourceFps,0.0)
        assertEquals(20.0,plan.outputFps,0.0)
    }

    @Test fun sourceFrameBudgetIsStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(pts(1801, 33_333), 60_000, false, 2)
        }
    }

    @Test fun variableFourTimesKeepsEverySourceTimestampAndExactTail() {
        val source = listOf(0L,31_111L,81_234L,120_001L)
        val plan = MotionLabTimingContract.plan(source,160,false,4,160_001L)
        assertEquals(source,plan.preservedPresentationUs.filterIndexed { i,_ -> i%4==0 })
        assertEquals(16,plan.expectedOutputFrames)
        assertEquals(160_001L,plan.preservedDurationUs)
        assertEquals(4,plan.tailFrames)
        assertEquals(0.25,plan.pairFractions[1][1],0.001)
        MotionLabVfrMux.verify(plan.preservedPresentationUs,160_001L,plan)
        assertThrows(IllegalStateException::class.java) {
            MotionLabVfrMux.verify(plan.preservedPresentationUs.mapIndexed { i,v -> if(i==3) v+1000 else v },160_001L,plan)
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabVfrMux.verify(plan.preservedPresentationUs,160_003L,plan)
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabVfrMux.verify(plan.preservedPresentationUs.reversed(),160_001L,plan)
        }
    }

    @Test fun subMicrosecondSubdivisionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabTimingContract.plan(listOf(0L,1L,70_000L),150,false,2)
        }
    }

    @Test fun largeTimelineUsesBoundedBalancedExpression() {
        val source = (0 until 1800).map { it*33_000L + (it%2)*1000L }
        val plan = MotionLabTimingContract.plan(source,60_000,false,4)
        val args = MotionLabVfrMux.arguments(plan)
        assertEquals(7200,plan.expectedOutputFrames)
        assertEquals(7200,plan.preservedPresentationUs.size)
        org.junit.Assert.assertTrue(args.maxOf { it.length } < 128_000)
        assertEquals(60.0,MotionLabVfrMux.durationSeconds(plan),0.0)
    }
}
