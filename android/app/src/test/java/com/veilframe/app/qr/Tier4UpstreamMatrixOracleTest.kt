package com.veilframe.app.qr

import com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel
import com.veilframe.app.qr.encoder.engine.VeilQrEncoder
import org.junit.Assert.*
import org.junit.Test

/**
 * ADR 0003 Tier 4: Upstream Matrix Oracle Verification Suite.
 *
 * Verifies bit-for-bit, module-for-module mathematical and topological equivalence
 * between VeilFrame's [VeilQrEncoder] and upstream Swift `QRCodeSwift` / `EFQRCode 7.0.3`
 * across all error correction levels (L, M, Q, H), versions 1 to 26+, multi-byte UTF-8,
 * deep URLs, empty payloads, and stress boundaries.
 */
class Tier4UpstreamMatrixOracleTest {

    data class OracleVector(
        val name: String,
        val text: String,
        val level: VeilCorrectionLevel,
        val version: Int,
        val size: Int,
        val bitString: String
    )

    private fun loadOracleVectors(): List<OracleVector> {
        val stream = javaClass.classLoader?.getResourceAsStream("tier4_upstream_oracle_matrices.json")
            ?: javaClass.getResourceAsStream("/tier4_upstream_oracle_matrices.json")
            ?: error("tier4_upstream_oracle_matrices.json not found in test resources")
        val jsonText = stream.bufferedReader().readText().replace("\r\n", "\n")
        val list = mutableListOf<OracleVector>()

        // Robust parsing without external reflection dependencies
        val blocks = jsonText.split("{\n").drop(1)
        for (b in blocks) {
            val name = Regex(""""name":\s*"([^"]+)"""").find(b)?.groupValues?.get(1) ?: continue
            val text = Regex(""""text":\s*"([^"]*)"""").find(b)?.groupValues?.get(1) ?: continue
            val level = Regex(""""level":\s*"([^"]+)"""").find(b)?.groupValues?.get(1) ?: continue
            val version = Regex(""""version":\s*(\d+)""").find(b)?.groupValues?.get(1)?.toInt() ?: continue
            val size = Regex(""""size":\s*(\d+)""").find(b)?.groupValues?.get(1)?.toInt() ?: continue
            val bitString = Regex(""""bitString":\s*"([^"]+)"""").find(b)?.groupValues?.get(1) ?: continue
            list.add(OracleVector(name, text, VeilCorrectionLevel.valueOf(level), version, size, bitString))
        }
        return list
    }

    @Test
    fun testTier4CompleteOracleSuiteBitForBitEquivalence() {
        val vectors = loadOracleVectors()
        assertTrue("Oracle corpus must contain at least 126 vectors, found: ${vectors.size}", vectors.size >= 126)

        var totalModulesVerified = 0L
        val coveredVersionsAndLevels = mutableSetOf<Pair<Int, VeilCorrectionLevel>>()

        for (vector in vectors) {
            coveredVersionsAndLevels.add(Pair(vector.version, vector.level))

            val encoded = VeilQrEncoder.encode(vector.text, vector.level)
            val matrix = encoded.matrix
            val size = matrix.size

            assertEquals("${vector.name}: version mismatch", vector.version, encoded.version)
            assertEquals("${vector.name}: module size mismatch", vector.size, size)

            // Bit-for-bit assertion across all coordinates
            for (row in 0 until vector.size) {
                for (col in 0 until vector.size) {
                    val expectedDark = vector.bitString[row * vector.size + col] == '1'
                    val actualDark = matrix.isDark(col, row)
                    if (expectedDark != actualDark) {
                        fail(
                            "Bit divergence in ${vector.name} at (col=$col, row=$row): " +
                                "expected upstream bit=$expectedDark, actual VeilQrEncoder bit=$actualDark " +
                                "(Version ${vector.version}, EC ${vector.level})"
                        )
                    }
                    totalModulesVerified++
                }
            }
        }

        // Validate exhaustive matrix coverage across Versions 1..26 x L/M/Q/H
        for (v in 1..26) {
            for (lvl in VeilCorrectionLevel.values()) {
                assertTrue("Exhaustive matrix must cover Version $v at level $lvl", coveredVersionsAndLevels.contains(Pair(v, lvl)))
            }
        }

        assertTrue("Verified more than 500,000 QR modules without a single bit divergence", totalModulesVerified > 500_000)
    }

    @Test
    fun testCanonicalUpstreamSimple() {
        val vector = loadOracleVectors().first { it.name == "UPSTREAM_SIMPLE" }
        val encoded = VeilQrEncoder.encode(vector.text, vector.level)
        assertEquals(5, encoded.version)
        assertEquals(37, encoded.matrix.size)

        for (row in 0 until 37) {
            for (col in 0 until 37) {
                val expectedDark = vector.bitString[row * 37 + col] == '1'
                assertEquals("Simple test mismatch at ($col, $row)", expectedDark, encoded.matrix.isDark(col, row))
            }
        }
    }

    @Test
    fun testCanonicalUpstreamLowEc() {
        val vector = loadOracleVectors().first { it.name == "UPSTREAM_LOW_EC" }
        val encoded = VeilQrEncoder.encode(vector.text, vector.level)
        assertEquals(5, encoded.version)
        assertEquals(37, encoded.matrix.size)

        for (row in 0 until 37) {
            for (col in 0 until 37) {
                val expectedDark = vector.bitString[row * 37 + col] == '1'
                assertEquals("Low EC mismatch at ($col, $row)", expectedDark, encoded.matrix.isDark(col, row))
            }
        }
    }

    @Test
    fun testCanonicalUpstreamBorderless() {
        val vector = loadOracleVectors().first { it.name == "UPSTREAM_BORDERLESS" }
        val encoded = VeilQrEncoder.encode(vector.text, vector.level)
        assertEquals(4, encoded.version)
        assertEquals(33, encoded.matrix.size)

        for (row in 0 until 33) {
            for (col in 0 until 33) {
                val expectedDark = vector.bitString[row * 33 + col] == '1'
                assertEquals("Borderless mismatch at ($col, $row)", expectedDark, encoded.matrix.isDark(col, row))
            }
        }
    }

    @Test
    fun testCanonicalUpstreamEfQrCode() {
        val vector = loadOracleVectors().first { it.name == "UPSTREAM_EFQRCODE" }
        val encoded = VeilQrEncoder.encode(vector.text, vector.level)
        assertEquals(5, encoded.version)
        assertEquals(37, encoded.matrix.size)

        for (row in 0 until 37) {
            for (col in 0 until 37) {
                val expectedDark = vector.bitString[row * 37 + col] == '1'
                assertEquals("EFQRCode test mismatch at ($col, $row)", expectedDark, encoded.matrix.isDark(col, row))
            }
        }
    }

    @Test
    fun testCanonicalUpstreamEmpty() {
        val vector = loadOracleVectors().first { it.name == "UPSTREAM_EMPTY" }
        val encoded = VeilQrEncoder.encode(vector.text, vector.level)
        assertEquals(1, encoded.version)
        assertEquals(21, encoded.matrix.size)

        for (row in 0 until 21) {
            for (col in 0 until 21) {
                val expectedDark = vector.bitString[row * 21 + col] == '1'
                assertEquals("Empty test mismatch at ($col, $row)", expectedDark, encoded.matrix.isDark(col, row))
            }
        }
    }

    @Test
    fun testCanonicalUpstreamPiStressVersion26() {
        val vector = loadOracleVectors().first { it.name == "UPSTREAM_PI_STRESS_V26" }
        val encoded = VeilQrEncoder.encode(vector.text, vector.level)
        assertEquals(26, encoded.version)
        assertEquals(121, encoded.matrix.size)

        for (row in 0 until 121) {
            for (col in 0 until 121) {
                val expectedDark = vector.bitString[row * 121 + col] == '1'
                assertEquals("Pi stress mismatch at ($col, $row)", expectedDark, encoded.matrix.isDark(col, row))
            }
        }
    }
}
