package com.veilframe.app.document

import com.veilframe.app.cv.document.QualityMetrics
import com.veilframe.app.cv.document.QuadStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class ScanResultModelTest {

    @Test
    fun testQualityMetricsDefaultValues() {
        val defaultMetrics = QualityMetrics.DEFAULT
        assertTrue(defaultMetrics.isSharp)
        assertEquals(100.0, defaultMetrics.sharpnessVariance, 0.001)
        assertFalse(defaultMetrics.isTooDark)
        assertEquals(128.0, defaultMetrics.meanLuma, 0.001)
        assertFalse(defaultMetrics.hasGlare)
        assertEquals(0.0, defaultMetrics.glarePercentage, 0.001)
    }

    @Test
    fun testScanResultCreationAndProperties() {
        val corners = listOf(
            Point(10.0, 10.0),
            Point(100.0, 10.0),
            Point(100.0, 200.0),
            Point(10.0, 200.0)
        )
        val metrics = QualityMetrics(
            isSharp = true,
            sharpnessVariance = 210.0,
            isTooDark = false,
            meanLuma = 145.0,
            hasGlare = false,
            glarePercentage = 0.5
        )
        val result = ScanResult(
            corners = corners,
            quality = metrics,
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
        assertEquals(210.0, result.quality.sharpnessVariance, 0.001)
        assertEquals(145.0, result.quality.meanLuma, 0.001)
        assertTrue(result.quality.isSharp)
        assertFalse(result.quality.isTooDark)
        assertFalse(result.quality.hasGlare)
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
