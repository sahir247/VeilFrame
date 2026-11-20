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
    const val MAX_MATTING_DIMENSION = 4096

    fun decodeOrientedBitmap(bytes: ByteArray, maxDimension: Int? = null, inMutable: Boolean = false): Bitmap {
        require(maxDimension == null || maxDimension > 0) { "maxDimension must be positive" }
        // EXIF parsing traverses metadata: read it once for bounds and orientation.
        val orientation = readOrientation(bytes)
        val options = BitmapFactory.Options().apply {
            this.inMutable = inMutable
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        if (maxDimension != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val orientedWidth = if (requiresSwap(orientation)) bounds.outHeight else bounds.outWidth
            val orientedHeight = if (requiresSwap(orientation)) bounds.outWidth else bounds.outHeight
            require(orientedWidth > 0 && orientedHeight > 0) { "Could not read image dimensions" }
            options.inSampleSize = sampleSizeForMax(orientedWidth, orientedHeight, maxDimension)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Could not decode image")
        val oriented = try {
            applyOrientation(orientation, bitmap)
        } catch (failure: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw failure
        }
        if (maxDimension == null || (oriented.width <= maxDimension && oriented.height <= maxDimension)) {
            return oriented
        }
        return try {
            val scaled = scaleToFit(oriented, maxDimension, maxDimension)
            if (scaled !== oriented) oriented.recycle()
            scaled
        } catch (failure: Throwable) {
            if (!oriented.isRecycled) oriented.recycle()
            throw failure
        }
    }

    // Reserve memory for EXIF rotation, original RGB, composited output and edge passes.
    // The cap is deliberately conservative; a Bitmap-returning API cannot promise arbitrary sizes.
    internal fun canCompositeOriginal(width: Int, height: Int, availableBytes: Long): Boolean {
        if (width <= 0 || height <= 0 || max(width, height) > 16384) return false
        val pixels = width.toLong() * height
        return pixels <= 24_000_000L && pixels * 16L <= (availableBytes - 24L * 1024 * 1024).coerceAtLeast(0)
    }

    internal fun decodeCompositeSource(
        bytes: ByteArray,
        analysis: Bitmap,
        info: RembgSourceInfo,
        warnings: MutableList<String>
    ): Bitmap {
        if (analysis.width == info.width && analysis.height == info.height) return analysis
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (canCompositeOriginal(info.width, info.height, available)) {
            val original = decodeOrientedBitmap(bytes)
            if (original.width == info.width && original.height == info.height) return original
            original.recycle()
        }
        warnings.add(0, "Memory-limited output before trim: ${analysis.width}×${analysis.height}; original: ${info.width}×${info.height}.")
        return analysis
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

    private fun applyOrientation(orientation: Int, bitmap: Bitmap): Bitmap {
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

    internal fun sampleSizeForMax(width: Int, height: Int, maxDimension: Int): Int {
        require(width > 0 && height > 0 && maxDimension > 0)
        var sample = 1L
        val largest = max(width, height).toLong()
        // Bound the actual decoder intermediate, not only the final scaled bitmap.
        while ((largest + sample - 1) / sample > maxDimension) sample *= 2
        return sample.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
