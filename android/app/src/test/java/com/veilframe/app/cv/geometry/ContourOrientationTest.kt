package com.veilframe.app.cv.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point
import com.veilframe.app.cv.geometry.Point as GeometryPoint

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

    @Test
    fun testMergeSegmentsChecksWrapAroundEvenWithFourSegments() {
        val segmentClass = Class.forName(
            "com.veilframe.app.cv.geometry.ContourOrientation\$ContourSegment"
        )
        val constructor = segmentClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType,
            Double::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val segments = listOf(
            constructor.newInstance(0, 10, 0.0, 100.0),
            constructor.newInstance(10, 20, Math.PI / 2.0, 100.0),
            constructor.newInstance(20, 30, Math.PI, 100.0),
            constructor.newInstance(30, 40, 0.01, 10.0),
        )
        val merge = ContourOrientation::class.java.getDeclaredMethod(
            "mergeSegments",
            List::class.java,
            Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val merged = merge.invoke(ContourOrientation, segments, Math.toRadians(7.0)) as List<*>
        assertEquals("The first and last segments are one circular edge", 3, merged.size)

        val first = merged.first()!!
        val start = segmentClass.getDeclaredField("start").apply { isAccessible = true }
        val end = segmentClass.getDeclaredField("end").apply { isAccessible = true }
        assertEquals(30, start.getInt(first))
        assertEquals(10, end.getInt(first))
    }


    @Test
    fun testCreateQuadUsesAxisAlignedOrderingAcrossAtan2BranchCut() {
        val cx = 200.0
        val cy = 200.0
        val angle = Math.toRadians(35.0)
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        fun rotate(x: Double, y: Double) = GeometryPoint(
            cx + c * x - s * y,
            cy + s * x + c * y
        )

        val topLeft = rotate(-60.0, -40.0)
        val topRight = rotate(60.0, -40.0)
        val bottomRight = rotate(60.0, 40.0)
        val bottomLeft = rotate(-60.0, 40.0)

        // Deliberately scramble input order. bottomLeft falls just above cy,
        // so a raw atan2 sort starts at bottomLeft (the original audit defect).
        val quad = createQuad(listOf(bottomRight, bottomLeft, topRight, topLeft))
        assertEquals(topLeft, quad.topLeft)
        assertEquals(topRight, quad.topRight)
        assertEquals(bottomRight, quad.bottomRight)
        assertEquals(bottomLeft, quad.bottomLeft)
    }

}
