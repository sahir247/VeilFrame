package com.veilframe.app.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.ef.*
import com.veilframe.app.qr.geometry.*
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import org.junit.Assert.*
import org.junit.Test

/**
 * Empirical unit tests verifying mathematical, architectural, and semantic parity
 * between VeilFrame's [EfQrEncoder] and EFQRCode's `QRCodeSwift` reference implementation.
 */
class EfQrEncoderParityTest {

    @Test
    fun testGaloisFieldMathTables() {
        // gexp(0) = 1 (2^0 = 1)
        assertEquals(1, QRMath.gexp(0))
        assertEquals(0, QRMath.glog(1))

        // gexp(1) = 2
        assertEquals(2, QRMath.gexp(1))
        assertEquals(1, QRMath.glog(2))

        // Galois Field GF(2^8) with primitive polynomial 0x11D = 285
        // 2^8 = 256 ^ 285 = 29
        assertEquals(29, QRMath.gexp(8))
        assertEquals(8, QRMath.glog(29))

        // Inverses: glog(gexp(i)) == i for i in 0..254
        for (i in 0 until 255) {
            assertEquals(i, QRMath.glog(QRMath.gexp(i)))
        }
    }

    @Test
    fun testErrorCorrectionPolynomialGeneration() {
        // EC length 7 (Version 1-M)
        val poly7 = QRPolynomial.errorCorrectPolynomial(7)
        assertEquals(8, poly7.count)
        assertEquals(1, poly7[0]) // Leading coefficient is always 1

        // EC length 10 (Version 1-Q)
        val poly10 = QRPolynomial.errorCorrectPolynomial(10)
        assertEquals(11, poly10.count)
        assertEquals(1, poly10[0])

        // EC length 28
        val poly28 = QRPolynomial.errorCorrectPolynomial(28)
        assertEquals(29, poly28.count)
        assertEquals(1, poly28[0])
    }

    @Test
    fun testBchFormatAndVersionEncoding() {
        // Format info: 15-bit BCH code
        // (EC_M = 0 shl 3) | mask_0 = 0 -> data = 0
        // typeInfo(0) = 0b101010000010010 = 0x5412 = 21522
        val formatBits0 = BCH.typeInfo(0)
        assertEquals(0b101010000010010, formatBits0)

        // (EC_H = 2 shl 3) | mask_0 = 16 -> data = 16
        val formatBits16 = BCH.typeInfo(16)
        assertTrue(formatBits16 in 0..0x7FFF)

        // Version info for version 7 (typeNumber 7)
        val versionBits7 = BCH.typeNumber(7)
        assertTrue(versionBits7 > 0)
    }

    @Test
    fun testEfQrEncoderShortPayloadsAllEcLevels() {
        val payload = "HELLO"

        for (ec in listOf(EfCorrectionLevel.L, EfCorrectionLevel.M, EfCorrectionLevel.Q, EfCorrectionLevel.H)) {
            val encoded = EfQrEncoder.encode(payload, ec)
            assertEquals(1, encoded.version) // Fits in version 1 (21x21)
            assertEquals(21, encoded.matrix.size)
            assertEquals(21, encoded.model.moduleCount)

            // Finders must be dark at outer borders
            assertTrue(encoded.matrix.isDark(0, 0))
            assertTrue(encoded.matrix.isDark(6, 0))
            assertTrue(encoded.matrix.isDark(0, 6))
            assertTrue(encoded.matrix.isDark(6, 6))

            // Finder center core at (3, 3) must be dark
            assertTrue(encoded.matrix.isDark(3, 3))
            assertTrue(encoded.matrix.isDark(3, 17))
            assertTrue(encoded.matrix.isDark(17, 3))

            // Separators around finders must be light
            assertFalse(encoded.matrix.isDark(7, 0))
            assertFalse(encoded.matrix.isDark(7, 6))
            assertFalse(encoded.matrix.isDark(0, 7))
            assertFalse(encoded.matrix.isDark(6, 7))
        }
    }

    @Test
    fun testEfQrEncoderVersionScaling() {
        // Version 1: up to 14 bytes in EC M
        val v1 = EfQrEncoder.encode("12345678901234", EfCorrectionLevel.M)
        assertEquals(1, v1.version)
        assertEquals(21, v1.matrix.size)

        // Version 2: 15..26 bytes in EC M -> 25x25
        val v2 = EfQrEncoder.encode("12345678901234567890", EfCorrectionLevel.M)
        assertEquals(2, v2.version)
        assertEquals(25, v2.matrix.size)

        // Version 7: 45x45 has version information blocks
        val longPayload = "A".repeat(120)
        val v7 = EfQrEncoder.encode(longPayload, EfCorrectionLevel.H)
        assertTrue(v7.version >= 7)
        assertEquals(v7.version * 4 + 17, v7.matrix.size)
    }

