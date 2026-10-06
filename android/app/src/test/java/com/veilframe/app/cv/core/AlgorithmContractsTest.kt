package com.veilframe.app.cv.core

import com.veilframe.app.cv.crop.SmartAutoCrop
import com.veilframe.app.cv.motion.FlowEstimator
import com.veilframe.app.cv.motion.FrameSynthesizer
import com.veilframe.app.cv.segmentation.BackgroundRemover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmContractsTest {

    @Test
    fun smartAutoCropRejectsInvalidCustomRatio() {
        try {
            SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = null)
            org.junit.Assert.fail("Expected exception for null customRatio")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = -1.0)
            org.junit.Assert.fail("Expected exception for negative customRatio")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = Double.NaN)
            org.junit.Assert.fail("Expected exception for NaN customRatio")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun smartAutoCropAcceptsValidCustomRatio() {
        val options = SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = 1.33)
        assertEquals(1.33, options.customRatio!!, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun smartAutoCropRejectsInvalidMargin() {
        SmartAutoCrop.Options(margin = 0.6) // > 0.5
    }

    @Test(expected = IllegalArgumentException::class)
    fun smartAutoCropRejectsInvalidCenterBias() {
        SmartAutoCrop.Options(centerBias = 1.2) // > 1.0
    }

    @Test(expected = IllegalArgumentException::class)
    fun backgroundRemoverRejectsEvenCleanupKernel() {
        BackgroundRemover.Options(cleanupKernel = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun backgroundRemoverRejectsNegativeFeatherRadius() {
        BackgroundRemover.Options(featherRadius = -0.5)
    }

    @Test
    fun backgroundRemoverAcceptsValidOptions() {
        val options = BackgroundRemover.Options(cleanupKernel = 7, featherRadius = 3.5)
        assertEquals(7, options.cleanupKernel)
        assertEquals(3.5, options.featherRadius, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameSynthesizerRejectsZeroArtifactFactor() {
        FrameSynthesizer.Options(artifactFactor = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameSynthesizerRejectsNegativeMinConfidence() {
        FrameSynthesizer.Options(minConfidence = -0.1)
    }

    @Test
    fun flowScalePreservesIndependentAxes() {
        val scale = FlowEstimator.FlowScale(x = 0.5, y = 0.75)
        assertEquals(0.5, scale.x, 1e-6)
        assertEquals(0.75, scale.y, 1e-6)
        assertFalse(scale.isUniform)

        val uniformScale = FlowEstimator.FlowScale(x = 0.5, y = 0.5)
        assertTrue(uniformScale.isUniform)
    }
}
