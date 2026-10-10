package com.veilframe.app.cv.segmentation.rembg

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 1:1 Port of Cel-Android MaskPostprocessor:
 * - Parses raw ONNX tensor float buffers
 * - Applies sigmoid activation when required (e.g., BiRefNet Lite)
 * - Computes normalized alpha mask and merges into full-resolution source with striped memory budgeting
 * - Applies edge tightening (1px alpha erosion to pull cutout inward and strip background bleed/fringe)
 * - Applies edge feathering (1px alpha box blur to soften cutouts)
 * - Trims transparent borders (bounding box crop around subject)
 */
object MaskPostprocessor {

    private const val STRIPE_ROWS = 256

    fun parseMaskFromValue(value: OnnxValue): Triple<FloatArray, Int, Int> {
        val tensor = value as OnnxTensor
        val shape = tensor.info.shape
        val height = shape[shape.size - 2].toInt()
        val width = shape[shape.size - 1].toInt()
        val count = width * height
        val flat = FloatArray(count)
        val buffer = tensor.floatBuffer
        buffer.rewind()
        buffer.get(flat, 0, min(count, buffer.remaining()))
        return Triple(flat, width, height)
    }

    fun applyMask(
        source: Bitmap,
        rawMask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        applySigmoid: Boolean = false,
    ): Bitmap {
        if (applySigmoid) {
            for (i in rawMask.indices) {
                rawMask[i] = 1f / (1f + exp(-rawMask[i]))
            }
        }
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        for (value in rawMask) {
            minVal = min(minVal, value)
            maxVal = max(maxVal, value)
        }
        val range = max(maxVal - minVal, 1e-6f)

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val stripe = IntArray(source.width * STRIPE_ROWS)

        var y = 0
        while (y < source.height) {
            val rows = min(STRIPE_ROWS, source.height - y)
            source.getPixels(stripe, 0, source.width, 0, y, source.width, rows)

            for (row in 0 until rows) {
                val imageY = y + row
                val srcY = imageY * maskHeight / source.height
                for (x in 0 until source.width) {
                    val srcX = x * maskWidth / source.width
                    val maskIdx = srcY * maskWidth + srcX
                    val normalized = (rawMask[maskIdx] - minVal) / range
                    val alpha = (normalized * 255f).toInt().coerceIn(0, 255)
                    val i = row * source.width + x
                    stripe[i] = (stripe[i] and 0x00FFFFFF) or (alpha shl 24)
                }
            }
            output.setPixels(stripe, 0, source.width, 0, y, source.width, rows)
            y += rows
        }
        return output
    }

    /**
     * Crops empty space around the cutout to the non-zero alpha bounding box.
     * Uses striped memory buffering to avoid huge contiguous heap allocations on large images.
     */
    fun trimTransparent(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val stripe = IntArray(width * STRIPE_ROWS)

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        var y = 0
        while (y < height) {
            val rows = min(STRIPE_ROWS, height - y)
            bitmap.getPixels(stripe, 0, width, 0, y, width, rows)

            for (r in 0 until rows) {
                val py = y + r
                val rowOffset = r * width
                for (px in 0 until width) {
                    val alpha = stripe[rowOffset + px] ushr 24
                    if (alpha > 0) {
                        minX = min(minX, px)
                        minY = min(minY, py)
                        maxX = max(maxX, px)
                        maxY = max(maxY, py)
                    }
                }
            }
            y += rows
        }

        // Entirely transparent image or no non-zero alpha pixels found
        if (maxX < minX || maxY < minY) return bitmap

        // If the bounding box covers the entire original image, avoid redundant allocation
        if (minX == 0 && minY == 0 && maxX == width - 1 && maxY == height - 1) return bitmap

        val rect = Rect(minX, minY, maxX + 1, maxY + 1)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    /**
     * Shrinks the cutout inward with a 1px-radius erosion on the alpha channel.
     * Effectively strips color fringes/bleeding from background around edges/hair.
     */
    fun tightenEdges(bitmap: Bitmap, radius: Int = 1): Bitmap {
        if (radius <= 0) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val stripeOut = IntArray(w * STRIPE_ROWS)

        var y0 = 0
        while (y0 < h) {
            val rows = min(STRIPE_ROWS, h - y0)
            val readTop = max(0, y0 - radius)
            val readBottom = min(h, y0 + rows + radius)
            val readRows = readBottom - readTop
            val readPixels = IntArray(w * readRows)
            bitmap.getPixels(readPixels, 0, w, 0, readTop, w, readRows)

            for (row in 0 until rows) {
                val srcY = y0 + row
                for (x in 0 until w) {
                    var minAlpha = 255
                    for (dy in -radius..radius) {
                        val ny = (srcY + dy).coerceIn(0, h - 1)
                        val bufferRow = ny - readTop
                        for (dx in -radius..radius) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            minAlpha = min(minAlpha, readPixels[bufferRow * w + nx] ushr 24)
                        }
                    }
                    val origPixel = readPixels[(srcY - readTop) * w + x]
                    stripeOut[row * w + x] = (origPixel and 0x00FFFFFF) or (minAlpha shl 24)
                }
            }
            output.setPixels(stripeOut, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return output
    }

    /**
     * Softens cutout edges with a 1px-radius box blur on the alpha channel.
     */
    fun featherEdges(bitmap: Bitmap, radius: Int = 1): Bitmap {
        if (radius <= 0) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val stripeOut = IntArray(w * STRIPE_ROWS)

        var y0 = 0
        while (y0 < h) {
            val rows = min(STRIPE_ROWS, h - y0)
            val readTop = max(0, y0 - radius)
            val readBottom = min(h, y0 + rows + radius)
            val readRows = readBottom - readTop
            val readPixels = IntArray(w * readRows)
            bitmap.getPixels(readPixels, 0, w, 0, readTop, w, readRows)

            for (row in 0 until rows) {
                val srcY = y0 + row
                for (x in 0 until w) {
                    var sum = 0
                    var count = 0
                    for (dy in -radius..radius) {
                        val ny = (srcY + dy).coerceIn(0, h - 1)
                        val bufferRow = ny - readTop
                        for (dx in -radius..radius) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            sum += readPixels[bufferRow * w + nx] ushr 24
                            count++
                        }
                    }
                    val origPixel = readPixels[(srcY - readTop) * w + x]
                    stripeOut[row * w + x] = (origPixel and 0x00FFFFFF) or ((sum / count) shl 24)
                }
            }
            output.setPixels(stripeOut, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return output
    }
}
