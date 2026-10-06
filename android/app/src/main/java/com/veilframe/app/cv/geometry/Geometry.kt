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

    /** Orders 4 arbitrary points into TL, TR, BR, BL. */
    fun orderCorners(points: List<Point>): List<Point> {
        require(points.size == 4) { "expected 4 corners, got ${points.size}" }
        val sorted = points.sortedWith(compareBy({ it.x + it.y }, { it.x - it.y }))
        val tl = sorted.first()
        val br = sorted.last()
        val rest = sorted.subList(1, 3)
        val (tr, bl) = if (rest[0].y < rest[1].y) rest[0] to rest[1] else rest[1] to rest[0]
        return listOf(tl, tr, br, bl)
    }

    /** Maps corners detected on a downscaled working image back to source scale. */
    fun scalePoints(points: List<Point>, scale: Double): List<Point> =
        points.map { Point(it.x * scale, it.y * scale) }

    /** Width/height of the perspective output: max edge lengths of the quad. */
    fun outputSizeFor(corners: List<Point>): Pair<Int, Int> {
        val (tl, tr, br, bl) = ordered(corners)
        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        val width = maxOf(widthTop, widthBottom)
        val height = maxOf(heightLeft, heightRight)
        return maxOf(1, Math.round(width).toInt()) to maxOf(1, Math.round(height).toInt())
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

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
