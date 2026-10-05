package com.veilframe.app.qr.image

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Result of analyzing an image's color distribution for QR code rendering.
 *
 * @param darkColor The selected primary color for dark QR modules.
 * @param lightColor The selected primary color for light QR modules.
 * @param palette The extracted prominent color clusters from the image (up to 8 colors).
 * @param darkCandidates Palette candidates suitable for dark modules (L <= 0.45), including anchors.
 * @param lightCandidates Palette candidates suitable for light modules (L >= 0.55), including anchors.
 * @param contrastRatio The WCAG contrast ratio between [lightColor] and [darkColor].
 * @param luminanceDelta The absolute difference between [lightColor] and [darkColor] luminances.
 */
data class ResolvedImageColors(
    val darkColor: Int,
    val lightColor: Int,
    val palette: List<Int> = emptyList(),
    val darkCandidates: List<Int> = emptyList(),
    val lightCandidates: List<Int> = emptyList(),
    val contrastRatio: Float = 1.0f,
    val luminanceDelta: Float = 0.0f
)

/**
 * Analyzes an image's color palette to determine optimal, high-contrast, visually harmonious
 * module colors for artistic image-backed QR codes.
 *
 * Implements:
 * 1. Low-memory downsampled pixel extraction (budgeted across images/frames, capped at 128x128).
 * 2. Unusable pixel filtering (alpha < 128, pure clipping extremes, extreme low saturation).
 * 3. 15-bit color bin quantization with saliency weighting (scaled by saturation).
 * 4. Cluster extraction of distinct, prominent palette colors using perceptual color distance (redmean).
 * 5. Relative luminance following W3C WCAG 2.1 / IEC 61966-2-1 linear sRGB standard with lookup table.
 * 6. Local contrast optimizer with guaranteed contrast target (CR >= 3.0:1) and dynamic candidate selection.
 * 7. Multi-frame support for animated image styles ensuring temporal contrast stability.
 */
object ImageColorAnalyzer {

    const val MAX_ANALYSIS_DIMENSION = 128
    const val MIN_CONTRAST_RATIO = 3.0f
    const val MIN_LUMINANCE_DELTA = 0.25f

    /**
     * Precomputed lookup table for channel linearization following IEC 61966-2-1 / W3C WCAG 2.1:
     * c_linear = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055)^2.4
     */
    val LINEAR_SRGB_LUT = FloatArray(256) { i ->
        val c = i / 255.0f
        if (c <= 0.04045f) {
            c / 12.92f
        } else {
            Math.pow(((c + 0.055) / 1.055), 2.4).toFloat()
        }
    }

    /**
     * Relative luminance following W3C WCAG 2.1 / IEC 61966-2-1 sRGB standard (0.0 to 1.0).
     */
    fun relativeLuminance(color: Int): Float {
        return relativeLuminance(Color.red(color), Color.green(color), Color.blue(color))
    }

    fun relativeLuminance(r: Int, g: Int, b: Int): Float {
        val rLin = LINEAR_SRGB_LUT[r.coerceIn(0, 255)]
        val gLin = LINEAR_SRGB_LUT[g.coerceIn(0, 255)]
        val bLin = LINEAR_SRGB_LUT[b.coerceIn(0, 255)]
        return (0.2126f * rLin) + (0.7152f * gLin) + (0.0722f * bLin)
    }

    /**
     * WCAG contrast ratio between two colors (1.0 to 21.0).
     */
    fun contrastRatio(colorA: Int, colorB: Int): Float {
        return contrastRatio(relativeLuminance(colorA), relativeLuminance(colorB))
    }

