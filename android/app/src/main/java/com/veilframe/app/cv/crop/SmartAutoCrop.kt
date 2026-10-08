package com.veilframe.app.cv.crop

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/**
 * SmartAutoCrop — content-aware cropping on top of the other CV systems.
 *
 *   Image → subject/saliency estimation → important-region map → bounding box →
 *   aspect-ratio optimizer → crop
 *
 * Classical CV alone cannot reliably identify every photographic subject; for
 * genuinely good subject detection, pass a model-derived mask through
 * [SubjectMaskProvider] (ONNX-side) and the crop optimizer will fuse it with
 * the classical importance map.
 *
 * Naming honesty: despite the historical "smart" name, without a
 * [SubjectMaskProvider] this is CONTENT-AWARE crop (gradient + center-prior
 * heuristics) — it can favour text, edges and high-contrast objects over the
 * semantic subject. Don't present it as AI subject cropping until the ONNX
 * mask is wired.
 */
object SmartAutoCrop {

    /** Common aspect ratios plus custom. */
    enum class Aspect(val ratio: Double?) {
        SQUARE(1.0),
        RATIO_4_5(4.0 / 5.0),
        RATIO_3_4(3.0 / 4.0),
        RATIO_16_9(16.0 / 9.0),
        RATIO_9_16(9.0 / 16.0),
        CUSTOM(null),
    }

    /** Optional model-derived subject mask (8-bit, same size as the source). */
    fun interface SubjectMaskProvider {
        fun maskFor(image: Mat): Mat?
    }

