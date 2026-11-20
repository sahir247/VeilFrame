package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Local 3x3 high-frequency enhancement. The output is synthesized micro-contrast,
 * not recovered source detail. Alpha is preserved. A bounded deterministic CPU
 * implementation remains available if native acceleration cannot be used.
 */
object AdaptiveDetailEnhancer {
    fun enhance(
        sourceBitmap: Bitmap,
        strength: Float = 0.35f,
        checkpoint: (() -> Unit)? = null
    ): Bitmap {
        require(strength.isFinite() && strength in 0f..1f)
        if (strength <= 0.01f) return sourceBitmap
        checkpoint?.invoke()
        // Native pixels may be premultiplied. Use the straight-color scanline path
        // for alpha-bearing images, not a gain that can violate RGB <= alpha.
        if (!sourceBitmap.hasAlpha() && sourceBitmap.width >= 4 &&
            sourceBitmap.height >= 4 && NcnnNative.isAvailable) {
            val candidate = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("Could not allocate detail enhancement bitmap")
            var delivered = false
            try {
                if (NcnnNative.adaptiveDetailEnhance(candidate, strength)) {
                    checkpoint?.invoke()
                    delivered = true
                    return candidate
                }
            } finally {
                if (!delivered) candidate.recycle()
            }
        }
        return enhanceScanlines(sourceBitmap, strength, checkpoint)
    }

    internal fun enhanceScanlines(source: Bitmap, strength: Float, checkpoint: (() -> Unit)? = null): Bitmap {
        val w = source.width
        val h = source.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = Array(3) { IntArray(w) }
        val lum = Array(3) { FloatArray(w) }
        val blur = Array(3) { FloatArray(w) }
        val tags = IntArray(3) { -1 }
        val out = IntArray(w)
        fun load(y: Int): Int {
            val slot = y % 3
            if (tags[slot] == y) return slot
            source.getPixels(pixels[slot], 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = pixels[slot][x]
                lum[slot][x] = .299f * (p ushr 16 and 255) +
                    .587f * (p ushr 8 and 255) + .114f * (p and 255)
            }
            for (x in 0 until w) {
                blur[slot][x] = .25f * lum[slot][maxOf(0, x - 1)] +
                    .5f * lum[slot][x] + .25f * lum[slot][minOf(w - 1, x + 1)]
            }
            tags[slot] = y
            return slot
        }
        try {
            result.setHasAlpha(source.hasAlpha())
            for (y in 0 until h) {
                checkpoint?.invoke()
                val prev = load(maxOf(0, y - 1))
                val curr = load(y)
                val next = load(minOf(h - 1, y + 1))
                for (x in 0 until w) {
                    val p = pixels[curr][x]
                    val alpha = p ushr 24
                    if (alpha == 0) { out[x] = 0; continue }
                    val left = maxOf(0, x - 1)
                    val right = minOf(w - 1, x + 1)
                    val value = lum[curr][x]
                    val smooth = .25f * blur[prev][x] + .5f * blur[curr][x] + .25f * blur[next][x]
                    val gx = lum[prev][right] + 2f * lum[curr][right] + lum[next][right] -
                        (lum[prev][left] + 2f * lum[curr][left] + lum[next][left])
                    val gy = lum[next][left] + 2f * lum[next][x] + lum[next][right] -
                        (lum[prev][left] + 2f * lum[prev][x] + lum[prev][right])
                    val weight = ((abs(gx) + abs(gy)) / 128f).coerceIn(0f, 1f)
                    val enhanced = (value + strength * weight * (value - smooth)).coerceIn(0f, 255f)
                    val gain = (enhanced + .001f) / (value + .001f)
                    fun channel(c: Int) = (c * gain + .5f).toInt().coerceIn(0, 255)
                    out[x] = (alpha shl 24) or (channel(p ushr 16 and 255) shl 16) or
                        (channel(p ushr 8 and 255) shl 8) or channel(p and 255)
                }
                result.setPixels(out, 0, w, 0, y, w, 1)
            }
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }
}
