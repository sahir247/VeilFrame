package com.veilframe.app.cv.geometry

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * Geometry primitives shared by document scanning, QR recovery and artwork /
 * label / poster correction.
 *
 * Corner order convention everywhere in VeilFrame CV: TL, TR, BR, BL.
 */
object Geometry {

    /**
     * Orders 4 arbitrary points into canonical TL, TR, BR, BL order.
     * Uses relative-angle clockwise sorting around polygon centroid to guarantee
     * continuous non-inverting boundary traversal without branch-cut risks.
     */
    fun orderCorners(points: List<Point>): List<Point> {
        require(points.size == 4) { "expected 4 corners, got ${points.size}" }
        val cx = points.sumOf { it.x } / 4.0
        val cy = points.sumOf { it.y } / 4.0

        // Reference vector from centroid to points[0]
        val p0 = points[0]
        val uX = p0.x - cx
        val uY = p0.y - cy

        // Sort all 4 points in strict clockwise order relative to p0
        // In screen coordinates (+x right, +y down): cross product > 0 is clockwise
        val clockwise = points.sortedBy { pt ->
            val vX = pt.x - cx
            val vY = pt.y - cy
            val cross = uX * vY - uY * vX
            val dot = uX * vX + uY * vY
            var angle = kotlin.math.atan2(cross, dot)
            if (angle < 0.0) angle += 2.0 * Math.PI
            angle
        }

        // Top-left is the point minimizing distance to bounding box top-left (minX, minY)
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val tlIndex = clockwise.indices.minByOrNull { i ->
            val pt = clockwise[i]
            val dx = pt.x - minX
            val dy = pt.y - minY
            dx * dx + dy * dy
        } ?: 0

        val tl = clockwise[tlIndex]
        val tr = clockwise[(tlIndex + 1) % 4]
        val br = clockwise[(tlIndex + 2) % 4]
        val bl = clockwise[(tlIndex + 3) % 4]
        return listOf(tl, tr, br, bl)
    }

    /** Maps corners detected on a downscaled working image back to source scale. */
    fun scalePoints(points: List<Point>, scale: Double): List<Point> =
        points.map { Point(it.x * scale, it.y * scale) }

    /** Width/height of the perspective output: bounded max edge lengths of the quad. */
    fun outputSizeFor(
        corners: List<Point>,
        maxDimensionCap: Int = 4096,
        minDimension: Int = 100
    ): Pair<Int, Int> {
        val (tl, tr, br, bl) = ordered(corners)
        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        var width = maxOf(widthTop, widthBottom)
        var height = maxOf(heightLeft, heightRight)

        // Protect against extreme skew or malformed aspect ratios
        val aspect = width / height.coerceAtLeast(1.0)
        if (aspect > 6.0) width = height * 6.0
        if (aspect < 1.0 / 6.0) height = width * 6.0

        // Cap dimensions to prevent OOM on high-resolution sensors
        val maxDim = maxOf(width, height)
        if (maxDim > maxDimensionCap) {
            val scale = maxDimensionCap.toDouble() / maxDim
            width *= scale
            height *= scale
        }

        val outW = Math.round(width).toInt().coerceIn(minDimension, maxDimensionCap)
        val outH = Math.round(height).toInt().coerceIn(minDimension, maxDimensionCap)
        return outW to outH
    }

