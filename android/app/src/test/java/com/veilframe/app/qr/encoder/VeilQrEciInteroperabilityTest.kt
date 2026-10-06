package com.veilframe.app.qr.encoder

import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.GenerationMode
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.decoder.ZxingQrDecoder
import com.veilframe.app.qr.encoder.engine.QRCodeModel
import com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel
import com.veilframe.app.qr.encoder.engine.VeilQrEncoder
import com.veilframe.app.qr.model.ErrorCorrectionChoice
import com.veilframe.app.qr.model.PaletteStyle
import com.veilframe.app.qr.model.QrDesign
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Complete ECI (Extended Channel Interpretation) Interoperability & Capacity Test Corpus.
 *
 * Mathematically and empirically verifies:
 * 1. Low-level ECI bitstream structure (ECI mode 0b0111 + assignment 26 for UTF-8).
 * 2. ECI capacity overhead in QR Version/typeNumber selection (12-bit header stepping up version at capacity boundary).
 * 3. Complete cross-mode encoding & decoding interoperability matrix:
 *    - ASCII + no ECI
 *    - ASCII + explicit ECI 26
 *    - UTF-8 non-ASCII + ECI 26
 *    - UTF-8 non-ASCII + no ECI (raw byte mode)
 *    Across PARITY_EF, ARTISTIC_ENGINE, and SAFE (ZXing).
 * 4. End-to-end decode verification through ZXing decoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VeilQrEciInteroperabilityTest {

    private val decoder = ZxingQrDecoder()

    @Test
    fun testLowLevelBitstreamHeader_WithAndWithoutEci() {
        val payload = "Hello"
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)

        // 1. Without ECI: First 4 bits must be Byte Mode (0b0100 = 4)
        val dataNoEci = QRCodeModel.createData(1, VeilCorrectionLevel.M, payloadBytes, writeEci = false)
        assertTrue("Data cache must not be empty", dataNoEci.isNotEmpty())
        // First byte contains: 4 bits mode (0b0100) + top 4 bits of length (0) = 0x40
        val firstByteNoEci = dataNoEci[0]
        assertEquals("Without ECI, top 4 bits must be Byte mode (0b0100)", 0x40, firstByteNoEci and 0xF0)

        // 2. With ECI: First 4 bits must be ECI Mode (0b0111 = 7), followed by assignment 26 (0x1A)
        val dataWithEci = QRCodeModel.createData(1, VeilCorrectionLevel.M, payloadBytes, writeEci = true)
        assertTrue("Data cache must not be empty", dataWithEci.isNotEmpty())
        // Top 4 bits of first byte: 0b0111 = 0x70
        val firstByteWithEci = dataWithEci[0]
        assertEquals("With ECI, top 4 bits must be ECI mode (0b0111)", 0x70, firstByteWithEci and 0xF0)
        // Next 8 bits are assignment 26 (0x1A): lower 4 bits of byte 0 (0x1) and upper 4 bits of byte 1 (0xA)
        val assignment = ((dataWithEci[0] and 0x0F) shl 4) or ((dataWithEci[1] ushr 4) and 0x0F)
        assertEquals("ECI assignment for UTF-8 must be 26 (0x1A)", 26, assignment)
        // Following 4 bits must be Byte mode (0b0100): lower 4 bits of byte 1
        assertEquals("Following 4 bits must be Byte mode (0b0100)", 0x4, dataWithEci[1] and 0x0F)
    }

    @Test
    fun testEciOverhead_ForcesVersionStepUpAtCapacityBoundary() {
        // Version 1 EC Level L capacity:
        // Total data capacity = 19 codewords = 152 bits.
        // Byte mode without ECI: 4 bits mode + 8 bits length = 12 bits header -> (152 - 12) / 8 = 17 bytes max.
        // Byte mode with ECI: 4 bits ECI + 8 bits assignment + 4 bits mode + 8 bits length = 24 bits header -> (152 - 24) / 8 = 16 bytes max.
        val boundary17Bytes = "12345678901234567" // Exactly 17 bytes
        assertEquals(17, boundary17Bytes.toByteArray(Charsets.UTF_8).size)

        // Without ECI: 17 bytes fits in Version 1 (size = 21 modules)
        val matrixNoEci = VeilQrEncoder.encode(boundary17Bytes, ErrorCorrectionLevel.L, writeEci = false).matrix
        assertEquals("17 bytes without ECI must fit in Version 1 (size 21)", 21, matrixNoEci.size)

        // With ECI: 17 bytes requires 160 bits > 152 bits capacity of Version 1 -> MUST step up to Version 2 (size 25 modules)
        val matrixWithEci = VeilQrEncoder.encode(boundary17Bytes, ErrorCorrectionLevel.L, writeEci = true).matrix
        assertEquals("17 bytes with ECI must step up to Version 2 (size 25) due to ECI overhead", 25, matrixWithEci.size)
    }

    @Test
    fun testEciInteroperabilityMatrix_ParityEfMode() = runBlocking {
        // PARITY_EF Mode:
        // 1. By default, forceEci is null -> writeEci is false (EFQRCode 7.0.3 / QRCodeSwift 2.3.1 parity)
        // 2. When forceEci = true -> writeEci is true
        val asciiPayload = "https://veilframe.app/parity-ascii"
        val unicodePayload = "https://veilframe.app/日本語/arabic/عربي/emoji/🚀"

        // Case A: ASCII with default EF parity (no ECI)
        val designA = QrDesign(style = QrStyle.BASIC, forceEci = null, explicitQuietZone = 4)
        val resA = QrGenerator.generateParity(asciiPayload, designA)
        assertTrue(resA is QrRenderResult.Success)
        val decodeA = decoder.decode((resA as QrRenderResult.Success).bitmap!!)
        assertTrue("ASCII under PARITY_EF default must decode", decodeA.success)
        assertEquals(asciiPayload, decodeA.text)

        // Case B: Unicode with default EF parity (raw UTF-8 bytes without ECI header)
        val designB = QrDesign(style = QrStyle.BASIC, forceEci = null, explicitQuietZone = 4)
        val resB = QrGenerator.generateParity(unicodePayload, designB)
        assertTrue(resB is QrRenderResult.Success)
        val decodeB = decoder.decode((resB as QrRenderResult.Success).bitmap!!)
        assertTrue("Unicode under PARITY_EF default (raw UTF-8) must decode", decodeB.success)
        assertEquals(unicodePayload, decodeB.text)

        // Case C: Unicode with explicit forceEci = true under PARITY_EF
        val designC = QrDesign(style = QrStyle.BASIC, forceEci = true, explicitQuietZone = 4)
        val resC = QrGenerator.generateParity(unicodePayload, designC)
        assertTrue(resC is QrRenderResult.Success)
        val decodeC = decoder.decode((resC as QrRenderResult.Success).bitmap!!)
        assertTrue("Unicode under PARITY_EF with forceEci=true must decode", decodeC.success)
        assertEquals(unicodePayload, decodeC.text)

        // Case D: ASCII with explicit forceEci = true under PARITY_EF
        val designD = QrDesign(style = QrStyle.BASIC, forceEci = true, explicitQuietZone = 4)
        val resD = QrGenerator.generateParity(asciiPayload, designD)
        assertTrue(resD is QrRenderResult.Success)
        val decodeD = decoder.decode((resD as QrRenderResult.Success).bitmap!!)
        assertTrue("ASCII under PARITY_EF with forceEci=true must decode", decodeD.success)
        assertEquals(asciiPayload, decodeD.text)
    }

    @Test
    fun testEciInteroperabilityMatrix_ArtisticEngineMode() = runBlocking {
        // ARTISTIC_ENGINE Mode:
        // 1. By default, forceEci is null: non-ASCII triggers ECI 26, ASCII does not
        // 2. forceEci = false forces raw UTF-8 bytes (no ECI)
        // 3. forceEci = true forces ECI header even for ASCII
        val asciiPayload = "https://veilframe.app/artistic-ascii"
        val unicodePayload = "VeilFrame 藝術風格 — Arabic العربية — Emoji ✨🎨"

        // Case A: ASCII with default auto policy (no ECI)
        val designA = QrDesign(style = QrStyle.BASIC, forceEci = null, explicitQuietZone = 4)
        val resA = QrGenerator.generateArtistic(asciiPayload, designA)
        assertTrue(resA is QrRenderResult.Success)
        val decodeA = decoder.decode((resA as QrRenderResult.Success).bitmap!!)
        assertTrue("ASCII under ARTISTIC_ENGINE default must decode", decodeA.success)
        assertEquals(asciiPayload, decodeA.text)

        // Case B: Unicode with default auto policy (emits ECI 26)
        val designB = QrDesign(style = QrStyle.BASIC, forceEci = null, explicitQuietZone = 4)
        val resB = QrGenerator.generateArtistic(unicodePayload, designB)
        assertTrue(resB is QrRenderResult.Success)
        val decodeB = decoder.decode((resB as QrRenderResult.Success).bitmap!!)
        assertTrue("Unicode under ARTISTIC_ENGINE default (with ECI 26) must decode", decodeB.success)
        assertEquals(unicodePayload, decodeB.text)

        // Case C: Unicode with explicit forceEci = false (raw UTF-8 bytes without ECI)
        val designC = QrDesign(style = QrStyle.BASIC, forceEci = false, explicitQuietZone = 4)
        val resC = QrGenerator.generateArtistic(unicodePayload, designC)
        assertTrue(resC is QrRenderResult.Success)
        val decodeC = decoder.decode((resC as QrRenderResult.Success).bitmap!!)
        assertTrue("Unicode under ARTISTIC_ENGINE with forceEci=false must decode", decodeC.success)
        assertEquals(unicodePayload, decodeC.text)

        // Case D: ASCII with explicit forceEci = true (emits ECI 26 header for ASCII)
        val designD = QrDesign(style = QrStyle.BASIC, forceEci = true, explicitQuietZone = 4)
        val resD = QrGenerator.generateArtistic(asciiPayload, designD)
        assertTrue(resD is QrRenderResult.Success)
        val decodeD = decoder.decode((resD as QrRenderResult.Success).bitmap!!)
        assertTrue("ASCII under ARTISTIC_ENGINE with forceEci=true must decode", decodeD.success)
        assertEquals(asciiPayload, decodeD.text)
    }

    @Test
    fun testEciInteroperabilityMatrix_SafeZxingMode() = runBlocking {
        // SAFE Mode:
        // Uses standard ISO/IEC 18004 ZXing encoder and enforces 4-module quiet zone
        val asciiPayload = "https://veilframe.app/safe-production-ascii"
        val unicodePayload = "VeilFrame ISO Compliance: 日本語 / 한국어 / 🌍"

        val designAscii = QrDesign(style = QrStyle.BASIC, explicitQuietZone = 4)
        val resAscii = QrGenerator.generateSafe(asciiPayload, designAscii)
        assertTrue(resAscii is QrRenderResult.Success)
        val decodeAscii = decoder.decode((resAscii as QrRenderResult.Success).bitmap!!)
        assertTrue("ASCII under SAFE mode must decode", decodeAscii.success)
        assertEquals(asciiPayload, decodeAscii.text)

        val designUnicode = QrDesign(style = QrStyle.BASIC, explicitQuietZone = 4)
        val resUnicode = QrGenerator.generateSafe(unicodePayload, designUnicode)
        assertTrue(resUnicode is QrRenderResult.Success)
        val decodeUnicode = decoder.decode((resUnicode as QrRenderResult.Success).bitmap!!)
        assertTrue("Unicode under SAFE mode must decode", decodeUnicode.success)
        assertEquals(unicodePayload, decodeUnicode.text)
    }
}

