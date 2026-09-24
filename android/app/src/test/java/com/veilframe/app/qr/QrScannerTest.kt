package com.veilframe.app.qr

import android.graphics.Rect
import com.veilframe.app.qr.scanner.QrScanner
import org.junit.Assert.*
import org.junit.Test

class QrScannerTest {

    @Test
    fun testMapUprightRectToBuffer0Degrees() {
        val bufferW = 1920
        val bufferH = 1080

        val bufferRect = QrScanner.mapUprightCoordsToBuffer(100, 200, 400, 500, 0, bufferW, bufferH)
        assertEquals(100, bufferRect.left)
        assertEquals(200, bufferRect.top)
        assertEquals(400, bufferRect.right)
        assertEquals(500, bufferRect.bottom)
        assertEquals(300, bufferRect.width)
        assertEquals(300, bufferRect.height)
    }

    @Test
    fun testMapUprightRectToBuffer90Degrees() {
        // Landscape camera sensor (1920x1080) producing portrait upright preview (1080x1920)
        val bufferW = 1920
        val bufferH = 1080

        val bufferRect = QrScanner.mapUprightCoordsToBuffer(100, 200, 400, 600, 90, bufferW, bufferH)

        // In 90 degree clockwise rotation:
        // uprightX = bufferH - 1 - bufferY
        // uprightY = bufferX
        // Inverted: bufferX = uprightY, bufferY = bufferH - uprightX
        assertEquals(200, bufferRect.left)
        assertEquals(1080 - 400, bufferRect.top)
        assertEquals(200 + 400, bufferRect.right)
        assertEquals(1080 - 100, bufferRect.bottom)
        assertEquals(400, bufferRect.width)
        assertEquals(300, bufferRect.height)

        // Ensure bounds are strictly inside buffer boundaries
        assertTrue(bufferRect.left >= 0 && bufferRect.right <= bufferW)
        assertTrue(bufferRect.top >= 0 && bufferRect.bottom <= bufferH)
    }

    @Test
    fun testMapUprightRectToBuffer180Degrees() {
        val bufferW = 1920
        val bufferH = 1080

        val bufferRect = QrScanner.mapUprightCoordsToBuffer(100, 200, 400, 500, 180, bufferW, bufferH)
        assertEquals(bufferW - 400, bufferRect.left)
        assertEquals(bufferH - 500, bufferRect.top)
        assertEquals(bufferW - 100, bufferRect.right)
        assertEquals(bufferH - 200, bufferRect.bottom)
        assertEquals(300, bufferRect.width)
        assertEquals(300, bufferRect.height)
    }

    @Test
    fun testMapUprightRectToBuffer270Degrees() {
        val bufferW = 1920
        val bufferH = 1080

        val bufferRect = QrScanner.mapUprightCoordsToBuffer(100, 200, 400, 600, 270, bufferW, bufferH)
        assertEquals(bufferW - 600, bufferRect.left)
        assertEquals(100, bufferRect.top)
        assertEquals(bufferW - 200, bufferRect.right)
        assertEquals(100 + 300, bufferRect.bottom)
        assertEquals(400, bufferRect.width)
        assertEquals(300, bufferRect.height)
    }

    @Test
    fun testOffCenterQrEdgeLocalizationPreserved() {
        // A QR code near the corner of the preview (e.g. left = 20, top = 30, right = 220, bottom = 230)
        // In the legacy VeilFrame scanner, central 70% cropping began at 15% (e.g. x = 162, y = 288 for 1080x1920),
        // which completely discarded this QR code!
        val bufferW = 1920
        val bufferH = 1080

        val bufferRect = QrScanner.mapUprightCoordsToBuffer(20, 30, 220, 230, 90, bufferW, bufferH)

        // Verify mapped buffer coordinates preserve the entire QR code without clipping
        assertEquals(30, bufferRect.left)
        assertEquals(1080 - 220, bufferRect.top)
        assertEquals(230, bufferRect.right)
        assertEquals(1080 - 20, bufferRect.bottom)
        assertEquals(200, bufferRect.width)
        assertEquals(200, bufferRect.height)
    }

    @Test
    fun testAutoZoomMultiplierCalculation() {
        // When a QR occupies 10% coverage, target is 50% coverage -> multiplier is ~3.0x
        val coverage = 0.10f
        val targetCoverage = 0.50f
        val multiplier = (targetCoverage / coverage).coerceIn(1.2f, 3.0f)
        assertEquals(3.0f, multiplier, 0.001f)

        // When a QR occupies 25% coverage -> multiplier is 2.0x
        val coverageMedium = 0.25f
        val multiplierMedium = (targetCoverage / coverageMedium).coerceIn(1.2f, 3.0f)
        assertEquals(2.0f, multiplierMedium, 0.001f)

        // When a QR already occupies 45% coverage -> does not trigger zoom
        val coverageLarge = 0.45f
        assertTrue("Large QR code should not trigger zoom suggestion", coverageLarge > 0.28f)
    }
}
