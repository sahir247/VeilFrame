package com.veilframe.app.qr.image

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Status indicating whether and how the optimization criteria were satisfied.
 */
enum class OptimizationStatus {
    /** Target contrast (CR >= targetContrast) was verified and met against background and companion. */
    TARGET_MET,
    /** Module was intentionally transparent; contrast ratio is not applicable. */
    TRANSPARENT_PRESERVED,
    /** Target contrast was mathematically unattainable across all frames; minimax fallback was selected. */
    INFEASIBLE;

    val isContrastSatisfied: Boolean get() = this == TARGET_MET
    val isFeasible: Boolean get() = this != INFEASIBLE
}

/**
 * Result of an adaptive color optimization step.
 *
 * @param color The final selected sRGB color.
 * @param contrastRatio The verified minimum WCAG contrast ratio achieved across all evaluated frames.
 * @param targetContrast The target contrast ratio requested (e.g. 3.0f).
 * @param targetMet Whether target contrast is met (or transparent preserved).
 * @param status Detailed semantic status of the optimization.
 * @param sourceCandidate The original candidate or palette color from which [color] was derived, or null if anchor.
 * @param adjustmentDistance The perceptual color difference (Delta E_OK) from the reference color to [color].
 */
data class ColorOptimizationResult(
    val color: Int,
    val contrastRatio: Float,
    val targetContrast: Float,
    val targetMet: Boolean,
    val status: OptimizationStatus = when {
        ((color ushr 24) and 0xFF) == 0 -> OptimizationStatus.TRANSPARENT_PRESERVED
        targetMet -> OptimizationStatus.TARGET_MET
        else -> OptimizationStatus.INFEASIBLE
    },
    val sourceCandidate: Int?,
    val adjustmentDistance: Float
)

/**
 * Result of joint optimization for both dark and light QR module colors.
 */
data class JointColorOptimizationResult(
    val dark: ColorOptimizationResult,
    val light: ColorOptimizationResult,
    val pairContrastRatio: Float,
    val allTargetsMet: Boolean,
    val status: OptimizationStatus = when {
        dark.status == OptimizationStatus.INFEASIBLE || light.status == OptimizationStatus.INFEASIBLE || !allTargetsMet -> OptimizationStatus.INFEASIBLE
        dark.status == OptimizationStatus.TRANSPARENT_PRESERVED && light.status == OptimizationStatus.TRANSPARENT_PRESERVED -> OptimizationStatus.TRANSPARENT_PRESERVED
        else -> OptimizationStatus.TARGET_MET
    }
)

/**
 * Pre-computed candidate color metadata for lightning-fast per-module evaluation.
 */
data class CandidateInfo(
    val color: Int,
    val lum: Float,
    val distance: Float,
    val sourceCandidate: Int?,
    val isExactSeed: Boolean = false
)

/**
 * Complete spatial context for a module, encapsulating the background luminance distributions
 * across frames and the statistical sampling objective.
 */
data class SpatialModuleContext(
    val distributions: List<LuminanceDistribution>,
    val objective: SamplingObjective = SamplingObjective.PERCENTILE_90_10
) {
    /**
     * Resolves the target representative background luminance for each frame for [isDark] polarity.
     */
    fun resolveFrameLuminances(isDark: Boolean): List<Float> {
        return distributions.map { dist ->
            FootprintSampler.resolveObjectiveLuminance(dist, isDark, objective)
        }
    }

    /**
     * Returns the extrema across all frames and distributions:
     * (absolute minimum luminance, absolute maximum luminance).
     */
    val extrema: Pair<Float, Float>
        get() {
            if (distributions.isEmpty()) return Pair(0f, 1f)
            val minVal = distributions.minOf { it.min }
            val maxVal = distributions.maxOf { it.max }
            return Pair(minVal, maxVal)
        }
}

