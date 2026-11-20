package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Built-in mathematical image scaling filters (Lanczos-3, Bicubic, Nearest).
 * Offline, zero-download, memory-efficient implementation.
 */
object AlgorithmicUpscaler {

    fun scaleNearest(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        checkpoint: (() -> Unit)? = null,
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(targetWidth * targetHeight)
        val xRatio = srcW.toFloat() / targetWidth.toFloat()
        val yRatio = srcH.toFloat() / targetHeight.toFloat()

        for (y in 0 until targetHeight) {
            checkpoint?.invoke() // B6: cancellable per output row
            val srcY = (y * yRatio).toInt().coerceIn(0, srcH - 1)
            val srcRowOffset = srcY * srcW
            val dstRowOffset = y * targetWidth
            for (x in 0 until targetWidth) {
                val srcX = (x * xRatio).toInt().coerceIn(0, srcW - 1)
                dstPixels[dstRowOffset + x] = srcPixels[srcRowOffset + srcX]
            }
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(dstPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return result
    }

    /** Keys cubic interpolation (a = -0.5), not Canvas bilinear filtering. */
    fun scaleBicubic(source: Bitmap, targetWidth: Int, targetHeight: Int,
                     checkpoint: (() -> Unit)? = null): Bitmap =
        scaleSeparable(source, targetWidth, targetHeight, 2, checkpoint) { distance ->
            val x = abs(distance)
            when {
                x < 1f -> ((1.5f * x - 2.5f) * x) * x + 1f
                x < 2f -> ((-.5f * x + 2.5f) * x - 4f) * x + 2f
                else -> 0f
            }
        }

    /**
     * Select a single deterministic Lanczos-4 implementation for a banded job.
     * A native error must not silently change the kernel at the next band.
     * Alpha-bearing images use premultiplied channels in the portable path.
     */
    fun scaleLanczos(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        checkpoint: (() -> Unit)? = null,
    ): Bitmap {
        checkpoint?.invoke()
        if (!source.hasAlpha() && NcnnNative.isAvailable) {
            val bitmap = NcnnNative.scaleLanczos4(source, targetWidth, targetHeight)
            if (bitmap != null) {
                try { checkpoint?.invoke(); return bitmap }
                catch (failure: Throwable) { bitmap.recycle(); throw failure }
            }
        }
        return scaleLanczos4Portable(source, targetWidth, targetHeight, checkpoint)
    }

    internal fun scaleLanczos4Portable(source: Bitmap, targetWidth: Int, targetHeight: Int,
                                       checkpoint: (() -> Unit)? = null): Bitmap =
        scaleWindowedSinc(source, targetWidth, targetHeight, 4, checkpoint)

    fun scaleLanczos3(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        checkpoint: (() -> Unit)? = null,
    ): Bitmap {
        return scaleWindowedSinc(source, targetWidth, targetHeight, 3, checkpoint)
    }

    private fun scaleWindowedSinc(source: Bitmap, targetWidth: Int, targetHeight: Int,
                                  a: Int, checkpoint: (() -> Unit)?): Bitmap {
        fun sinc(x: Float): Float {
            if (abs(x) < 1e-6f) return 1.0f
            val piX = (PI.toFloat() * x)
            return sin(piX) / piX
        }

        fun lanczosKernel(x: Float): Float {
            val absX = abs(x)
            if (absX >= a) return 0.0f
            return sinc(absX) * sinc(absX / a)
        }

        return scaleSeparable(source, targetWidth, targetHeight, a, checkpoint, ::lanczosKernel)
    }

    private fun scaleSeparable(source: Bitmap, targetWidth: Int, targetHeight: Int,
                               a: Int, checkpoint: (() -> Unit)?, kernel: (Float) -> Float): Bitmap {
        require(targetWidth > 0 && targetHeight > 0)
        val srcW = source.width
        val srcH = source.height
        // Compute phase from integer quotient/remainder for exact integer-scale
        // bands; avoid losing fractional phase in large source coordinates.
        fun center(index: Int, src: Int, dst: Int): Double {
            val numerator = (2L * index + 1) * src - dst
            val denominator = 2L * dst
            return Math.floorDiv(numerator, denominator).toDouble() +
                Math.floorMod(numerator, denominator).toDouble() / denominator
        }

        // Audit fix (perf): tap weights are precomputed once per destination
        // row/column. The old loop re-evaluated two sin() calls per tap per
        // output pixel (~billions of sin() calls at 4x of a 12 MP image —
        // minutes of pure kernel math on the degrade/refinement paths).
        // Weights use the identical formula, tap order and accumulation order,
        // so output stays bit-identical — just hoisted.
        val colTapIdx: Array<IntArray>
        val colTapW: Array<FloatArray>
        run {
            colTapIdx = Array(targetWidth) { IntArray(0) }
            colTapW = Array(targetWidth) { FloatArray(0) }
            for (x in 0 until targetWidth) {
                val center = center(x, srcW, targetWidth)
                val minS = (center - a).toInt().coerceAtLeast(0)
                val maxS = (center + a).toInt().coerceAtMost(srcW - 1)
                val idx = ArrayList<Int>(2 * a + 1)
                val ws = ArrayList<Float>(2 * a + 1)
                for (s in minS..maxS) {
                    val w = kernel((center - s).toFloat())
                    if (w != 0.0f) {
                        idx.add(s)
                        ws.add(w)
                    }
                }
                colTapIdx[x] = idx.toIntArray()
                colTapW[x] = ws.toFloatArray()
            }
        }
        val rowTapIdx: Array<IntArray>
        val rowTapW: Array<FloatArray>
        run {
            rowTapIdx = Array(targetHeight) { IntArray(0) }
            rowTapW = Array(targetHeight) { FloatArray(0) }
            for (y in 0 until targetHeight) {
                val center = center(y, srcH, targetHeight)
                val minS = (center - a).toInt().coerceAtLeast(0)
                val maxS = (center + a).toInt().coerceAtMost(srcH - 1)
                val idx = ArrayList<Int>(2 * a + 1)
                val ws = ArrayList<Float>(2 * a + 1)
                for (s in minS..maxS) {
                    val w = kernel((center - s).toFloat())
                    if (w != 0.0f) {
                        idx.add(s)
                        ws.add(w)
                    }
                }
                rowTapIdx[y] = idx.toIntArray()
                rowTapW[y] = ws.toFloatArray()
            }
        }

        // Sliding 16-slot ring buffer of horizontally resampled source rows.
        // Peak RAM is 16 * targetWidth * 4 floats (~512 KB for 8K) instead of 384+ MB.
        val cacheSlots = 16
        val rowStride = targetWidth * 4
        val horizCache = FloatArray(cacheSlots * rowStride)
        val cachedRowTag = IntArray(cacheSlots) { -1 }
        val srcRowBuf = IntArray(srcW)
        val dstRowBuf = IntArray(targetWidth)

        fun ensureHorizRow(srcY: Int): Int {
            val slot = srcY and (cacheSlots - 1)
            if (cachedRowTag[slot] == srcY) {
                return slot * rowStride
            }
            checkpoint?.invoke()
            source.getPixels(srcRowBuf, 0, srcW, 0, srcY, srcW, 1)
            val baseOffset = slot * rowStride
            for (x in 0 until targetWidth) {
                val taps = colTapIdx[x]
                val weights = colTapW[x]
                var sumWeight = 0.0f
                var r = 0.0f
                var g = 0.0f
                var b = 0.0f
                var alpha = 0.0f

                for (k in taps.indices) {
                    val w = weights[k]
                    val c = srcRowBuf[taps[k]]
                    val opacity = ((c ushr 24) and 0xff).toFloat()
                    val premul = opacity / 255f
                    alpha += opacity * w
                    r += ((c ushr 16) and 0xff).toFloat() * premul * w
                    g += ((c shr 8) and 0xff).toFloat() * premul * w
                    b += (c and 0xff).toFloat() * premul * w
                    sumWeight += w
                }

                val outBase = baseOffset + x * 4
                if (sumWeight > 0.0f) {
                    val inv = 1.0f / sumWeight
                    horizCache[outBase] = alpha * inv
                    horizCache[outBase + 1] = r * inv
                    horizCache[outBase + 2] = g * inv
                    horizCache[outBase + 3] = b * inv
                } else {
                    horizCache[outBase] = 0f
                    horizCache[outBase + 1] = 0f
                    horizCache[outBase + 2] = 0f
                    horizCache[outBase + 3] = 0f
                }
            }
            cachedRowTag[slot] = srcY
            return baseOffset
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val tapBases = IntArray(2 * a + 1)

        try {
        for (y in 0 until targetHeight) {
            checkpoint?.invoke() // B6: cancellable per output row
            val taps = rowTapIdx[y]
            val weights = rowTapW[y]
            for (k in taps.indices) {
                tapBases[k] = ensureHorizRow(taps[k])
            }

            for (x in 0 until targetWidth) {
                var sumWeight = 0.0f
                var r = 0.0f
                var g = 0.0f
                var b = 0.0f
                var alpha = 0.0f
                val xOffset = x * 4

                for (k in taps.indices) {
                    val w = weights[k]
                    val srcIdx = tapBases[k] + xOffset
                    alpha += horizCache[srcIdx] * w
                    r += horizCache[srcIdx + 1] * w
                    g += horizCache[srcIdx + 2] * w
                    b += horizCache[srcIdx + 3] * w
                    sumWeight += w
                }

                if (sumWeight > 0.0f) {
                    val inv = 1.0f / sumWeight
                    val rawAlpha = alpha * inv
                    val finalA = rawAlpha.roundToInt().coerceIn(0, 255)
                    if (finalA == 0) {
                        dstRowBuf[x] = 0
                    } else {
                        val unpremultiply = inv * 255f / rawAlpha
                        val finalR = (r * unpremultiply).roundToInt().coerceIn(0, 255)
                        val finalG = (g * unpremultiply).roundToInt().coerceIn(0, 255)
                        val finalB = (b * unpremultiply).roundToInt().coerceIn(0, 255)
                        dstRowBuf[x] = (finalA shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                } else {
                    dstRowBuf[x] = 0
                }
            }
            result.setPixels(dstRowBuf, 0, targetWidth, 0, y, targetWidth, 1)
            // Yield briefly every 32 scanlines so UI thread and GC stay responsive
            if ((y and 31) == 31) Thread.yield()
        }

        result.setHasAlpha(source.hasAlpha())
        return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }
}