    data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val isValid: Boolean get() = width > 0 && height > 0
        fun toRect(): Rect = Rect(x, y, width, height)
    }

    data class CropResult(
        val rect: CropRect,
        /** Fraction of the importance map captured by the crop, 0..1. */
        val energyCoverage: Double,
        val aspect: Aspect,
    )

    data class Options(
        val aspect: Aspect = Aspect.SQUARE,
        val customRatio: Double? = null,
        /** Margin around the important region, fraction of crop size. */
        val margin: Double = 0.05,
        /** Center-bias weight fused into the importance map. */
        val centerBias: Double = 0.15,
    ) {
        init {
            if (aspect == Aspect.CUSTOM) {
                require(customRatio != null && customRatio.isFinite() && customRatio > 0.0) {
                    "customRatio must be non-null, finite and > 0 for Aspect.CUSTOM (got $customRatio)"
                }
            }
            com.veilframe.app.cv.core.CvContracts.requireInRange(margin, 0.0, 0.5, "margin")
            com.veilframe.app.cv.core.CvContracts.requireInRange(centerBias, 0.0, 1.0, "centerBias")
        }
    }

    /**
     * Computes the best crop for [source].
     * Falls back to the largest centered crop when the image is featureless.
     */
    fun computeCrop(
        source: Mat,
        options: Options = Options(),
        subjectMask: SubjectMaskProvider? = null,
    ): CropResult {
        require(!source.empty()) { "source is empty" }
        val ratio = resolveRatio(options) ?: return centeredCrop(source, options.aspect)

        val importance = importanceMap(source, options.centerBias)
        val modelMask = try {
            subjectMask?.maskFor(source)
        } catch (e: Exception) {
            // Subject mask plugin failure: continue with classical importance map only.
            // Errors (OOM, etc.) propagate.
            null
        }
        if (modelMask != null) {
            fuseMask(importance, modelMask)
            modelMask.release()
        }

        try {
            val width = source.cols()
            val height = source.rows()
            var cropW: Int
            var cropH: Int
            if (ratio >= 1.0) {
                cropW = width
                cropH = maxOf(1, Math.round(width / ratio).toInt())
                if (cropH > height) {
                    cropH = height
                    cropW = maxOf(1, Math.round(height * ratio).toInt())
                }
            } else {
                cropH = height
                cropW = maxOf(1, Math.round(height * ratio).toInt())
                if (cropW > width) {
                    cropW = width
                    cropH = maxOf(1, Math.round(width / ratio).toInt())
                }
            }

            val paddedW = minOf(width, Math.round(cropW * (1 + options.margin)).toInt())
            val paddedH = minOf(height, Math.round(cropH * (1 + options.margin)).toInt())

            // Integral image search: window of (paddedW x paddedH) with max energy.
            val integral = Mat()
            val squared = Mat()
            try {
                Imgproc.integral2(importance, integral, squared)
            } finally {
                squared.release()
            }

            try { // CV-11: integral released even when the search throws
            var bestX = (width - paddedW) / 2
            var bestY = (height - paddedH) / 2
            var bestEnergy = -1.0
            val stepX = maxOf(1, paddedW / 16)
            val stepY = maxOf(1, paddedH / 16)
            var y = 0
            while (y + paddedH <= height) {
                var x = 0
                while (x + paddedW <= width) {
                    val energy = windowSum(integral, x, y, paddedW, paddedH)
                    if (energy > bestEnergy) {
                        bestEnergy = energy
                        bestX = x
                        bestY = y
                    }
                    x += stepX
                }
                y += stepY
            }
            // Clamp to bounds after stepping.
            bestX = bestX.coerceIn(0, width - paddedW)
            bestY = bestY.coerceIn(0, height - paddedH)
            } finally {
                integral.release()
            }

            val totalEnergy = Core.sumElems(importance).`val`[0]
            val rect = CropRect(bestX, bestY, paddedW, paddedH)
            return CropResult(
                rect = rect,
                energyCoverage = if (totalEnergy <= 0) 1.0 else (bestEnergy / totalEnergy).coerceIn(0.0, 1.0),
                aspect = options.aspect,
            )
        } finally {
            importance.release()
        }
    }

    /** Convenience: crops [source] with the computed rect. Caller owns result. */
    fun crop(source: Mat, options: Options = Options(), subjectMask: SubjectMaskProvider? = null): Pair<Mat, CropResult> {
        val result = computeCrop(source, options, subjectMask)
        val out = Mat(source, result.rect.toRect()).clone()
        return out to result
    }

    // ------------------------------------------------------------- internals

    private fun resolveRatio(options: Options): Double? = when (options.aspect) {
        Aspect.CUSTOM -> options.customRatio?.takeIf { it > 0 }
        else -> options.aspect.ratio
    }

    private fun centeredCrop(source: Mat, aspect: Aspect): CropResult {
        val width = source.cols()
        val height = source.rows()
        val rect = CropRect(0, 0, width, height)
        return CropResult(rect, 1.0, aspect)
    }

    /**
     * Classical importance map: gradient energy (subject edges) fused with a
     * gentle center prior. Returned as CV_32F single channel.
     */
    private fun importanceMap(source: Mat, centerBias: Double): Mat {
        val gray = Mat()
        val gx = Mat()
        val gy = Mat()
        val magnitude = Mat()
        val blurred = Mat()
        try {
            when (source.channels()) {
                1 -> source.copyTo(gray)
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY)
                else -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            }
            Imgproc.Sobel(gray, gx, org.opencv.core.CvType.CV_32F, 1, 0, 3, 1.0, 0.0, org.opencv.core.Core.BORDER_REPLICATE)
            Imgproc.Sobel(gray, gy, org.opencv.core.CvType.CV_32F, 0, 1, 3, 1.0, 0.0, org.opencv.core.Core.BORDER_REPLICATE)
            org.opencv.core.Core.magnitude(gx, gy, magnitude)
            Imgproc.GaussianBlur(magnitude, blurred, org.opencv.core.Size(25.0, 25.0), 0.0)

            if (centerBias > 0.0) {
                val center = centerPrior(blurred.rows(), blurred.cols(), blurred.type())
                org.opencv.core.Core.addWeighted(blurred, 1.0, center, centerBias, 0.0, blurred)
                center.release()
            }
            val out = blurred.clone()
            return out
        } finally {
            gray.release()
            gx.release()
            gy.release()
            magnitude.release()
            blurred.release()
        }
    }

    private fun centerPrior(rows: Int, cols: Int, type: Int): Mat {
        val prior = Mat.zeros(rows, cols, type)
        val cx = cols / 2.0
        val cy = rows / 2.0
        val sigma = maxOf(rows, cols) / 3.0
        val row = FloatArray(cols)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val dx = (x - cx) / sigma
                val dy = (y - cy) / sigma
                row[x] = kotlin.math.exp(-(dx * dx + dy * dy)).toFloat()
            }
            prior.put(y, 0, row)
        }
        return prior
    }

    /** Multiplies importance by a model mask (resized if needed). */
    private fun fuseMask(importance: Mat, modelMask: Mat) {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(modelMask, "modelMask")
        com.veilframe.app.cv.core.CvContracts.requireGray8(modelMask, "modelMask")
        val resized = if (modelMask.size() != importance.size()) {
            val r = Mat()
            Imgproc.resize(modelMask, r, importance.size())
            r
        } else {
            modelMask
        }
        val maskFloat = Mat()
        resized.convertTo(maskFloat, importance.type(), 1.0 / 255.0)
        org.opencv.core.Core.multiply(importance, maskFloat, importance)
        maskFloat.release()
        if (resized !== modelMask) resized.release()
    }

    /** Sum over the (x, y, w, h) window using an integral image. */
    private fun windowSum(integral: Mat, x: Int, y: Int, w: Int, h: Int): Double {
        val a = integral.get(y, x)[0]
        val b = integral.get(y, x + w)[0]
        val c = integral.get(y + h, x)[0]
        val d = integral.get(y + h, x + w)[0]
        return d - b - c + a
    }
}