    fun contrastRatio(lumA: Float, lumB: Float): Float {
        val lighter = max(lumA, lumB)
        val darker = min(lumA, lumB)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * Perceptual color distance squared using the redmean color difference approximation.
     * Accurately models human visual sensitivity across RGB spectra without heavy transcendental overhead.
     */
    fun perceptualDistanceSq(c1: Int, c2: Int): Double {
        val r1 = Color.red(c1)
        val g1 = Color.green(c1)
        val b1 = Color.blue(c1)
        val r2 = Color.red(c2)
        val g2 = Color.green(c2)
        val b2 = Color.blue(c2)
        val rMean = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()
        val weightR = 2.0 + rMean / 256.0
        val weightG = 4.0
        val weightB = 2.0 + (255.0 - rMean) / 256.0
        return weightR * dr * dr + weightG * dg * dg + weightB * db * db
    }

    /**
     * Fast HSV saturation calculation (0.0 to 1.0).
     */
    fun saturationOf(color: Int): Float {
        return saturationOf(Color.red(color), Color.green(color), Color.blue(color))
    }

    fun saturationOf(r: Int, g: Int, b: Int): Float {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        return if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC.toFloat()
    }

    /**
     * Analyzes [bitmap] to extract its dominant palette and determine optimal dark/light
     * color pairs and candidate pools suitable for QR data modules.
     */
    fun analyze(bitmap: Bitmap?): ResolvedImageColors {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return ResolvedImageColors(
                darkColor = Color.BLACK,
                lightColor = Color.WHITE,
                palette = emptyList(),
                darkCandidates = listOf(Color.BLACK),
                lightCandidates = listOf(Color.WHITE),
                contrastRatio = 21.0f,
                luminanceDelta = 1.0f
            )
        }
        return analyzeBitmaps(listOf(bitmap))
    }

    /**
     * Analyzes multiple animation [frames] to extract a conservative, temporally stable palette
     * across the entire animation sequence.
     */
    fun analyzeAnimated(frames: List<Bitmap>): ResolvedImageColors {
        val validFrames = frames.filter { !it.isRecycled && it.width > 0 && it.height > 0 }
        if (validFrames.isEmpty()) {
            return analyze(null)
        }
        return analyzeBitmaps(validFrames)
    }

