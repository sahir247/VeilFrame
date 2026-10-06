package com.veilframe.app.cv.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TemplateMatcherContractTest {

    @Test
    fun defaultOptionsAreValid() {
        val options = TemplateMatcher.Options()
        assertEquals(0.7, options.minConfidence, 1e-6)
        assertEquals(0.25, options.coarseStep, 1e-6)
        assertEquals(12, options.maxScales)
        assertEquals(4096, options.maxImageEdge)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoarseStepZeroToPreventInfiniteLoop() {
        TemplateMatcher.Options(coarseStep = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeCoarseStep() {
        TemplateMatcher.Options(coarseStep = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroMaxScales() {
        TemplateMatcher.Options(maxScales = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeMaxScales() {
        TemplateMatcher.Options(maxScales = -5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMinConfidenceAboveOne() {
        TemplateMatcher.Options(minConfidence = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMinConfidenceNegative() {
        TemplateMatcher.Options(minConfidence = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMinConfidenceNaN() {
        TemplateMatcher.Options(minConfidence = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedScaleRange() {
        TemplateMatcher.Options(scaleMin = 2.0, scaleMax = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroScaleMin() {
        TemplateMatcher.Options(scaleMin = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeMaxImageEdge() {
        TemplateMatcher.Options(maxImageEdge = 0)
    }
}
