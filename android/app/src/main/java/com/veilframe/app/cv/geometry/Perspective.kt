package com.veilframe.app.cv.geometry

import com.veilframe.app.cv.document.ColorMode
import com.veilframe.app.cv.document.enhanceCapturedImage
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 3D single-view metrology for document scanner perspective correction.
 * Reconstructs true physical document aspect ratio using vanishing points and
 * camera rays, then snaps to standard ISO/ANSI paper formats (A4, Letter, Legal, etc.).
 *
 * Grounded in Hartley & Zisserman Multiple View Geometry / Criminisi single-view metrology.
 */
data class Vector3D(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Vector3D) = Vector3D(x - other.x, y - other.y, z - other.z)
    operator fun times(t: Double) = Vector3D(x * t, y * t, z * t)
    fun dotProduct(other: Vector3D) = x * other.x + y * other.y + z * other.z
    fun crossProduct(other: Vector3D) = Vector3D(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )
    fun norm() = sqrt(x * x + y * y + z * z)
}

data class CameraIntrinsics(
    /** Focal length in millimeters. */
    val focalLengthMm: Float,
    /** Sensor width in millimeters. */
    val sensorWidthMm: Float,
) {
    fun focalLengthInPixels(imageWidthInPixels: Int): Double =
        (focalLengthMm / sensorWidthMm * imageWidthInPixels).toDouble()
}

data class OpticalMeasures(
    val cameraIntrinsics: CameraIntrinsics,
    val subjectDistanceMm: Float? = null,
)

sealed class EstimatedDimensions {
    data class Physical(val widthMm: Double, val heightMm: Double) : EstimatedDimensions()
    data class Ratio(val width: Double, val height: Double) : EstimatedDimensions()

    val aspectRatio: Double get() = when (this) {
        is Physical -> heightMm / widthMm
        is Ratio -> height / width
    }

    fun snapToStandardFormat(
        ratioTolerance: Double = 0.04,
        dimensionTolerance: Double = 0.20,
    ): EstimatedDimensions {
        if (this !is Physical) return this

        // Normalize to portrait for format comparison
        val portrait = widthMm <= heightMm
        val (w, h) = if (portrait) widthMm to heightMm else heightMm to widthMm

        for (format in PaperFormats.all) {
            val (fw, fh) = format.widthMm to format.heightMm
            val ratioError = abs((h / w) - (fh / fw)) / (fh / fw)
            val dimError = maxOf(abs(w - fw) / fw, abs(h - fh) / fh)
            if (ratioError < ratioTolerance && dimError < dimensionTolerance) {
                return if (portrait) format else Physical(format.heightMm, format.widthMm)
            }
        }

        return this
    }

    fun toPixelDimensions(corners: List<Point>): Pair<Double, Double> {
        val (tl, tr, br, bl) = Geometry.ordered(corners)
        val w = (hypot(tr.x - tl.x, tr.y - tl.y) + hypot(br.x - bl.x, br.y - bl.y)) / 2.0
        val h = (hypot(bl.x - tl.x, bl.y - tl.y) + hypot(br.x - tr.x, br.y - tr.y)) / 2.0
        val projectedArea = w * h

        val ratio = aspectRatio.coerceIn(0.15, 6.5)
        val targetWidth = sqrt(projectedArea / ratio)
        val targetHeight = targetWidth * ratio
        return Pair(targetWidth, targetHeight)
    }
}

object PaperFormats {
    val A3 = EstimatedDimensions.Physical(297.0, 420.0)
    val A4 = EstimatedDimensions.Physical(210.0, 297.0)
    val A5 = EstimatedDimensions.Physical(148.0, 210.0)
    val Letter = EstimatedDimensions.Physical(215.9, 279.4)
    val Legal = EstimatedDimensions.Physical(215.9, 355.6)

    val all = listOf(A4, Letter, Legal, A5, A3)
}

object PerspectiveMetrology {

