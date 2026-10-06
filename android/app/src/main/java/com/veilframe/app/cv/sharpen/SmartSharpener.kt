package com.veilframe.app.cv.sharpen

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * SmartSharpener — unsharp masking with edge protection.
 *
 *   Original ──┬───────────────┐
 *              │          Gaussian blur
 *              └──── subtract ─┘
 *                        │
 *                      detail × amount
 *                        │
 *                    sharpened
 *
 * The entire detail computation runs in CV_32F: 8-bit `Core.subtract` SATURATES
 * negative values to 0, which silently halves the detail layer (bright-side
 * bias) and turns unsharp masking into a brightness-drifting operation.
 *
 * Edge protection: detail is scaled down where local gradient energy is low
 * (flat/noisy areas) so noise is not amplified while real edges gain punch.
 * Parameters follow the classic Amount / Radius / Threshold model.
 */
object SmartSharpener {

    data class Params(
        /** Detail multiplier. 1.0 = classic subtle sharpen. */
        val amount: Double = 1.0,
        /** Gaussian sigma of the blur stage. */
        val radius: Double = 1.0,
        /** Detail below this magnitude is ignored (flat-area protection), in 8-bit levels. */
        val threshold: Double = 4.0,
        /** Strength of the noise protection mask (0 = off, 1 = aggressive). */
        val edgeProtection: Double = 0.6,
    ) {
        init {
            require(amount >= 0.0) { "amount must be >= 0" }
            require(radius > 0.0) { "radius must be > 0" }
            require(threshold >= 0.0) { "threshold must be >= 0" }
            require(edgeProtection in 0.0..1.0) { "edgeProtection must be in [0, 1]" }
        }
    }

    /** Unsharp mask with optional edge protection. Caller owns the result. */
    fun sharpen(source: Mat, params: Params = Params()): Mat {
        require(!source.empty()) { "source is empty" }
        val sourceType = source.type()
        val src32 = Mat()
        val blur32 = Mat()
        val detail = Mat()
        val out32 = Mat()
        val out = Mat()
        try {
            source.convertTo(src32, CvType.CV_32F)

            // Blur the original type (keeps OpenCV's blur heuristics on 8-bit),
            // then lift to float for the subtraction.
            val blurred8 = Mat()
            Imgproc.GaussianBlur(source, blurred8, Size(0.0, 0.0), params.radius)
            blurred8.convertTo(blur32, CvType.CV_32F)
            blurred8.release()

            // detail = source - blurred  (positive AND negative, no saturation)
            Core.subtract(src32, blur32, detail)

            // Threshold: zero-out weak detail (|detail| <= threshold).
            if (params.threshold > 0.0) {
                val absDetail = Mat()
                Core.absdiff(detail, Scalar(0.0), absDetail)
                val keep = Mat()
                Imgproc.threshold(absDetail, keep, params.threshold, 1.0, Imgproc.THRESH_BINARY)
                Core.multiply(detail, keep, detail)
                absDetail.release()
                keep.release()
            }

            // Edge protection: scale detail down on flat regions.
            if (params.edgeProtection > 0.0) {
                val gray = Mat()
                if (source.channels() == 1) source.convertTo(gray, CvType.CV_8U)
                else Imgproc.cvtColor(
                    source,
                    gray,
                    if (source.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY,
                )
                val gx = Mat()
                val gy = Mat()
                val magnitude = Mat()
                val normalized = Mat()
                val maskFloat = Mat()
                val mask = Mat()
                try {
                    Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                    Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                    Core.magnitude(gx, gy, magnitude)
                    Core.normalize(magnitude, normalized, 0.0, 1.0, Core.NORM_MINMAX)
                    // mask = protection·normalized + (1 − protection):
                    //   protection=1 → mask = normalized (flat-area detail fully
                    //                  suppressed, edges keep their punch);
                    //   protection=0 → mask = 1 everywhere (protection off).
                    // (The previous scale/bias were swapped, so the parameter
                    // ran backwards: 1.0 disabled the protection entirely.)
                    normalized.convertTo(maskFloat, CvType.CV_32F, params.edgeProtection, 1.0 - params.edgeProtection)
                    when (detail.channels()) {
                        1 -> maskFloat.copyTo(mask)
                        3 -> Imgproc.cvtColor(maskFloat, mask, Imgproc.COLOR_GRAY2BGR)
                        else -> Imgproc.cvtColor(maskFloat, mask, Imgproc.COLOR_GRAY2BGRA)
                    }
                    Core.multiply(detail, mask, detail)
                } finally {
                    gray.release()
                    gx.release()
                    gy.release()
                    magnitude.release()
                    normalized.release()
                    maskFloat.release()
                    mask.release()
                }
            }

            // sharpened = source + amount * detail  (all in float)
            Core.addWeighted(src32, 1.0, detail, params.amount, 0.0, out32)
            out32.convertTo(out, sourceType)
            return out
        } finally {
            src32.release()
            blur32.release()
            detail.release()
            out32.release()
        }
    }
}
