package com.veilframe.app.cv.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

class SauvolaBinarizationTest {

    @Test
    fun testSauvolaFormulaFlatBackgroundSuppression() {
        // Flat white paper region (m = 200, s = 0)
        // Sauvola formula: T = m * (1 + k * (s/R - 1))
        // With s = 0, k = 0.2, R = 128: T = 200 * (1 - 0.2) = 160.0
        val m = 200.0
        val s = 0.0
        val threshold = Preprocessor.sauvolaThreshold(m, s, k = 0.2, R = 128.0)
        assertEquals(160.0, threshold, 0.001)

        // The background pixel value (200) is > threshold (160), so it correctly classifies as paper (255)
        assertTrue("Flat white paper must be strictly above threshold to prevent speckle noise", m > threshold)
    }

    @Test
    fun testSauvolaFormulaHighContrastTextEdge() {
        // Text edge region (m = 128, s = 64)
        // s/R = 64/128 = 0.5 -> T = 128 * (1 + 0.2 * (0.5 - 1)) = 128 * 0.9 = 115.2
        val m = 128.0
        val s = 64.0
        val threshold = Preprocessor.sauvolaThreshold(m, s, k = 0.2, R = 128.0)
        assertEquals(115.2, threshold, 0.001)

        val inkPixel = 80.0
        val paperPixel = 140.0
        assertTrue("Ink pixel must be <= threshold", inkPixel <= threshold)
        assertTrue("Paper pixel must be > threshold", paperPixel > threshold)
    }

    @Test
    fun testSauvolaFormulaDeepShadowedDocument() {
        // Deep shadow covering document: local mean is low (m = 50), text variation exists (s = 25)
        // s/R = 25/128 = 0.1953125
        // T = 50 * (1 + 0.2 * (0.1953125 - 1)) = 50 * (1 - 0.1609375) = 41.953125
        val m = 50.0
        val s = 25.0
        val threshold = Preprocessor.sauvolaThreshold(m, s, k = 0.2, R = 128.0)
        assertEquals(41.953125, threshold, 0.001)

        val shadowedInk = 30.0
        val shadowedPaper = 50.0
        assertTrue("Shadowed ink must be detected under harsh shadow", shadowedInk <= threshold)
        assertTrue("Shadowed paper must remain white under harsh shadow", shadowedPaper > threshold)
    }

    @Test
    fun testSauvolaFormulaDynamicKFactor() {
        val m = 100.0
        val s = 32.0 // s/R = 0.25 -> (s/R - 1) = -0.75

        // With k = 0.2: T = 100 * (1 - 0.15) = 85.0
        val t1 = Preprocessor.sauvolaThreshold(m, s, k = 0.2, R = 128.0)
        assertEquals(85.0, t1, 0.001)

        // With k = 0.5: T = 100 * (1 - 0.375) = 62.5
        val t2 = Preprocessor.sauvolaThreshold(m, s, k = 0.5, R = 128.0)
        assertEquals(62.5, t2, 0.001)
    }

    @Test
    fun testSauvolaFormulaWhenStandardDeviationEqualsR() {
        // When s == R, s/R - 1 == 0, so threshold equals mean m exactly
        val m = 150.0
        val s = 128.0
        val threshold = Preprocessor.sauvolaThreshold(m, s, k = 0.2, R = 128.0)
        assertEquals(150.0, threshold, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSauvolaWindowSizeMustBeOdd() {
        val dummy = allocateDummyMat()
        Preprocessor.sauvolaBinarize(dummy, dummy, windowSize = 50)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSauvolaWindowSizeMustBeAtLeastThree() {
        val dummy = allocateDummyMat()
        Preprocessor.sauvolaBinarize(dummy, dummy, windowSize = 1)
    }

    private fun allocateDummyMat(): Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Mat::class.java) as Mat
    }
}
