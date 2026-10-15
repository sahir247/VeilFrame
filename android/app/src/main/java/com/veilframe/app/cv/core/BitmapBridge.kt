package com.veilframe.app.cv.core

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * Bitmap ⇄ Mat bridge.
 *
 * Isolated from the rest of the core so that pure policy classes stay free of
 * android.graphics types. All conversions produce 8-bit Mats:
 *  - ARGB_8888  → CV_8UC4 (BGRA)
 *  - RGB_565    → CV_8UC3 (BGR)
 *  - ALPHA_8    → CV_8UC1 (GRAY)
 */
object BitmapBridge {

    /** Allocates a Mat matching [bitmap]'s geometry. Does not copy pixels. */
    fun createMatFor(bitmap: Bitmap): Mat {
        val channels = when (bitmap.config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565 -> 3
            Bitmap.Config.ALPHA_8 -> 1
            else -> 4 // Fallback: convert via ARGB_8888 path in toMat().
        }
        return Mat(bitmap.height, bitmap.width, CvType.CV_8UC(channels))
    }

    /** Copies bitmap pixels into a new Mat. */
    fun toMat(bitmap: Bitmap): Mat {
        require(bitmap.config != Bitmap.Config.HARDWARE) {
            "HARDWARE bitmaps have no CPU-readable pixels; re-decode with a software config " +
                "(or Bitmap.copy(ARGB_8888)) before converting to Mat"
        }
        if (bitmap.config == Bitmap.Config.ALPHA_8) {
            val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC1)
            try {
                // ALPHA_8 is a single byte per pixel; copy raw storage instead
                // of asking Utils.bitmapToMat to interpret an unsupported format.
                val storage = ByteArray(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(ByteBuffer.wrap(storage))
                val row = ByteArray(bitmap.width)
                val rowStride = bitmap.rowBytes
                require(rowStride >= bitmap.width) { "Invalid ALPHA_8 bitmap row stride" }
                for (y in 0 until bitmap.height) {
                    System.arraycopy(storage, y * rowStride, row, 0, bitmap.width)
                    mat.put(y, 0, row)
                }
                return mat
            } catch (t: Throwable) {
                mat.release()
                throw t
            }
        }

        val needsCopy = bitmap.config != Bitmap.Config.ARGB_8888 &&
            bitmap.config != Bitmap.Config.RGB_565 &&
            bitmap.config != Bitmap.Config.ALPHA_8
        val source = if (needsCopy) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        try {
            val mat = createMatFor(source)
            Utils.bitmapToMat(source, mat)
            // Android Bitmap storage is RGBA; VeilFrame's CV contract is BGRA/BGR.
            when (mat.channels()) {
                4 -> Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGRA)
                3 -> Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2BGR)
            }
            return mat
        } finally {
            // CV-9: the intermediate copy used to leak (never recycled).
            if (needsCopy && source !== bitmap) source.recycle()
        }
    }

    /**
     * Copies Mat pixels into a new Bitmap.
     * CV_8UC4 → ARGB_8888, CV_8UC3 → ARGB_8888 (alpha forced opaque),
     * CV_8UC1 → ARGB_8888 (gray replicated).
     */
    fun toBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        when (mat.channels()) {
            4 -> {
                val rgba = Mat()
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGRA2RGBA)
                try {
                    Utils.matToBitmap(rgba, bitmap)
                } finally {
                    rgba.release()
                }
            }
            3 -> {
                val rgba = Mat()
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
                try {
                    Utils.matToBitmap(rgba, bitmap)
                } finally {
                    rgba.release()
                }
            }
            1 -> {
                val rgba = Mat()
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
                try {
                    Utils.matToBitmap(rgba, bitmap)
                } finally {
                    rgba.release()
                }
            }
            else -> throw IllegalArgumentException("unsupported channel count ${mat.channels()}")
        }
        return bitmap
    }
}
