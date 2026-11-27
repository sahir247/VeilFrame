package com.veilframe.app.cv.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MotionLabFrameSamplingTest {
    @Test fun nativeHdNeedsNoDownsample() {
        assertEquals(1, MotionLabFrameSampling.sampleSize(1920, 1080))
        assertEquals(1, MotionLabFrameSampling.sampleSize(1080, 1920))
        assertEquals(1, MotionLabFrameSampling.sampleSize(1280, 720))
    }

    @Test fun ultraHdAndEightKRemainBounded() {
        assertEquals(2, MotionLabFrameSampling.sampleSize(3840, 2160))
        assertEquals(4, MotionLabFrameSampling.sampleSize(7680, 4320))
        assertEquals(8, MotionLabFrameSampling.sampleSize(7681, 4320))
        assertEquals(4, MotionLabFrameSampling.sampleSize(4320, 7680))
    }

    @Test fun refuseNonPositiveDimensionsAndUnboundedHugeFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionLabFrameSampling.sampleSize(0, 1080)
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabFrameSampling.sampleSize(200_000, 1080)
        }
    }
}
