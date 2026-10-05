package com.veilframe.app.qr.image

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Result of analyzing an image's color distribution for QR code rendering.
 *
 * @param darkColor The selected color for dark QR modules.
 * @param lightColor The selected color for light QR modules.
 * @param palette The extracted prominent color clusters from the image (up to 8 colors).
 * @param contrastRatio The WCAG contrast ratio between [lightColor] and [darkColor].
 * @param luminanceDelta The absolute difference between [lightColor] and [darkColor] luminances.
 */
data class ResolvedImageColors(
    val darkColor: Int,
    val lightColor: Int,
    val palette: List<Int> = emptyList(),
    val contrastRatio: Float = 1.0f,
    val luminanceDelta: Float = 0.0f
)

/**
 * Analyzes an image's color palette to determine optimal, high-contrast, visually harmonious
 * module colors for artistic image-backed QR codes.
 *
 * Implements:
 * 1. Low-memory downsampled pixel extraction (capped at 128x128).
 * 2. Unusable pixel filtering (alpha < 128, pure clipping extremes, extreme low saturation).
 * 3. 15-bit color bin quantization with visual prominence weighting (scaled by saturation).
 * 4. Cluster extraction of distinct, prominent palette colors.
 * 5. Relative luminance and WCAG contrast validation, selecting a pair that maximizes contrast
 *    and satisfies minimum luminance separation (separation >= 0.25).
 * 6. High-contrast anchor fallback for monochromatic or low-contrast images.
 */
object ImageColorAnalyzer {

    const val MAX_ANALYSIS_DIMENSION = 128
    const val MIN_CONTRAST_RATIO = 3.0f
    const val MIN_LUMINANCE_DELTA = 0.25f

    /**
     * Analyzes [bitmap] to extract its dominant palette and determine a high-contrast dark/light
     * color pair suitable for QR data modules.
     */
    fun analyze(bitmap: Bitmap?): ResolvedImageColors {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return ResolvedImageColors(
                darkColor = Color.BLACK,
                lightColor = Color.WHITE,
                palette = emptyList(),
                contrastRatio = 21.0f,
                luminanceDelta = 1.0f
            )
        }

        // 1. Downsample to small analysis bitmap to conserve memory and maintain fast execution
        val maxDim = max(bitmap.width, bitmap.height)
        val (scaledBitmap, mustRecycle) = if (maxDim > MAX_ANALYSIS_DIMENSION) {
            val scale = MAX_ANALYSIS_DIMENSION.toFloat() / maxDim
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

        // 2. Sample and filter pixels
        class ColorBin(var count: Int = 0, var weight: Float = 0f, var sumR: Long = 0L, var sumG: Long = 0L, var sumB: Long = 0L)

        val chromaticBins = mutableMapOf<Int, ColorBin>()
        val allValidBins = mutableMapOf<Int, ColorBin>()

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
            val weight = 1.0f + 2.0f * sat

            val allBin = allValidBins.getOrPut(binKey) { ColorBin() }
            allBin.count++
            allBin.weight += weight
            allBin.sumR += r
            allBin.sumG += g
            allBin.sumB += b

            // Filter out extreme luminance (< 0.05 or > 0.95) and washed-out low saturation (< 0.05) for chromatic clustering
            if (lum in 0.05f..0.95f && sat >= 0.05f) {
                val chromBin = chromaticBins.getOrPut(binKey) { ColorBin() }
                chromBin.count++
                chromBin.weight += weight
                chromBin.sumR += r
                chromBin.sumG += g
                chromBin.sumB += b
            }
        }

