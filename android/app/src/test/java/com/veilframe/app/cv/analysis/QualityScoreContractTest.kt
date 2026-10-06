package com.veilframe.app.cv.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [QualityScore] policy math.
 *
 * Tests mathematical properties, not absolute scores.  The score is explicitly
 * an *indicator*, not an objective quality truth — but the indicators must have
 * monotone properties relative to the thing they measure.
 *
 * All tests run on the JVM without OpenCV.
 */
class QualityScoreContractTest {

    // ------------------------------------------------------------------ helpers

    private fun raw(
        varianceOfLaplacian: Double = 500.0,
        noiseSigma: Double = 0.0,
        meanLuma: Double = 128.0,
        clippedHighlightRatio: Double = 0.0,
        clippedShadowRatio: Double = 0.0,
        contrastRms: Double = 64.0,
        lumaSpreadP5P95: Double = 180.0,
        saturationMean: Double = 0.35,
        blockiness: Double = 0.0,
        ringing: Double = 0.0,
    ) = QualityRawMeasurements(
        varianceOfLaplacian = varianceOfLaplacian,
        noiseSigma = noiseSigma,
        meanLuma = meanLuma,
        clippedHighlightRatio = clippedHighlightRatio,
        clippedShadowRatio = clippedShadowRatio,
        contrastRms = contrastRms,
        lumaSpreadP5P95 = lumaSpreadP5P95,
        saturationMean = saturationMean,
        blockiness = blockiness,
        ringing = ringing,
    )

    // ------------------------------------------------------------------ overall range

    @Test
    fun `overall score is always 0 to 100`() {
        for (variant in listOf(
            raw(),
            raw(varianceOfLaplacian = 0.0),
            raw(noiseSigma = 1000.0),
            raw(meanLuma = 0.0),
            raw(meanLuma = 255.0),
            raw(clippedHighlightRatio = 1.0),
            raw(clippedShadowRatio = 1.0),
            raw(contrastRms = 0.0),
            raw(lumaSpreadP5P95 = 0.0),
            raw(blockiness = 1.0, ringing = 1.0),
        )) {
            val score = QualityScore.evaluate(variant).overall
            assertTrue("overall score must be >= 0 (got $score)", score >= 0)
            assertTrue("overall score must be <= 100 (got $score)", score <= 100)
        }
    }

    // ------------------------------------------------------------------ monotone properties

    @Test
    fun `higher sharpness never lowers sharpness component`() {
        val low  = QualityScore.saturate(10.0,  reference = 500.0)
        val high = QualityScore.saturate(1000.0, reference = 500.0)
        assertTrue("sharpness must increase with variance of Laplacian", high > low)
    }

    @Test
    fun `more noise never improves noise score`() {
        val clean = QualityScore.noiseScore(0.0)
        val noisy = QualityScore.noiseScore(10.0)
        assertTrue("noise score must decrease as sigma grows", clean > noisy)
    }

    @Test
    fun `extreme noise score approaches zero`() {
        assertTrue(QualityScore.noiseScore(1000.0) < 5)
    }

    @Test
    fun `zero noise gives maximum noise score`() {
        assertEquals(100, QualityScore.noiseScore(0.0))
    }

    @Test
    fun `mid-grey exposure is best`() {
        val perfect   = QualityScore.exposureScore(128.0)
        val dark      = QualityScore.exposureScore(0.0)
        val bright    = QualityScore.exposureScore(255.0)
        assertTrue("mid-grey must beat dark exposure",  perfect > dark)
        assertTrue("mid-grey must beat blown exposure", perfect > bright)
    }

    @Test
    fun `pure black and pure white are symmetrically penalised`() {
        val dark   = QualityScore.exposureScore(0.0)
        val bright = QualityScore.exposureScore(255.0)
        assertEquals("black and white are equidistant from mid-grey", dark, bright)
    }

    @Test
    fun `more clipping is always worse`() {
        val none = QualityScore.clippingScore(0.0)
        val some = QualityScore.clippingScore(0.02)
        val lots = QualityScore.clippingScore(0.05)
        assertTrue(none > some)
        assertTrue(some > lots)
        assertEquals(0, lots) // 5% clipped is the full-penalty boundary
    }

    @Test
    fun `zero clipping gives maximum clipping score`() {
        assertEquals(100, QualityScore.clippingScore(0.0))
    }

    @Test
    fun `higher blockiness and ringing lower compression score`() {
        val clean     = QualityScore.compressionScore(0.0,  0.0)
        val blocky    = QualityScore.compressionScore(0.5,  0.0)
        val ringy     = QualityScore.compressionScore(0.0,  0.5)
        val worst     = QualityScore.compressionScore(1.0,  1.0)
        assertEquals(100, clean)
        assertTrue(clean > blocky)
        assertTrue(clean > ringy)
        assertEquals(0, worst)
    }

    // ------------------------------------------------------------------ weight normalisation

    @Test
    fun `default weights sum to a positive value`() {
        assertTrue(QualityWeights().total() > 0.0)
    }

    @Test
    fun `custom weights with one component set to zero still work`() {
        val report = QualityScore.evaluate(raw(), QualityWeights(sharpness = 0.0))
        assertTrue(report.overall in 0..100)
    }

    @Test
    fun `weights with all-saturation produces valid score`() {
        val report = QualityScore.evaluate(raw(), QualityWeights(saturation = 1.0, sharpness = 0.0, noise = 0.0,
            exposure = 0.0, contrast = 0.0, highlightClipping = 0.0, shadowClipping = 0.0,
            dynamicRange = 0.0, compressionIndicators = 0.0))
        assertTrue(report.overall in 0..100)
    }

    @Test
    fun `zero weights sum throws`() {
        var threw = false
        try {
            QualityWeights(sharpness = 0.0, noise = 0.0, exposure = 0.0, contrast = 0.0,
                saturation = 0.0, highlightClipping = 0.0, shadowClipping = 0.0,
                dynamicRange = 0.0, compressionIndicators = 0.0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("QualityWeights with all-zero weights must throw", threw)
    }

    // ------------------------------------------------------------------ NaN / Infinity guard

    @Test
    fun `saturate does not return NaN or Infinity`() {
        for (v in listOf(0.0, Double.MAX_VALUE, Double.POSITIVE_INFINITY, -1.0, Double.NaN)) {
            val result = QualityScore.saturate(v.coerceAtLeast(0.0), reference = 500.0)
            assertTrue("saturate result must be finite integer: got $result for input $v", result in 0..100)
        }
    }

    @Test
    fun `evaluate with extreme raw values still produces bounded report`() {
        val extreme = raw(
            varianceOfLaplacian = Double.MAX_VALUE,
            noiseSigma = Double.MAX_VALUE,
            meanLuma = 0.0,
            clippedHighlightRatio = 1.0,
            clippedShadowRatio = 1.0,
            contrastRms = Double.MAX_VALUE,
            lumaSpreadP5P95 = Double.MAX_VALUE,
            saturationMean = 1.0,
            blockiness = 1.0,
            ringing = 1.0,
        )
        val report = QualityScore.evaluate(extreme)
        assertTrue(report.overall in 0..100)
    }
}
