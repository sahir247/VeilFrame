package com.veilframe.app.cv.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class ContourOrientationTest {

    @Test
    fun testReconstructsRectangleContour() {
        // Generate a dense rectangle contour with noise/slight curve
        val points = mutableListOf<Point>()

        // Top edge: (100, 100) to (500, 100)
        for (x in 100..500 step 10) points.add(Point(x.toDouble(), 100.0))
        // Right edge: (500, 100) to (500, 700)
        for (y in 100..700 step 10) points.add(Point(500.0, y.toDouble()))
        // Bottom edge: (500, 700) to (100, 700)
        for (x in 500 downTo 100 step 10) points.add(Point(x.toDouble(), 700.0))
        // Left edge: (100, 700) to (100, 100)
        for (y in 700 downTo 100 step 10) points.add(Point(100.0, y.toDouble()))

        val quad = ContourOrientation.findQuadFromContourOrientation(points)
        assertNotNull("Should reconstruct 4 corners from dominant edge orientations", quad)
        assertEquals(4, quad!!.size)

        val ordered = Geometry.ordered(quad)
        assertEquals("TL x", 100.0, ordered[0].x, 5.0)
        assertEquals("TL y", 100.0, ordered[0].y, 5.0)
        assertEquals("TR x", 500.0, ordered[1].x, 5.0)
        assertEquals("TR y", 100.0, ordered[1].y, 5.0)
        assertEquals("BR x", 500.0, ordered[2].x, 5.0)
        assertEquals("BR y", 700.0, ordered[2].y, 5.0)
        assertEquals("BL x", 100.0, ordered[3].x, 5.0)
        assertEquals("BL y", 700.0, ordered[3].y, 5.0)
    }

    @Test
    fun testRejectsTinyOrIncompleteContour() {
        val fewPoints = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0))
        val quad = ContourOrientation.findQuadFromContourOrientation(fewPoints)
        assertNull(quad)
    }

    @Test
    fun testMinAreaRectFindsMinimalBoundingBox() {
        // Rotated diamond
        val polygon = listOf(
            Point(100.0, 200.0),
            Point(200.0, 100.0),
            Point(300.0, 200.0),
            Point(200.0, 300.0)
        )
        val rect = MinAreaRect.minAreaRect(polygon)
        assertNotNull(rect)
        assertEquals(4, rect!!.size)
        assertTrue("MinAreaRect produces valid quad", Geometry.isValidQuad(rect))
    }
}