        // If chromatic pixels are too few (e.g. monochromatic or grayscale image), use all valid non-transparent bins
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
                contrastRatio = 21.0f,
                luminanceDelta = 1.0f
            )
        }

        // 3. Cluster into distinct, visually prominent palette colors
        val sortedBins = activeBins.values.sortedByDescending { it.weight }
        val palette = mutableListOf<Int>()
        for (bin in sortedBins) {
            if (bin.count <= 0) continue
            val cr = (bin.sumR / bin.count).toInt().coerceIn(0, 255)
            val cg = (bin.sumG / bin.count).toInt().coerceIn(0, 255)
            val cb = (bin.sumB / bin.count).toInt().coerceIn(0, 255)
            val candidateColor = Color.rgb(cr, cg, cb)

            // Ensure perceptual distance from existing palette colors
            val isDistinct = palette.all { existing ->
                val dr = cr - Color.red(existing)
                val dg = cg - Color.green(existing)
                val db = cb - Color.blue(existing)
                (dr * dr + dg * dg + db * db) >= 900 // distance >= 30 in RGB
            }

            if (isDistinct) {
                palette.add(candidateColor)
                if (palette.size >= 8) break
            }
        }

        // 4. Pair selection: Find candidates satisfying contrast and separation
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
                contrastRatio = best.contrast,
                luminanceDelta = best.deltaL
            )
        }

        // 5. Fallback: No intra-palette pair met the threshold (e.g. pastel image, Miku reference with light background).
        // Pair the most prominent color with an anchor (WHITE or BLACK) that guarantees deltaL >= 0.25 and high contrast.
        val primaryColor = palette.firstOrNull() ?: Color.BLACK
        val primaryLum = relativeLuminance(primaryColor)

        val (darkColor, lightColor) = if (1.0f - primaryLum >= MIN_LUMINANCE_DELTA) {
            // Primary color is dark enough relative to white (e.g. Miku cyan #39C5BC with lum ~0.65 vs Color.WHITE lum 1.0)
            Pair(primaryColor, Color.WHITE)
        } else {
            // Primary color is very bright (lum > 0.75), use it as light module paired with black
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
            contrastRatio = finalCr,
            luminanceDelta = finalDeltaL
        )
    }

    /**
     * Relative luminance following sRGB standard / WCAG 2.1 (0.0 to 1.0).
     */
    fun relativeLuminance(color: Int): Float {
        return relativeLuminance(Color.red(color), Color.green(color), Color.blue(color))
    }

    fun relativeLuminance(r: Int, g: Int, b: Int): Float {
        val rNorm = r / 255f
        val gNorm = g / 255f
        val bNorm = b / 255f
        return (0.2126f * rNorm) + (0.7152f * gNorm) + (0.0722f * bNorm)
    }

    /**
     * WCAG contrast ratio between two colors (1.0 to 21.0).
     */
    fun contrastRatio(colorA: Int, colorB: Int): Float {
        val lumA = relativeLuminance(colorA)
        val lumB = relativeLuminance(colorB)
        val lighter = max(lumA, lumB)
        val darker = min(lumA, lumB)
        return (lighter + 0.05f) / (darker + 0.05f)
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
     * Resolves the adaptive contrast color for a data module at local image luminance [localLum].
     *
     * Ensures that dark modules are strictly darker than the local background and light modules
     * are strictly lighter, preventing localized binarizer dropout and bit-inversion.
     *
     * @param isDark Whether the QR matrix module is dark.
     * @param localLum Relative luminance of the underlying image at the module location (0.0 to 1.0).
     * @param defaultDark Color configured for dark modules.
     * @param defaultLight Color configured for light modules.
     */
    fun resolveAdaptiveContrastColor(
        isDark: Boolean,
        localLum: Float,
        defaultDark: Int,
        defaultLight: Int
    ): Int {
        val darkLum = relativeLuminance(defaultDark)
        val lightLum = relativeLuminance(defaultLight)

        return if (isDark) {
            if (darkLum < localLum - 0.15f) {
                defaultDark
            } else {
                Color.BLACK
            }
        } else {
            if (lightLum > localLum + 0.15f) {
                defaultLight
            } else {
                Color.WHITE
            }
        }
    }
}
