package com.veilframe.app.cv.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat

/**
 * Unit tests verifying the mathematical behavior and contract guarantees of
 * the Gaussian High-Pass Homomorphic Filter in [Preprocessor].
 */
class HomomorphicFilterTest {

    @Test
    fun testHomomorphicTransferFunctionAtOriginDc() {
        // At DC (d = 0), the filter must attenuate slow-changing illumination by returning exactly gammaL.
        val gammaL = 0.4
        val gammaH = 1.4
        val h = Preprocessor.homomorphicTransferFunction(d = 0.0, d0 = 35.0, c = 1.0, gammaL = gammaL, gammaH = gammaH)
        assertEquals("Transfer function at origin (d=0) must equal gammaL exactly", gammaL, h, 0.0001)
        assertTrue("DC gain must be strictly less than 1.0 to suppress background shadows", h < 1.0)
    }

    @Test
    fun testHomomorphicTransferFunctionAtInfinity() {
        // At high spatial frequencies (d >> d0), the filter must amplify sharp text edges by approaching gammaH.
        val gammaL = 0.4
        val gammaH = 1.4
        val h = Preprocessor.homomorphicTransferFunction(d = 1000.0, d0 = 35.0, c = 1.0, gammaL = gammaL, gammaH = gammaH)
        assertEquals("Transfer function at high frequencies must approach gammaH asymptotically", gammaH, h, 0.0001)
        assertTrue("High frequency gain must be strictly greater than 1.0 to boost text contrast", h > 1.0)
    }

    @Test
    fun testHomomorphicTransferFunctionAtCutoffBoundary() {
        // At cutoff boundary d = d0 with c = 1.0:
        // H(d0) = (gammaH - gammaL) * (1 - exp(-1)) + gammaL
        // For gammaL = 0.4, gammaH = 1.4: (1.0) * (1 - 0.36787944) + 0.4 = 1.03212056
        val gammaL = 0.4
        val gammaH = 1.4
        val d0 = 35.0
        val expectedAtCutoff = (gammaH - gammaL) * (1.0 - Math.exp(-1.0)) + gammaL
        val actual = Preprocessor.homomorphicTransferFunction(d = d0, d0 = d0, c = 1.0, gammaL = gammaL, gammaH = gammaH)
        assertEquals("Transfer function at d0 must match theoretical Gaussian attenuation", expectedAtCutoff, actual, 0.0001)
    }

    @Test
    fun testHomomorphicTransferFunctionMonotonicity() {
        // As radial frequency distance d increases from DC to Nyquist, gain must monotonically increase.
        var previousGain = Preprocessor.homomorphicTransferFunction(d = 0.0)
        for (step in 1..200) {
            val d = step * 1.0
            val currentGain = Preprocessor.homomorphicTransferFunction(d = d)
            assertTrue("Gain at d=$d ($currentGain) must be >= gain at d=${d - 1} ($previousGain)", currentGain >= previousGain)
            previousGain = currentGain
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testHomomorphicTransferFunctionInvalidD0() {
        Preprocessor.homomorphicTransferFunction(d = 10.0, d0 = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testHomomorphicTransferFunctionInvalidC() {
        Preprocessor.homomorphicTransferFunction(d = 10.0, c = -0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testHomomorphicTransferFunctionInvalidGammaOrder() {
        Preprocessor.homomorphicTransferFunction(d = 10.0, gammaL = 1.5, gammaH = 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testHomomorphicFilterChannelContractValidation() {
        val dummy = allocateDummyMat()
        Preprocessor.homomorphicFilterChannel(dummy, dummy, d0 = -5.0)
    }

    @Test
    fun testHomomorphicFilterChannelFallbackWhenNativeUnavailable() {
        // When native OpenCV is unavailable, homomorphicFilterChannel must gracefully complete
        val dummy = allocateDummyMat()
        Preprocessor.homomorphicFilterChannel(dummy, dummy, gammaL = 0.4, gammaH = 1.4, c = 1.0, d0 = 35.0)
    }

    private fun allocateDummyMat(): Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Mat::class.java) as Mat
    }
}
