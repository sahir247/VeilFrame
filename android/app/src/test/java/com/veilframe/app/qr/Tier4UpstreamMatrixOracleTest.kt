package com.veilframe.app.qr

import com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel
import com.veilframe.app.qr.encoder.engine.VeilQrEncoder
import org.junit.Assert.*
import org.junit.Test

/**
 * ADR 0003 Tier 4A: Upstream Matrix Oracle Verification Suite.
 *
 * Mathematically proves bit-for-bit, module-for-module topological and bitstream equivalence
 * between VeilFrame's [VeilQrEncoder] and upstream Swift `QRCodeSwift` (pinned at revision
 * `d1605333f7edac39b4518538ef4f2638fdd2e4d6`, version 2.3.1, used by `EFQRCode 7.0.3`).
 *
 * Scope:
 * - Tier 4A (Active): Versions 1..26 x L/M/Q/H (104 systematic vectors + 22 named vectors = 126 vectors),
 *   verifying upstream-selected optimal penalty score mask pattern parity.
 * - Tier 4B (Roadmap): Extended version range (Versions 27..40) and forced mask patterns 0..7.
 */
class Tier4UpstreamMatrixOracleTest {

    data class OracleProvenance(
        val efQrCodeVersion: String,
        val qrCodeSwiftVersion: String,
        val qrCodeSwiftRevision: String,
        val generatorCommit: String
    )

    data class OracleVector(
        val name: String,
        val text: String,
        val level: VeilCorrectionLevel,
        val version: Int,
        val size: Int,
        val bitString: String
    )

    private fun loadOracleProvenance(): OracleProvenance {
        val stream = javaClass.classLoader?.getResourceAsStream("tier4_upstream_oracle_matrices.json")
            ?: javaClass.getResourceAsStream("/tier4_upstream_oracle_matrices.json")
            ?: error("tier4_upstream_oracle_matrices.json not found in test resources")
        val jsonText = stream.bufferedReader().readText().replace("\r\n", "\n")
        val efVer = Regex(""""efqrcode_version":\s*"([^"]+)"""").find(jsonText)?.groupValues?.get(1)
            ?: error("Missing efqrcode_version in oracle metadata")
        val swiftVer = Regex(""""qrcode_swift_version":\s*"([^"]+)"""").find(jsonText)?.groupValues?.get(1)
            ?: error("Missing qrcode_swift_version in oracle metadata")
        val swiftRev = Regex(""""qrcode_swift_revision":\s*"([^"]+)"""").find(jsonText)?.groupValues?.get(1)
            ?: error("Missing qrcode_swift_revision in oracle metadata")
        val commit = Regex(""""generator_commit":\s*"([^"]+)"""").find(jsonText)?.groupValues?.get(1)
            ?: error("Missing generator_commit in oracle metadata")
        return OracleProvenance(efVer, swiftVer, swiftRev, commit)
    }

    @Test
    fun testOracleProvenanceMetadataMatchesUpstreamPins() {
        val provenance = loadOracleProvenance()
        assertEquals("7.0.3", provenance.efQrCodeVersion)
        assertEquals("2.3.1", provenance.qrCodeSwiftVersion)
        assertEquals("d1605333f7edac39b4518538ef4f2638fdd2e4d6", provenance.qrCodeSwiftRevision)
        assertNotNull(provenance.generatorCommit)
    }

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
