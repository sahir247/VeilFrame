package com.veilframe.app.cv.edges

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * EdgeDetector — internal CV primitives (Canny / Sobel / Scharr / Laplacian).
 *
 * These are infrastructure, not user-facing features. They feed document
 * detection, QR preprocessing, quality analysis, segmentation refinement,
 * motion boundaries and smart sharpening. All functions allocate their own
 * output Mat — inside engine jobs prefer MatPool leases and copy into them.
 */
object EdgeDetector {

    /** Canny edge map (8-bit, 0/255). */
    fun canny(
        source: Mat,
        threshold1: Double = 50.0,
        threshold2: Double = 150.0,
        apertureSize: Int = 3,
        l2gradient: Boolean = true,
    ): Mat {
        val gray = asGray(source)
        val edges = Mat()
        try {
            Imgproc.Canny(gray, edges, threshold1, threshold2, apertureSize, l2gradient)
            return edges
        } finally {
            if (gray !== source) gray.release()
        }
    }

    /** Sobel gradient magnitude (CV_32F). */
    fun sobelMagnitude(source: Mat, ksize: Int = 3): Mat {
        val gray = asGray(source)
        val gx = Mat()
        val gy = Mat()
        val magnitude = Mat()
        try {
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, ksize, 1.0, 0.0, Core.BORDER_REPLICATE)
            Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, ksize, 1.0, 0.0, Core.BORDER_REPLICATE)
            Core.magnitude(gx, gy, magnitude)
            return magnitude
        } finally {
            if (gray !== source) gray.release()
            gx.release()
            gy.release()
        }
    }

    /** Scharr gradient magnitude (CV_32F) — more rotation-accurate than Sobel. */
    fun scharrMagnitude(source: Mat): Mat {
        val gray = asGray(source)
        val gx = Mat()
        val gy = Mat()
        val magnitude = Mat()
        try {
            Imgproc.Scharr(gray, gx, CvType.CV_32F, 1, 0, 1.0, 0.0, Core.BORDER_REPLICATE)
            Imgproc.Scharr(gray, gy, CvType.CV_32F, 0, 1, 1.0, 0.0, Core.BORDER_REPLICATE)
            Core.magnitude(gx, gy, magnitude)
            return magnitude
        } finally {
            if (gray !== source) gray.release()
            gx.release()
            gy.release()
        }
    }

    /** Laplacian response (CV_32F). */
    fun laplacian(source: Mat, ksize: Int = 3): Mat {
        val gray = asGray(source)
        val out = Mat()
        try {
            Imgproc.Laplacian(gray, out, CvType.CV_32F, ksize, 1.0, 0.0, Core.BORDER_REPLICATE)
            return out
        } finally {
            if (gray !== source) gray.release()
        }
    }

    /** Horizontal/vertical gradient pair for directional blur diagnostics. */
    fun directionalGradientEnergy(source: Mat): Pair<Double, Double> {
        val gray = asGray(source)
        val gx = Mat()
        val gy = Mat()
        try {
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
            Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
            val absX = absOf(gx)
            val absY = absOf(gy)
            val energyX = Core.mean(absX).`val`[0]
            val energyY = Core.mean(absY).`val`[0]
            absX.release()
            absY.release()
            return energyX to energyY
        } finally {
            if (gray !== source) gray.release()
            gx.release()
            gy.release()
        }
    }

    private fun absOf(src: Mat): Mat {
        val out = Mat()
        Core.absdiff(src, Scalar(0.0, 0.0, 0.0, 0.0), out)
        return out
    }

    internal fun asGray(source: Mat): Mat = when (source.channels()) {
        1 -> source
        else -> {
            val gray = Mat()
            val code = if (source.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY
            Imgproc.cvtColor(source, gray, code)
            gray
        }
    }
}
