package com.veilframe.app.qr.image

import com.veilframe.app.qr.model.ModuleShape
import kotlin.math.*

/**
 * Statistical distribution of background luminance samples across a module footprint or frame region.
 */
data class LuminanceDistribution(
    val min: Float,
    val p10: Float,
    val p25: Float,
    val median: Float,
    val p75: Float,
    val p90: Float,
    val max: Float,
    val mean: Float,
    val sampleCount: Int
)

/**
 * Statistical objective used by the optimizer to select which percentile of the background
 * distribution to evaluate contrast against.
 */
enum class SamplingObjective {
    /** Worst-case extreme: max background for dark modules, min background for light modules. */
    EXTREMA,
    /** 90th percentile for dark modules, 10th percentile for light modules (noise-resistant tail). */
    PERCENTILE_90_10,
    /** 75th percentile for dark modules, 25th percentile for light modules. */
    PERCENTILE_75_25,
    /** Median background luminance. */
    MEDIAN,
    /** Mean background luminance. */
    MEAN
}

/**
 * Independent spatial footprint sampler for rendered QR module geometry.
 *
 * Samples effective background luminance within the actual rendered mark boundary
 * (accounting for dataScale and module shape) and computes non-parametric percentile
 * distributions.
 */
object FootprintSampler {

    /**
     * Computes the percentile distribution from an arbitrary collection of luminance samples.
     */
    fun computeDistribution(samples: List<Float>): LuminanceDistribution {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        if (samples.size == 1) {
            val v = samples[0]
            return LuminanceDistribution(v, v, v, v, v, v, v, v, 1)
        }
        val sorted = samples.sorted()
        val n = sorted.size
        val meanVal = samples.sum() / n

        fun percentile(p: Float): Float {
            val idx = (p * (n - 1)).coerceIn(0f, (n - 1).toFloat())
            val lower = idx.toInt()
            val upper = min(lower + 1, n - 1)
            val frac = idx - lower
            return sorted[lower] * (1f - frac) + sorted[upper] * frac
        }

        return LuminanceDistribution(
            min = sorted.first(),
            p10 = percentile(0.10f),
            p25 = percentile(0.25f),
            median = percentile(0.50f),
            p75 = percentile(0.75f),
            p90 = percentile(0.90f),
            max = sorted.last(),
            mean = meanVal,
            sampleCount = n
        )
    }

    /**
     * Generates normalized sample offsets (du, dv) relative to module center,
     * scaled by [dataScale] and tailored to [shape].
     * Coordinates are fractions of matrix module cell width (e.g. within [-0.5, +0.5] * dataScale).
     */
    fun generateFootprintOffsets(dataScale: Float, shape: ModuleShape = ModuleShape.SQUARE): List<Pair<Float, Float>> {
        val s = dataScale.coerceIn(0.01f, 1.0f)
        val half = s * 0.5f

        return when (shape) {
            ModuleShape.CIRCLE, ModuleShape.DOT, ModuleShape.BUBBLE, ModuleShape.BUBBLE_CLUSTER, ModuleShape.PILL -> {
                // Circular / radial geometry: center + 8 radial points at r = 0.70 * radius
                val r = half * 0.70f
                val points = mutableListOf(Pair(0.0f, 0.0f))
                for (i in 0 until 8) {
                    val angle = (i * Math.PI / 4.0).toFloat()
                    points.add(Pair(r * cos(angle), r * sin(angle)))
                }
                points
            }
            ModuleShape.DIAMOND, ModuleShape.STAR -> {
                // Diamond / Star geometry: center + 4 tips + 4 inner diagonal points
                val tip = half * 0.75f
                val mid = half * 0.35f
                listOf(
                    Pair(0.0f, 0.0f),
                    Pair(tip, 0.0f), Pair(-tip, 0.0f), Pair(0.0f, tip), Pair(0.0f, -tip),
                    Pair(mid, mid), Pair(-mid, mid), Pair(mid, -mid), Pair(-mid, -mid)
                )
            }
            ModuleShape.HEX -> {
                // Hexagonal geometry: center + 6 vertices at 0.70 * radius
                val r = half * 0.70f
                val points = mutableListOf(Pair(0.0f, 0.0f))
                for (i in 0 until 6) {
                    val angle = (i * Math.PI / 3.0).toFloat()
                    points.add(Pair(r * cos(angle), r * sin(angle)))
                }
                points
            }
            ModuleShape.ROUNDED, ModuleShape.SQUIRCLE, ModuleShape.ORGANIC, ModuleShape.CONNECTED -> {
                // Rounded / squircle: corners pulled inward to remain inside rounded corner radius
                val axis = half * 0.75f
                val corner = half * 0.55f
                listOf(
                    Pair(0.0f, 0.0f),
                    Pair(0.0f, -axis), Pair(0.0f, axis), Pair(-axis, 0.0f), Pair(axis, 0.0f),
                    Pair(-corner, -corner), Pair(corner, -corner), Pair(-corner, corner), Pair(corner, corner)
                )
            }
            ModuleShape.SQUARE, ModuleShape.LINE, ModuleShape.NONE, ModuleShape.CUSTOM -> {
                // Standard 3x3 grid inside square module footprint
                val step = half * 0.75f
                listOf(
                    Pair(0.0f, 0.0f),
                    Pair(-step, -step), Pair(0.0f, -step), Pair(step, -step),
                    Pair(-step, 0.0f),                     Pair(step, 0.0f),
                    Pair(-step, step),  Pair(0.0f, step),  Pair(step, step)
                )
            }
        }
    }

    /**
     * Samples a module cell's footprint luminance distribution given an arbitrary composited
     * pixel luminance provider `(normU, normV) -> Float`.
     */
    fun sampleModuleDistribution(
        col: Int,
        row: Int,
        matrixSize: Int,
        dataScale: Float,
        shape: ModuleShape = ModuleShape.SQUARE,
        luminanceProvider: (Float, Float) -> Float
    ): LuminanceDistribution {
        val offsets = generateFootprintOffsets(dataScale, shape)
        val n = matrixSize.toFloat()
        val uCenter = (col + 0.5f) / n
        val vCenter = (row + 0.5f) / n

        val samples = ArrayList<Float>(offsets.size)
        for ((du, dv) in offsets) {
            val u = (uCenter + du / n).coerceIn(0.0f, 1.0f)
            val v = (vCenter + dv / n).coerceIn(0.0f, 1.0f)
            samples.add(luminanceProvider(u, v))
        }

        return computeDistribution(samples)
    }

    /**
     * Resolves the target representative background luminance from a [LuminanceDistribution]
     * according to the desired [SamplingObjective] and module polarity [isDark].
     */
    fun resolveObjectiveLuminance(
        dist: LuminanceDistribution,
        isDark: Boolean,
        objective: SamplingObjective = SamplingObjective.PERCENTILE_90_10
    ): Float {
        return when (objective) {
            SamplingObjective.EXTREMA -> if (isDark) dist.max else dist.min
            SamplingObjective.PERCENTILE_90_10 -> if (isDark) dist.p90 else dist.p10
            SamplingObjective.PERCENTILE_75_25 -> if (isDark) dist.p75 else dist.p25
            SamplingObjective.MEDIAN -> dist.median
            SamplingObjective.MEAN -> dist.mean
        }
    }
}
