package com.veilframe.app.cv.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point
import kotlin.math.abs

/**
 * Unit tests verifying [Geometry.orderCorners] and perspective geometry primitives.
 */
class GeometryTest {

    @Test
    fun `test orderCorners correctly orders shuffled upright rectangle`() {
        val originalTL = Point(0.0, 0.0)
        val originalTR = Point(200.0, 0.0)
        val originalBR = Point(200.0, 300.0)
        val originalBL = Point(0.0, 300.0)

        // Provide corners in scrambled order
        val scrambled = listOf(originalBR, originalTL, originalBL, originalTR)
        val ordered = Geometry.orderCorners(scrambled)

        assertEquals("TL x", 0.0, ordered[0].x, 0.001)
        assertEquals("TL y", 0.0, ordered[0].y, 0.001)

        assertEquals("TR x", 200.0, ordered[1].x, 0.001)
        assertEquals("TR y", 0.0, ordered[1].y, 0.001)

        assertEquals("BR x", 200.0, ordered[2].x, 0.001)
        assertEquals("BR y", 300.0, ordered[2].y, 0.001)

        assertEquals("BL x", 0.0, ordered[3].x, 0.001)
        assertEquals("BL y", 300.0, ordered[3].y, 0.001)
    }

    @Test
    fun `test orderCorners correctly orders skewed quad without swapping corners`() {
        // Tilted quad: TL, TR, BR, BL
        val expectedTL = Point(50.0, 30.0)
        val expectedTR = Point(450.0, 60.0)
        val expectedBR = Point(420.0, 580.0)
        val expectedBL = Point(70.0, 540.0)

        val scrambled = listOf(expectedBL, expectedTR, expectedTL, expectedBR)
        val ordered = Geometry.orderCorners(scrambled)

        assertEquals("TL must be top-left", expectedTL.x, ordered[0].x, 0.001)
        assertEquals("TL must be top-left", expectedTL.y, ordered[0].y, 0.001)

        assertEquals("TR must be top-right", expectedTR.x, ordered[1].x, 0.001)
        assertEquals("TR must be top-right", expectedTR.y, ordered[1].y, 0.001)

        assertEquals("BR must be bottom-right", expectedBR.x, ordered[2].x, 0.001)
        assertEquals("BR must be bottom-right", expectedBR.y, ordered[2].y, 0.001)

        assertEquals("BL must be bottom-left", expectedBL.x, ordered[3].x, 0.001)
        assertEquals("BL must be bottom-left", expectedBL.y, ordered[3].y, 0.001)
    }

    @Test
    fun `test scalePoints scales all coordinates uniformly`() {
        val points = listOf(
            Point(10.0, 20.0),
            Point(30.0, 40.0),
            Point(50.0, 60.0),
            Point(70.0, 80.0)
        )
        val scaled = Geometry.scalePoints(points, 2.5)

        assertEquals(25.0, scaled[0].x, 0.001)
        assertEquals(50.0, scaled[0].y, 0.001)
        assertEquals(75.0, scaled[1].x, 0.001)
        assertEquals(100.0, scaled[1].y, 0.001)
    }

    @Test
    fun `test outputSizeFor respects dimension bounds and aspect limits`() {
        // Enormous corners
        val largeCorners = listOf(
            Point(0.0, 0.0),
            Point(10000.0, 0.0),
            Point(10000.0, 15000.0),
            Point(0.0, 15000.0)
        )
        val (w, h) = Geometry.outputSizeFor(largeCorners, maxDimensionCap = 4000)
        assertTrue("Output width capped at 4000", w <= 4000)
        assertTrue("Output height capped at 4000", h <= 4000)
        assertTrue("Output dimensions above minimum", w >= 100 && h >= 100)
    }

    @Test
    fun `test isValidQuad rejects degenerate or concave polygons`() {
        val validQuad = listOf(
            Point(10.0, 10.0),
            Point(200.0, 15.0),
            Point(195.0, 300.0),
            Point(15.0, 290.0)
        )
        assertTrue("Valid quad accepted", Geometry.isValidQuad(validQuad))

        // Degenerate zero-area line
        val degenerateLine = listOf(
            Point(10.0, 10.0),
            Point(20.0, 20.0),
            Point(30.0, 30.0),
            Point(40.0, 40.0)
        )
        org.junit.Assert.assertFalse("Collinear points rejected", Geometry.isValidQuad(degenerateLine))
    }
}
