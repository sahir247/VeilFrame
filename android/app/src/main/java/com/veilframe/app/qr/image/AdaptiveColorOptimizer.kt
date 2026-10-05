package com.veilframe.app.qr.image

import android.graphics.Color
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
 * Adaptive Color Optimizer v2.
 *
 * Implements a principled, deterministic optimization pipeline:
 * 1. Evaluates effective composited background luminance distributions across frames.
 * 2. Generates candidate colors via Oklab / OKLCH perceptual lightness modulation along constant-hue rays.
 * 3. Applies constant-hue chroma clipping to keep chroma maximized within the sRGB cube.
 * 4. Verifies actual contrast against W3C WCAG 2.1 relative luminance.
 * 5. Minimizes perceptual adjustment distance (Delta E_OK) from user/reference color among valid candidates.
 * 6. Explicitly reports feasibility via [ColorOptimizationResult.targetMet] and [ColorOptimizationResult.status].
 * 7. Supports true joint candidate-pair evaluation (candidateDark x candidateLight) guaranteeing
 *    dark-bg, light-bg, and dark-light pair contrast simultaneously while preserving QR polarity.
 */
object AdaptiveColorOptimizer {

    const val DEFAULT_TARGET_CONTRAST = 3.0f
    const val MIN_LUMINANCE_DELTA = 0.20f
    val MID_LUM_ANCHOR = 0xFF7D7D7D.toInt()

    private data class EvaluatedJointPair(
        val darkCandidate: CandidateInfo,
        val lightCandidate: CandidateInfo,
        val crDarkBg: Float,
        val crLightBg: Float,
        val pairCr: Float,
        val minCr: Float,
        val jointDistance: Float,
        val seedsPreserved: Int
    )

