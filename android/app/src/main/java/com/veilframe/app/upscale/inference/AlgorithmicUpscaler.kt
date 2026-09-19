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

    fun scaleNearest(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(targetWidth * targetHeight)
        val xRatio = srcW.toFloat() / targetWidth.toFloat()
        val yRatio = srcH.toFloat() / targetHeight.toFloat()

        for (y in 0 until targetHeight) {
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

    fun scaleLanczos3(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
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

        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        // Pass 1: Horizontal resampling to intermediate primitive buffer.
        // Memory-efficient contiguous primitive array (zero per-pixel object allocations).
        val interPixels = FloatArray(targetWidth * srcH * 4)
        val xRatio = srcW.toFloat() / targetWidth.toFloat()

        for (y in 0 until srcH) {
            val srcRowOffset = y * srcW
            val dstRowOffset = y * targetWidth
            for (x in 0 until targetWidth) {
                val center = (x + 0.5f) * xRatio - 0.5f
                val minX = (center - a).toInt().coerceAtLeast(0)
                val maxX = (center + a).toInt().coerceAtMost(srcW - 1)

                var sumWeight = 0.0f
                var r = 0.0f
                var g = 0.0f
                var b = 0.0f
                var alpha = 0.0f

                for (sx in minX..maxX) {
                    val w = lanczosKernel(center - sx.toFloat())
                    if (w != 0.0f) {
                        val c = srcPixels[srcRowOffset + sx]
                        val pxA = ((c ushr 24) and 0xff).toFloat()
                        val pxR = ((c ushr 16) and 0xff).toFloat()
                        val pxG = ((c ushr 8) and 0xff).toFloat()
                        val pxB = (c and 0xff).toFloat()

                        alpha += pxA * w
                        r += pxR * w
                        g += pxG * w
                        b += pxB * w
                        sumWeight += w
                    }
                }

                val outBase = (dstRowOffset + x) * 4
                if (sumWeight > 0.0f) {
                    interPixels[outBase] = (alpha / sumWeight).coerceIn(0.0f, 255.0f)
                    interPixels[outBase + 1] = (r / sumWeight).coerceIn(0.0f, 255.0f)
                    interPixels[outBase + 2] = (g / sumWeight).coerceIn(0.0f, 255.0f)
                    interPixels[outBase + 3] = (b / sumWeight).coerceIn(0.0f, 255.0f)
                }
            }
        }

        // Pass 2: Vertical resampling to destination pixels
        val dstPixels = IntArray(targetWidth * targetHeight)
        val yRatio = srcH.toFloat() / targetHeight.toFloat()

        for (x in 0 until targetWidth) {
            for (y in 0 until targetHeight) {
                val center = (y + 0.5f) * yRatio - 0.5f
                val minY = (center - a).toInt().coerceAtLeast(0)
                val maxY = (center + a).toInt().coerceAtMost(srcH - 1)

                var sumWeight = 0.0f
                var r = 0.0f
                var g = 0.0f
                var b = 0.0f
                var alpha = 0.0f

                for (sy in minY..maxY) {
                    val w = lanczosKernel(center - sy.toFloat())
                    if (w != 0.0f) {
                        val srcIdx = (sy * targetWidth + x) * 4
                        val pxA = interPixels[srcIdx]
                        val pxR = interPixels[srcIdx + 1]
                        val pxG = interPixels[srcIdx + 2]
                        val pxB = interPixels[srcIdx + 3]

                        alpha += pxA * w
                        r += pxR * w
                        g += pxG * w
                        b += pxB * w
                        sumWeight += w
                    }
                }

                if (sumWeight > 0.0f) {
                    val finalA = (alpha / sumWeight).toInt().coerceIn(0, 255)
                    val finalR = (r / sumWeight).toInt().coerceIn(0, 255)
                    val finalG = (g / sumWeight).toInt().coerceIn(0, 255)
                    val finalB = (b / sumWeight).toInt().coerceIn(0, 255)
                    dstPixels[y * targetWidth + x] = (finalA shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(dstPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return result
    }
}
