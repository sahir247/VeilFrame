package com.veilframe.app.upscale.inference

import java.nio.ShortBuffer

/**
 * Pure Kotlin/Java IEEE-754 binary16 (half-precision float) conversion utilities.
 * Completely standalone, works identically on Android ART and host desktop JVM.
 */
object Float16Utils {

    /**
     * Converts a 32-bit single-precision float to a 16-bit half-precision float (as Short).
     */
    fun floatToHalf(fval: Float): Short {
        val fbits = java.lang.Float.floatToIntBits(fval)
        val sign = (fbits ushr 16) and 0x8000
        var valExp = ((fbits ushr 23) and 0xff) - (127 - 15)
        var mant = fbits and 0x007fffff

        if (valExp <= 0) {
            if (valExp < -10) {
                // Too small, underflow to zero
                return sign.toShort()
            }
            // Subnormal number
            mant = (mant or 0x00800000) shr (1 - valExp)
            return (sign or (mant shr 13)).toShort()
        } else if (valExp == 0xff - (127 - 15)) {
            return if (mant == 0) {
                // Infinity
                (sign or 0x7c00).toShort()
            } else {
                // NaN
                (sign or 0x7e00 or (mant shr 13)).toShort()
            }
        } else {
            if (valExp > 30) {
                // Overflow to infinity
                return (sign or 0x7c00).toShort()
            }
            return (sign or (valExp shl 10) or (mant shr 13)).toShort()
        }
    }

    /**
     * Converts a 16-bit half-precision float (as Short) to a 32-bit single-precision float.
     */
    fun halfToFloat(hval: Short): Float {
        val h = hval.toInt() and 0xffff
        val sign = (h and 0x8000) shl 16
        val exp = (h and 0x7c00) ushr 10
        val mant = (h and 0x03ff) shl 13

        val fbits = when (exp) {
            0 -> {
                if (mant == 0) {
                    sign
                } else {
                    // Subnormal half-precision
                    var m = mant
                    var e = 127 - 15 + 1
                    while ((m and 0x00800000) == 0) {
                        m = m shl 1
                        e--
                    }
                    m = m and 0x007fffff
                    sign or (e shl 23) or m
                }
            }
            0x1f -> {
                // Infinity or NaN
                sign or 0x7f800000 or mant
            }
            else -> {
                // Normalized number
                val newExp = exp + (127 - 15)
                sign or (newExp shl 23) or mant
            }
        }
        return java.lang.Float.intBitsToFloat(fbits)
    }

    /**
     * Batch converts an array of normalized floats [0.0f, 1.0f] into a ShortBuffer of half-precision floats.
     */
    fun fillShortBufferWithFloats(floats: FloatArray, targetBuffer: ShortBuffer) {
        targetBuffer.rewind()
        for (i in floats.indices) {
            targetBuffer.put(floatToHalf(floats[i]))
        }
        targetBuffer.rewind()
    }
}
