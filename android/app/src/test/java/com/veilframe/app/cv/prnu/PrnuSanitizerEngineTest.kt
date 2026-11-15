package com.veilframe.app.cv.prnu

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
class PrnuSanitizerEngineTest {

    @Test
    fun testDefenseModeEnumValues() {
        val modes = PrnuSanitizerEngine.DefenseMode.values()
        assertEquals(5, modes.size)

        val metadataOnly = PrnuSanitizerEngine.DefenseMode.METADATA_ONLY
        assertEquals("Metadata Only", metadataOnly.displayName)
        assertTrue(metadataOnly.description.contains("Lossless"))

        val standard = PrnuSanitizerEngine.DefenseMode.STANDARD_FUSED_REMAP
        assertEquals("Standard (Fused Remap)", standard.displayName)
        assertTrue(standard.description.contains("sPCE"))

        val highSuppression = PrnuSanitizerEngine.DefenseMode.HIGH_SUPPRESSION
        assertEquals("High Suppression", highSuppression.displayName)
        assertTrue(highSuppression.description.contains("downsampling"))

        val high = PrnuSanitizerEngine.DefenseMode.HIGH_DEFENSE
        assertEquals("High Defense", high.displayName)

        val stealth = PrnuSanitizerEngine.DefenseMode.STEALTH_HYBRID
        assertEquals("Stealth (2-Stage Hybrid)", stealth.displayName)
        assertTrue(stealth.description.contains("VeilNet-Nano"))
    }

    @Test
    fun testGammaLutMathematicalProperties() {
        val gammaValues = listOf(0.985f, 1.0f, 1.015f)
        for (gamma in gammaValues) {
            val lut = ByteArray(256)
            for (i in 0..255) {
                val norm = i / 255.0f
                val adjusted = norm.pow(gamma)
                val px = (adjusted * 255.0f).roundToInt().coerceIn(0, 255)
                lut[i] = px.toByte()
            }

            // Verify endpoints
            assertEquals(0, lut[0].toInt() and 0xFF)
            assertEquals(255, lut[255].toInt() and 0xFF)

            // Verify monotonicity
            for (i in 1..255) {
                val prev = lut[i - 1].toInt() and 0xFF
                val curr = lut[i].toInt() and 0xFF
                assertTrue("LUT must be monotonic at i=$i (prev=$prev, curr=$curr)", curr >= prev)
            }
        }
    }

    @Test
    fun testSanitizeImageFileNonExistentInput() {
        val context = RuntimeEnvironment.getApplication()
        val dummyInput = File(context.cacheDir, "non_existent_image.jpg")
        val dummyOutput = File(context.cacheDir, "output.jpg")

        val result = PrnuSanitizerEngine.sanitizeImageFile(
            context = context,
            inputFile = dummyInput,
            outputFile = dummyOutput,
            mode = PrnuSanitizerEngine.DefenseMode.STEALTH_HYBRID
        )
        assertFalse("Sanitization should fail gracefully on non-existent input", result)
        assertFalse(dummyOutput.exists())
    }

    @Test
    fun testMetadataOnlyScrubHandling() {
        val context = RuntimeEnvironment.getApplication()
        val dummyInput = File(context.cacheDir, "test_input.jpg")
        dummyInput.writeBytes(ByteArray(128)) // Empty dummy file

        val dummyOutput = File(context.cacheDir, "test_output.jpg")
        val milestones = mutableListOf<String>()

        val result = PrnuSanitizerEngine.sanitizeImageFile(
            context = context,
            inputFile = dummyInput,
            outputFile = dummyOutput,
            mode = PrnuSanitizerEngine.DefenseMode.METADATA_ONLY,
            onMilestone = { milestones.add(it) }
        )

        assertTrue(milestones.any { it.contains("metadata scrub") })
        dummyInput.delete()
        dummyOutput.delete()
    }

    @Test
    fun testSelfAuditResultStructure() {
        val auditResult = PrnuSanitizerEngine.SelfAuditResult(
            baselineSpce = 4163892.0,
            postSpce = 0.53,
            post6StripMaxSpce = 6.88,
            stripSpces = listOf(-0.07, 0.0, 6.88, 0.36, -1.28, 0.0),
            verifiedSecure = true
        )
        assertTrue(auditResult.verifiedSecure)
        assertEquals(6, auditResult.stripSpces.size)
        assertTrue(auditResult.baselineSpce > 50.0)
        assertTrue(kotlin.math.abs(auditResult.postSpce) < 5.0)
        assertTrue(auditResult.post6StripMaxSpce < 8.0)
    }
}
