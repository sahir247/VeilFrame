package com.veilframe.app.cv.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class PerspectiveMetrologyTest {

    @Test
    fun testSnapToA4Format() {
        // Physical dimensions close to A4 (210 x 297 mm)
        val measured = EstimatedDimensions.Physical(212.0, 296.0)
        val snapped = measured.snapToStandardFormat()

        assertTrue("Should snap to standard A4 format", snapped is EstimatedDimensions.Physical)
        val a4 = snapped as EstimatedDimensions.Physical
        assertEquals("Snapped A4 width", 210.0, a4.widthMm, 0.001)
        assertEquals("Snapped A4 height", 297.0, a4.heightMm, 0.001)
    }

    @Test
    fun testSnapToLetterFormat() {
        // Physical dimensions close to US Letter (215.9 x 279.4 mm)
        val measured = EstimatedDimensions.Physical(216.0, 280.0)
        val snapped = measured.snapToStandardFormat()

        assertTrue("Should snap to standard Letter format", snapped is EstimatedDimensions.Physical)
        val letter = snapped as EstimatedDimensions.Physical
        assertEquals("Snapped Letter width", 215.9, letter.widthMm, 0.001)
        assertEquals("Snapped Letter height", 279.4, letter.heightMm, 0.001)
    }

    @Test
    fun testPreservesCustomNonStandardRatio() {
        // Long receipt ratio (e.g. 80mm x 300mm)
        val receipt = EstimatedDimensions.Physical(80.0, 300.0)
        val snapped = receipt.snapToStandardFormat()

        assertEquals("Non-standard aspect ratio must not snap", 80.0, (snapped as EstimatedDimensions.Physical).widthMm, 0.001)
        assertEquals("Non-standard aspect ratio must not snap", 300.0, snapped.heightMm, 0.001)
    }

    @Test
    fun testToPixelDimensionsPreservesTargetAspectRatio() {
        val corners = listOf(
            Point(100.0, 100.0),
            Point(500.0, 100.0),
            Point(500.0, 700.0),
            Point(100.0, 700.0)
        )
        val estimated = EstimatedDimensions.Ratio(210.0, 297.0) // A4 ratio ~ 1.4142
        val (w, h) = estimated.toPixelDimensions(corners)

        val targetRatio = 297.0 / 210.0
        val actualRatio = h / w
        assertEquals("Aspect ratio must match estimated ratio", targetRatio, actualRatio, 0.001)
    }
}
