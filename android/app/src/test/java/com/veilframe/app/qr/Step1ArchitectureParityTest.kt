package com.veilframe.app.qr

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.ImageNode
import com.veilframe.app.qr.model.BackdropStyle
import com.veilframe.app.qr.model.DirectionalInsets
import com.veilframe.app.qr.model.FractionalInsets
import com.veilframe.app.qr.model.LogoStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.ResolvedQuietZone
import com.veilframe.app.qr.renderer.ImageRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architectural parity and remediation test suite for Step 1:
 * - Deterministic UTF-8 encoding across multi-byte and Unicode payloads
 * - Authoritative 5-level quiet-zone resolution precedence
 * - Exact floating-point margin parity between QrGeometry and SvgExporter
 * - Content-anchored logo positioning (matrix content bounds vs canvas bounds)
 */
class Step1ArchitectureParityTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    private fun decodeHeadless(matrix: QrMatrix): String {
        val n = matrix.size
        val qz = 4
        val scale = 8
        val total = (n + 2 * qz) * scale
        val pixels = IntArray(total * total) { 0xFFFFFFFF.toInt() }
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix.isDark(c, r)) {
                    val sx = (c + qz) * scale
                    val sy = (r + qz) * scale
                    for (y in sy until sy + scale) {
                        for (x in sx until sx + scale) {
                            pixels[y * total + x] = 0xFF000000.toInt()
                        }
                    }
                }
            }
        }
        val source = RGBLuminanceSource(total, total, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
        val result = MultiFormatReader().decode(binaryBitmap, hints)
        return result.text
    }

    @Test
    fun testUnicodeEncodingDeterministicSafePath() {
        val unicodePayloads = listOf(
            "नमस्ते",
            "こんにちは",
            "中文测试二维码",
            "🔒🛡️ VeilFrame",
            "WIFI:T:WPA;S:☕Café_Net_5G;P:p@sswørd🔒;;",
            "BEGIN:VCARD\nVERSION:3.0\nN:Müller;René;;;\nFN:René Müller\nORG:Über-Tech GmbH\nTITLE:Ingenieur 🚀\nEND:VCARD"
        )

        for (payload in unicodePayloads) {
            val encoded = QrEncoder.encode(content = payload, errorCorrection = ErrorCorrectionLevel.M)
            assertNotNull("Encoded QR must not be null for payload: $payload", encoded)
            val decoded = decodeHeadless(encoded.matrix)
            assertEquals("Decoded text must exactly match multi-byte original for: $payload", payload, decoded)
        }
    }

    @Test
    fun testQuietZonePrecedenceContract() {
        val matrixSize = 21

        // Level 1: fractionalQuietZone overrides ALL other quiet zone configurations
        val designLevel1 = QrDesign(
            style = QrStyle.BASIC,
            quietZoneModules = 4,
            explicitQuietZone = 3,
            directionalQuietZone = DirectionalInsets(1, 2, 3, 4),
            backdropStyle = BackdropStyle(
                fractionalQuietZone = FractionalInsets(0.10f, 0.10f, 0.10f, 0.10f)
            )
        )
        val resolved1 = QrGeometry.resolveQuietZone(designLevel1, matrixSize)
        assertEquals(2.1f, resolved1.left, 0.001f)
        assertEquals(2.1f, resolved1.top, 0.001f)
        assertEquals(2.1f, resolved1.right, 0.001f)
        assertEquals(2.1f, resolved1.bottom, 0.001f)

        // Level 2: directionalQuietZone overrides explicitQuietZone, quietZoneModules, style default
        val designLevel2 = QrDesign(
            style = QrStyle.BASIC,
            quietZoneModules = 4,
            explicitQuietZone = 3,
            directionalQuietZone = DirectionalInsets(2, 5, 1, 4),
            backdropStyle = BackdropStyle(fractionalQuietZone = null)
        )
        val resolved2 = QrGeometry.resolveQuietZone(designLevel2, matrixSize)
        assertEquals(2f, resolved2.left, 0.001f)
        assertEquals(5f, resolved2.top, 0.001f)
        assertEquals(1f, resolved2.right, 0.001f)
        assertEquals(4f, resolved2.bottom, 0.001f)

        // Level 3: explicitQuietZone overrides quietZoneModules, style default
        val designLevel3 = QrDesign(
            style = QrStyle.BASIC,
            quietZoneModules = 6,
            explicitQuietZone = 3,
            directionalQuietZone = null,
            backdropStyle = BackdropStyle(fractionalQuietZone = null)
        )
        val resolved3 = QrGeometry.resolveQuietZone(designLevel3, matrixSize)
        assertEquals(ResolvedQuietZone(3f, 3f, 3f, 3f), resolved3)

        // Level 4: quietZoneModules overrides style default
        val designLevel4 = QrDesign(
            style = QrStyle.BASIC,
            quietZoneModules = 5,
            explicitQuietZone = null,
            directionalQuietZone = null,
            backdropStyle = BackdropStyle(fractionalQuietZone = null)
        )
        val resolved4 = QrGeometry.resolveQuietZone(designLevel4, matrixSize)
        assertEquals(ResolvedQuietZone(5f, 5f, 5f, 5f), resolved4)

        // Level 5: style default applied when explicit configurations are omitted
        val designLevel5D25 = QrDesign(
            style = QrStyle.D25,
            quietZoneModules = 0,
            explicitQuietZone = null,
            directionalQuietZone = null,
            backdropStyle = BackdropStyle(fractionalQuietZone = null)
        )
        val resolved5D25 = QrGeometry.resolveQuietZone(designLevel5D25, matrixSize)
        assertEquals(ResolvedQuietZone(0f, 0f, 0f, 0f), resolved5D25)

        val designLevel5Basic = QrDesign(
            style = QrStyle.BASIC,
            explicitQuietZone = null,
            directionalQuietZone = null,
            backdropStyle = BackdropStyle(fractionalQuietZone = null)
        )
        val resolved5Basic = QrGeometry.resolveQuietZone(designLevel5Basic, matrixSize)
        assertEquals(ResolvedQuietZone(1f, 1f, 1f, 1f), resolved5Basic)
    }

    @Test
    fun testRasterAndSvgQuietZoneParity() {
        val matrix = QrMatrix("https://veilframe.app/step1-qz-parity", ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = QrStyle.BASIC,
            quietZoneModules = 4,
            explicitQuietZone = null
        )

        val geometry = QrGeometry.fromDesign(matrix.size, 580, 580, design)
        val svg = SvgExporter.generateSvg(matrix, design)

        val expectedTotalModules = matrix.size + 8
        assertEquals(expectedTotalModules.toFloat(), geometry.totalModulesXFloat, 0.001f)
        assertEquals(expectedTotalModules.toFloat(), geometry.totalModulesYFloat, 0.001f)

        // Geometry offsets normalized to module size must equal 4.0 modules
        assertEquals(4.0f, geometry.offsetX / geometry.moduleSize, 0.001f)
        assertEquals(4.0f, geometry.offsetY / geometry.moduleSize, 0.001f)

        // SVG viewBox must match the exact total module count
        assertTrue("SVG viewBox must match totalModules $expectedTotalModules", svg.contains("viewBox=\"0 0 $expectedTotalModules $expectedTotalModules\""))
    }

    @Test
    fun testFractionalQuietZoneFloatPreservationInRasterAndSvg() {
        // 10% margin on Version 1 QR code (matrix size 21): 21 * 0.10 = 2.1 modules on each side
        // Total extent = 21 + 2 * 2.1 = 25.2 modules
        val matrix = QrMatrix("VEIL", ErrorCorrectionLevel.M)
        assertEquals(21, matrix.size)

        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(
                fractionalQuietZone = FractionalInsets(0.10f, 0.10f, 0.10f, 0.10f)
            )
        )

        val geometry = QrGeometry.fromDesign(matrix.size, 504, 504, design)
        assertEquals(2.1f, geometry.quietZoneLeftFloat, 0.001f)
        assertEquals(2.1f, geometry.quietZoneTopFloat, 0.001f)
        assertEquals(2.1f, geometry.quietZoneRightFloat, 0.001f)
        assertEquals(2.1f, geometry.quietZoneBottomFloat, 0.001f)
        assertEquals(25.2f, geometry.totalModulesXFloat, 0.001f)
        assertEquals(25.2f, geometry.totalModulesYFloat, 0.001f)

        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("SVG viewBox must preserve fractional extent 25.2", svg.contains("viewBox=\"0 0 25.2 25.2\""))
    }

    @Test
    fun testImageStyleLogoPositioningAnchoredToMatrixNotCanvas() {
        val dummyBmp = allocateBitmapReflectively()
        val matrix = QrMatrix("https://veilframe.app/step1-logo-anchoring", ErrorCorrectionLevel.H)

        // Asymmetric quiet zone: left=6, top=2, right=2, bottom=2
        val design = QrDesign(
            style = QrStyle.IMAGE,
            directionalQuietZone = DirectionalInsets(left = 6, top = 2, right = 2, bottom = 2),
            logo = LogoStyle(
                bitmap = dummyBmp,
                scaleFraction = 0.20f
            )
        )

        val outputSize = 600
        val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)
        val contentWidth = matrix.size * geometry.moduleSize
        val expectedLogoCenterX = geometry.offsetX + contentWidth / 2f
        val canvasCenterX = outputSize / 2f // 300f

        // With asymmetric quiet zone (left=6 vs right=2), matrix center is shifted relative to canvas center
        assertNotEquals(canvasCenterX, expectedLogoCenterX, 1.0f)

        // Generate IR geometry through ImageRenderer
        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)
        val imgNode = ir.rootNodes.filterIsInstance<ImageNode>().lastOrNull()
        assertNotNull("Must include logo ImageNode", imgNode)

        val actualLogoCenterX = imgNode!!.x + imgNode.width / 2f
        val actualLogoCenterY = imgNode.y + imgNode.height / 2f

        assertEquals("Logo X center must be anchored to matrix content bounds, not canvas center", expectedLogoCenterX, actualLogoCenterX, 0.1f)
        assertEquals("Logo Y center must be anchored to matrix content bounds, not canvas center", geometry.offsetY + contentWidth / 2f, actualLogoCenterY, 0.1f)
    }
}
