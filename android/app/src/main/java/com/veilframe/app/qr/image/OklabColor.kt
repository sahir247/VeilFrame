package com.veilframe.app.qr.image

import android.graphics.Color
import kotlin.math.*

/**
 * High-precision Oklab and OKLCH perceptual color space implementation (Björn Ottosson, 2020).
 *
 * Provides:
 * 1. Exact forward/inverse transforms between sRGB and Oklab (L, a, b) / OKLCH (L, C, h).
 * 2. Perceptual color distance Delta E_OK.
 * 3. Gamut-safe chroma clipping along constant-hue rays (preserves perceived hue angle).
 */
object OklabColor {

    data class Oklab(val l: Float, val a: Float, val b: Float)
    data class Oklch(val l: Float, val c: Float, val h: Float)

    /**
     * Converts an sRGB packed Int color to Oklab coordinates.
     */
    fun sRgbToOklab(color: Int): Oklab {
        val r = sRgbToLinear(((color ushr 16) and 0xFF) / 255.0f)
        val g = sRgbToLinear(((color ushr 8) and 0xFF) / 255.0f)
        val b = sRgbToLinear((color and 0xFF) / 255.0f)

        val lRoot = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val mRoot = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val sRoot = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

        val L = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot
        val a = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot
        val bCoord = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot

        return Oklab(L, a, bCoord)
    }

    /**
     * Converts Oklab coordinates to an sRGB packed Int color with channel clamping.
     */
    fun oklabToSRgb(oklab: Oklab): Int {
        val lPrime = oklab.l + 0.3963377774f * oklab.a + 0.2158037573f * oklab.b
        val mPrime = oklab.l - 0.1055613458f * oklab.a - 0.0638541728f * oklab.b
        val sPrime = oklab.l - 0.0894841775f * oklab.a - 1.2914855480f * oklab.b

        val l = lPrime * lPrime * lPrime
        val m = mPrime * mPrime * mPrime
        val s = sPrime * sPrime * sPrime

        val rLin = +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val gLin = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bLin = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s

        val r = (linearToSRgb(rLin) * 255.0f).roundToInt().coerceIn(0, 255)
        val g = (linearToSRgb(gLin) * 255.0f).roundToInt().coerceIn(0, 255)
        val b = (linearToSRgb(bLin) * 255.0f).roundToInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Converts Oklab to cylindrical OKLCH coordinates (L in [0, 1], C >= 0, h in [0, 360)).
     */
    fun oklabToOklch(oklab: Oklab): Oklch {
        val c = sqrt(oklab.a * oklab.a + oklab.b * oklab.b)
        var h = (atan2(oklab.b, oklab.a) * (180.0f / Math.PI.toFloat()))
        if (h < 0f) h += 360.0f
        return Oklch(oklab.l, c, h)
    }

    /**
     * Converts OKLCH to Oklab.
     */
    fun oklchToOklab(oklch: Oklch): OklchOklab {
        val hRad = oklch.h * (Math.PI.toFloat() / 180.0f)
        val a = oklch.c * cos(hRad)
        val b = oklch.c * sin(hRad)
        return Oklab(oklch.l, a, b)
    }

    /**
     * Converts sRGB Int to OKLCH.
     */
    fun sRgbToOklch(color: Int): Oklch = oklabToOklch(sRgbToOklab(color))

    /**
     * Checks if coordinates (L, a, b) fall strictly within the sRGB gamut [0, 1]^3.
     */
    fun isInSRgbGamut(l: Float, a: Float, b: Float): Boolean {
        val lPrime = l + 0.3963377774f * a + 0.2158037573f * b
        val mPrime = l - 0.1055613458f * a - 0.0638541728f * b
        val sPrime = l - 0.0894841775f * a - 1.2914855480f * b

        val lCube = lPrime * lPrime * lPrime
        val mCube = mPrime * mPrime * mPrime
        val sCube = sPrime * sPrime * sPrime

        val rLin = +4.0767416621f * lCube - 3.3077115913f * mCube + 0.2309699292f * sCube
        val gLin = -1.2684380046f * lCube + 2.6097574011f * mCube - 0.3413193965f * sCube
        val bLin = -0.0041960863f * lCube - 0.7034186147f * mCube + 1.7076147010f * sCube

        return rLin in -0.0001f..1.0001f && gLin in -0.0001f..1.0001f && bLin in -0.0001f..1.0001f
    }

    /**
     * Gamut maps (targetL, targetC, h) to sRGB along the constant-hue ray.
     * Preserves target perceived lightness [targetL] and hue [h], binary-searching the maximum
     * in-gamut chroma C' in [0, targetC].
     */
    fun gamutMapOklch(targetL: Float, targetC: Float, h: Float): Int {
        val clampedL = targetL.coerceIn(0.0f, 1.0f)
        if (clampedL <= 0.0001f) return Color.BLACK
        if (clampedL >= 0.9999f) return Color.WHITE

        val hRad = h * (Math.PI.toFloat() / 180.0f)
        val cosH = cos(hRad)
        val sinH = sin(hRad)

        // Check if original chroma is already in gamut
        val aInit = targetC * cosH
        val bInit = targetC * sinH
        if (isInSRgbGamut(clampedL, aInit, bInit)) {
            return oklabToSRgb(Oklab(clampedL, aInit, bInit))
        }

        // Binary search for maximum in-gamut chroma
        var lowC = 0.0f
        var highC = targetC
        var bestA = 0.0f
        var bestB = 0.0f

        for (iter in 0 until 16) {
            val midC = (lowC + highC) * 0.5f
            val aMid = midC * cosH
            val bMid = midC * sinH
            if (isInSRgbGamut(clampedL, aMid, bMid)) {
                bestA = aMid
                bestB = bMid
                lowC = midC
            } else {
                highC = midC
            }
        }

        return oklabToSRgb(Oklab(clampedL, bestA, bestB))
    }

    /**
     * Perceptual color difference Delta E_OK between two sRGB colors.
     */
    fun deltaEOk(colorA: Int, colorB: Int): Float {
        val oA = sRgbToOklab(colorA)
        val oB = sRgbToOklab(colorB)
        val dL = oA.l - oB.l
        val da = oA.a - oB.a
        val db = oA.b - oB.b
        return sqrt(dL * dL + da * da + db * db)
    }

    fun deltaEOk(oA: Oklab, oB: Oklab): Float {
        val dL = oA.l - oB.l
        val da = oA.a - oB.a
        val db = oA.b - oB.b
        return sqrt(dL * dL + da * da + db * db)
    }

    private fun sRgbToLinear(c: Float): Float {
        return if (c <= 0.04045f) {
            c / 12.92f
        } else {
            Math.pow(((c + 0.055) / 1.055), 2.4).toFloat()
        }
    }

    private fun linearToSRgb(c: Float): Float {
        val clamped = c.coerceAtLeast(0.0f)
        return if (clamped <= 0.0031308f) {
            12.92f * clamped
        } else {
            (1.055f * Math.pow(clamped.toDouble(), 1.0 / 2.4).toFloat()) - 0.055f
        }
    }

    private fun cbrt(v: Float): Float {
        return if (v >= 0.0f) {
            Math.pow(v.toDouble(), 1.0 / 3.0).toFloat()
        } else {
            -Math.pow((-v).toDouble(), 1.0 / 3.0).toFloat()
        }
    }
}
typealias OklchOklab = OklabColor.Oklab
