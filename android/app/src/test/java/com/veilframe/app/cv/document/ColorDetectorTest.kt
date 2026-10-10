package com.veilframe.app.cv.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point

class ColorDetectorTest {

    @Test
    fun testDocumentColorModeValues() {
        assertEquals("COLOR", DocumentColorMode.COLOR.name)
        assertEquals("GRAYSCALE", DocumentColorMode.GRAYSCALE.name)
        assertEquals("BLACK_AND_WHITE", DocumentColorMode.BLACK_AND_WHITE.name)
        assertEquals("AUTO", DocumentColorMode.AUTO.name)
    }

    @Test
    fun testAutoColorModeDegenerateQuadReturnsColorSafely() {
        val dummyMat = Mat()
        val mode = ColorDetector.autoColorMode(dummyMat, emptyList())
        assertEquals(DocumentColorMode.COLOR, mode)
    }

    @Test
    fun testAutoColorModeWithQuadOnJvmReturnsColorSafely() {
        val dummyMat = Mat()
        val quad = listOf(
            Point(0.0, 0.0),
            Point(100.0, 0.0),
            Point(100.0, 150.0),
            Point(0.0, 150.0)
        )
        val mode = ColorDetector.autoColorMode(dummyMat, quad)
        assertNotNull(mode)
    }
}
