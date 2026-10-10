package com.veilframe.app.document

import com.veilframe.app.cv.document.QuadStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencv.core.Point

class ScanResultModelTest {

    @Test
    fun testScanResultCreationAndProperties() {
        val corners = listOf(
            Point(10.0, 10.0),
            Point(100.0, 10.0),
            Point(100.0, 200.0),
            Point(10.0, 200.0)
        )
        val result = ScanResult(
            corners = corners,
            frameId = 42L,
            frameWidth = 1080,
            frameHeight = 1920,
            stabilizerState = QuadStabilizer.State.STABLE
        )

        assertEquals(4, result.corners?.size)
        assertEquals(42L, result.frameId)
        assertEquals(1080, result.frameWidth)
        assertEquals(1920, result.frameHeight)
        assertEquals(QuadStabilizer.State.STABLE, result.stabilizerState)
    }

    @Test
    fun testScanResultNullCornersSearchingState() {
        val result = ScanResult(
            corners = null,
            frameId = 1L,
            frameWidth = 720,
            frameHeight = 1280,
            stabilizerState = QuadStabilizer.State.SEARCHING
        )

        assertNull(result.corners)
        assertEquals(QuadStabilizer.State.SEARCHING, result.stabilizerState)
    }
}
