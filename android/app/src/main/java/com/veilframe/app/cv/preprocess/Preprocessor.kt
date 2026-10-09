package com.veilframe.app.cv.preprocess

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
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

    /**
     * Mathematical formula for Sauvola threshold:
     *   T = m * [1 + k * (s/R - 1)]
     * Pure evaluator for testing and validation.
     */
    fun sauvolaThreshold(m: Double, s: Double, k: Double = 0.2, R: Double = 128.0): Double {
        return m * (1.0 + k * ((s / R) - 1.0))
    }

    /**
     * Sauvola Local Binarization.
     * Computes a dynamic threshold for every pixel based on local mean and variance:
     *   T(x,y) = m(x,y) * [1 + k * (s(x,y)/R - 1)]
     * Uses O(1) Box Filters (Imgproc.blur) via statistical identity Var = E[X^2] - (E[X])^2
     * for high performance on mobile devices.
     *
     * @param src Single-channel 8-bit grayscale Mat (CV_8UC1).
     * @param dst Destination Mat to write binary output (CV_8UC1, 0 for ink, 255 for paper).
     * @param windowSize Local window width/height (must be odd and >= 3, default 51).
     * @param k Dynamic range sensitivity factor (typically 0.2 to 0.5, default 0.2).
     * @param R Dynamic range of standard deviation (default 128.0 for 8-bit images).
     */
    fun sauvolaBinarize(
        src: Mat,
        dst: Mat,
        windowSize: Int = 51,
        k: Double = 0.2,
        R: Double = 128.0,
    ) {
        require(windowSize % 2 == 1 && windowSize >= 3) { "windowSize must be odd and >= 3" }

        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            return
        }

        require(src.channels() == 1 && src.type() == CvType.CV_8UC1) { "Requires 8UC1 grayscale" }

        val srcFloat = Mat()
        src.convertTo(srcFloat, CvType.CV_32F)

        val localMean = Mat()
        val localMeanSq = Mat()
        val srcSquared = Mat()
        val localMeanSqCalc = Mat()
        val localVar = Mat()
        val localStddev = Mat()
        val term1 = Mat()
        val threshold = Mat()
        val diff = Mat()
        val threshFloat = Mat()

        try {
            val kSize = Size(windowSize.toDouble(), windowSize.toDouble())

            // 1. Local Mean (m) via O(1) Box Filter
            Imgproc.blur(srcFloat, localMean, kSize)

            // 2. Local Mean of Squares (m2)
            Core.multiply(srcFloat, srcFloat, srcSquared)
            Imgproc.blur(srcSquared, localMeanSq, kSize)

            // 3. Local Variance (var = m2 - m^2)
            Core.multiply(localMean, localMean, localMeanSqCalc)
            Core.subtract(localMeanSq, localMeanSqCalc, localVar)

            // Clamp variance to 0 to avoid NaN from sqrt of tiny negative floats
            Core.max(localVar, Scalar(0.0), localVar)

            // 4. Local Standard Deviation (s = sqrt(var))
            Core.sqrt(localVar, localStddev)

            // 5. Calculate Threshold: T = m * (1 + k * (s/R - 1))
            Core.divide(localStddev, Scalar(R), term1)
            Core.subtract(term1, Scalar(1.0), term1)
            Core.multiply(term1, Scalar(k), term1)
            Core.add(term1, Scalar(1.0), term1)
            Core.multiply(localMean, term1, threshold)

            // 6. Binarize: diff = src - T
            Core.subtract(srcFloat, threshold, diff)

            // 7. Apply standard thresholding on the difference map
            // > 0 becomes 255 (Paper), <= 0 becomes 0 (Ink)
            Imgproc.threshold(diff, threshFloat, 0.0, 255.0, Imgproc.THRESH_BINARY)
            threshFloat.convertTo(dst, CvType.CV_8UC1)
        } finally {
            listOf(
                srcFloat, localMean, localMeanSq, srcSquared, localMeanSqCalc,
                localVar, localStddev, term1, threshold, diff, threshFloat
            ).forEach { it.release() }
        }
    }

    /**
     * Functional wrapper for [sauvolaBinarize] returning a new CV_8UC1 Mat.
     */
    fun sauvola(
        source: Mat,
        windowSize: Int = 51,
        k: Double = 0.2,
        R: Double = 128.0,
    ): Mat {
        val gray = toGray8(source)
        val out = Mat()
        try {
            sauvolaBinarize(gray, out, windowSize, k, R)
            return out
        } finally {
            if (gray !== source) gray.release()
        }
    }

    /**
     * Mathematical transfer function for Gaussian High-Pass Homomorphic Filter:
     *   H(d) = (gammaH - gammaL) * [1 - exp(-c * d^2 / d0^2)] + gammaL
     *
     * Pure evaluator for testing, mathematical validation, and transfer curve analysis.
     *
     * @param d Radial distance from DC frequency origin (D(u,v) = sqrt(du^2 + dv^2)).
     * @param d0 Cutoff frequency radius.
     * @param c High-frequency slope sharpness factor (typically 1.0).
     * @param gammaL Low-frequency gain (attenuates slow-varying shadows/illumination, e.g. 0.4).
     * @param gammaH High-frequency gain (boosts crisp text reflectance edges, e.g. 1.4).
     */
    fun homomorphicTransferFunction(
        d: Double,
        d0: Double = 35.0,
        c: Double = 1.0,
        gammaL: Double = 0.4,
        gammaH: Double = 1.4,
    ): Double {
        require(d0 > 0.0) { "d0 must be > 0 (got $d0)" }
        require(c > 0.0) { "c must be > 0 (got $c)" }
        require(gammaL >= 0.0) { "gammaL must be >= 0 (got $gammaL)" }
        require(gammaH >= gammaL) { "gammaH must be >= gammaL (got gammaH=$gammaH, gammaL=$gammaL)" }

        val exponent = -c * (d * d) / (d0 * d0)
        return (gammaH - gammaL) * (1.0 - Math.exp(exponent)) + gammaL
    }

    /**
     * Homomorphic filter on a single 8-bit grayscale channel.
     *
     * Operates on the image formation model I(x,y) = L(x,y) * R(x,y):
     * 1. Log Transform: ln(1 + I) converts multiplicative illumination into additive components.
     * 2. Pad to optimal DFT dimensions (Core.getOptimalDFTSize) with Core.BORDER_REFLECT to suppress border ringing.
     * 3. Forward 2D Discrete Fourier Transform (Core.dft) into complex frequency domain.
     * 4. Multiplies by Gaussian high-pass emphasis transfer function H(u,v) = (gammaH - gammaL) * [1 - exp(-c * D^2 / D0^2)] + gammaL.
     * 5. Inverse Discrete Fourier Transform (Core.idft) with Core.DFT_SCALE.
     * 6. Exponential transform: exp(spatial) - 1 reverses the log transform.
     * 7. Crop back to original dimensions and stretch dynamic range to [0..255] via min-max normalization.
     *
     * @param src Single-channel 8-bit grayscale Mat (CV_8UC1).
     * @param dst Single-channel 8-bit output Mat (CV_8UC1).
     */
    fun homomorphicFilterChannel(
        src: Mat,
        dst: Mat,
        gammaL: Double = 0.4,
        gammaH: Double = 1.4,
        c: Double = 1.0,
        d0: Double = 35.0,
    ) {
        require(d0 > 0.0) { "d0 must be > 0" }
        require(c > 0.0) { "c must be > 0" }
        require(gammaL >= 0.0) { "gammaL must be >= 0" }
        require(gammaH >= gammaL) { "gammaH must be >= gammaL" }

        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            if (src !== dst) src.copyTo(dst)
            return
        }

        require(src.channels() == 1 && src.type() == CvType.CV_8UC1) { "Requires 8UC1 grayscale channel" }

        val optRows = Core.getOptimalDFTSize(src.rows())
        val optCols = Core.getOptimalDFTSize(src.cols())

        val srcFloat = Mat()
        val logMat = Mat()
        val padded = Mat()
        val zeroPlane = Mat()
        val complexI = Mat()
        val hMat = Mat(optRows, optCols, CvType.CV_32FC1)
        val dftPlanes = ArrayList<Mat>()
        val idftPlanes = ArrayList<Mat>()
        val spatial = Mat()
        val expMat = Mat()

        try {
            // 1. Float conversion & ln(1 + I)
            src.convertTo(srcFloat, CvType.CV_32F)
            Core.add(srcFloat, Scalar(1.0), logMat)
            Core.log(logMat, logMat)

            // 2. Pad to optimal DFT dimensions with BORDER_REFLECT to prevent boundary discontinuities
            Core.copyMakeBorder(logMat, padded, 0, optRows - src.rows(), 0, optCols - src.cols(), Core.BORDER_REFLECT)

            // 3. Forward DFT (complex: Real = padded, Imag = 0)
            val planes = ArrayList<Mat>()
            planes.add(padded)
            zeroPlane.create(padded.size(), CvType.CV_32F)
            zeroPlane.setTo(Scalar.all(0.0))
            planes.add(zeroPlane)
            Core.merge(planes, complexI)
            Core.dft(complexI, complexI)

            // 4. Construct Gaussian High-Pass Homomorphic Filter H(u,v)
            val filter = FloatArray(optRows * optCols)
            val d0Sq = d0 * d0
            val rowExp = FloatArray(optRows) { u ->
                val du = Math.min(u, optRows - u).toDouble()
                Math.exp(-c * (du * du) / d0Sq).toFloat()
            }
            val colExp = FloatArray(optCols) { v ->
                val dv = Math.min(v, optCols - v).toDouble()
                Math.exp(-c * (dv * dv) / d0Sq).toFloat()
            }
            val gammaDiff = (gammaH - gammaL).toFloat()
            val gL = gammaL.toFloat()
            for (u in 0 until optRows) {
                val rVal = rowExp[u]
                val rowOffset = u * optCols
                for (v in 0 until optCols) {
                    filter[rowOffset + v] = gammaDiff * (1.0f - rVal * colExp[v]) + gL
                }
            }
            hMat.put(0, 0, filter)

            // 5. Apply filter in frequency domain: Real' = Real * H, Imag' = Imag * H
            Core.split(complexI, dftPlanes)
            Core.multiply(dftPlanes[0], hMat, dftPlanes[0])
            Core.multiply(dftPlanes[1], hMat, dftPlanes[1])
            Core.merge(dftPlanes, complexI)

            // 6. Inverse DFT
            Core.idft(complexI, complexI, Core.DFT_SCALE)
            Core.split(complexI, idftPlanes)
            idftPlanes[0].copyTo(spatial)

            // 7. Exponential transform: exp(spatial) - 1
            Core.exp(spatial, expMat)
            Core.subtract(expMat, Scalar(1.0), expMat)

            // 8. Crop out padding & normalize to full 0..255 dynamic range
            val roi = Rect(0, 0, src.cols(), src.rows())
            val croppedSubmat = expMat.submat(roi)
            try {
                Core.normalize(croppedSubmat, dst, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC1)
            } finally {
                croppedSubmat.release()
            }
        } finally {
            listOf(
                srcFloat, logMat, padded, zeroPlane, complexI, hMat,
                spatial, expMat
            ).forEach { it.release() }
            dftPlanes.forEach { it.release() }
            idftPlanes.forEach { it.release() }
        }
    }

    /**
     * Homomorphic Filtering for illumination flattening and shadow gradient elimination.
     *
     * For single-channel grayscale images, filters the channel directly.
     * For 3-channel (BGR) or 4-channel (BGRA) images, converts to CIE L*a*b* color space
     * and processes ONLY the L* (lightness) channel, preserving 100% natural ink, stamp,
     * and seal chromaticity without color drift.
     */
    fun homomorphicFilter(
        source: Mat,
        gammaL: Double = 0.4,
        gammaH: Double = 1.4,
        c: Double = 1.0,
        d0: Double = 35.0,
    ): Mat {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(source, "source")

        if (source.channels() == 1) {
            val gray = toGray8(source)
            val out = Mat()
            try {
                homomorphicFilterChannel(gray, out, gammaL, gammaH, c, d0)
                return out
            } finally {
                if (gray !== source) gray.release()
            }
        }

        val is4Channel = source.channels() == 4
        val bgr = Mat()
        val lab = Mat()
        val planes = ArrayList<Mat>()
        val filteredL = Mat()
        val out = Mat()

        try {
            if (is4Channel) {
                Imgproc.cvtColor(source, bgr, Imgproc.COLOR_BGRA2BGR)
            } else {
                source.copyTo(bgr)
            }

            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, planes)

            homomorphicFilterChannel(planes[0], filteredL, gammaL, gammaH, c, d0)

            val mergedPlanes = listOf(filteredL, planes[1], planes[2])
            Core.merge(mergedPlanes, lab)

            if (is4Channel) {
                val tempBgr = Mat()
                try {
                    Imgproc.cvtColor(lab, tempBgr, Imgproc.COLOR_Lab2BGR)
                    Imgproc.cvtColor(tempBgr, out, Imgproc.COLOR_BGR2BGRA)
                } finally {
                    tempBgr.release()
                }
            } else {
                Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2BGR)
            }
            return out
        } finally {
            bgr.release()
            lab.release()
            planes.forEach { it.release() }
            filteredL.release()
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
