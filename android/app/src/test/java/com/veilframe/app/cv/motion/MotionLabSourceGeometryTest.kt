package com.veilframe.app.cv.motion

import org.junit.Assert.*
import org.junit.Test

class MotionLabSourceGeometryTest {
    @Test fun rotationAndRifeSamplingAreAppliedBeforeOpeningPipes() {
        val plan = MotionLabSourceGeometry(3840,2160,90).plan(true)
        assertEquals(MotionLabRawFramePipe.Geometry(1080,1920),plan.geometry)
        assertEquals("transpose=clock,scale=1080:1920:flags=lanczos",plan.decodeFilter)
        assertEquals("pad=1080:1920,setsar=1/1:max=65535",plan.encodeFilter)
    }
    @Test fun oddGeometryAndAnamorphicRatioAreExplicit() {
        val plan = MotionLabSourceGeometry(641,481,0,4,3).plan(false)
        assertEquals(MotionLabRawFramePipe.Geometry(641,481),plan.geometry)
        assertTrue(plan.encodeFilter.startsWith("pad=642:482,setsar="))
    }
}
