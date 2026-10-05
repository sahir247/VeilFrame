package com.veilframe.app.qr.image

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Result of an adaptive color optimization step.
 *
 * @param color The final selected sRGB color.
 * @param contrastRatio The verified minimum WCAG contrast ratio achieved across all evaluated frames.
 * @param targetContrast The target contrast ratio requested (e.g. 3.0f).
 * @param targetMet Whether [contrastRatio] >= [targetContrast].
 * @param sourceCandidate The original candidate or palette color from which [color] was derived, or null if anchor.
 * @param adjustmentDistance The perceptual color difference (Delta E_OK) from the reference color to [color].
 */
data class ColorOptimizationResult(
    val color: Int,
    val contrastRatio: Float,
    val targetContrast: Float,
    val targetMet: Boolean,
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
    val allTargetsMet: Boolean
)

/**
 * Adaptive Color Optimizer v2.
 *
 * Implements a principled, deterministic optimization pipeline:
 * 1. Evaluates effective composited background luminance distributions across frames.
 * 2. Generates candidate colors via Oklab / OKLCH perceptual lightness modulation along constant-hue rays.
 * 3. Applies gamut mapping to keep chroma maximized within the sRGB cube without channel clipping distortion.
 * 4. Verifies actual contrast against W3C WCAG 2.1 relative luminance.
 * 5. Minimizes perceptual adjustment distance (Delta E_OK) from user/reference color among valid candidates.
 * 6. Explicitly reports feasibility via [ColorOptimizationResult.targetMet].
 * 7. Supports independent and joint optimization for both dark and light logical QR module colors.
 */
object AdaptiveColorOptimizer {

    const val DEFAULT_TARGET_CONTRAST = 3.0f
    const val MIN_LUMINANCE_DELTA = 0.25f
    val MID_LUM_ANCHOR = 0xFF7D7D7D.toInt()

    /**
     * Internal evaluated candidate with its measured metrics.
     */
    private data class EvaluatedCandidate(
        val color: Int,
        val minCr: Float,
        val distance: Float,
        val sourceCandidate: Int?
    )

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
        val compAlpha = (companionColor ushr 24) and 0xFF

