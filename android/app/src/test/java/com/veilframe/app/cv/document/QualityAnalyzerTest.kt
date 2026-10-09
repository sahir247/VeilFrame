package com.veilframe.app.cv.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat

class QualityAnalyzerTest {

    @Test
    fun testEvaluateIdealCondition() {
        val metrics = QualityAnalyzer.evaluate(
            meanLuma = 120.0,
            lapVariance = 250.0,
            glarePct = 0.5
        )

        assertTrue("High variance must be considered sharp", metrics.isSharp)
        assertFalse("120 luma must not be too dark", metrics.isTooDark)
        assertFalse("0.5% glare must not trigger glare warning", metrics.hasGlare)
        assertEquals(250.0, metrics.sharpnessVariance, 0.001)
        assertEquals(120.0, metrics.meanLuma, 0.001)
        assertEquals(0.5, metrics.glarePercentage, 0.001)
    }

    @Test
    fun testEvaluateTooDarkCondition() {
        val metrics = QualityAnalyzer.evaluate(
            meanLuma = 35.0, // < 45.0 threshold
            lapVariance = 200.0,
            glarePct = 0.0
        )

        assertTrue("Mean luma below 45 must trigger isTooDark", metrics.isTooDark)
        assertTrue(metrics.isSharp)
        assertFalse(metrics.hasGlare)
    }

    @Test
    fun testEvaluateBlurryCondition() {
        val metrics = QualityAnalyzer.evaluate(
            meanLuma = 130.0,
            lapVariance = 90.0, // < 150.0 threshold
            glarePct = 0.0
        )

        assertFalse("Variance below 150 must trigger blur warning", metrics.isSharp)
        assertFalse(metrics.isTooDark)
        assertFalse(metrics.hasGlare)
    }

    @Test
    fun testEvaluateGlareCondition() {
        val metrics = QualityAnalyzer.evaluate(
            meanLuma = 160.0,
            lapVariance = 300.0,
            glarePct = 4.8 // > 3.0 threshold
        )

        assertTrue("Glare percentage above 3.0% must trigger hasGlare", metrics.hasGlare)
        assertTrue(metrics.isSharp)
        assertFalse(metrics.isTooDark)
    }

    @Test
    fun testEvaluateThresholdBoundaries() {
        // Exactly at boundary
        val atBoundary = QualityAnalyzer.evaluate(
            meanLuma = QualityAnalyzer.MIN_LUMA_THRESHOLD,
            lapVariance = QualityAnalyzer.SHARPNESS_VARIANCE_THRESHOLD,
            glarePct = QualityAnalyzer.GLARE_PERCENTAGE_THRESHOLD
        )

        assertFalse("Exactly at min luma is not too dark", atBoundary.isTooDark)
        assertTrue("Exactly at threshold is sharp", atBoundary.isSharp)
        assertFalse("Exactly at max glare threshold does not trigger glare", atBoundary.hasGlare)

        // Just below boundary
        val justBelow = QualityAnalyzer.evaluate(
            meanLuma = QualityAnalyzer.MIN_LUMA_THRESHOLD - 0.1,
            lapVariance = QualityAnalyzer.SHARPNESS_VARIANCE_THRESHOLD - 0.1,
            glarePct = QualityAnalyzer.GLARE_PERCENTAGE_THRESHOLD + 0.1
        )

        assertTrue(justBelow.isTooDark)
        assertFalse(justBelow.isSharp)
        assertTrue(justBelow.hasGlare)
    }

    @Test
    fun testCustomThresholds() {
        val custom = QualityAnalyzer.evaluate(
            meanLuma = 55.0,
            lapVariance = 110.0,
            glarePct = 2.0,
            minLuma = 60.0,
            minSharpness = 100.0,
            maxGlare = 1.5
        )

        assertTrue("55 < custom minLuma 60", custom.isTooDark)
        assertTrue("110 >= custom minSharpness 100", custom.isSharp)
        assertTrue("2.0 > custom maxGlare 1.5", custom.hasGlare)
    }

    @Test
    fun testAnalyzeJvmSafeFallback() {
        // When OpenCV native library is not loaded on JVM, analyze must return DEFAULT safely
        val dummyMat = allocateDummyMat()
        val dummyMatOfDouble = allocateDummyMatOfDouble()
        val metrics = QualityAnalyzer.analyze(
            source = dummyMat,
            laplacianMat = dummyMat,
            glareMaskMat = dummyMat,
            meanMat = dummyMatOfDouble,
            stddevMat = dummyMatOfDouble
        )

        assertEquals(QualityMetrics.DEFAULT, metrics)
    }

    private fun allocateDummyMat(): Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Mat::class.java) as Mat
    }

    private fun allocateDummyMatOfDouble(): org.opencv.core.MatOfDouble {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, org.opencv.core.MatOfDouble::class.java) as org.opencv.core.MatOfDouble
    }
}
