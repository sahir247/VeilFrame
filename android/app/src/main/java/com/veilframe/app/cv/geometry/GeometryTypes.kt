package com.veilframe.app.cv.geometry

import kotlin.math.atan2
import kotlin.math.hypot

data class Point(val x: Double, val y: Double) {
    constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())
}

data class Line(val from: Point, val to: Point) {
    fun norm(): Double = norm(from, to)
}

fun norm(p1: Point, p2: Point): Double {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    return hypot(dx, dy)
}

data class ImageSize(val width: Double, val height: Double) {
    constructor(width: Int, height: Int) : this(width.toDouble(), height.toDouble())
}

data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point
) {
    fun edges(): List<Line> {
        return listOf(
            Line(topLeft, topRight),
            Line(topRight, bottomRight),
            Line(bottomRight, bottomLeft),
            Line(bottomLeft, topLeft)
        )
    }

    /**
     * Returns true when the four corners form a strictly convex polygon with
     * correct winding order (topLeft -> topRight -> bottomRight -> bottomLeft
     * clockwise in screen coordinates where y increases downward).
     */
    fun isConvex(): Boolean {
        val pts = listOf(topLeft, topRight, bottomRight, bottomLeft)
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 0.0) return false
        }
        return true
    }

    fun rotate90(iterations: Int, imageSize: ImageSize): Quad {
        val rotatedPoints = listOf(
            rotate90(topLeft, imageSize, iterations),
            rotate90(topRight, imageSize, iterations),
            rotate90(bottomRight, imageSize, iterations),
            rotate90(bottomLeft, imageSize, iterations)
        )
        return createQuad(rotatedPoints)
    }

    private fun rotate90(p: Point, imageSize: ImageSize, iterations: Int): Point {
        val width = imageSize.width
        val height = imageSize.height
        return when (iterations % 4) {
            1 -> Point(height - p.y, p.x)         // 90°
            2 -> Point(width - p.x, height - p.y) // 180°
            3 -> Point(p.y, width - p.x)          // 270°
            else -> p                             // 0°
        }
    }
}

fun createQuad(vertices: List<Point>): Quad {
    require(vertices.size == 4) { "vertices must contain exactly 4 points" }

    // Centroid of the points
    val cx = vertices.map { it.x }.average()
    val cy = vertices.map { it.y }.average()

    // Sort by angle from centroid (clockwise)
    val sorted = vertices.sortedWith(compareBy {
        atan2(it.y - cy, it.x - cx)
    })

    return Quad(sorted[0], sorted[1], sorted[2], sorted[3])
}

fun Quad.scaledTo(fromWidth: Double, fromHeight: Double, toWidth: Double, toHeight: Double): Quad {
    val scaleX = toWidth / fromWidth
    val scaleY = toHeight / fromHeight
    return Quad(
        topLeft = topLeft.scaled(scaleX, scaleY),
        topRight = topRight.scaled(scaleX, scaleY),
        bottomRight = bottomRight.scaled(scaleX, scaleY),
        bottomLeft = bottomLeft.scaled(scaleX, scaleY)
    )
}

fun Quad.scaledTo(fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int): Quad {
    return scaledTo(
        fromWidth.toDouble(),
        fromHeight.toDouble(),
        toWidth.toDouble(),
        toHeight.toDouble()
    )
}

fun Point.scaled(scaleX: Double, scaleY: Double): Point {
    return Point(x * scaleX, y * scaleY)
}

fun Point.toCv(): org.opencv.core.Point = org.opencv.core.Point(x, y)
fun org.opencv.core.Point.toPoint(): Point = Point(x, y)
fun org.opencv.core.Size.toImageSize(): ImageSize = ImageSize(width, height)
fun Quad.toList(): List<Point> = listOf(topLeft, topRight, bottomRight, bottomLeft)
fun Quad.toCvList(): List<org.opencv.core.Point> = listOf(topLeft.toCv(), topRight.toCv(), bottomRight.toCv(), bottomLeft.toCv())

fun List<org.opencv.core.Point>.toQuad(): Quad {
    require(size == 4) { "Must contain 4 points to convert to Quad" }
    return createQuad(map { it.toPoint() })
}
