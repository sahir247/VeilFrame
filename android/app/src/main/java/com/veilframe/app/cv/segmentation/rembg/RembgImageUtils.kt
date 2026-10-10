package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Image decoding, EXIF orientation correction, and aspect scaling utilities
 * ported 1:1 from Cel-Android.
 */
object RembgImageUtils {

    const val MAX_DISPLAY_PX = 2048

    fun decodeOrientedBitmap(bytes: ByteArray, maxDimension: Int? = null): Bitmap {
        val options = BitmapFactory.Options()
        if (maxDimension != null) {
            val (orientW, orientH) = orientedDimensions(bytes)
            options.inSampleSize = sampleSizeForMax(orientW, orientH, maxDimension)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Could not decode image")
        val oriented = applyOrientation(bytes, bitmap)
        if (maxDimension == null || (oriented.width <= maxDimension && oriented.height <= maxDimension)) {
            return oriented
        }
        val scaled = scaleToFit(oriented, maxDimension, maxDimension)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    fun orientedDimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        var width = options.outWidth
        var height = options.outHeight
        if (requiresSwap(readOrientation(bytes))) {
            width = options.outHeight
            height = options.outWidth
        }
        return width to height
    }

    fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxWidth && h <= maxHeight) return bitmap
        val scale = min(maxWidth.toFloat() / w, maxHeight.toFloat() / h)
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private fun applyOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = readOrientation(bytes)
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }
        val matrix = orientationMatrix(orientation)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun sampleSizeForMax(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension * 2 || height / sample > maxDimension * 2) {
            sample *= 2
        }
        return max(1, sample)
    }

    private fun readOrientation(bytes: ByteArray): Int =
        runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun requiresSwap(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

    private fun orientationMatrix(orientation: Int): Matrix {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(-90f)
        }
        return matrix
    }
}
