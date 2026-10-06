package com.veilframe.app.cv.analysis

/**
 * Image quality model + aggregation.
 *
 * Component values are individually measurable and honest:
 * the aggregate score is CONFIGURABLE and is explicitly not an absolute
 * photographic quality judgement. Pure Kotlin — no OpenCV imports — so score
 * policy is unit-testable on the JVM.
 *
 * Presentation contract: surface this as "quality INDICATORS" in UI, never as
 * an objective "image quality: 72/100" — several components (saturation,
 * contrast, sharpness) are content-dependent and a deliberately low-saturation
 * photo can score badly despite being excellent.
 */

/** Measured components, each normalised to 0..100 where higher is better. */
data class QualityComponents(
    val sharpness: Int,
    val noise: Int,
    val exposure: Int,
    val contrast: Int,
    val saturation: Int,
    val highlightClipping: Int,
    val shadowClipping: Int,
    val dynamicRange: Int,
    val compressionIndicators: Int,
)

/** Raw (un-normalised) measurements kept for diagnostics and thresholds. */
data class QualityRawMeasurements(
    val varianceOfLaplacian: Double,
    val noiseSigma: Double,
    val meanLuma: Double,
    val clippedHighlightRatio: Double,
    val clippedShadowRatio: Double,
    val contrastRms: Double,
    val lumaSpreadP5P95: Double,
    val saturationMean: Double,
    val blockiness: Double,
    val ringing: Double,
)

data class QualityWeights(
    val sharpness: Double = 0.25,
    val noise: Double = 0.15,
    val exposure: Double = 0.15,
    val contrast: Double = 0.10,
    val saturation: Double = 0.05,
    val highlightClipping: Double = 0.08,
    val shadowClipping: Double = 0.07,
    val dynamicRange: Double = 0.10,
    val compressionIndicators: Double = 0.05,
) {
    init {
        val sum = sharpness + noise + exposure + contrast + saturation +
            highlightClipping + shadowClipping + dynamicRange + compressionIndicators
        require(sum > 0.0) { "weights must sum to a positive value" }
    }

    /** Weights are renormalised at aggregation time, so editing one is safe. */
    fun total(): Double = sharpness + noise + exposure + contrast + saturation +
        highlightClipping + shadowClipping + dynamicRange + compressionIndicators
}

data class QualityReport(
    val components: QualityComponents,
    val raw: QualityRawMeasurements,
    /** Weighted aggregate, 0..100. Configurable — not an absolute quality score. */
    val overall: Int,
    val weights: QualityWeights,
)

/**
 * Converts raw measurements into normalised components and the aggregate.
 *
 * Normalisation is deterministic and documented in VEILFRAME_CV_ENGINE.md:
 * every component saturates smoothly so a single extreme metric cannot skew the
 * report, and clipping components are inverted (fewer clipped pixels = better).
 */
object QualityScore {

    fun evaluate(raw: QualityRawMeasurements, weights: QualityWeights = QualityWeights()): QualityReport {
        val components = QualityComponents(
            sharpness = saturate(raw.varianceOfLaplacian, reference = 500.0),
            noise = noiseScore(raw.noiseSigma),
            exposure = exposureScore(raw.meanLuma),
            contrast = saturate(raw.contrastRms, reference = 64.0),
            saturation = saturationScore(raw.saturationMean),
            highlightClipping = clippingScore(raw.clippedHighlightRatio),
            shadowClipping = clippingScore(raw.clippedShadowRatio),
            dynamicRange = saturate(raw.lumaSpreadP5P95, reference = 180.0),
            compressionIndicators = compressionScore(raw.blockiness, raw.ringing),
        )
        val total = weights.total()
        val weighted =
            components.sharpness * weights.sharpness +
                components.noise * weights.noise +
                components.exposure * weights.exposure +
                components.contrast * weights.contrast +
                components.saturation * weights.saturation +
                components.highlightClipping * weights.highlightClipping +
                components.shadowClipping * weights.shadowClipping +
                components.dynamicRange * weights.dynamicRange +
                components.compressionIndicators * weights.compressionIndicators
        return QualityReport(
            components = components,
            raw = raw,
            overall = (weighted / total).toInt().coerceIn(0, 100),
            weights = weights,
        )
    }

    /** 0..100 with smooth saturation: x/ref mapped through 1 - exp(-x/ref). */
    fun saturate(value: Double, reference: Double): Int {
        val normalized = 1.0 - kotlin.math.exp(-value.coerceAtLeast(0.0) / reference)
        return (normalized * 100.0).toInt().coerceIn(0, 100)
    }

    /** Noise score: 0 sigma (clean) -> 100, moderate noise (sigma=2) -> ~50, heavy noise -> near 0. */
    fun noiseScore(noiseSigma: Double): Int {
        val normalized = 1.0 / (1.0 + noiseSigma.coerceAtLeast(0.0) / 2.0)
        return (normalized * 100.0).toInt().coerceIn(0, 100)
    }

    /** Mid-grey (128) is the ideal exposure; symmetric falloff to both ends. */
    fun exposureScore(meanLuma: Double): Int {
        val distance = kotlin.math.abs(meanLuma - 128.0) / 128.0
        return ((1.0 - distance) * 100.0).toInt().coerceIn(0, 100)
    }

    /** Saturation 0 → 0, ~0.35 typical → high score, oversaturation penalised. */
    fun saturationScore(saturationMean: Double): Int {
        val s = saturationMean.coerceIn(0.0, 1.0)
        return if (s <= 0.35) {
            (s / 0.35 * 100.0).toInt().coerceIn(0, 100)
        } else {
            (100.0 * (1.0 - (s - 0.35) / 0.65 * 0.6)).toInt().coerceIn(0, 100)
        }
    }

    /** Fewer clipped pixels is better; >5% clipped is already near-zero. */
    fun clippingScore(clippedRatio: Double): Int =
        ((1.0 - clippedRatio.coerceIn(0.0, 0.05) / 0.05) * 100.0).toInt().coerceIn(0, 100)

    fun compressionScore(blockiness: Double, ringing: Double): Int {
        val penalty = (blockiness + ringing).coerceIn(0.0, 1.0)
        return ((1.0 - penalty) * 100.0).toInt().coerceIn(0, 100)
    }
}
