package com.veilframe.app.cv.template

import com.veilframe.app.cv.core.CvContracts
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * TemplateMatcher — multi-scale template matching with an adaptive scale range.
 *
 * Instead of blindly sweeping dozens of scales, the matcher does a coarse sweep
 * over the configured range, then refines around the best scale — bounded work
 * regardless of image size.
 */
object TemplateMatcher {

    enum class Method(val code: Int) {
        CCOEFF_NORMED(Imgproc.TM_CCOEFF_NORMED),
        CCORR_NORMED(Imgproc.TM_CCORR_NORMED),
    }

    data class MatchResult(
        val location: Point,
        val scale: Double,
        val confidence: Double,
        /** true when confidence >= [Options.minConfidence]. */
        val accepted: Boolean,
    )

    data class Options(
        val method: Method = Method.CCOEFF_NORMED,
        val minConfidence: Double = 0.7,
        val scaleMin: Double = 0.5,
        val scaleMax: Double = 2.0,
        /** Coarse sweep step before refinement. */
        val coarseStep: Double = 0.25,
        /** Hard cap on total scales evaluated (coarse + refined). */
        val maxScales: Int = 12,
        /** Resource cap: maximum image edge admitted for template matching. */
        val maxImageEdge: Int = 4096,
    ) {
        init {
            CvContracts.requireInRange(minConfidence, 0.0, 1.0, "minConfidence")
            CvContracts.requirePositive(scaleMin, "scaleMin")
            CvContracts.requireFinite(scaleMax, "scaleMax")
            require(scaleMax >= scaleMin) { "scaleMax ($scaleMax) must be >= scaleMin ($scaleMin)" }
            CvContracts.requirePositive(coarseStep, "coarseStep")
            CvContracts.requirePositive(maxScales, "maxScales")
            CvContracts.requirePositive(maxImageEdge, "maxImageEdge")
        }
    }

    /**
     * Finds the best occurrence of [template] in [image].
     * Returns null when the template is larger than the image at every scale or
     * nothing exceeds [Options.minConfidence].
     */
    fun match(image: Mat, template: Mat, options: Options = Options()): MatchResult? {
        CvContracts.requireNonEmpty(image, "image")
        CvContracts.requireNonEmpty(template, "template")
        require(image.cols() <= options.maxImageEdge && image.rows() <= options.maxImageEdge) {
            "image dimensions (${image.cols()}x${image.rows()}) exceed maxImageEdge (${options.maxImageEdge})"
        }
        require(options.scaleMin > 0 && options.scaleMax >= options.scaleMin) { "invalid scale range" }

        val coarseScales = buildList {
            var s = options.scaleMin
            while (s <= options.scaleMax + 1e-9 && size < options.maxScales / 2) {
                add(s)
                s += options.coarseStep
            }
            if (isEmpty()) add(1.0)
        }

        var best: MatchResult? = null
        for (scale in coarseScales) {
            val candidate = evaluateScale(image, template, scale, options)
            if (best == null || (candidate != null && candidate.confidence > best.confidence)) {
                best = candidate ?: best
            }
        }

        // Refine around the coarse winner with a finer step.
        val center = best?.scale ?: return null
        val fineStep = options.coarseStep / 4.0
        val refinement = listOf(-fineStep, fineStep, -fineStep / 2, fineStep / 2)
        var budget = options.maxScales - coarseScales.size
        for (delta in refinement) {
            if (budget <= 0) break
            budget--
            val scale = center + delta
            if (scale < options.scaleMin || scale > options.scaleMax) continue
            val candidate = evaluateScale(image, template, scale, options)
            if (candidate != null && candidate.confidence > (best?.confidence ?: Double.NEGATIVE_INFINITY)) {
                best = candidate
            }
        }

        return best?.takeIf { it.confidence >= options.minConfidence }
    }

    private fun evaluateScale(image: Mat, template: Mat, scale: Double, options: Options): MatchResult? {
        val scaledTemplate = Mat()
        try {
            if (scale == 1.0) {
                template.copyTo(scaledTemplate)
            } else {
                Imgproc.resize(
                    template,
                    scaledTemplate,
                    org.opencv.core.Size(),
                    scale,
                    scale,
                    if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR,
                )
            }
            if (scaledTemplate.cols() > image.cols() || scaledTemplate.rows() > image.rows()) return null

            val result = Mat()
            try {
                Imgproc.matchTemplate(image, scaledTemplate, result, options.method.code)
                val mmr = Core.minMaxLoc(result)
                val (confidence, location) = when (options.method) {
                    Method.CCOEFF_NORMED, Method.CCORR_NORMED -> mmr.maxVal to mmr.maxLoc
                    else -> mmr.maxVal to mmr.maxLoc
                }
                return MatchResult(
                    location = location,
                    scale = scale,
                    confidence = confidence,
                    accepted = confidence >= options.minConfidence,
                )
            } finally {
                result.release()
            }
        } finally {
            scaledTemplate.release()
        }
    }
}