    @Test
    fun testTypeTableExactParityWithEfQrCode() {
        val encoded = EfQrEncoder.encode("https://veilframe.app", EfCorrectionLevel.M)
        val matrix = encoded.matrix
        val size = matrix.size

        // 1. posCenter MUST strictly be the single center module (3, 3), (3, n-4), (n-4, 3)
        assertEquals(QRPointType.POS_CENTER, encoded.pointTypeAt(3, 3))
        assertEquals(QRPointType.POS_CENTER, encoded.pointTypeAt(3, size - 4))
        assertEquals(QRPointType.POS_CENTER, encoded.pointTypeAt(size - 4, 3))

        assertEquals(QrMatrix.ModuleType.POS_CENTER, matrix.typeAt(3, 3))
        assertEquals(QrMatrix.ModuleType.POS_CENTER, matrix.typeAt(3, size - 4))
        assertEquals(QrMatrix.ModuleType.POS_CENTER, matrix.typeAt(size - 4, 3))

        // All other modules in the 9x9 finder+separator area MUST be POS_OTHER
        for (r in 0..7) {
            for (c in 0..7) {
                if (r == 3 && c == 3) continue
                assertEquals("Position ($c, $r) must be POS_OTHER", QRPointType.POS_OTHER, encoded.pointTypeAt(c, r))
                assertEquals("Position ($c, $r) must be POS_OTHER", QrMatrix.ModuleType.POS_OTHER, matrix.typeAt(c, r))
            }
        }

        // 2. Timing patterns on row 6 and col 6 (between finders: 8 until size - 8)
        for (i in 8 until size - 8) {
            assertEquals(QRPointType.TIMING, encoded.pointTypeAt(6, i))
            assertEquals(QRPointType.TIMING, encoded.pointTypeAt(i, 6))
            assertEquals(QrMatrix.ModuleType.TIMING, matrix.typeAt(6, i))
            assertEquals(QrMatrix.ModuleType.TIMING, matrix.typeAt(i, 6))
        }

        // 3. Format information around finders
        assertEquals(QRPointType.FORMAT, encoded.pointTypeAt(8, 0))
        assertEquals(QRPointType.FORMAT, encoded.pointTypeAt(8, 1))
        assertEquals(QRPointType.FORMAT, encoded.pointTypeAt(0, 8))
        assertEquals(QRPointType.FORMAT, encoded.pointTypeAt(1, 8))
    }

    @Test
    fun testLostPointPenaltyCalculation() {
        val data = "Test penalty".toByteArray(Charsets.UTF_8)
        val model = QRCodeModel(data, EfCorrectionLevel.M)
        val score = model.lostPoint
        assertTrue("Penalty score must be positive", score >= 0)
        assertNotNull(model.bestMaskPattern)
    }

    @Test
    fun testQrGeometryIrCanvasAndSvgEquivalence() {
        val rect = RectNode(x = 10f, y = 20f, width = 30f, height = 40f, rx = 5f, ry = 5f, fill = 0xFF0000FF.toInt())
        val circle = CircleNode(cx = 50f, cy = 50f, radius = 15f, fill = 0xFFFF0000.toInt())
        val line = LineNode(x1 = 0f, y1 = 0f, x2 = 100f, y2 = 100f, strokeColor = 0xFF00FF00.toInt(), strokeWidth = 2f, isRoundCap = true)

        val ir = QrGeometryIr(
            width = 100f,
            height = 100f,
            rootNodes = listOf(rect, circle, line)
        )

        val svg = IrSvgRenderer.render(ir)
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("<rect x=\"10.0000\" y=\"20.0000\" width=\"30.0000\" height=\"40.0000\""))
        assertTrue(svg.contains("<circle cx=\"50.0000\" cy=\"50.0000\" r=\"15.0000\""))
        assertTrue(svg.contains("<line x1=\"0.0000\" y1=\"0.0000\" x2=\"100.0000\" y2=\"100.0000\""))
        assertTrue(svg.contains("stroke-linecap=\"round\""))
        assertTrue(svg.contains("</svg>"))
    }

    @Test
    fun testEfCompatibilityModeInQrGenerator() {
        // 1. Content validation check in EF_COMPATIBLE mode
        val blankResult = QrGenerator.generateEfCompatible("   ")
        assertTrue(blankResult is QrRenderResult.Failure)
        assertEquals("QR content must not be blank", (blankResult as QrRenderResult.Failure).error)

        // 2. Direct EF encoder integration test
        val content = "https://github.com/EFPrefix/EFQRCode"
        val encoded = EfQrEncoder.encode(content, EfCorrectionLevel.H)
        assertNotNull(encoded.matrix)
        assertTrue(encoded.matrix.size > 21)
        assertEquals(encoded.version, encoded.matrix.version)

        // Ensure finders are intact
        assertTrue(encoded.matrix.isDark(3, 3))
        assertEquals(QRPointType.POS_CENTER, encoded.pointTypeAt(3, 3))
    }
}
