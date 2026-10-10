package com.veilframe.app.cv.geometry

import org.opencv.core.Core
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
    ): Detection? = detectWithBuffers(
        source = source,
        blurredMat = null,
        edgesMat = null,
        workingMaxEdge = workingMaxEdge,
        minCoverage = minCoverage,
        cannyLow = cannyLow,
        cannyHigh = cannyHigh,
        context = context,
    )

    /**
     * Detects the dominant document-like quad using optional pre-allocated buffers.
     * When [blurredMat] and [edgesMat] are provided, intermediate Mat allocations are eliminated.
     */
    fun detectWithBuffers(
        source: Mat,
        blurredMat: Mat? = null,
        edgesMat: Mat? = null,
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
        val isAlreadyWorkingRes = maxOf(source.cols(), source.rows()) <= workingMaxEdge
        val working = if (isAlreadyWorkingRes) source else workingImage(source, workingMaxEdge)
        val scale = source.cols().toDouble() / working.cols()

        val isWorkingGray = working.channels() == 1
        val gray = if (isWorkingGray) working else Mat()
        val blurred = blurredMat ?: Mat()
        val edges = edgesMat ?: Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            if (!isWorkingGray) {
                Imgproc.cvtColor(working, gray, grayCode(working))
            }
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

            // Sort contours descending by area to check the most prominent document candidates first
            val sortedContours = contours.sortedByDescending { Imgproc.contourArea(it) }

            for (contour in sortedContours) {
                // B6: noisy frames yield thousands of contours; stay cancellable.
                context?.ensureActive()
                val contourArea = Imgproc.contourArea(contour)
                if (contourArea < workingArea * minCoverage) continue

                val contourPts = contour.toList()

                // Cascade 1: FairScan ContourOrientation (dominant side orientation fitting & line intersection)
                var candidatePoints: List<Point>? = ContourOrientation.findQuadFromContourOrientation(contourPts)
                if (candidatePoints != null && (!Geometry.isValidQuad(candidatePoints) || !isInsideWorking(candidatePoints, working.cols(), working.rows()))) {
                    candidatePoints = null
                }

                // Cascade 2: Multi-epsilon approxPolyDP
                if (candidatePoints == null) {
                    val contour2f = MatOfPoint2f(*contourPts.toTypedArray())
                    val arcLen = Imgproc.arcLength(contour2f, true)
                    for (epsRatio in doubleArrayOf(0.02, 0.015, 0.03, 0.04)) {
                        val approx = MatOfPoint2f()
                        Imgproc.approxPolyDP(contour2f, approx, epsRatio * arcLen, true)
                        val pts = approx.toList()
                        approx.release()
                        if (pts.size == 4) {
                            val approxMat = MatOfPoint(*pts.toTypedArray())
                            val isConvex = Imgproc.isContourConvex(approxMat)
                            approxMat.release()
                            if (isConvex && Geometry.isValidQuad(pts) && isInsideWorking(pts, working.cols(), working.rows())) {
                                candidatePoints = pts
                                break
                            }
                        }
                    }
                    contour2f.release()
                }

                // Cascade 3: MinAreaRect bounding quad fallback for high-coverage contours
                if (candidatePoints == null && contourArea > workingArea * 0.15) {
                    val minRect = MinAreaRect.minAreaRect(contourPts, working.cols(), working.rows())
                    if (minRect != null && minRect.size == 4 && Geometry.isValidQuad(minRect)) {
                        candidatePoints = minRect
                    }
                }

                if (candidatePoints == null || candidatePoints.size != 4) continue

                val ordered = Geometry.ordered(candidatePoints)
                val area = Geometry.quadArea(ordered)
                val coverage = area / workingArea
                // Reject quads covering > 98% of the frame (camera frame border contours)
                if (coverage > 0.98 || coverage < minCoverage) continue

                val confidence = shapeConfidence(ordered, area)
                val candidate = Detection(
                    corners = Geometry.scalePoints(ordered, scale),
                    coverage = coverage,
                    confidence = confidence,
                    workingScale = scale,
                )
                if (best == null || candidate.score() > best.score()) {
                    best = candidate
                }
            }
            return best
        } finally {
            if (working !== source) working.release()
            if (gray !== working) gray.release()
            if (blurredMat == null) blurred.release()
            if (edgesMat == null) edges.release()
            contours.forEach { it.release() }
        }
    }

    private fun isInsideWorking(pts: List<Point>, w: Int, h: Int): Boolean =
        pts.all { it.x >= -5.0 && it.x <= w + 5.0 && it.y >= -5.0 && it.y <= h + 5.0 }

    private fun Detection.score(): Double = confidence * (0.5 + 0.5 * coverage)

    /**
     * Regularity score: penalises quads that are extremely thin, have
     * grossly unequal opposite edges, or deviate excessively from rectangular corners.
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
        val aspectPenalty = when {
            aspect in 0.5..2.5 -> 1.0 // Standard paper/card/receipt range
            aspect in 0.3..3.5 -> 0.8
            else -> 0.4
        }
        val areaScore = (area / ((top + bottom) * (left + right) / 4.0).coerceAtLeast(1e-6))
            .coerceIn(0.0, 1.0)

        // Corner orthogonality score: average cosine deviation from 90 degrees
        var orthogonalScore = 0.0
        val pts = listOf(tl, tr, br, bl)
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]
            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val l1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y).coerceAtLeast(1e-6)
            val l2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y).coerceAtLeast(1e-6)
            val cosAngle = kotlin.math.abs((v1x * v2x + v1y * v2y) / (l1 * l2))
            orthogonalScore += (1.0 - cosAngle).coerceIn(0.0, 1.0)
        }
        val avgOrthogonal = orthogonalScore / 4.0

        return (0.25 * widthRatio + 0.25 * heightRatio + 0.25 * areaScore + 0.25 * avgOrthogonal) * aspectPenalty
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

    /**
     * FairScan neural segmentation-driven quadrilateral detection.
     * Evaluates multi-threshold masks, extracts dominant contours,
     * fits edges via [ContourOrientation], and scores candidates directly against
     * the continuous probability map [segmentation].
     */
    fun detectFromSegmentation(
        segmentation: com.veilframe.app.cv.segmentation.DocumentSegmentation,
        originalWidth: Int,
        originalHeight: Int,
        isCaptureMode: Boolean = false
    ): List<Point>? {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) return null

        val mode = if (isCaptureMode) com.veilframe.app.cv.document.Mode.CAPTURE else com.veilframe.app.cv.document.Mode.LIVE_ANALYSIS
        val originalSize = ImageSize(originalWidth, originalHeight)
        val quadInMask = com.veilframe.app.cv.document.detectDocumentQuad(segmentation, originalSize, mode) ?: return null

        val scaledQuad = quadInMask.scaledTo(
            fromWidth = segmentation.width,
            fromHeight = segmentation.height,
            toWidth = originalWidth,
            toHeight = originalHeight
        )
        val pts = listOf(
            scaledQuad.topLeft.toCv(),
            scaledQuad.topRight.toCv(),
            scaledQuad.bottomRight.toCv(),
            scaledQuad.bottomLeft.toCv()
        )
        return Geometry.orderCorners(pts)
    }
}
