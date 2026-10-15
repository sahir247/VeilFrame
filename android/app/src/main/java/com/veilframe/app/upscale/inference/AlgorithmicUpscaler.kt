package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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

    fun scaleBicubic(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        // High quality hardware-filtered bilinear/bicubic sampling via Canvas Paint
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = true
        }
        val srcRect = android.graphics.Rect(0, 0, source.width, source.height)
        val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        return result
    }

    fun scaleLanczos3(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        checkpoint: (() -> Unit)? = null,
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val a = 3 // 3 lobes

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

        // Audit fix (perf): tap weights are precomputed once per destination
        // row/column. The old loop re-evaluated two sin() calls per tap per
        // output pixel (~billions of sin() calls at 4x of a 12 MP image —
        // minutes of pure kernel math on the degrade/refinement paths).
        // Weights use the identical formula, tap order and accumulation order,
        // so output stays bit-identical — just hoisted.
        val colTapIdx: Array<IntArray>
        val colTapW: Array<FloatArray>
        run {
            val ratio = srcW.toFloat() / targetWidth.toFloat()
            colTapIdx = Array(targetWidth) { IntArray(0) }
            colTapW = Array(targetWidth) { FloatArray(0) }
            for (x in 0 until targetWidth) {
                val center = (x + 0.5f) * ratio - 0.5f
                val minS = (center - a).toInt().coerceAtLeast(0)
                val maxS = (center + a).toInt().coerceAtMost(srcW - 1)
                val idx = ArrayList<Int>(2 * a + 1)
                val ws = ArrayList<Float>(2 * a + 1)
                for (s in minS..maxS) {
                    val w = lanczosKernel(center - s.toFloat())
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
            val ratio = srcH.toFloat() / targetHeight.toFloat()
            rowTapIdx = Array(targetHeight) { IntArray(0) }
            rowTapW = Array(targetHeight) { FloatArray(0) }
            for (y in 0 until targetHeight) {
                val center = (y + 0.5f) * ratio - 0.5f
                val minS = (center - a).toInt().coerceAtLeast(0)
                val maxS = (center + a).toInt().coerceAtMost(srcH - 1)
                val idx = ArrayList<Int>(2 * a + 1)
                val ws = ArrayList<Float>(2 * a + 1)
                for (s in minS..maxS) {
                    val w = lanczosKernel(center - s.toFloat())
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
                    alpha += ((c ushr 24) and 0xff).toFloat() * w
                    r += ((c ushr 16) and 0xff).toFloat() * w
                    g += ((c shr 8) and 0xff).toFloat() * w
                    b += (c and 0xff).toFloat() * w
                    sumWeight += w
                }

                val outBase = baseOffset + x * 4
                if (sumWeight > 0.0f) {
                    val inv = 1.0f / sumWeight
                    horizCache[outBase] = (alpha * inv).coerceIn(0.0f, 255.0f)
                    horizCache[outBase + 1] = (r * inv).coerceIn(0.0f, 255.0f)
                    horizCache[outBase + 2] = (g * inv).coerceIn(0.0f, 255.0f)
                    horizCache[outBase + 3] = (b * inv).coerceIn(0.0f, 255.0f)
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
                    val finalA = (alpha * inv).toInt().coerceIn(0, 255)
                    val finalR = (r * inv).toInt().coerceIn(0, 255)
                    val finalG = (g * inv).toInt().coerceIn(0, 255)
                    val finalB = (b * inv).toInt().coerceIn(0, 255)
                    dstRowBuf[x] = (finalA shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                } else {
                    dstRowBuf[x] = 0
                }
            }
            result.setPixels(dstRowBuf, 0, targetWidth, 0, y, targetWidth, 1)
            // Yield briefly every 32 scanlines so UI thread and GC stay responsive
            if ((y and 31) == 31) Thread.yield()
        }

        return result
    }
}
