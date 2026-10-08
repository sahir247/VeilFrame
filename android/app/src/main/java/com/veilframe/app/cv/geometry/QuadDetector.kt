package com.veilframe.app.cv.geometry

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * QuadDetector — finds the document quadrilateral in a photograph.
 *
 * Pipeline (as specified in the VeilFrame CV plan):
 *   downscale → grayscale → Gaussian blur → Canny → contours →
 *   polygon approximation → largest plausible quadrilateral
 *
 * Detection runs on a working image (~1280–1600 px) for speed and stable
 * contour behaviour; returned corners are already in SOURCE coordinates.
 */
object QuadDetector {

    operator fun invoke(): QuadDetector = this

    data class Detection(
        /** TL, TR, BR, BL in SOURCE-image coordinates (already scaled back). */
        val corners: List<Point>,
        /** Quad area / image area, 0..1. */
        val coverage: Double,
        /** Heuristic 0..1: quad shape regularity + edge support. */
        val confidence: Double,
        /** Ratio such that SOURCE = working × workingScale (info only — corners
         *  are already source coordinates; do NOT rescale them again). */
        val workingScale: Double,
    )

    /**
     * Detects the dominant document-like quad.
     * Returns null when no plausible quadrilateral exists.
     */
    fun detect(
        source: Mat,
        workingMaxEdge: Int = 1280,
        minCoverage: Double = 0.08,
        cannyLow: Double = 40.0,
        cannyHigh: Double = 140.0,
        context: com.veilframe.app.cv.core.CvContext? = null,
    ): Detection? {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(source, "source")
        com.veilframe.app.cv.core.CvContracts.requirePositive(workingMaxEdge, "workingMaxEdge")
        com.veilframe.app.cv.core.CvContracts.requireInRange(minCoverage, 0.0, 1.0, "minCoverage")
        com.veilframe.app.cv.core.CvContracts.requireNonNegative(cannyLow, "cannyLow")
        com.veilframe.app.cv.core.CvContracts.requirePositive(cannyHigh, "cannyHigh")
        require(cannyHigh >= cannyLow) { "cannyHigh ($cannyHigh) must be >= cannyLow ($cannyLow)" }

        context?.ensureActive() // B6: checkpoint before the heavy stage chain
        val working = workingImage(source, workingMaxEdge)
        val scale = source.cols().toDouble() / working.cols()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            if (working.channels() == 1) working.copyTo(gray)
            else Imgproc.cvtColor(working, gray, grayCode(working))
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, cannyLow, cannyHigh)
            // Close small gaps in the document border before contouring.
            val closeKernel = Mat.ones(3, 3, CvType8u)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, closeKernel)
            closeKernel.release()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            } finally {
                hierarchy.release()
            }

            val workingArea = working.rows().toDouble() * working.cols()
            var best: Detection? = null
            for (contour in contours) {
                // B6: noisy frames yield thousands of contours; stay cancellable.
                context?.ensureActive()
                if (Imgproc.contourArea(contour) < workingArea * minCoverage) continue
                val approx = MatOfPoint2f()
                val contour2f = MatOfPoint2f(*contour.toArray())
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * Imgproc.arcLength(contour2f, true), true)
                val points = approx.toList()
                if (points.size != 4) {
                    approx.release()
                    contour2f.release()
                    continue
                }
                val approxMat = MatOfPoint(*points.toTypedArray())
                val isConvex = Imgproc.isContourConvex(approxMat)
                approxMat.release()
                if (!isConvex) {
                    approx.release()
                    contour2f.release()
                    continue
                }
                val ordered = Geometry.ordered(points)
                val area = Geometry.quadArea(ordered)
                val coverage = area / workingArea
                val confidence = shapeConfidence(ordered, area)
                val candidate = Detection(
                    corners = Geometry.scalePoints(ordered, scale),
                    coverage = coverage,
                    confidence = confidence,
                    workingScale = scale,
                )
                if (best == null || candidate.score() > best.score()) best = candidate
                approx.release()
                contour2f.release()
            }
            return best
        } finally {
            working.release()
            gray.release()
            blurred.release()
            edges.release()
            contours.forEach { it.release() }
        }
    }

    private fun Detection.score(): Double = confidence * (0.5 + 0.5 * coverage)

    /**
     * Regularity score: penalises quads that are extremely thin or have
     * grossly unequal opposite edges (typical of false contour matches).
     */
    private fun shapeConfidence(quad: List<Point>, area: Double): Double {
        val (tl, tr, br, bl) = quad
        val top = dist(tl, tr)
        val bottom = dist(bl, br)
        val left = dist(tl, bl)
        val right = dist(tr, br)
        val widthRatio = minOf(top, bottom) / maxOf(top, bottom).coerceAtLeast(1e-6)
        val heightRatio = minOf(left, right) / maxOf(left, right).coerceAtLeast(1e-6)
        val aspect = maxOf(top, bottom) / maxOf(left, right).coerceAtLeast(1e-6)
        val aspectPenalty = if (aspect > 4.0 || aspect < 0.25) 0.5 else 1.0
        val areaScore = (area / ((top + bottom) * (left + right) / 4.0).coerceAtLeast(1e-6))
            .coerceIn(0.0, 1.0)
        return (0.35 * widthRatio + 0.35 * heightRatio + 0.3 * areaScore) * aspectPenalty
    }

    private val CvType8u: Int get() = org.opencv.core.CvType.CV_8UC1

    private fun workingImage(source: Mat, maxEdge: Int): Mat {
        val srcEdge = maxOf(source.cols(), source.rows())
        if (srcEdge <= maxEdge) return source.clone()
        val out = Mat()
        val scale = maxEdge.toDouble() / srcEdge
        Imgproc.resize(
            source,
            out,
            Size(
                maxOf(1, Math.round(source.cols() * scale).toInt()).toDouble(),
                maxOf(1, Math.round(source.rows() * scale).toInt()).toDouble(),
            ),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        return out
    }

    private fun grayCode(source: Mat): Int = when (source.channels()) {
        4 -> Imgproc.COLOR_BGRA2GRAY
        else -> Imgproc.COLOR_BGR2GRAY
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
