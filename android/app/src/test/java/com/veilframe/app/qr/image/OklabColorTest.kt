package com.veilframe.app.qr.image

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Unit tests verifying Oklab and OKLCH color space transforms,
 * round-trip precision, perceptual color difference Delta E_OK,
 * and constant-hue gamut mapping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class OklabColorTest {

    @Test
    fun testSRgbToOklabRoundTrip() {
        val testColors = listOf(
            Color.BLACK,
            Color.WHITE,
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.CYAN,
            Color.MAGENTA,
            Color.YELLOW,
            Color.GRAY,
            Color.DKGRAY,
            Color.LTGRAY,
            0xFF39C5BC.toInt(), // Miku Hatsune cyan
            0xFFFFA500.toInt(), // Orange
            0xFF800080.toInt(), // Purple
            0xFF123456.toInt()  // Arbitrary deep blue
        )

        for (original in testColors) {
            val oklab = OklabColor.sRgbToOklab(original)
            val reconstructed = OklabColor.oklabToSRgb(oklab)

            val rDiff = abs(Color.red(original) - Color.red(reconstructed))
            val gDiff = abs(Color.green(original) - Color.green(reconstructed))
            val bDiff = abs(Color.blue(original) - Color.blue(reconstructed))

            assertTrue("Red channel drift <= 1 for ${Integer.toHexString(original)} (was $rDiff)", rDiff <= 1)
            assertTrue("Green channel drift <= 1 for ${Integer.toHexString(original)} (was $gDiff)", gDiff <= 1)
            assertTrue("Blue channel drift <= 1 for ${Integer.toHexString(original)} (was $bDiff)", bDiff <= 1)

            val de = OklabColor.deltaEOk(original, reconstructed)
            assertTrue("Delta E_OK must be < 0.005 for round trip (was $de)", de < 0.005f)
        }
    }

    @Test
    fun testOklabToOklchRoundTrip() {
        val testColors = listOf(
            0xFF39C5BC.toInt(),
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            0xFFFF69B4.toInt() // Hot pink
        )

        for (c in testColors) {
            val oklab = OklabColor.sRgbToOklab(c)
            val oklch = OklabColor.oklabToOklch(oklab)
            val backToOklab = OklabColor.oklchToOklab(oklch)

            assertEquals("Lightness L preserved", oklab.l, backToOklab.l, 0.0001f)
            assertEquals("Component a preserved", oklab.a, backToOklab.a, 0.0001f)
            assertEquals("Component b preserved", oklab.b, backToOklab.b, 0.0001f)

            assertTrue("Chroma C must be non-negative", oklch.c >= 0f)
            assertTrue("Hue h must be in [0, 360)", oklch.h in 0f..360f)
        }
    }

    @Test
    fun testGamutMappingPreservesHueAndLightness() {
        // Test an out-of-gamut coordinate with extreme chroma
        val targetL = 0.5f
        val extremeC = 0.6f // Far beyond sRGB gamut boundary
        val targetH = 195.0f // Cyan hue

        val mappedColor = OklabColor.gamutMapOklch(targetL, extremeC, targetH)
        val mappedOklch = OklabColor.sRgbToOklch(mappedColor)

        // Lightness should be closely preserved
        assertEquals("Lightness L should match targetL", targetL, mappedOklch.l, 0.03f)

        // Hue angle should be preserved within 1 degree
        assertEquals("Hue h should be preserved along constant-hue ray", targetH, mappedOklch.h, 1.0f)

        // Chroma should be reduced into sRGB gamut
        assertTrue("Chroma should be clamped into valid gamut (< extremeC)", mappedOklch.c < extremeC)
    }

    @Test
    fun testGamutMappingInGamutColorUnchanged() {
        val mikuColor = 0xFF39C5BC.toInt()
        val oklch = OklabColor.sRgbToOklch(mikuColor)

        val mapped = OklabColor.gamutMapOklch(oklch.l, oklch.c, oklch.h)
        val de = OklabColor.deltaEOk(mikuColor, mapped)
        assertTrue("In-gamut color mapped through gamutMapOklch should be virtually identical (dE = $de)", de < 0.01f)
    }

    @Test
    fun testDeltaEOkProperties() {
        // Distance to self is 0
        assertEquals(0f, OklabColor.deltaEOk(Color.BLACK, Color.BLACK), 0.00001f)
        assertEquals(0f, OklabColor.deltaEOk(Color.WHITE, Color.WHITE), 0.00001f)
        assertEquals(0f, OklabColor.deltaEOk(0xFF39C5BC.toInt(), 0xFF39C5BC.toInt()), 0.00001f)

        // Distance between Black and White is ~1.0
        val bwDist = OklabColor.deltaEOk(Color.BLACK, Color.WHITE)
        assertEquals(1.0f, bwDist, 0.01f)

        // Symmetry: d(A, B) == d(B, A)
        val dAB = OklabColor.deltaEOk(Color.RED, Color.BLUE)
        val dBA = OklabColor.deltaEOk(Color.BLUE, Color.RED)
        assertEquals(dAB, dBA, 0.0001f)

        // Similar colors have small Delta E
        val red1 = Color.rgb(255, 0, 0)
        val red2 = Color.rgb(250, 5, 5)
        val distClose = OklabColor.deltaEOk(red1, red2)
        assertTrue("Very close colors must have Delta E_OK < 0.05 (was $distClose)", distClose < 0.05f)
    }
}