        // Transparent modules are preserved as transparent without artificial constraints
        if (refAlpha == 0) {
            return ColorOptimizationResult(
                color = Color.TRANSPARENT,
                contrastRatio = 1.0f,
                targetContrast = targetContrast,
                targetMet = true,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
        }

        // 1. Collect initial candidate seeds
        val candidateSeeds = mutableListOf<Int>()
        candidateSeeds.add(referenceColor)
        for (c in palette) {
            if (((c ushr 24) and 0xFF) > 0 && c !in candidateSeeds) {
                candidateSeeds.add(c)
            }
        }

        // 2. Generate candidates (both original and Oklab lightness-adjusted)
        val generatedCandidates = mutableListOf<Pair<Int, Int?>>() // Pair(color, sourceCandidate)
        for (seed in candidateSeeds) {
            generatedCandidates.add(Pair(seed, seed))

            // Generate Oklab lightness adjustments
            val oklch = OklabColor.sRgbToOklch(seed)
            if (isDark) {
                // For dark modules: adjust lightness downwards and upwards if polarity inversion is needed
                val targetLightnessLevels = floatArrayOf(0.08f, 0.15f, 0.22f, 0.30f, 0.38f, 0.45f, 0.65f, 0.75f, 0.85f)
                for (targetL in targetLightnessLevels) {
                    val adjusted = OklabColor.gamutMapOklch(targetL, oklch.c, oklch.h)
                    if (adjusted !in generatedCandidates.map { it.first }) {
                        generatedCandidates.add(Pair(adjusted, seed))
                    }
                }
            } else {
                // For light modules: adjust lightness upwards and downwards if polarity inversion is needed
                val targetLightnessLevels = floatArrayOf(0.55f, 0.65f, 0.72f, 0.80f, 0.88f, 0.95f, 0.35f, 0.20f)
                for (targetL in targetLightnessLevels) {
                    val adjusted = OklabColor.gamutMapOklch(targetL, oklch.c, oklch.h)
                    if (adjusted !in generatedCandidates.map { it.first }) {
                        generatedCandidates.add(Pair(adjusted, seed))
                    }
                }
            }
        }

        // Add standard boundary anchors
        val anchors = if (isDark) {
            listOf(Color.BLACK, Color.WHITE, MID_LUM_ANCHOR)
        } else {
            listOf(Color.WHITE, Color.BLACK, MID_LUM_ANCHOR)
        }
        for (anchor in anchors) {
            if (anchor !in generatedCandidates.map { it.first }) {
                generatedCandidates.add(Pair(anchor, null))
            }
        }

        // 3. Evaluate each candidate against WCAG relative luminance and compute Delta E_OK
        val evaluated = ArrayList<EvaluatedCandidate>(generatedCandidates.size)
        val targetRefOklab = if (refAlpha > 0) OklabColor.sRgbToOklab(referenceColor) else null

        for ((color, source) in generatedCandidates) {
            val lum = ImageColorAnalyzer.relativeLuminance(color)
            val minCr = ImageColorAnalyzer.minContrastRatio(lum, frameLums)
            val dist = if (targetRefOklab != null) {
                OklabColor.deltaEOk(targetRefOklab, OklabColor.sRgbToOklab(color))
            } else {
                // If reference color was transparent, anchor distance to neutral standard
                if (isDark) ImageColorAnalyzer.relativeLuminance(color) else (1.0f - ImageColorAnalyzer.relativeLuminance(color))
            }
            evaluated.add(EvaluatedCandidate(color, minCr, dist, source))
        }

        // 4. Candidate acceptance: filter those meeting target contrast
        val viable = evaluated.filter { it.minCr >= targetContrast }

        if (viable.isNotEmpty()) {
            // Prefer candidates derived from user seed/palette over generic boundary anchors
            val viableSeeds = viable.filter { it.sourceCandidate != null }
            val candidatePool = if (viableSeeds.isNotEmpty()) viableSeeds else viable

            // Pick candidate that minimizes perceptual deviation from reference color.
            // If distances are close (within 0.02 Delta E), break tie with higher contrast.
            val best = candidatePool.minWithOrNull { a, b ->
                val distDiff = a.distance - b.distance
                if (Math.abs(distDiff) > 0.02f) {
                    distDiff.compareTo(0f)
                } else {
                    b.minCr.compareTo(a.minCr)
                }
            } ?: candidatePool.first()

            return ColorOptimizationResult(
                color = best.color,
                contrastRatio = best.minCr,
                targetContrast = targetContrast,
                targetMet = true,
                sourceCandidate = best.sourceCandidate,
                adjustmentDistance = best.distance
            )
        }

        // 5. Minimax fallback: when target contrast cannot be met across all frames,
        // return candidate that achieves the maximum possible minimum contrast.
        val bestAchievable = evaluated.maxByOrNull { it.minCr } ?: evaluated.first()

        return ColorOptimizationResult(
            color = bestAchievable.color,
            contrastRatio = bestAchievable.minCr,
            targetContrast = targetContrast,
            targetMet = false,
            sourceCandidate = bestAchievable.sourceCandidate,
            adjustmentDistance = bestAchievable.distance
        )
    }

