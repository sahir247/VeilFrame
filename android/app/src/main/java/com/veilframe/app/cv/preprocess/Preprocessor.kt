package com.veilframe.app.cv.preprocess

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Preprocessor — shared classical-CV primitives.
 *
 * QR, document, quality-analysis and background-removal code paths must all go
 * through here instead of duplicating cvt/threshold/morphology sequences.
 *
 * Functions return NEW Mats (caller releases). Inside engine jobs, prefer
 * acquiring from MatPool and copying — these primitives are allocation-simple
 * on purpose so they can be composed and tested independently.
 */
object Preprocessor {

    fun grayscale(source: Mat): Mat {
        if (source.channels() == 1) return source.clone()
        val out = Mat()
        Imgproc.cvtColor(source, out, if (source.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY)
        return out
    }

    /** Alias for [grayscale] matching common image processing conventions. */
    fun toGray(source: Mat): Mat = grayscale(source)

    enum class BlurMethod { GAUSSIAN, BOX, MEDIAN }

    /** Blurs an image using the specified [BlurMethod] with an odd [kernelSize]. */
    fun blur(source: Mat, method: BlurMethod = BlurMethod.GAUSSIAN, kernelSize: Int = 5): Mat {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(source, "source")
        com.veilframe.app.cv.core.CvContracts.requireOddPositive(kernelSize, "kernelSize")
        val out = Mat()
        when (method) {
            BlurMethod.GAUSSIAN -> Imgproc.GaussianBlur(source, out, Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)
            BlurMethod.BOX -> Imgproc.blur(source, out, Size(kernelSize.toDouble(), kernelSize.toDouble()))
            BlurMethod.MEDIAN -> Imgproc.medianBlur(source, out, kernelSize)
        }
        return out
    }

    /**
     * Resize with algorithm selection:
     *  - downscale  → INTER_AREA (anti-aliased decimation)
     *  - upscale    → INTER_LINEAR by default (INTER_CUBIC for quality paths)
     * Never upscales unless [allowUpscale] is true.
     */
    fun resize(
        source: Mat,
        maxEdge: Int,
        allowUpscale: Boolean = false,
        interpolation: Int = Imgproc.INTER_LINEAR,
    ): Mat {
        require(maxEdge > 0) { "maxEdge must be > 0" }
        val srcEdge = maxOf(source.cols(), source.rows())
        if (srcEdge <= maxEdge && !allowUpscale) return source.clone()
        val scale = maxEdge.toDouble() / srcEdge
        val width = maxOf(1, Math.round(source.cols() * scale).toInt())
        val height = maxOf(1, Math.round(source.rows() * scale).toInt())
        val out = Mat()
        val algorithm = when {
            scale < 1.0 -> Imgproc.INTER_AREA
            interpolation != Imgproc.INTER_LINEAR -> interpolation
            else -> Imgproc.INTER_LINEAR
        }
        Imgproc.resize(source, out, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, algorithm)
        return out
    }

    /** Linear contrast/level adjust: out = alpha * src + beta. */
    fun contrast(source: Mat, alpha: Double, beta: Double = 0.0): Mat {
        val out = Mat()
        source.convertTo(out, -1, alpha, beta)
        return out
    }

    /** CLAHE-based local contrast on the L channel (Lab) or Luma (gray). */
    fun normalize(source: Mat, clipLimit: Double = 2.0, tileGrid: Int = 8): Mat {
        val isGray = source.channels() == 1
        val bgr = Mat()
        if (isGray) Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR) else source.copyTo(bgr)
        val lab = Mat()
        val planes = ArrayList<Mat>()
        val merged = Mat()
        val enhanced = Mat()
        val outBgr = Mat()
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, planes)
            val clahe = Imgproc.createCLAHE(clipLimit, Size(tileGrid.toDouble(), tileGrid.toDouble()))
            clahe.apply(planes[0], enhanced)
            enhanced.copyTo(planes[0])
            Core.merge(planes, merged)
            Imgproc.cvtColor(merged, outBgr, Imgproc.COLOR_Lab2BGR)
            return if (isGray) {
                val gray = Mat()
                Imgproc.cvtColor(outBgr, gray, Imgproc.COLOR_BGR2GRAY)
                gray
            } else {
                outBgr
            }
        } finally {
            bgr.release()
            lab.release()
            planes.forEach { it.release() }
            merged.release()
            enhanced.release()
            if (isGray) outBgr.release()
        }
    }

    /**
     * Estimates slowly-varying background illumination and compensates shading
     * to remove uneven shadows across paper documents.
     */
    fun removeShadows(source: Mat, kernelSize: Int = 25): Mat {
        val isGray = source.channels() == 1
        val bgr = Mat()
        if (isGray) Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR) else source.copyTo(bgr)

        val channels = ArrayList<Mat>()
        val resultChannels = ArrayList<Mat>()
        val merged = Mat()
        val safeKernel = if (kernelSize % 2 == 1) kernelSize else kernelSize + 1
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(safeKernel.toDouble(), safeKernel.toDouble()))
        try {
            Core.split(bgr, channels)
            for (ch in channels) {
                val bg = Mat()
                val diff = Mat()
                val normCh = Mat()
                val normalized = Mat()
                try {
                    // Dilation expands background paper over text lines
                    Imgproc.morphologyEx(ch, bg, Imgproc.MORPH_DILATE, kernel)
                    Imgproc.medianBlur(bg, bg, safeKernel)
                    // Compute absolute difference and invert: diff = 255 - (bg - ch)
                    Core.absdiff(ch, bg, diff)
                    Core.bitwise_not(diff, normCh)
                    // Stretch to full 0..255 dynamic range
                    Core.normalize(normCh, normalized, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC1)
                    resultChannels.add(normalized)
                } finally {
                    bg.release()
                    diff.release()
                    normCh.release()
                }
            }
            Core.merge(resultChannels, merged)
            return if (isGray) {
                val gray = Mat()
                Imgproc.cvtColor(merged, gray, Imgproc.COLOR_BGR2GRAY)
                gray
            } else {
                merged.clone()
            }
        } finally {
            bgr.release()
            kernel.release()
            channels.forEach { it.release() }
            resultChannels.forEach { it.release() }
            merged.release()
        }
    }

    /** Content-agnostic fast denoise (Gaussian / Median / Bilateral). */
    fun denoise(source: Mat, strength: Int = 5, method: DenoiseMethod = DenoiseMethod.BILATERAL): Mat {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(source, "source")
        com.veilframe.app.cv.core.CvContracts.requirePositive(strength, "strength")
        if (method == DenoiseMethod.MEDIAN) {
            require(strength % 2 == 1) { "strength for MEDIAN denoise must be odd (got $strength)" }
        }
        return when (method) {
            DenoiseMethod.GAUSSIAN -> {
                val out = Mat()
                Imgproc.GaussianBlur(source, out, Size(0.0, 0.0), strength / 2.5)
                out
            }
            DenoiseMethod.MEDIAN -> {
                val out = Mat()
                Imgproc.medianBlur(source, out, strength)
                out
            }
            DenoiseMethod.BILATERAL -> {
                val out = Mat()
                Imgproc.bilateralFilter(source, out, strength, 75.0, 75.0)
                out
            }
        }
    }

    /** Simple unsharp-mask sharpening. See SmartSharpener for the full version. */
    fun sharpen(source: Mat, amount: Double = 1.0, radius: Double = 1.0): Mat {
        val blurred = Mat()
        val out = Mat()
        try {
            Imgproc.GaussianBlur(source, blurred, Size(0.0, 0.0), radius)
            Core.addWeighted(source, 1.0 + amount, blurred, -amount, 0.0, out)
            return out
        } finally {
            blurred.release()
        }
    }

    fun threshold(source: Mat, thresh: Double = 127.0, maxVal: Double = 255.0, type: Int = Imgproc.THRESH_BINARY): Mat {
        val gray = toGray8(source)
        val out = Mat()
        try {
            Imgproc.threshold(gray, out, thresh, maxVal, type)
            return out
        } finally {
            if (gray !== source) gray.release()
        }
    }

    fun adaptiveThreshold(
        source: Mat,
        blockSize: Int = 31,
        c: Double = 10.0,
        type: Int = Imgproc.THRESH_BINARY,
    ): Mat {
        require(blockSize % 2 == 1 && blockSize >= 3) { "blockSize must be odd and >= 3" }
        val gray = toGray8(source)
        val out = Mat()
        try {
            Imgproc.adaptiveThreshold(
                gray, out, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, type, blockSize, c,
            )
            return out
        } finally {
            if (gray !== source) gray.release()
        }
    }

    /** Morphological open/close/dilate/erode with a configurable kernel. */
    fun morphology(
        source: Mat,
        op: MorphOp,
        kernelSize: Int = 3,
        iterations: Int = 1,
    ): Mat {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(source, "source")
        com.veilframe.app.cv.core.CvContracts.requireOddPositive(kernelSize, "kernelSize")
        require(iterations >= 1) { "iterations must be >= 1 (got $iterations)" }
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(kernelSize.toDouble(), kernelSize.toDouble()),
        )
        val out = Mat()
        try {
            Imgproc.morphologyEx(source, out, op.code, kernel, Point(-1.0, -1.0), iterations)
            return out
        } finally {
            kernel.release()
        }
    }

    /** Edge-preserving smoothing (bilateral by default). */
    fun edgePreserve(source: Mat, sigmaColor: Double = 50.0, sigmaSpace: Double = 50.0): Mat {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(source, "source")
        com.veilframe.app.cv.core.CvContracts.requirePositive(sigmaColor, "sigmaColor")
        com.veilframe.app.cv.core.CvContracts.requirePositive(sigmaSpace, "sigmaSpace")
        val out = Mat()
        Imgproc.bilateralFilter(source, out, 0, sigmaColor, sigmaSpace)
        return out
    }

    /** Non-destructive crop region extraction. */
    fun crop(source: Mat, x: Int, y: Int, width: Int, height: Int): Mat {
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "invalid crop rect" }
        require(x + width <= source.cols() && y + height <= source.rows()) {
            "crop rect exceeds image bounds"
        }
        return Mat(source, Rect(x, y, width, height)).clone()
    }

    private fun toGray8(source: Mat): Mat = when {
        source.channels() == 1 && source.type() == CvType.CV_8UC1 -> source
        source.channels() == 1 -> {
            val out = Mat()
            source.convertTo(out, CvType.CV_8UC1)
            out
        }
        else -> grayscale(source)
    }
}

enum class DenoiseMethod { GAUSSIAN, MEDIAN, BILATERAL }

enum class MorphOp(val code: Int) {
    ERODE(Imgproc.MORPH_ERODE),
    DILATE(Imgproc.MORPH_DILATE),
    OPEN(Imgproc.MORPH_OPEN),
    CLOSE(Imgproc.MORPH_CLOSE),
    GRADIENT(Imgproc.MORPH_GRADIENT),
    TOPHAT(Imgproc.MORPH_TOPHAT),
    BLACKHAT(Imgproc.MORPH_BLACKHAT),
}
