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

        fun sinc(x: Double): Double {
            if (abs(x) < 1e-6) return 1.0
            val piX = PI * x
            return sin(piX) / piX
        }

        fun lanczosKernel(x: Double): Double {
            val absX = abs(x)
            if (absX >= a) return 0.0
            return sinc(absX) * sinc(absX / a)
        }

        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        // Pass 1: Horizontal resampling to intermediate buffer
        val interPixels = Array(targetWidth * srcH) { DoubleArray(4) }
        val xRatio = srcW.toDouble() / targetWidth.toDouble()

        for (y in 0 until srcH) {
            val srcRowOffset = y * srcW
            val dstRowOffset = y * targetWidth
            for (x in 0 until targetWidth) {
                val center = (x + 0.5) * xRatio - 0.5
                val minX = (center - a).toInt().coerceAtLeast(0)
                val maxX = (center + a).toInt().coerceAtMost(srcW - 1)

                var sumWeight = 0.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var alpha = 0.0

                for (sx in minX..maxX) {
                    val w = lanczosKernel(center - sx)
                    if (w != 0.0) {
                        val c = srcPixels[srcRowOffset + sx]
                        val pxA = Color.alpha(c).toDouble()
                        val pxR = Color.red(c).toDouble()
                        val pxG = Color.green(c).toDouble()
                        val pxB = Color.blue(c).toDouble()

                        alpha += pxA * w
                        r += pxR * w
                        g += pxG * w
                        b += pxB * w
                        sumWeight += w
                    }
                }

                if (sumWeight > 0.0) {
                    interPixels[dstRowOffset + x][0] = (alpha / sumWeight).coerceIn(0.0, 255.0)
                    interPixels[dstRowOffset + x][1] = (r / sumWeight).coerceIn(0.0, 255.0)
                    interPixels[dstRowOffset + x][2] = (g / sumWeight).coerceIn(0.0, 255.0)
                    interPixels[dstRowOffset + x][3] = (b / sumWeight).coerceIn(0.0, 255.0)
                }
            }
        }

        // Pass 2: Vertical resampling to destination pixels
        val dstPixels = IntArray(targetWidth * targetHeight)
        val yRatio = srcH.toDouble() / targetHeight.toDouble()

        for (x in 0 until targetWidth) {
            for (y in 0 until targetHeight) {
                val center = (y + 0.5) * yRatio - 0.5
                val minY = (center - a).toInt().coerceAtLeast(0)
                val maxY = (center + a).toInt().coerceAtMost(srcH - 1)

                var sumWeight = 0.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var alpha = 0.0

                for (sy in minY..maxY) {
                    val w = lanczosKernel(center - sy)
                    if (w != 0.0) {
                        val srcIdx = sy * targetWidth + x
                        val pxA = interPixels[srcIdx][0]
                        val pxR = interPixels[srcIdx][1]
                        val pxG = interPixels[srcIdx][2]
                        val pxB = interPixels[srcIdx][3]

                        alpha += pxA * w
                        r += pxR * w
                        g += pxG * w
                        b += pxB * w
                        sumWeight += w
                    }
                }

                if (sumWeight > 0.0) {
                    val finalA = (alpha / sumWeight).toInt().coerceIn(0, 255)
                    val finalR = (r / sumWeight).toInt().coerceIn(0, 255)
                    val finalG = (g / sumWeight).toInt().coerceIn(0, 255)
                    val finalB = (b / sumWeight).toInt().coerceIn(0, 255)
                    dstPixels[y * targetWidth + x] = Color.argb(finalA, finalR, finalG, finalB)
                }
            }
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(dstPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return result
    }
}
