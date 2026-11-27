package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap

/** Immutable uncomposited neural alpha, independent of the original image's alpha. */
class RembgAlphaMatte private constructor(val width: Int, val height: Int, private val alpha: ByteArray) {
    companion object {
        internal fun fromBitmap(bitmap: Bitmap, checkpoint: () -> Unit = {}): RembgAlphaMatte {
            require(bitmap.width in 1..4096 && bitmap.height in 1..4096)
            val values = ByteArray(bitmap.width * bitmap.height)
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                checkpoint()
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) values[y * bitmap.width + x] = (row[x] ushr 24).toByte()
            }
            return RembgAlphaMatte(bitmap.width, bitmap.height, values)
        }

        internal fun fromAlpha(width: Int, height: Int, alpha: ByteArray): RembgAlphaMatte {
            require(width in 1..4096 && height in 1..4096 && alpha.size == width * height)
            return RembgAlphaMatte(width, height, alpha.copyOf())
        }
    }

    internal fun sampler(targetWidth: Int, targetHeight: Int): Sampler {
        require(targetWidth > 0 && targetHeight > 0)
        return Sampler(targetWidth, targetHeight)
    }

    internal inner class Sampler(private val targetWidth: Int, private val targetHeight: Int) {
        private val x0 = IntArray(targetWidth)
        private val x1 = IntArray(targetWidth)
        private val fx = FloatArray(targetWidth)
        init {
            for (x in x0.indices) {
                val sx = ((x + 0.5) * width / targetWidth - 0.5).coerceIn(0.0, (width - 1).toDouble())
                x0[x] = sx.toInt()
                x1[x] = minOf(x0[x] + 1, width - 1)
                fx[x] = (sx - x0[x]).toFloat()
            }
        }
        fun apply(row: IntArray, y: Int) = sampleRow(row, y, false)

        internal fun fillMaskRow(row: IntArray, y: Int) = sampleRow(row, y, true)

        private fun sampleRow(row: IntArray, y: Int, maskOnly: Boolean) {
            require(row.size == targetWidth && y in 0 until targetHeight)
            val sy = ((y + 0.5) * height / targetHeight - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sy.toInt()
            val y1 = minOf(y0 + 1, height - 1)
            val fy = (sy - y0).toFloat()
            val upper = y0 * width
            val lower = y1 * width
            for (x in row.indices) {
                val left = x0[x]; val right = x1[x]; val weight = fx[x]
                val a = (alpha[upper + left].toInt() and 255) * (1f - weight) +
                    (alpha[upper + right].toInt() and 255) * weight
                val b = (alpha[lower + left].toInt() and 255) * (1f - weight) +
                    (alpha[lower + right].toInt() and 255) * weight
                val mask = (a * (1f - fy) + b * fy + 0.5f).toInt().coerceIn(0, 255)
                if (maskOnly) {
                    row[x] = if (mask == 0) 0 else (mask shl 24) or 0x00FFFFFF
                } else {
                    val pixel = row[x]
                    val outAlpha = ((pixel ushr 24) * mask + 127) / 255
                    row[x] = if (outAlpha == 0) 0 else (pixel and 0x00FFFFFF) or (outAlpha shl 24)
                }
            }
        }
    }
}