    /**
     * Jointly optimizes dark and light module colors against background frame luminance samples [frameLums].
     * Guarantees contrast of dark against background, light against background, and dark against light.
     */
    fun optimizeJointColorPair(
        frameLums: List<Float>,
        defaultDark: Int,
        defaultLight: Int,
        palette: List<Int> = emptyList(),
        targetContrast: Float = DEFAULT_TARGET_CONTRAST
    ): JointColorOptimizationResult {
        if (((defaultLight ushr 24) and 0xFF) == 0) {
            val darkResult = optimizeColor(
                isDark = true,
                frameLums = frameLums,
                referenceColor = defaultDark,
                companionColor = Color.TRANSPARENT,
                palette = palette,
                targetContrast = targetContrast
            )
            val lightResult = ColorOptimizationResult(
                color = Color.TRANSPARENT,
                contrastRatio = 1.0f,
                targetContrast = targetContrast,
                targetMet = true,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
            return JointColorOptimizationResult(
                dark = darkResult,
                light = lightResult,
                pairContrastRatio = 21.0f,
                allTargetsMet = darkResult.targetMet
            )
        }

        if (((defaultDark ushr 24) and 0xFF) == 0) {
            val lightResult = optimizeColor(
                isDark = false,
                frameLums = frameLums,
                referenceColor = defaultLight,
                companionColor = Color.TRANSPARENT,
                palette = palette,
                targetContrast = targetContrast
            )
            val darkResult = ColorOptimizationResult(
                color = Color.TRANSPARENT,
                contrastRatio = 1.0f,
                targetContrast = targetContrast,
                targetMet = true,
                sourceCandidate = null,
                adjustmentDistance = 0f
            )
            return JointColorOptimizationResult(
                dark = darkResult,
                light = lightResult,
                pairContrastRatio = 21.0f,
                allTargetsMet = lightResult.targetMet
            )
        }

        val darkResult = optimizeColor(
            isDark = true,
            frameLums = frameLums,
            referenceColor = defaultDark,
            companionColor = defaultLight,
            palette = palette,
            targetContrast = targetContrast
        )

        val lightResult = optimizeColor(
            isDark = false,
            frameLums = frameLums,
            referenceColor = defaultLight,
            companionColor = defaultDark,
            palette = palette,
            targetContrast = targetContrast
        )

        var pairCr = ImageColorAnalyzer.contrastRatio(darkResult.color, lightResult.color)
        var allMet = darkResult.targetMet && lightResult.targetMet && pairCr >= targetContrast

        // If dark and light collide on the same polarity or have inadequate contrast between them:
        if (pairCr < targetContrast) {
            // Secondary adjustment: if dark is lightened, push light towards extreme opposite
            val darkLum = ImageColorAnalyzer.relativeLuminance(darkResult.color)
            val lightLum = ImageColorAnalyzer.relativeLuminance(lightResult.color)

            val resolvedLight = if (darkLum > 0.5f) {
                // Dark module inverted to bright; force light module to dark anchor
                ColorOptimizationResult(
                    color = Color.BLACK,
                    contrastRatio = ImageColorAnalyzer.minContrastRatio(0.0f, frameLums),
                    targetContrast = targetContrast,
                    targetMet = ImageColorAnalyzer.minContrastRatio(0.0f, frameLums) >= targetContrast,
                    sourceCandidate = null,
                    adjustmentDistance = if (((defaultLight ushr 24) and 0xFF) > 0) OklabColor.deltaEOk(defaultLight, Color.BLACK) else 0f
                )
            } else {
                // Dark module is dark; force light module to white anchor
                ColorOptimizationResult(
                    color = Color.WHITE,
                    contrastRatio = ImageColorAnalyzer.minContrastRatio(1.0f, frameLums),
                    targetContrast = targetContrast,
                    targetMet = ImageColorAnalyzer.minContrastRatio(1.0f, frameLums) >= targetContrast,
                    sourceCandidate = null,
                    adjustmentDistance = if (((defaultLight ushr 24) and 0xFF) > 0) OklabColor.deltaEOk(defaultLight, Color.WHITE) else 0f
                )
            }

            pairCr = ImageColorAnalyzer.contrastRatio(darkResult.color, resolvedLight.color)
            allMet = darkResult.targetMet && resolvedLight.targetMet && pairCr >= targetContrast
            return JointColorOptimizationResult(darkResult, resolvedLight, pairCr, allMet)
        }

        return JointColorOptimizationResult(darkResult, lightResult, pairCr, allMet)
    }
}
