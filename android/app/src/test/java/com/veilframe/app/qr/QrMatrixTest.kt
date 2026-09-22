package com.veilframe.app.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class QrMatrixTest {

    @Test
    fun testQrMatrixCreation() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        assertTrue("Size should be >= 21 for QR Code Version 1", matrix.size >= 21)

        // Verify finder patterns exist at corners
        // Top-left finder center (3,3)
        assertEquals(QrMatrix.ModuleType.POS_CENTER, matrix.typeAt(3, 3))
        // Finder centers should be dark
        assertTrue(matrix.isDark(3, 3))

        // Timing patterns
        assertEquals(QrMatrix.ModuleType.TIMING, matrix.typeAt(6, 10))
        assertEquals(QrMatrix.ModuleType.TIMING, matrix.typeAt(10, 6))
    }

    @Test
    fun testAllStylesDefinedInEnum() {
        val expectedStyles = setOf(
            "BASIC", "BUBBLE", "D25", "DSJ", "IMAGE_FILL",
            "IMAGE", "IMAGE_RESAMPLE", "LINE", "RANDOM_RECTANGLE",
            "FUNCTION", "STYLE_FUNCTION"
        )
        val actualStyles = QrStyle.values().map { it.name }.toSet()
        assertEquals(expectedStyles, actualStyles)
        assertEquals(11, QrStyle.values().size)
    }

    @Test
    fun testQrStyleParamsDefaults() {
        val params = QrStyleParams()
        assertEquals(512, params.outputSize)
        assertEquals(QrStyle.BASIC, params.style)
        assertNotNull(params.foreground)
        assertNotNull(params.background)
        assertNull(params.logo)
        assertNull(params.backgroundImage)
    }
}