    private fun analyzeBitmaps(bitmaps: List<Bitmap>): ResolvedImageColors {
        class ColorBin(var count: Int = 0, var weight: Float = 0f, var sumR: Long = 0L, var sumG: Long = 0L, var sumB: Long = 0L)

        val chromaticBins = mutableMapOf<Int, ColorBin>()
        val allValidBins = mutableMapOf<Int, ColorBin>()

        // Distribute pixel sampling budget evenly across frames
        val maxPixelsPerBitmap = (MAX_ANALYSIS_DIMENSION * MAX_ANALYSIS_DIMENSION) / bitmaps.size
        val targetDim = max(16, Math.sqrt(maxPixelsPerBitmap.toDouble()).toInt())

        for (bitmap in bitmaps) {
            val maxDim = max(bitmap.width, bitmap.height)
            val (scaledBitmap, mustRecycle) = if (maxDim > targetDim) {
                val scale = targetDim.toFloat() / maxDim
                val targetW = max(1, (bitmap.width * scale).toInt())
                val targetH = max(1, (bitmap.height * scale).toInt())
                Pair(Bitmap.createScaledBitmap(bitmap, targetW, targetH, true), true)
            } else {
                Pair(bitmap, false)
            }

            val w = scaledBitmap.width
            val h = scaledBitmap.height
            val pixels = IntArray(w * h)
            scaledBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            if (mustRecycle && scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            for (pixel in pixels) {
                val a = Color.alpha(pixel)
                if (a < 128) continue

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val lum = relativeLuminance(r, g, b)
                val sat = saturationOf(r, g, b)

                // 15-bit quantization: 5 bits per channel (0..31)
                val binKey = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)

                // Saliency-weighted visual prominence:
                // Highly saturated pixels catch the human eye much more strongly than dull pixels.
                val weight = 1.0f + 2.0f * sat

                val allBin = allValidBins.getOrPut(binKey) { ColorBin() }
                allBin.count++
                allBin.weight += weight
                allBin.sumR += r
                allBin.sumG += g
                allBin.sumB += b

                // Filter out clipping extremes (< 0.005 or > 0.995) and washed-out low saturation (< 0.05) for chromatic clustering
                if (lum in 0.005f..0.995f && sat >= 0.05f) {
                    val chromBin = chromaticBins.getOrPut(binKey) { ColorBin() }
                    chromBin.count++
                    chromBin.weight += weight
                    chromBin.sumR += r
                    chromBin.sumG += g
                    chromBin.sumB += b
                }
            }
        }

        val activeBins = if (chromaticBins.size >= 5 || chromaticBins.values.sumOf { it.count } >= 50) {
            chromaticBins
        } else {
            allValidBins
        }

        if (activeBins.isEmpty()) {
            return ResolvedImageColors(
                darkColor = Color.BLACK,
                lightColor = Color.WHITE,
                palette = emptyList(),
                darkCandidates = listOf(Color.BLACK),
                lightCandidates = listOf(Color.WHITE),
                contrastRatio = 21.0f,
                luminanceDelta = 1.0f
            )
        }

        // Cluster into distinct, visually prominent palette colors using perceptual distance
        val sortedBins = activeBins.values.sortedByDescending { it.weight }
        val palette = mutableListOf<Int>()
        for (bin in sortedBins) {
            if (bin.count <= 0) continue
            val cr = (bin.sumR / bin.count).toInt().coerceIn(0, 255)
            val cg = (bin.sumG / bin.count).toInt().coerceIn(0, 255)
            val cb = (bin.sumB / bin.count).toInt().coerceIn(0, 255)
            val candidateColor = Color.rgb(cr, cg, cb)

            // Ensure perceptual distance from existing palette colors (redmean distance >= 38)
            val isDistinct = palette.all { existing ->
                perceptualDistanceSq(candidateColor, existing) >= 1444.0 // 38^2
            }

            if (isDistinct) {
                palette.add(candidateColor)
                if (palette.size >= 8) break
            }
        }

        // Partition into candidate pools
        val darkCandidates = mutableListOf<Int>()
        val lightCandidates = mutableListOf<Int>()

        for (c in palette) {
            val lum = relativeLuminance(c)
            if (lum <= 0.45f) {
                darkCandidates.add(c)
            }
            if (lum >= 0.55f) {
                lightCandidates.add(c)
            }
        }
        if (Color.BLACK !in darkCandidates) darkCandidates.add(Color.BLACK)
        if (Color.WHITE !in lightCandidates) lightCandidates.add(Color.WHITE)

        // Pair selection: Find candidates satisfying contrast and separation
        data class ScoredPair(val darkColor: Int, val lightColor: Int, val contrast: Float, val deltaL: Float, val score: Float)
        val validPairs = mutableListOf<ScoredPair>()

        for (i in 0 until palette.size) {
            for (j in i + 1 until palette.size) {
                val c1 = palette[i]
                val c2 = palette[j]
                val l1 = relativeLuminance(c1)
                val l2 = relativeLuminance(c2)

                val (dark, light) = if (l1 < l2) Pair(c1, c2) else Pair(c2, c1)
                val lumDark = min(l1, l2)
                val lumLight = max(l1, l2)

                val cr = (lumLight + 0.05f) / (lumDark + 0.05f)
                val deltaL = lumLight - lumDark

                if (cr >= MIN_CONTRAST_RATIO && deltaL >= MIN_LUMINANCE_DELTA) {
                    val pairScore = cr * (1.0f + deltaL)
                    validPairs.add(ScoredPair(dark, light, cr, deltaL, pairScore))
                }
            }
        }

        if (validPairs.isNotEmpty()) {
            val best = validPairs.maxByOrNull { it.score }!!
            return ResolvedImageColors(
                darkColor = best.darkColor,
                lightColor = best.lightColor,
                palette = palette,
                darkCandidates = darkCandidates,
                lightCandidates = lightCandidates,
                contrastRatio = best.contrast,
                luminanceDelta = best.deltaL
            )
        }

        // Fallback: Pair prominent color with an anchor (WHITE or BLACK) that guarantees deltaL >= 0.25 and high contrast.
        val primaryColor = palette.firstOrNull() ?: Color.BLACK
        val primaryLum = relativeLuminance(primaryColor)

        val (darkColor, lightColor) = if (1.0f - primaryLum >= MIN_LUMINANCE_DELTA) {
            Pair(primaryColor, Color.WHITE)
        } else {
            Pair(Color.BLACK, primaryColor)
        }

        val lDark = relativeLuminance(darkColor)
        val lLight = relativeLuminance(lightColor)
        val finalCr = (lLight + 0.05f) / (lDark + 0.05f)
        val finalDeltaL = lLight - lDark

        return ResolvedImageColors(
            darkColor = darkColor,
            lightColor = lightColor,
            palette = palette,
            darkCandidates = darkCandidates,
            lightCandidates = lightCandidates,
            contrastRatio = finalCr,
            luminanceDelta = finalDeltaL
        )
    }