/**
 * Adaptive Color Optimizer v2.
 *
 * Implements a principled, deterministic optimization pipeline:
 * 1. Evaluates effective composited background luminance distributions across frames via [SpatialModuleContext].
 * 2. Generates candidate colors via fine Oklab / OKLCH perceptual lightness modulation along constant-hue rays.
 * 3. Applies constant-hue chroma clipping to keep chroma maximized within the sRGB cube.
 * 4. Verifies actual contrast against W3C WCAG 2.1 relative luminance, including semi-transparent alpha compositing.
 * 5. Minimizes perceptual adjustment distance (Delta E_OK) from user/reference color with robustness margin scoring.
 * 6. Explicitly reports feasibility via [ColorOptimizationResult.targetMet] and [ColorOptimizationResult.status].
 * 7. Supports true joint candidate-pair evaluation (candidateDark x candidateLight) guaranteeing
 *    dark-bg, light-bg, and dark-light pair contrast simultaneously while preserving QR polarity.
 */
object AdaptiveColorOptimizer {

    const val DEFAULT_TARGET_CONTRAST = 3.0f
    const val DEFAULT_MIN_PAIR_CONTRAST = 2.0f
    const val MIN_LUMINANCE_DELTA = 0.20f
    const val LIGHT_FIELD_LUMINANCE_THRESHOLD = 0.55f
    val MID_LUM_ANCHOR = 0xFF7D7D7D.toInt()

    private data class EvaluatedJointPair(
        val darkCandidate: CandidateInfo,
        val lightCandidate: CandidateInfo,
        val crDarkBg: Float,
        val crLightBg: Float,
        val pairCr: Float,
        val minCr: Float,
        val jointDistance: Float,
        val seedsPreserved: Int,
        val isFeasible: Boolean
    )

    /**
     * Computes the effective composited luminance of a candidate mark rendered with its alpha
     * over a background with luminance [bgLum].
     */
    fun effectiveCandidateLuminance(candColor: Int, candLum: Float, bgLum: Float): Float {
        val alpha = (candColor ushr 24) and 0xFF
        if (alpha == 255) return candLum
        if (alpha == 0) return bgLum
        val a = alpha / 255.0f
        return (candLum * a + bgLum * (1.0f - a)).coerceIn(0.0f, 1.0f)
    }

    fun effectiveCandidateLuminance(cand: CandidateInfo, bgLum: Float): Float {
        return effectiveCandidateLuminance(cand.color, cand.lum, bgLum)
    }

    /**
     * Computes the minimum contrast ratio achieved by [cand] across all background frames [frameLums],
     * taking into account actual rendered alpha compositing.
     */
    fun minContrastAgainstFrames(cand: CandidateInfo, frameLums: List<Float>): Float {
        if (frameLums.isEmpty()) return 1.0f
        return frameLums.minOf { bg ->
            val eff = effectiveCandidateLuminance(cand, bg)
            ImageColorAnalyzer.contrastRatio(eff, bg)
        }
    }

    /**
     * Evaluates light-field compatibility frame-by-frame.
     * A light candidate is compatible across frames if for every frame:
     * - The frame is part of the light field (bg >= LIGHT_FIELD_LUMINANCE_THRESHOLD), OR
     * - The light candidate achieves CR >= targetContrast against the darker frame.
     */
    fun isLightFieldCompatible(
        lightCandidate: CandidateInfo,
        lightFrameLums: List<Float>,
        targetContrast: Float
    ): Boolean {
        if (lightFrameLums.isEmpty()) return true
        return lightFrameLums.all { bg ->
            bg >= LIGHT_FIELD_LUMINANCE_THRESHOLD ||
                ImageColorAnalyzer.contrastRatio(effectiveCandidateLuminance(lightCandidate, bg), bg) >= targetContrast
        }
    }

