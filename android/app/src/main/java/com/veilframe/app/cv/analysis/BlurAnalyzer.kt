package com.veilframe.app.cv.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * BlurAnalyzer — multi-metric sharpness measurement.
 *
 * A single metric lies: variance-of-Laplacian is texture-sensitive, Tenengrad
 * is noise-sensitive, and directional energy is what actually separates motion
 * blur from defocus. VeilFrame CV therefore measures all of them and combines
 * them with an explicit, configurable model.
 *
 * Pure computation lives in [combine]; Mat measurement lives in [measure].
 */
object BlurAnalyzer {

    /** Raw metric values for one frame/image. */
    data class RawMetrics(
        val varianceOfLaplacian: Double,
        val gradientEnergy: Double,
        val tenengrad: Double,
        /** mean |∂I/∂x| — directional energies for motion-blur diagnostics. */
        val gradientEnergyX: Double,
        val gradientEnergyY: Double,
    )

    /** Verdict-ready report. */
    data class BlurReport(
        /** 0..100, higher is sharper. */
        val sharpnessScore: Int,
        val motionBlurLikelihood: Double,
        val defocusBlurLikelihood: Double,
        val metrics: RawMetrics,
        val label: Label,
    ) {
        enum class Label { SHARP, SLIGHTLY_SOFT, BLURRED }
    }

    data class Weights(
        val laplacian: Double = 0.45,
        val gradient: Double = 0.30,
        val tenengrad: Double = 0.25,
        /** Reference scale for normalising raw metric magnitudes. */
        val laplacianReference: Double = 500.0,
        val gradientReference: Double = 12.0,
        val tenengradReference: Double = 400.0,
    )

    /**
     * Combines raw metrics into a report.
     *
     * Normalisation is a smooth saturating curve per metric (1 - exp(-x/ref)),
     * so scores are comparable across resolutions but are NOT presented as an
     * absolute photographic truth — they are a deterministic, documented scale.
     */
    fun combine(raw: RawMetrics, weights: Weights = Weights()): BlurReport {
        val laplacianScore = 1.0 - exp(-raw.varianceOfLaplacian / weights.laplacianReference)
        val gradientScore = 1.0 - exp(-raw.gradientEnergy / weights.gradientReference)
        val tenengradScore = 1.0 - exp(-raw.tenengrad / weights.tenengradReference)
        val combined =
            weights.laplacian * laplacianScore +
                weights.gradient * gradientScore +
                weights.tenengrad * tenengradScore
        val sharpnessScore = (combined * 100.0).toInt().coerceIn(0, 100)

        // Motion blur leaves one gradient direction dominant; defocus reduces
        // both directions together while keeping their ratio near 1.
        val gx = raw.gradientEnergyX
        val gy = raw.gradientEnergyY
        val anisotropy = if (maxOf(gx, gy) <= 1e-9) 0.0 else abs(gx - gy) / maxOf(gx, gy)
        val motionBlur = (anisotropy * (1.0 - combined)).coerceIn(0.0, 1.0)
        val defocus = ((1.0 - combined) * (1.0 - anisotropy)).coerceIn(0.0, 1.0)

        return BlurReport(
            sharpnessScore = sharpnessScore,
            motionBlurLikelihood = motionBlur,
            defocusBlurLikelihood = defocus,
            metrics = raw,
            label = when {
                sharpnessScore >= 70 -> BlurReport.Label.SHARP
                sharpnessScore >= 45 -> BlurReport.Label.SLIGHTLY_SOFT
                else -> BlurReport.Label.BLURRED
            },
        )
    }

    /** Per-frame scores for video pipelines (frame 1 → 78, frame 2 → 91, ...). */
    fun scoreFrames(rawPerFrame: List<RawMetrics>, weights: Weights = Weights()): List<BlurReport> =
        rawPerFrame.map { combine(it, weights) }

    /** Rejects frames below [minScore] — used to drop bad interpolated frames. */
    fun acceptFrame(report: BlurReport, minScore: Int): Boolean = report.sharpnessScore >= minScore

    /** log-space helper for stable score aggregation in tests/telemetry. */
    internal fun logScale(value: Double): Double = ln(value.coerceAtLeast(1e-9))
}
