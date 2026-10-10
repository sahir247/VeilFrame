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

        val probMat = segmentation.toProbMat()
        if (probMat.empty()) return null

        val probU8 = Mat()
        probMat.convertTo(probU8, org.opencv.core.CvType.CV_8U, 255.0)

        val probSmooth = Mat()
        Imgproc.GaussianBlur(probU8, probSmooth, Size(3.0, 3.0), 0.0)

        val thresholds = if (!isCaptureMode) {
            listOf(0.9)
        } else {
            listOf(0.5, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)
        }

        var bestQuad: List<Point>? = null
        var bestScore = 0.0
        val maskWidth = segmentation.width.toDouble()
        val maskHeight = segmentation.height.toDouble()

        for (thr in thresholds) {
            val bin = Mat()
            Imgproc.threshold(probSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contour = findBiggestContour(bin)
            if (contour != null) {
                val scaleX = originalWidth.toDouble() / maskWidth
                val scaleY = originalHeight.toDouble() / maskHeight

                // Scale contour to original aspect ratio for accurate angle calculation
                val scaledContour = contour.map { Point(it.x * scaleX, it.y * scaleY) }
                val quadScaled = ContourOrientation.findQuadFromContourOrientation(scaledContour)
                if (quadScaled != null && quadScaled.size == 4) {
                    val quadInMask = quadScaled.map { Point(it.x / scaleX, it.y / scaleY) }
                    val score = scoreQuadAgainstProbmap(quadInMask, probMat, minQuadAreaRatio = 0.02)
                    if (score > bestScore) {
                        bestScore = score
                        bestQuad = quadScaled
                    }
                }
            }
            bin.release()
        }

        // Fallback: minAreaRect for capture mode
        if (bestQuad == null && isCaptureMode) {
            val bin = Mat()
            Imgproc.threshold(probSmooth, bin, 0.7 * 255.0, 255.0, Imgproc.THRESH_BINARY)
            val contour = findBiggestContour(bin)
            if (contour != null) {
                val scaleX = originalWidth.toDouble() / maskWidth
                val scaleY = originalHeight.toDouble() / maskHeight
                val scaledContour = contour.map { Point(it.x * scaleX, it.y * scaleY) }
                val minRect = MinAreaRect.minAreaRect(scaledContour, originalWidth, originalHeight)
                if (minRect != null && minRect.size == 4) {
                    bestQuad = minRect
                }
            }
            bin.release()
        }

        probSmooth.release()
        probU8.release()
        probMat.release()

        return if (bestQuad != null && bestQuad.size == 4) {
            val ordered = Geometry.orderCorners(bestQuad)
            if (Geometry.isValidQuad(ordered)) ordered else null
        } else {
            null
        }
    }

    private fun findBiggestContour(binMat: Mat): List<Point>? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
        hierarchy.release()

        var biggest: List<Point>? = null
        var maxArea = 0.0
        for (c in contours) {
            val area = kotlin.math.abs(Imgproc.contourArea(c))
            if (area > maxArea) {
                maxArea = area
                biggest = c.toList()
            }
            c.release()
        }
        return biggest
    }

    /**
     * Compute correspondence score between quad and continuous probability map.
     */
    fun scoreQuadAgainstProbmap(
        quad: List<Point>,
        probmap: Mat,
        minQuadAreaRatio: Double = 0.02
    ): Double {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable || probmap.empty() || quad.size != 4) return 0.0

        val mask = Mat.zeros(probmap.size(), org.opencv.core.CvType.CV_8U)
        val pts = MatOfPoint(*quad.toTypedArray())
        Imgproc.fillPoly(mask, listOf(pts), org.opencv.core.Scalar(1.0))

        val maskFloat = Mat()
        mask.convertTo(maskFloat, org.opencv.core.CvType.CV_32F)
        val masked = Mat()
        Core.multiply(probmap, maskFloat, masked)

        val sumMaskedScalar = Core.sumElems(masked)
        val sumMaskScalar = Core.sumElems(maskFloat)
        val sumMaskedVal: Double = sumMaskedScalar.`val`[0]
        val sumMaskVal: Double = sumMaskScalar.`val`[0]

        val meanProb: Double = if (sumMaskVal > 0.0) sumMaskedVal / sumMaskVal else 0.0
        val areaRatio: Double = sumMaskVal / (probmap.rows().toDouble() * probmap.cols().toDouble())

        mask.release()
        maskFloat.release()
        masked.release()
        pts.release()

        return if (areaRatio < minQuadAreaRatio) 0.0 else meanProb * (0.7 + 0.3 * areaRatio)
    }
}