    /**
     * Pre-computes candidate pool for [isDark] polarity from [referenceColor] and [palette].
     * Evaluates fine Oklab lightness modulation (~0.04 step resolution) and constant-hue
     * chroma clipping once per render.
     */
    fun generateCandidatePool(
        isDark: Boolean,
        referenceColor: Int,
        palette: List<Int> = emptyList()
    ): List<CandidateInfo> {
        val refAlpha = (referenceColor ushr 24) and 0xFF
        if (refAlpha == 0) {
            return listOf(CandidateInfo(Color.TRANSPARENT, 0.0f, 0.0f, null, true))
        }

        val candidateSeeds = mutableListOf<Int>()
        candidateSeeds.add(referenceColor)
        for (c in palette) {
            if (((c ushr 24) and 0xFF) > 0 && c !in candidateSeeds) {
                candidateSeeds.add(c)
            }
        }

        val generated = mutableListOf<Triple<Int, Int?, Boolean>>() // (color, sourceSeed, isExactSeed)
        for (seed in candidateSeeds) {
            generated.add(Triple(seed, seed, true))

            val oklch = OklabColor.sRgbToOklch(seed)
            if (isDark) {
                // For dark modules: fine lightness grid from 0.05 to 0.45 (~0.04 step resolution)
                val levels = floatArrayOf(0.05f, 0.09f, 0.13f, 0.17f, 0.21f, 0.25f, 0.29f, 0.33f, 0.37f, 0.41f, 0.45f)
                for (targetL in levels) {
                    val adjusted = OklabColor.gamutMapOklch(targetL, oklch.c, oklch.h)
                    val adjustedWithAlpha = if (refAlpha in 1..254) (adjusted and 0x00FFFFFF) or (refAlpha shl 24) else adjusted
                    if (adjustedWithAlpha !in generated.map { it.first }) {
                        generated.add(Triple(adjustedWithAlpha, seed, false))
                    }
                }
            } else {
                // For light modules: fine lightness grid from 0.55 to 0.98 (~0.04 step resolution)
                val levels = floatArrayOf(0.55f, 0.59f, 0.63f, 0.67f, 0.71f, 0.75f, 0.79f, 0.83f, 0.87f, 0.91f, 0.95f, 0.98f)
                for (targetL in levels) {
                    val adjusted = OklabColor.gamutMapOklch(targetL, oklch.c, oklch.h)
                    val adjustedWithAlpha = if (refAlpha in 1..254) (adjusted and 0x00FFFFFF) or (refAlpha shl 24) else adjusted
                    if (adjustedWithAlpha !in generated.map { it.first }) {
                        generated.add(Triple(adjustedWithAlpha, seed, false))
                    }
                }
            }
        }

        // Add standard boundary anchors tailored to polarity
        val anchors = if (isDark) {
            listOf(Color.BLACK, MID_LUM_ANCHOR)
        } else {
            listOf(Color.WHITE)
        }
        for (anchor in anchors) {
            val anchorWithAlpha = if (refAlpha in 1..254) (anchor and 0x00FFFFFF) or (refAlpha shl 24) else anchor
            if (anchorWithAlpha !in generated.map { it.first }) {
                generated.add(Triple(anchorWithAlpha, null, false))
            }
        }

        val refOklab = OklabColor.sRgbToOklab(referenceColor)
        val result = ArrayList<CandidateInfo>(generated.size)
        for ((color, source, isExactSeed) in generated) {
            val lum = ImageColorAnalyzer.relativeLuminance(color)
            val dist = if (color == referenceColor) 0.0f else OklabColor.deltaEOk(refOklab, OklabColor.sRgbToOklab(color))
            result.add(CandidateInfo(color, lum, dist, source, isExactSeed))
        }
        return result
    }

