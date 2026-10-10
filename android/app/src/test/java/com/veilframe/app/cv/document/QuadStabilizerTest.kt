package com.veilframe.app.cv.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class QuadStabilizerTest {

    private fun sampleQuad(offset: Double = 0.0): List<Point> = listOf(
        Point(100.0 + offset, 100.0 + offset),
        Point(500.0 + offset, 100.0 + offset),
        Point(500.0 + offset, 700.0 + offset),
        Point(100.0 + offset, 700.0 + offset)
    )

    @Test
    fun testStabilizerConvergesToStableAfterSufficientHits() {
        var currentTime = 1000L
        val stabilizer = QuadStabilizer(
            alpha = 0.5f,
            stableHits = 3,
            maxConsecutiveMisses = 2,
            maxAgeMs = 500L,
            timeProvider = { currentTime }
        )

        assertEquals(QuadStabilizer.State.SEARCHING, stabilizer.state)

        // Frame 1
        val res1 = stabilizer.update(sampleQuad(0.0))
        assertNotNull(res1)
        assertEquals(QuadStabilizer.State.TRACKING, stabilizer.state)
        assertFalse(stabilizer.isPredicted)

        // Frame 2
        currentTime += 33L
        val res2 = stabilizer.update(sampleQuad(2.0))
        assertNotNull(res2)
        assertEquals(QuadStabilizer.State.TRACKING, stabilizer.state)

        // Frame 3
        currentTime += 33L
        val res3 = stabilizer.update(sampleQuad(1.0))
        assertNotNull(res3)
        assertEquals(QuadStabilizer.State.STABLE, stabilizer.state)
    }

    @Test
    fun testStabilizerBoundedMissExpiry() {
        var currentTime = 1000L
        val stabilizer = QuadStabilizer(
            stableHits = 2,
            maxConsecutiveMisses = 2,
            maxAgeMs = 300L,
            timeProvider = { currentTime }
        )

        // Acquire stable track
        stabilizer.update(sampleQuad(0.0))
        currentTime += 33L
        stabilizer.update(sampleQuad(0.0))
        assertEquals(QuadStabilizer.State.STABLE, stabilizer.state)

        // Miss 1: Within tolerance, should hold predicted quad
        currentTime += 33L
        val held1 = stabilizer.update(null)
        assertNotNull("One miss should hold smoothed quad", held1)
        assertTrue("Held quad must be flagged as predicted", stabilizer.isPredicted)
        assertEquals(QuadStabilizer.State.TRACKING, stabilizer.state)

        // Miss 2: Within tolerance
        currentTime += 33L
        val held2 = stabilizer.update(null)
        assertNotNull("Second miss within maxConsecutiveMisses holds quad", held2)
        assertTrue(stabilizer.isPredicted)

        // Miss 3: Exceeds maxConsecutiveMisses (2), should immediately reset
        currentTime += 33L
        val held3 = stabilizer.update(null)
        assertNull("Third consecutive miss must expire and reset track", held3)
        assertEquals(QuadStabilizer.State.SEARCHING, stabilizer.state)
    }

    @Test
    fun testStabilizerWallClockTimeExpiry() {
        var currentTime = 1000L
        val stabilizer = QuadStabilizer(
            stableHits = 2,
            maxConsecutiveMisses = 5,
            maxAgeMs = 200L,
            timeProvider = { currentTime }
        )

        stabilizer.update(sampleQuad(0.0))
        currentTime += 33L
        stabilizer.update(sampleQuad(0.0))

        // Miss after 500ms (exceeds maxAgeMs 200ms)
        currentTime += 500L
        val res = stabilizer.update(null)
        assertNull("Stale quad older than maxAgeMs must expire immediately", res)
        assertEquals(QuadStabilizer.State.SEARCHING, stabilizer.state)
    }
}