    /**
     * Pre-computes candidate pool for [isDark] polarity from [referenceColor] and [palette].
     * Evaluates Oklab lightness modulation and constant-hue chroma clipping once per render.
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
                // For dark modules: lightness levels bounded to dark range
                val levels = floatArrayOf(0.08f, 0.15f, 0.22f, 0.30f, 0.38f, 0.45f)
                for (targetL in levels) {
                    val adjusted = OklabColor.gamutMapOklch(targetL, oklch.c, oklch.h)
                    if (adjusted !in generated.map { it.first }) {
                        generated.add(Triple(adjusted, seed, false))
                    }
                }
            } else {
                // For light modules: lightness levels bounded to light range
                val levels = floatArrayOf(0.55f, 0.65f, 0.72f, 0.80f, 0.88f, 0.95f)
                for (targetL in levels) {
                    val adjusted = OklabColor.gamutMapOklch(targetL, oklch.c, oklch.h)
                    if (adjusted !in generated.map { it.first }) {
                        generated.add(Triple(adjusted, seed, false))
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
            if (anchor !in generated.map { it.first }) {
                generated.add(Triple(anchor, null, false))
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
            val cr = ImageColorAnalyzer.minContrastRatio(fallback, frameLums)
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
        val evaluated = candidates.map { Evaluated(it, ImageColorAnalyzer.minContrastRatio(it.lum, frameLums)) }
        val viable = evaluated.filter { it.minCr >= targetContrast }

        if (viable.isNotEmpty()) {
            // If the exact reference color is viable (distance == 0.0f), strictly preserve it!
            val exactRef = viable.firstOrNull { it.candidate.distance < 0.0001f }
            if (exactRef != null) {
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

            val best = pool.minWithOrNull { a, b ->
                val distDiff = a.candidate.distance - b.candidate.distance
                if (Math.abs(distDiff) > 0.02f) {
                    distDiff.compareTo(0f)
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
        targetContrast: Float = DEFAULT_TARGET_CONTRAST
    ): JointColorOptimizationResult {
        return optimizeJointColorPair(
            darkFrameLums = frameLums,
            lightFrameLums = frameLums,
            defaultDark = defaultDark,
            defaultLight = defaultLight,
            palette = palette,
            targetContrast = targetContrast
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
        targetContrast: Float = DEFAULT_TARGET_CONTRAST
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
            targetContrast = targetContrast
        )
    }

    /**
     * Truly joint candidate-pair evaluation over the Cartesian product (candidateDark x candidateLight).
     * Enforces the fundamental QR polarity constraint: dark modules must be darker than light modules.
     * Simultaneously evaluates:
     * - CR(dark, bg) >= targetContrast
     * - CR(light, bg) >= targetContrast
     * - CR(dark, light) >= targetContrast
     * while minimizing combined perceptual deviation (Delta E_OK) from user reference colors.
     */
    fun optimizeJointWithCandidates(
        darkFrameLums: List<Float>,
        lightFrameLums: List<Float>,
        darkCandidates: List<CandidateInfo>,
        lightCandidates: List<CandidateInfo>,
        defaultDark: Int,
        defaultLight: Int,
        targetContrast: Float = DEFAULT_TARGET_CONTRAST
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

        // 3. Both dark and light are opaque: evaluate all pairs jointly
        // Polarity invariant: dark module must be darker than light module
        val evaluatedPairs = ArrayList<EvaluatedJointPair>()

        for (d in darkCandidates) {
            val crDarkBg = ImageColorAnalyzer.minContrastRatio(d.lum, darkFrameLums)
            for (l in lightCandidates) {
                // Polarity invariant: dark must be darker than light by at least MIN_LUMINANCE_DELTA
                if (d.lum >= l.lum || (l.lum - d.lum) < MIN_LUMINANCE_DELTA) continue

                val crLightBg = ImageColorAnalyzer.minContrastRatio(l.lum, lightFrameLums)
                val pairCr = ImageColorAnalyzer.contrastRatio(d.lum, l.lum)
                val minCr = minOf(crDarkBg, crLightBg, pairCr)
                val jointDistance = d.distance + l.distance
                val seedsPreserved = (if (d.sourceCandidate != null) 1 else 0) + (if (l.sourceCandidate != null) 1 else 0)

                evaluatedPairs.add(
                    EvaluatedJointPair(
                        darkCandidate = d,
                        lightCandidate = l,
                        crDarkBg = crDarkBg,
                        crLightBg = crLightBg,
                        pairCr = pairCr,
                        minCr = minCr,
                        jointDistance = jointDistance,
                        seedsPreserved = seedsPreserved
                    )
                )
            }
        }

        // 4. Feasible pairs satisfying both dark background and pair separation requirements
        // In a QR code:
        // - Dark module must contrast with dark background (crDarkBg >= targetContrast)
        // - Pair contrast/separation must be sufficient (pairCr >= minOf(targetContrast, 2.0f) and deltaL >= MIN_LUMINANCE_DELTA)
        // - Light module must contrast with background if background is dark, or match light field
        val minLightBgLum = lightFrameLums.minOrNull() ?: 1.0f
        val feasiblePairs = evaluatedPairs.filter {
            it.crDarkBg >= targetContrast &&
            it.pairCr >= minOf(targetContrast, 2.0f) &&
            (minLightBgLum > 0.45f || it.crLightBg >= targetContrast)
        }

        val pool = if (feasiblePairs.isNotEmpty()) {
            feasiblePairs
        } else {
            // Fallback: evaluate pairs maintaining polarity separation and maximizing minimum contrast
            evaluatedPairs
        }

        val best = if (pool.isNotEmpty()) {
            // First check if exact reference colors are viable (jointDistance < 0.0001f)
            val exactRef = pool.firstOrNull { it.jointDistance < 0.0001f }
            if (exactRef != null) {
                exactRef
            } else {
                val exactSeedPairs = pool.filter { it.darkCandidate.isExactSeed && it.lightCandidate.isExactSeed }
                val targetPool = if (exactSeedPairs.isNotEmpty()) exactSeedPairs else {
                    val maxSeeds = pool.maxOf { it.seedsPreserved }
                    pool.filter { it.seedsPreserved == maxSeeds }
                }
                targetPool.minWithOrNull { a, b ->
                    val distDiff = a.jointDistance - b.jointDistance
                    if (Math.abs(distDiff) > 0.02f) {
                        distDiff.compareTo(0f)
                    } else {
                        b.minCr.compareTo(a.minCr)
                    }
                } ?: targetPool.first()
            }
        } else null

        if (best != null) {
            val darkTargetMet = best.crDarkBg >= targetContrast
            val lightTargetMet = best.crLightBg >= targetContrast
            val pairMet = best.pairCr >= targetContrast
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
        return JointColorOptimizationResult(
            dark = darkRes,
            light = lightRes,
            pairContrastRatio = pairCr,
            allTargetsMet = darkRes.targetMet && lightRes.targetMet && pairCr >= targetContrast
        )
    }
}