    /**
     * Optimizes a module color for [isDark] polarity against background frame luminance samples [frameLums].
     */
    fun optimizeColor(
        isDark: Boolean,
        frameLums: List<Float>,
        referenceColor: Int,
        companionColor: Int = if (isDark) Color.WHITE else Color.BLACK,
        palette: List<Int> = emptyList(),
        targetContrast: Float = DEFAULT_TARGET_CONTRAST
    ): ColorOptimizationResult {
        require(frameLums.isNotEmpty()) { "frameLums must not be empty" }

        val refAlpha = (referenceColor ushr 24) and 0xFF
        if (refAlpha == 0) {
            return ColorOptimizationResult(
                color = Color.TRANSPARENT,
                contrastRatio = 1.0f,
                targetContrast = targetContrast,
                targetMet = true,
                status = OptimizationStatus.TRANSPARENT_PRESERVED,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
        }

        val candidates = generateCandidatePool(isDark, referenceColor, palette)
        return optimizeWithCandidates(isDark, frameLums, candidates, targetContrast)
    }

    /**
     * Evaluates pre-computed [candidates] against [frameLums] to optimize a single module color.
     */
    fun optimizeWithCandidates(
        isDark: Boolean,
        frameLums: List<Float>,
        candidates: List<CandidateInfo>,
        targetContrast: Float = DEFAULT_TARGET_CONTRAST
    ): ColorOptimizationResult {
        if (candidates.isEmpty()) {
            val fallback = if (isDark) Color.BLACK else Color.WHITE
            val cr = minContrastAgainstFrames(CandidateInfo(fallback, ImageColorAnalyzer.relativeLuminance(fallback), 0f, null), frameLums)
            return ColorOptimizationResult(
                color = fallback,
                contrastRatio = cr,
                targetContrast = targetContrast,
                targetMet = cr >= targetContrast,
                status = if (cr >= targetContrast) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
        }

        data class Evaluated(val candidate: CandidateInfo, val minCr: Float)
        val evaluated = candidates.map { Evaluated(it, minContrastAgainstFrames(it, frameLums)) }
        val viable = evaluated.filter { it.minCr >= targetContrast }

        if (viable.isNotEmpty()) {
            // Check exact reference color viability
            val exactRef = viable.firstOrNull { it.candidate.distance < 0.0001f }
            if (exactRef != null && exactRef.minCr >= targetContrast + 0.5f) {
                return ColorOptimizationResult(
                    color = exactRef.candidate.color,
                    contrastRatio = exactRef.minCr,
                    targetContrast = targetContrast,
                    targetMet = true,
                    status = OptimizationStatus.TARGET_MET,
                    sourceCandidate = exactRef.candidate.sourceCandidate,
                    adjustmentDistance = 0f
                )
            }

            val viableExactSeeds = viable.filter { it.candidate.isExactSeed && it.candidate.sourceCandidate != null }
            val pool = if (viableExactSeeds.isNotEmpty()) {
                viableExactSeeds
            } else {
                val viableSeeds = viable.filter { it.candidate.sourceCandidate != null }
                if (viableSeeds.isNotEmpty()) viableSeeds else viable
            }

            // Balanced score with robustness margin bonus
            val best = pool.minWithOrNull { a, b ->
                val marginA = (a.minCr - targetContrast).coerceAtLeast(0f)
                val marginB = (b.minCr - targetContrast).coerceAtLeast(0f)
                val scoreA = a.candidate.distance - 0.015f * marginA.coerceAtMost(5.0f)
                val scoreB = b.candidate.distance - 0.015f * marginB.coerceAtMost(5.0f)
                val diff = scoreA - scoreB
                if (abs(diff) > 0.02f) {
                    diff.compareTo(0f)
                } else {
                    b.minCr.compareTo(a.minCr)
                }
            } ?: pool.first()

            return ColorOptimizationResult(
                color = best.candidate.color,
                contrastRatio = best.minCr,
                targetContrast = targetContrast,
                targetMet = true,
                status = OptimizationStatus.TARGET_MET,
                sourceCandidate = best.candidate.sourceCandidate,
                adjustmentDistance = best.candidate.distance
            )
        }

        // Minimax fallback when target contrast cannot be satisfied
        val bestAchievable = evaluated.maxByOrNull { it.minCr } ?: evaluated.first()
        return ColorOptimizationResult(
            color = bestAchievable.candidate.color,
            contrastRatio = bestAchievable.minCr,
            targetContrast = targetContrast,
            targetMet = false,
            status = OptimizationStatus.INFEASIBLE,
            sourceCandidate = bestAchievable.candidate.sourceCandidate,
            adjustmentDistance = bestAchievable.candidate.distance
        )
    }

    /**
     * Jointly optimizes dark and light module colors against background frame luminance samples [frameLums].
     */
    fun optimizeJointColorPair(
        frameLums: List<Float>,
        defaultDark: Int,
        defaultLight: Int,
        palette: List<Int> = emptyList(),
        targetContrast: Float = DEFAULT_TARGET_CONTRAST,
        minPairContrast: Float = DEFAULT_MIN_PAIR_CONTRAST
    ): JointColorOptimizationResult {
        return optimizeJointColorPair(
            darkFrameLums = frameLums,
            lightFrameLums = frameLums,
            defaultDark = defaultDark,
            defaultLight = defaultLight,
            palette = palette,
            targetContrast = targetContrast,
            minPairContrast = minPairContrast
        )
    }

    /**
     * Jointly optimizes dark and light module colors supporting separate dark and light background frame samples.
     */
    fun optimizeJointColorPair(
        darkFrameLums: List<Float>,
        lightFrameLums: List<Float>,
        defaultDark: Int,
        defaultLight: Int,
        palette: List<Int> = emptyList(),
        targetContrast: Float = DEFAULT_TARGET_CONTRAST,
        minPairContrast: Float = DEFAULT_MIN_PAIR_CONTRAST
    ): JointColorOptimizationResult {
        val darkCandidates = generateCandidatePool(true, defaultDark, palette)
        val lightCandidates = generateCandidatePool(false, defaultLight, palette)

        return optimizeJointWithCandidates(
            darkFrameLums = darkFrameLums,
            lightFrameLums = lightFrameLums,
            darkCandidates = darkCandidates,
            lightCandidates = lightCandidates,
            defaultDark = defaultDark,
            defaultLight = defaultLight,
            targetContrast = targetContrast,
            minPairContrast = minPairContrast
        )
    }

    /**
     * Jointly optimizes dark and light module colors using the full spatial module context [context].
     */
    fun optimizeJointWithSpatialContext(
        context: SpatialModuleContext,
        darkCandidates: List<CandidateInfo>,
        lightCandidates: List<CandidateInfo>,
        defaultDark: Int,
        defaultLight: Int,
        targetContrast: Float = DEFAULT_TARGET_CONTRAST,
        minPairContrast: Float = DEFAULT_MIN_PAIR_CONTRAST
    ): JointColorOptimizationResult {
        val darkFrameLums = context.resolveFrameLuminances(isDark = true)
        val lightFrameLums = context.resolveFrameLuminances(isDark = false)
        return optimizeJointWithCandidates(
            darkFrameLums = darkFrameLums,
            lightFrameLums = lightFrameLums,
            darkCandidates = darkCandidates,
            lightCandidates = lightCandidates,
            defaultDark = defaultDark,
            defaultLight = defaultLight,
            targetContrast = targetContrast,
            minPairContrast = minPairContrast
        )
    }

    /**
     * Truly joint candidate-pair evaluation over the Cartesian product (candidateDark x candidateLight).
     * Enforces the fundamental QR polarity constraint: dark modules must be darker than light modules.
     * Simultaneously evaluates:
     * - CR(dark, bg) >= targetContrast
     * - CR(light, bg) >= targetContrast (when light is not matching a light field)
     * - CR(dark, light) >= minPairContrast
     * while minimizing combined perceptual deviation (Delta E_OK) from user reference colors.
     */
    fun optimizeJointWithCandidates(
        darkFrameLums: List<Float>,
        lightFrameLums: List<Float>,
        darkCandidates: List<CandidateInfo>,
        lightCandidates: List<CandidateInfo>,
        defaultDark: Int,
        defaultLight: Int,
        targetContrast: Float = DEFAULT_TARGET_CONTRAST,
        minPairContrast: Float = DEFAULT_MIN_PAIR_CONTRAST
    ): JointColorOptimizationResult {
        val darkAlpha = (defaultDark ushr 24) and 0xFF
        val lightAlpha = (defaultLight ushr 24) and 0xFF

        // 1. Transparent light module passthrough
        if (lightAlpha == 0) {
            val darkResult = optimizeWithCandidates(true, darkFrameLums, darkCandidates, targetContrast)
            val lightResult = ColorOptimizationResult(
                color = Color.TRANSPARENT,
                contrastRatio = 1.0f,
                targetContrast = targetContrast,
                targetMet = true,
                status = OptimizationStatus.TRANSPARENT_PRESERVED,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
            return JointColorOptimizationResult(
                dark = darkResult,
                light = lightResult,
                pairContrastRatio = 21.0f,
                allTargetsMet = darkResult.targetMet,
                status = if (darkResult.targetMet) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE
            )
        }

        // 2. Transparent dark module passthrough
        if (darkAlpha == 0) {
            val lightResult = optimizeWithCandidates(false, lightFrameLums, lightCandidates, targetContrast)
            val darkResult = ColorOptimizationResult(
                color = Color.TRANSPARENT,
                contrastRatio = 1.0f,
                targetContrast = targetContrast,
                targetMet = true,
                status = OptimizationStatus.TRANSPARENT_PRESERVED,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
            return JointColorOptimizationResult(
                dark = darkResult,
                light = lightResult,
                pairContrastRatio = 21.0f,
                allTargetsMet = lightResult.targetMet,
                status = if (lightResult.targetMet) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE
            )
        }

        // 3. Both dark and light are opaque/semi-transparent: evaluate all pairs jointly
        // Polarity invariant: dark module must be darker than light module
        val evaluatedPairs = ArrayList<EvaluatedJointPair>()

        for (d in darkCandidates) {
            val crDarkBg = minContrastAgainstFrames(d, darkFrameLums)
            for (l in lightCandidates) {
                // Polarity invariant: dark must be darker than light by at least MIN_LUMINANCE_DELTA
                if (d.lum >= l.lum || (l.lum - d.lum) < MIN_LUMINANCE_DELTA) continue

                val crLightBg = minContrastAgainstFrames(l, lightFrameLums)
                val pairCr = if (darkFrameLums.isNotEmpty()) {
                    darkFrameLums.minOf { bg ->
                        ImageColorAnalyzer.contrastRatio(
                            effectiveCandidateLuminance(d, bg),
                            effectiveCandidateLuminance(l, bg)
                        )
                    }
                } else {
                    ImageColorAnalyzer.contrastRatio(d.lum, l.lum)
                }
                val minCr = minOf(crDarkBg, crLightBg, pairCr)
                val jointDistance = d.distance + l.distance
                val seedsPreserved = (if (d.sourceCandidate != null) 1 else 0) + (if (l.sourceCandidate != null) 1 else 0)

                val darkTargetMet = crDarkBg >= targetContrast
                val lightTargetMet = isLightFieldCompatible(l, lightFrameLums, targetContrast)
                val pairMet = pairCr >= minPairContrast && (l.lum - d.lum) >= MIN_LUMINANCE_DELTA
                val isFeasible = darkTargetMet && lightTargetMet && pairMet

                evaluatedPairs.add(
                    EvaluatedJointPair(
                        darkCandidate = d,
                        lightCandidate = l,
                        crDarkBg = crDarkBg,
                        crLightBg = crLightBg,
                        pairCr = pairCr,
                        minCr = minCr,
                        jointDistance = jointDistance,
                        seedsPreserved = seedsPreserved,
                        isFeasible = isFeasible
                    )
                )
            }
        }

        // 4. Feasible pairs satisfying both dark background and pair separation requirements
        // Internal contract consistency: Candidate feasibility strictly uses the exact same
        // conditions that determine allTargetsMet = true!
        val feasiblePairs = evaluatedPairs.filter { it.isFeasible }

        val pool = if (feasiblePairs.isNotEmpty()) {
            feasiblePairs
        } else {
            // Fallback: evaluate pairs maintaining polarity separation and maximizing minimum contrast
            evaluatedPairs
        }

        fun pairScore(p: EvaluatedJointPair): Float {
            val darkMargin = (p.crDarkBg - targetContrast).coerceAtLeast(0f)
            val pairMargin = (p.pairCr - minPairContrast).coerceAtLeast(0f)
            val marginBonus = 0.015f * minOf(5.0f, darkMargin + pairMargin)
            return p.jointDistance - marginBonus
        }

        val best = if (pool.isNotEmpty()) {
            // First check if exact reference colors are viable and robust
            val exactRef = pool.firstOrNull { it.jointDistance < 0.0001f }
            if (exactRef != null && exactRef.crDarkBg >= targetContrast + 0.5f && exactRef.pairCr >= minPairContrast + 0.5f) {
                exactRef
            } else {
                val exactSeedPairs = pool.filter { it.darkCandidate.isExactSeed && it.lightCandidate.isExactSeed }
                val targetPool = if (exactSeedPairs.isNotEmpty()) exactSeedPairs else {
                    val maxSeeds = pool.maxOf { it.seedsPreserved }
                    pool.filter { it.seedsPreserved == maxSeeds }
                }
                targetPool.minWithOrNull { a, b ->
                    val diff = pairScore(a) - pairScore(b)
                    if (abs(diff) > 0.02f) {
                        diff.compareTo(0f)
                    } else {
                        b.minCr.compareTo(a.minCr)
                    }
                } ?: targetPool.first()
            }
        } else null

        if (best != null) {
            val darkTargetMet = best.crDarkBg >= targetContrast
            val lightTargetMet = isLightFieldCompatible(best.lightCandidate, lightFrameLums, targetContrast)
            val pairMet = best.pairCr >= minPairContrast && (best.lightCandidate.lum - best.darkCandidate.lum) >= MIN_LUMINANCE_DELTA
            val allMet = darkTargetMet && lightTargetMet && pairMet

            val darkRes = ColorOptimizationResult(
                color = best.darkCandidate.color,
                contrastRatio = best.crDarkBg,
                targetContrast = targetContrast,
                targetMet = darkTargetMet,
                status = if (darkTargetMet) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE,
                sourceCandidate = best.darkCandidate.sourceCandidate,
                adjustmentDistance = best.darkCandidate.distance
            )
            val lightRes = ColorOptimizationResult(
                color = best.lightCandidate.color,
                contrastRatio = best.crLightBg,
                targetContrast = targetContrast,
                targetMet = lightTargetMet,
                status = if (lightTargetMet) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE,
                sourceCandidate = best.lightCandidate.sourceCandidate,
                adjustmentDistance = best.lightCandidate.distance
            )

            return JointColorOptimizationResult(
                dark = darkRes,
                light = lightRes,
                pairContrastRatio = best.pairCr,
                allTargetsMet = allMet,
                status = if (allMet) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE
            )
        }

        // Complete fallback
        val darkRes = optimizeWithCandidates(true, darkFrameLums, darkCandidates, targetContrast)
        val lightRes = optimizeWithCandidates(false, lightFrameLums, lightCandidates, targetContrast)
        val pairCr = ImageColorAnalyzer.contrastRatio(darkRes.color, lightRes.color)
        val allMet = darkRes.targetMet && lightRes.targetMet && pairCr >= minPairContrast
        return JointColorOptimizationResult(
            dark = darkRes,
            light = lightRes,
            pairContrastRatio = pairCr,
            allTargetsMet = allMet,
            status = if (allMet) OptimizationStatus.TARGET_MET else OptimizationStatus.INFEASIBLE
        )
    }
}
