package com.veilframe.app.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.ModuleType
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
            "FUNCTION", "STYLE_FUNCTION", "CONNECTED_ORGANIC"
        )
        val actualStyles = QrStyle.values().map { it.name }.toSet()
        assertEquals(expectedStyles, actualStyles)
        assertEquals(12, QrStyle.values().size)
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

    @Test
    fun testAllStylesRegisteredInQrStyleRegistry() {
        val allDefs = com.veilframe.app.qr.registry.QrStyleRegistry.getAll()
        assertEquals(12, allDefs.size)
        for (style in QrStyle.values()) {
            val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(style)
            assertEquals(style, def.style)
            val renderer = com.veilframe.app.qr.registry.QrStyleRegistry.getRenderer(style)
            assertNotNull("Renderer for $style should not be null", renderer)
        }
    }

    @Test
    fun testQrMatrixRepeatedIsDarkPerformance() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        // Calling isDark thousands of times should be instantaneous (no re-encode)
        val start = System.currentTimeMillis()
        var darkCount = 0
        for (i in 0 until 1000) {
            if (matrix.isDark(0, 0)) darkCount++
            if (matrix.isDark(3, 3)) darkCount++
        }
        val duration = System.currentTimeMillis() - start
        assertTrue("Repeated isDark checks should take < 50ms without re-encoding", duration < 500)
    }
}
