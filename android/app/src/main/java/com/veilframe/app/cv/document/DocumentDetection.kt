package com.veilframe.app.cv.document

import com.veilframe.app.cv.geometry.ContourOrientation
import com.veilframe.app.cv.geometry.ImageSize
import com.veilframe.app.cv.geometry.MinAreaRect
import com.veilframe.app.cv.geometry.Point
import com.veilframe.app.cv.geometry.Quad
import com.veilframe.app.cv.geometry.createQuad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

interface Mask {
    val width: Int
    val height: Int
    fun toMat(): Mat
}

enum class Mode {
    CAPTURE, IMPORT, LIVE_ANALYSIS
}

fun detectDocumentQuad(mask: Mask, originalSize: ImageSize, mode: Mode): Quad? {
    val mat = mask.toMat()
    if (mat.empty()) return null
    // Best thresholds on test dataset: {0.95=146, 0.85=39, 0.75=35, 0.90=8, 0.70=1, 0.35=1}
    val thresholds =
        if (mode != Mode.CAPTURE) listOf(0.9) else listOf(0.5, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)
    var vertices = findQuadFromOrientationWithAdaptiveThreshold(mat, originalSize, thresholds)
        ?.map { Point(it.x, it.y) }

    if (vertices == null && mode == Mode.CAPTURE) {
        // Fallback: bounding rectangle
        val biggest = biggestContour(mat)
        if (biggest != null) {
            val polygon = biggest.toList()
            biggest.release()
            vertices = MinAreaRect.minAreaRect(polygon, mask.width, mask.height)?.map { Point(it.x, it.y) }
        }
    }
    val maskSize = ImageSize(mask.width, mask.height)
    return if (vertices?.size == 4 && vertices.all { isInsideImage(it, maskSize) })
        createQuad(vertices)
    else null
}

fun findQuadFromOrientationWithAdaptiveThreshold(
    maskMat: Mat, originalSize: ImageSize, thresholds: List<Double>
): List<org.opencv.core.Point>? {
    val probmapU8 = Mat()
    val probmap = maskMat
    probmap.convertTo(probmapU8, CvType.CV_8U, 255.0)
    val probmapSmooth = Mat()
    Imgproc.GaussianBlur(probmapU8, probmapSmooth, Size(3.0, 3.0), 0.0)

    var bestQuad: List<org.opencv.core.Point>? = null
    var bestScore = 0.0
    for (thr in thresholds) {
        val bin = Mat()
        Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        val quad = findQuadFromOrientation(bin, originalSize)
        if (quad != null) {
            val probFloat = Mat()
            probmap.convertTo(probFloat, CvType.CV_32F)
            val score = scoreQuadAgainstProbmap(quad.map { Point(it.x, it.y) }, probFloat, minQuadAreaRatio = 0.02)
            probFloat.release()
            if (score > bestScore) {
                bestScore = score
                bestQuad = quad
            }
        }
        bin.release()
    }

    probmapSmooth.release()
    probmapU8.release()
    return bestQuad
}

fun isInsideImage(p: Point, imageSize: ImageSize): Boolean {
    return p.x >= 0 && p.x <= imageSize.width
       && p.y >= 0 && p.y <= imageSize.height
}

fun findQuadFromOrientation(maskMat: Mat, originalSize: ImageSize): List<org.opencv.core.Point>? {
    val contour = biggestContour(maskMat)
    contour ?: return null

    val scaleX = originalSize.width / maskMat.size().width
    val scaleY = originalSize.height / maskMat.size().height

    // The mask may have a different width/height ratio than the original image.
    // It's crucial for angles that the width/height ratio is the one of the original image.
    val result = ContourOrientation.findQuadFromContourOrientation(
        contour.toList().map { org.opencv.core.Point(it.x * scaleX, it.y * scaleY) }
    )?.map { org.opencv.core.Point(it.x / scaleX, it.y / scaleY) }
    contour.release()
    return result
}

fun biggestContour(mat: Mat): MatOfPoint? {
    val refinedMask = refineMask(mat)

    val blurred = Mat()
    Imgproc.GaussianBlur(refinedMask, blurred, Size(5.0, 5.0), 0.0)
    refinedMask.release()

    val edges = Mat()
    Imgproc.Canny(blurred, edges, 75.0, 200.0)
    blurred.release()

    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
    hierarchy.release()
    edges.release()

    var biggest: MatOfPoint? = null
    var maxArea = 0.0

    for (contour in contours) {
        val area = abs(Imgproc.contourArea(contour))
        if (area > maxArea) {
            maxArea = area
            biggest?.release()
            biggest = contour
        } else {
            contour.release()
        }
    }
    return biggest
}

/**
 * Applies morphological operations to improve a document mask.
 */
fun refineMask(original: Mat): Mat {
    // Step 0: Ensure the mask is binary (just in case)
    val binaryMask = Mat()
    Imgproc.threshold(original, binaryMask, 128.0, 255.0, Imgproc.THRESH_BINARY)

    // Step 1: Closing (fills small holes)
    val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val closed = Mat()
    Imgproc.morphologyEx(binaryMask, closed, Imgproc.MORPH_CLOSE, kernelClose)
    kernelClose.release()

    // Step 2: Gentle opening (removes isolated noise)
    val kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val opened = Mat()
    Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, kernelOpen)
    kernelOpen.release()

    binaryMask.release()
    closed.release()

    return opened
}

// Fill polygon and return binary mask (0/1)
fun makePolygonMask(size: Size, polygon: List<Point>): Mat {
    val mask = Mat.zeros(size, CvType.CV_8U)
    val pts = MatOfPoint(*polygon.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray())
    Imgproc.fillPoly(mask, listOf(pts), org.opencv.core.Scalar(1.0))
    pts.release()
    return mask
}

// Compute score between quad and probmap
fun scoreQuadAgainstProbmap(quad: List<Point>, probmap: Mat, minQuadAreaRatio: Double = 0.02): Double {
    val mask = makePolygonMask(probmap.size(), quad)
    val maskFloat = Mat()
    mask.convertTo(maskFloat, CvType.CV_32F)
    mask.release()

    val masked = Mat()
    Core.multiply(probmap, maskFloat, masked)
    val meanProb = Core.sumElems(masked).`val`[0] / Core.sumElems(maskFloat).`val`[0]
    val areaRatio = Core.sumElems(maskFloat).`val`[0] / (probmap.rows() * probmap.cols())
    masked.release()
    maskFloat.release()

    return if (areaRatio < minQuadAreaRatio) 0.0 else meanProb * (0.7 + 0.3 * areaRatio)
}