    /**
     * Estimates true width and height of the document using projective geometry,
     * correcting for perspective foreshortening.
     */
    fun estimateRealDimensions(
        corners: List<Point>,
        imageWidth: Int,
        imageHeight: Int,
        opticalMeasures: OpticalMeasures? = null,
    ): EstimatedDimensions {
        require(corners.size == 4) { "expected 4 corners" }
        val (tl, tr, br, bl) = Geometry.ordered(corners)

        fun averageSides(): EstimatedDimensions.Ratio {
            val w = (hypot(tr.x - tl.x, tr.y - tl.y) + hypot(br.x - bl.x, br.y - bl.y)) / 2.0
            val h = (hypot(bl.x - tl.x, bl.y - tl.y) + hypot(br.x - tr.x, br.y - tr.y)) / 2.0
            return EstimatedDimensions.Ratio(w, h)
        }

        fun toH(p: Point) = Vector3D(p.x, p.y, 1.0)
        fun lineThrough(p1: Point, p2: Point) = toH(p1).crossProduct(toH(p2))

        val v1h = lineThrough(tl, tr).crossProduct(lineThrough(bl, br))
        val v2h = lineThrough(tl, bl).crossProduct(lineThrough(tr, br))

        // Degenerate case: one pair of sides is parallel
        if (v1h.z.absoluteValue < 1e-6 || v2h.z.absoluteValue < 1e-6) {
            return averageSides()
        }

        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0

        val v1 = Point(v1h.x / v1h.z - cx, v1h.y / v1h.z - cy)
        val v2 = Point(v2h.x / v2h.z - cx, v2h.y / v2h.z - cy)

        val f = if (opticalMeasures != null) {
            opticalMeasures.cameraIntrinsics.focalLengthInPixels(max(imageWidth, imageHeight))
        } else {
            val f2 = -(v1.x * v2.x + v1.y * v2.y)
            if (f2 <= 0.0) return averageSides()
            sqrt(f2)
        }

        if (f > max(imageWidth, imageHeight) * 1.5) {
            return averageSides()
        }

        val d1 = Vector3D(v1.x, v1.y, f)
        val d2 = Vector3D(v2.x, v2.y, f)
        val n = d1.crossProduct(d2)

        fun ray(p: Point) = Vector3D((p.x - cx) / f, (p.y - cy) / f, 1.0)

        val subjectDistance = opticalMeasures?.subjectDistanceMm?.toDouble()
        val scale: Double? = if (subjectDistance != null) {
            val centerX = (tl.x + tr.x + bl.x + br.x) / 4.0
            val centerY = (tl.y + tr.y + bl.y + br.y) / 4.0
            val centerRay = ray(Point(centerX, centerY)).let { it * (1.0 / it.norm()) }
            val cosAngle = centerRay.dotProduct(n).absoluteValue
            if (cosAngle < 0.1) null else subjectDistance * cosAngle
        } else null

        fun corner3D(p: Point): Vector3D {
            val r = ray(p)
            val denom = n.dotProduct(r)
            if (denom.absoluteValue < 1e-6) return Vector3D(0.0, 0.0, 1.0)
            val t = if (scale != null) scale / denom else 1.0 / denom
            return r * t
        }

        val xTL = corner3D(tl)
        val xTR = corner3D(tr)
        val xBR = corner3D(br)
        val xBL = corner3D(bl)

        val realW = ((xTR - xTL).norm() + (xBR - xBL).norm()) / 2.0
        val realH = ((xBL - xTL).norm() + (xBR - xTR).norm()) / 2.0

        if (realW <= 0.0 || realH <= 0.0) return averageSides()

        return if (opticalMeasures != null && scale != null) {
            EstimatedDimensions.Physical(realW, realH)
        } else {
            EstimatedDimensions.Ratio(realW, realH)
        }
    }

    fun estimateRealDimensions(
        quad: Quad,
        imageWidth: Int,
        imageHeight: Int,
        opticalMeasures: OpticalMeasures? = null,
    ): EstimatedDimensions = estimateRealDimensions(
        listOf(quad.topLeft.toCv(), quad.topRight.toCv(), quad.bottomRight.toCv(), quad.bottomLeft.toCv()),
        imageWidth,
        imageHeight,
        opticalMeasures
    )
}

fun EstimatedDimensions.toPixelDimensions(quad: Quad): Pair<Double, Double> {
    val w = (norm(quad.topLeft, quad.topRight) + norm(quad.bottomLeft, quad.bottomRight)) / 2
    val h = (norm(quad.topLeft, quad.bottomLeft) + norm(quad.topRight, quad.bottomRight)) / 2
    val projectedArea = w * h

    val ratio = aspectRatio
    val targetWidth = sqrt(projectedArea / ratio)
    val targetHeight = targetWidth * ratio
    return Pair(targetWidth, targetHeight)
}

fun extractDocument(
    inputMat: Mat,
    quad: Quad,
    rotationDegrees: Int,
    colorMode: com.veilframe.app.cv.document.ColorMode,
    maxPixels: Long,
    opticalMeasures: OpticalMeasures? = null,
): Mat {
    val estimatedDimensions = PerspectiveMetrology.estimateRealDimensions(
        quad,
        inputMat.cols(),
        inputMat.rows(),
        opticalMeasures,
    ).snapToStandardFormat()
    val (targetWidth, targetHeight) = estimatedDimensions.toPixelDimensions(quad)
    val srcPoints = MatOfPoint2f(
        quad.topLeft.toCv(),
        quad.topRight.toCv(),
        quad.bottomRight.toCv(),
        quad.bottomLeft.toCv(),
    )
    val dstPoints = MatOfPoint2f(
        org.opencv.core.Point(0.0, 0.0),
        org.opencv.core.Point(targetWidth, 0.0),
        org.opencv.core.Point(targetWidth, targetHeight),
        org.opencv.core.Point(0.0, targetHeight)
    )
    var transform: Mat? = null
    var warped: Mat? = null
    var resized: Mat? = null
    var enhanced: Mat? = null
    try {
        val t = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        transform = t
        val w = Mat()
        warped = w
        val outputSize = Size(targetWidth, targetHeight)
        Imgproc.warpPerspective(inputMat, w, t, outputSize)

        val r = resizeForMaxPixels(w, maxPixels.toDouble())
        resized = r
        val e = com.veilframe.app.cv.document.enhanceCapturedImage(r, colorMode, maxPixels)
        enhanced = e
        return rotate(e, rotationDegrees)
    } finally {
        enhanced?.release()
        resized?.release()
        warped?.release()
        transform?.release()
        dstPoints.release()
        srcPoints.release()
    }
}

fun rotate(input: Mat, degrees: Int): Mat {
    val output = Mat()
    when ((degrees % 360 + 360) % 360) {
        0 -> input.copyTo(output)
        90 -> Core.rotate(input, output, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(input, output, Core.ROTATE_180)
        270 -> Core.rotate(input, output, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> throw IllegalArgumentException("Only 0, 90, 180, 270 degrees are supported")
    }
    return output
}

fun resizeForMaxPixels(img: Mat, maxPixels: Double, interpolation: Int = Imgproc.INTER_AREA): Mat {
    val origPixels = img.width() * img.height()
    if (origPixels <= maxPixels) {
        return img.clone()
    }
    val scale = sqrt(maxPixels / origPixels)
    val size = Size(img.width() * scale, img.height() * scale)
    val resizedImg = Mat()
    Imgproc.resize(img, resizedImg, size, 0.0, 0.0, interpolation)
    return resizedImg
}
