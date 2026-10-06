package com.veilframe.app.cv.core

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

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
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888 ||
            bitmap.config == Bitmap.Config.RGB_565 ||
            bitmap.config == Bitmap.Config.ALPHA_8
        ) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        }
        val mat = createMatFor(source)
        Utils.bitmapToMat(source, mat)
        return mat
    }

    /**
     * Copies Mat pixels into a new Bitmap.
     * CV_8UC4 → ARGB_8888, CV_8UC3 → ARGB_8888 (alpha forced opaque),
     * CV_8UC1 → ARGB_8888 (gray replicated).
     */
    fun toBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        when (mat.channels()) {
            4 -> Utils.matToBitmap(mat, bitmap)
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