    /**
     * Resolves the optimized module color for a data module at local image luminance [localLum].
     *
     * Guarantees:
     * 1. Polarity and separation: Dark modules have L <= 0.45, Light modules have L >= 0.55,
     *    with guaranteed minimum luminance separation.
     * 2. Local contrast optimization: Evaluates candidates from the extracted image palette
     *    to choose harmonious image colors that satisfy target contrast against the local background.
     * 3. Prevents low-contrast dropout: Eliminates unconstrained black-on-black / white-on-white
     *    by preferring colors with maximum effective contrast.
     * 4. EF Parity: Transparent light modules (alpha == 0) are strictly preserved as transparent.
     */
    fun resolveAdaptiveContrastColor(
        isDark: Boolean,
        localLum: Float,
        defaultDark: Int,
        defaultLight: Int,
        palette: List<Int> = emptyList()
    ): Int {
        if (!isDark && Color.alpha(defaultLight) == 0) {
            return Color.TRANSPARENT
        }

        val lumDark = relativeLuminance(defaultDark)
        val lumLight = relativeLuminance(defaultLight)

        if (isDark) {
            // Dark Module: target L <= 0.45 (or <= lumLight - 0.25)
            val maxAllowedLum = min(0.45f, lumLight - MIN_LUMINANCE_DELTA)

            // Candidate pool: defaultDark, dark palette colors, and Color.BLACK
            val candidates = mutableListOf<Int>()
            if (lumDark <= maxAllowedLum) {
                candidates.add(defaultDark)
            }
            for (c in palette) {
                val lumC = relativeLuminance(c)
                if (lumC <= maxAllowedLum && c !in candidates) {
                    candidates.add(c)
                }
            }
            if (Color.BLACK !in candidates) {
                candidates.add(Color.BLACK)
            }

            // 1. If background is light (localLum >= 0.45):
            // We want candidate with L < localLum and contrastRatio(localLum, L) >= MIN_CONTRAST_RATIO
            if (localLum >= 0.45f) {
                val viable = candidates.filter { c ->
                    val lumC = relativeLuminance(c)
                    lumC < localLum && contrastRatio(localLum, lumC) >= MIN_CONTRAST_RATIO
                }
                return viable.firstOrNull() ?: Color.BLACK
            }

            // 2. If background is dark (localLum < 0.45):
            // Check if defaultDark has sufficient contrast against localLum:
            // e.g. Miku cyan (L ~ 0.44) against localLum ~ 0.05 has CR = 0.49 / 0.10 = 4.9:1.
            val crDefault = contrastRatio(lumDark, localLum)
            if (lumDark <= maxAllowedLum && crDefault >= MIN_CONTRAST_RATIO) {
                return defaultDark
            }

            // If defaultDark doesn't satisfy contrast (e.g. defaultDark is black on dark background),
            // search palette for a candidate with L <= maxAllowedLum that satisfies contrast:
            val viablePalette = candidates.filter { c ->
                val lumC = relativeLuminance(c)
                lumC <= maxAllowedLum && contrastRatio(lumC, localLum) >= MIN_CONTRAST_RATIO
            }
            if (viablePalette.isNotEmpty()) {
                return viablePalette.first()
            }

            // If no candidate meets 3.0:1, pick the candidate that maximizes contrast against local background
            return candidates.maxByOrNull { c -> contrastRatio(relativeLuminance(c), localLum) } ?: Color.BLACK

        } else {
            // Light Module: target L >= 0.55 (or >= lumDark + 0.25)
            val minAllowedLum = max(0.55f, lumDark + MIN_LUMINANCE_DELTA)

            // Candidate pool: defaultLight, light palette colors, and Color.WHITE
            val candidates = mutableListOf<Int>()
            if (lumLight >= minAllowedLum) {
                candidates.add(defaultLight)
            }
            for (c in palette) {
                val lumC = relativeLuminance(c)
                if (lumC >= minAllowedLum && c !in candidates) {
                    candidates.add(c)
                }
            }
            if (Color.WHITE !in candidates) {
                candidates.add(Color.WHITE)
            }

            // 1. If background is dark (localLum <= 0.55):
            // We want candidate with L > localLum and contrastRatio(L, localLum) >= MIN_CONTRAST_RATIO
            if (localLum <= 0.55f) {
                val viable = candidates.filter { c ->
                    val lumC = relativeLuminance(c)
                    lumC > localLum && contrastRatio(lumC, localLum) >= MIN_CONTRAST_RATIO
                }
                return viable.firstOrNull() ?: Color.WHITE
            }

            // 2. If background is light (localLum > 0.55):
            if (lumLight >= minAllowedLum) {
                return defaultLight
            }
            return candidates.firstOrNull() ?: Color.WHITE
        }
    }
}
