package com.veilframe.app.cv.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM policy tests for quality/blur aggregation. Measurement happens over Mats
 * on-device; the normalisation and weighting policy is what's asserted here.
 */
class QualityScorePolicyTest {

    private fun raw(
        sharpness: Double = 500.0,
        noiseSigma: Double = 1.5,
        meanLuma: Double = 128.0,
        highlightClip: Double = 0.01,
        shadowClip: Double = 0.01,
        contrastRms: Double = 64.0,
        spread: Double = 180.0,
        saturation: Double = 0.35,
        blockiness: Double = 0.1,
        ringing: Double = 0.1,
    ) = QualityRawMeasurements(
        varianceOfLaplacian = sharpness,
        noiseSigma = noiseSigma,
        meanLuma = meanLuma,
        clippedHighlightRatio = highlightClip,
        clippedShadowRatio = shadowClip,
        contrastRms = contrastRms,
        lumaSpreadP5P95 = spread,
        saturationMean = saturation,
        blockiness = blockiness,
        ringing = ringing,
    )

    @Test
    fun `ideal measurements score high overall`() {
        val report = QualityScore.evaluate(raw())
        assertTrue("overall ${report.overall} should be strong", report.overall >= 70)
    }

    @Test
    fun `heavy noise and clipping drag the score down`() {
        val good = QualityScore.evaluate(raw())
        val bad = QualityScore.evaluate(
            raw(noiseSigma = 12.0, highlightClip = 0.2, shadowClip = 0.2, contrastRms = 8.0)
        )
        assertTrue(bad.overall < good.overall)
    }

    @Test
    fun `mid grey exposure beats blown-out exposure`() {
        assertTrue(QualityScore.exposureScore(128.0) > QualityScore.exposureScore(250.0))
        assertTrue(QualityScore.exposureScore(128.0) > QualityScore.exposureScore(10.0))
    }

    @Test
    fun `saturation score rewards typical saturation and punishes oversaturation`() {
        assertTrue(QualityScore.saturationScore(0.35) >= QualityScore.saturationScore(0.05))
        assertTrue(QualityScore.saturationScore(0.35) > QualityScore.saturationScore(0.95))
    }

    @Test
    fun `clipping score is zero at five percent clipped`() {
        assertEquals(100, QualityScore.clippingScore(0.0))
        assertEquals(0, QualityScore.clippingScore(0.05))
        assertEquals(0, QualityScore.clippingScore(0.5))
    }

    @Test
    fun `custom weights change the aggregate deterministically`() {
        val sharpOnly = QualityWeights(
            sharpness = 1.0, noise = 0.0, exposure = 0.0, contrast = 0.0, saturation = 0.0,
            highlightClipping = 0.0, shadowClipping = 0.0, dynamicRange = 0.0, compressionIndicators = 0.0,
        )
        val blurry = raw(sharpness = 0.0)
        val report = QualityScore.evaluate(blurry, sharpOnly)
        assertEquals(0, report.overall)
    }

    @Test
    fun `blur combiner rewards multiple agreeing metrics`() {
        val sharp = BlurAnalyzer.combine(
            BlurAnalyzer.RawMetrics(
                varianceOfLaplacian = 800.0,
                gradientEnergy = 20.0,
                tenengrad = 800.0,
                gradientEnergyX = 12.0,
                gradientEnergyY = 12.0,
            )
        )
        val soft = BlurAnalyzer.combine(
            BlurAnalyzer.RawMetrics(
                varianceOfLaplacian = 20.0,
                gradientEnergy = 1.0,
                tenengrad = 20.0,
                gradientEnergyX = 1.0,
                gradientEnergyY = 1.0,
            )
        )
        assertTrue(sharp.sharpnessScore > soft.sharpnessScore)
        assertEquals(BlurAnalyzer.BlurReport.Label.SHARP, sharp.label)
        assertEquals(BlurAnalyzer.BlurReport.Label.BLURRED, soft.label)
    }

    @Test
    fun `directional gradient imbalance raises motion blur likelihood`() {
        val directional = BlurAnalyzer.combine(
            BlurAnalyzer.RawMetrics(
                varianceOfLaplacian = 30.0,
                gradientEnergy = 2.0,
                tenengrad = 30.0,
                gradientEnergyX = 10.0,
                gradientEnergyY = 1.0,
            )
        )
        val symmetric = BlurAnalyzer.combine(
            BlurAnalyzer.RawMetrics(
                varianceOfLaplacian = 30.0,
                gradientEnergy = 2.0,
                tenengrad = 30.0,
                gradientEnergyX = 5.0,
                gradientEnergyY = 5.0,
            )
        )
        assertTrue(directional.motionBlurLikelihood > symmetric.motionBlurLikelihood)
        assertTrue(symmetric.defocusBlurLikelihood >= directional.defocusBlurLikelihood)
    }

    @Test
    fun `frame scoring and acceptance are consistent`() {
        val reports = BlurAnalyzer.scoreFrames(
            listOf(
                BlurAnalyzer.RawMetrics(800.0, 20.0, 800.0, 12.0, 12.0),
                BlurAnalyzer.RawMetrics(10.0, 0.5, 10.0, 0.5, 0.5),
            )
        )
        assertTrue(BlurAnalyzer.acceptFrame(reports[0], minScore = 70))
        assertTrue(!BlurAnalyzer.acceptFrame(reports[1], minScore = 70))
    }
}