    /** Validates that 4 points form a non-degenerate, strictly convex quadrilateral. */
    fun isValidQuad(corners: List<Point>): Boolean {
        if (corners.size != 4) return false
        val pts = ordered(corners)
        val area = quadArea(pts)
        if (area <= 100.0) return false

        // Check that all 4 internal angles are reasonable for a document (between 35° and 145°)
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]
            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val len1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
            val len2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)
            if (len1 < 5.0 || len2 < 5.0) return false
            val dot = (v1x * v2x + v1y * v2y) / (len1 * len2)
            if (kotlin.math.abs(dot) > 0.88) return false
        }
        return true
    }

    fun perspectiveTransform(src: List<Point>, dst: List<Point>): Mat {
        require(src.size == 4 && dst.size == 4)
        val srcMat = MatOfPoint2f(*src.toTypedArray())
        val dstMat = MatOfPoint2f(*dst.toTypedArray())
        try {
            return Imgproc.getPerspectiveTransform(srcMat, dstMat)
        } finally {
            srcMat.release()
            dstMat.release()
        }
    }

    /** Area of the quad defined by [corners] (shoelace formula). */
    fun quadArea(corners: List<Point>): Double {
        val pts = ordered(corners)
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(area) / 2.0
    }

    /** Point ordering with the project convention TL, TR, BR, BL. */
    fun ordered(points: List<Point>): List<Point> = orderCorners(points)

    /**
     * Refines corner positions with sub-pixel precision using gradient descent
     * ([Imgproc.cornerSubPix]).
     *
     * @param image Grayscale or color source Mat.
     * @param corners 4 initial corner estimates.
     * @param winSize Half of the side length of the search window (default 5x5).
     * @param maxDrift Maximum allowed movement in pixels before falling back to original estimate (default 15px).
     * @return Refined sub-pixel corners.
     */
    fun refineCornersSubPix(
        image: Mat,
        corners: List<Point>,
        winSize: org.opencv.core.Size = org.opencv.core.Size(5.0, 5.0),
        maxDrift: Double = 15.0
    ): List<Point> {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable || image.nativeObj == 0L || corners.size != 4) return corners

        // Ensure single-channel grayscale input for cornerSubPix
        val gray = if (image.channels() == 1) {
            image
        } else {
            val g = Mat()
            Imgproc.cvtColor(image, g, Imgproc.COLOR_BGR2GRAY)
            g
        }

        try {
            val w = gray.cols().toDouble()
            val h = gray.rows().toDouble()
            val marginX = winSize.width + 1.0
            val marginY = winSize.height + 1.0

            // Clamp initial corners inside search window margins
            val clamped = corners.map { pt ->
                Point(
                    pt.x.coerceIn(marginX, w - 1.0 - marginX),
                    pt.y.coerceIn(marginY, h - 1.0 - marginY)
                )
            }

            val cornersMat = MatOfPoint2f(*clamped.toTypedArray())
            val criteria = org.opencv.core.TermCriteria(
                org.opencv.core.TermCriteria.EPS + org.opencv.core.TermCriteria.COUNT,
                30,
                0.05
            )

            try {
                Imgproc.cornerSubPix(
                    gray,
                    cornersMat,
                    winSize,
                    org.opencv.core.Size(-1.0, -1.0),
                    criteria
                )
                val refinedArray = cornersMat.toArray()
                // Safeguard: verify drift distance from original points
                return corners.indices.map { i ->
                    val orig = corners[i]
                    val ref = refinedArray[i]
                    val dist = distance(orig, ref)
                    if (dist <= maxDrift && !ref.x.isNaN() && !ref.y.isNaN()) {
                        ref
                    } else {
                        orig
                    }
                }
            } catch (_: Throwable) {
                return corners
            } finally {
                cornersMat.release()
            }
        } finally {
            if (gray !== image) {
                gray.release()
            }
        }
    }

    /**
     * Estimates the dominant text line skew angle in degrees using morphological
     * baseline elongation and Hough line transform.
     * Returns angle in degrees in range [-maxAngle, maxAngle], or 0.0 if no dominant
     * text lines are detected.
     */
    fun detectSkewAngle(
        image: Mat,
        maxAngleDegrees: Double = 15.0
    ): Double {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable || image.nativeObj == 0L) return 0.0

        val gray = if (image.channels() == 1) {
            image
        } else {
            val g = Mat()
            Imgproc.cvtColor(image, g, Imgproc.COLOR_BGR2GRAY)
            g
        }

        val edges = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(25.0, 1.0))
        val textBands = Mat()
        val lines = Mat()

        try {
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            Imgproc.morphologyEx(edges, textBands, Imgproc.MORPH_CLOSE, kernel)

            Imgproc.HoughLinesP(
                textBands,
                lines,
                1.0,
                Math.PI / 180.0,
                80,
                50.0,
                15.0
            )

            if (lines.rows() == 0) return 0.0

            var totalWeight = 0.0
            var weightedAngleSum = 0.0
            val maxAngleRad = Math.toRadians(maxAngleDegrees)

            for (i in 0 until lines.rows()) {
                val vec = lines.get(i, 0) ?: continue
                val x1 = vec[0]
                val y1 = vec[1]
                val x2 = vec[2]
                val y2 = vec[3]
                val dx = x2 - x1
                val dy = y2 - y1
                val length = kotlin.math.sqrt(dx * dx + dy * dy)
                if (length < 20.0) continue

                val angleRad = kotlin.math.atan2(dy, dx)
                if (kotlin.math.abs(angleRad) <= maxAngleRad) {
                    weightedAngleSum += Math.toDegrees(angleRad) * length
                    totalWeight += length
                }
            }

            if (totalWeight <= 0.0) return 0.0
            return weightedAngleSum / totalWeight
        } catch (_: Throwable) {
            return 0.0
        } finally {
            edges.release()
            kernel.release()
            textBands.release()
            lines.release()
            if (gray !== image) gray.release()
        }
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
