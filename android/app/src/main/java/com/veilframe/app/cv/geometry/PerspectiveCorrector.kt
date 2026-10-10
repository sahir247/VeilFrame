package com.veilframe.app.cv.geometry

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * PerspectiveCorrector — reusable perspective warp primitive.
 *
 * Used by the document scanner, QR recovery, photographed artwork, labels,
 * posters and receipts. Detection always happens on a downscaled working image;
 * corner coordinates are mapped back to full resolution BEFORE the warp so the
 * output keeps source quality without enormous intermediate buffers.
 */
object PerspectiveCorrector {

    operator fun invoke(): PerspectiveCorrector = this

    fun warp(
        image: Mat,
        detection: QuadDetector.Detection,
        outputSize: Pair<Int, Int>? = null,
        interpolation: Int = Imgproc.INTER_LINEAR,
    ): Mat = correct(image, detection.corners, outputSize, interpolation)

    fun warp(
        image: Mat,
        corners: List<Point>,
        outputSize: Pair<Int, Int>? = null,
        interpolation: Int = Imgproc.INTER_LINEAR,
    ): Mat = correct(image, corners, outputSize, interpolation)

    /**
     * Warps the quadrilateral [sourceCorners] of [image] to a rectangle.
     * Optionally refines corners using sub-pixel gradient descent ([refineSubPix])
     * and performs auto-skew text baseline leveling ([autoSkewCorrection]).
     *
     * @param outputSize target size; when null, derived from the quad edge lengths.
     */
    fun correct(
        image: Mat,
        sourceCorners: List<Point>,
        outputSize: Pair<Int, Int>? = null,
        interpolation: Int = Imgproc.INTER_LINEAR,
        refineSubPix: Boolean = true,
        autoSkewCorrection: Boolean = true,
    ): Mat {
        require(!image.empty()) { "image is empty" }
        val orderedCorners = Geometry.ordered(sourceCorners)
        val corners = if (refineSubPix) {
            Geometry.refineCornersSubPix(image, orderedCorners)
        } else {
            orderedCorners
        }
        val (width, height) = outputSize ?: run {
            val estimated = PerspectiveMetrology.estimateRealDimensions(
                corners,
                image.cols(),
                image.rows()
            ).snapToStandardFormat()
            val (wDouble, hDouble) = estimated.toPixelDimensions(corners)
            var w = Math.round(wDouble).toInt()
            var h = Math.round(hDouble).toInt()
            val maxCap = 4096
            val maxEdge = maxOf(w, h)
            if (maxEdge > maxCap) {
                val scale = maxCap.toDouble() / maxEdge
                w = Math.round(w * scale).toInt()
                h = Math.round(h * scale).toInt()
            }
            w.coerceIn(100, maxCap) to h.coerceIn(100, maxCap)
        }
        require(width > 0 && height > 0) { "invalid output size ${width}x$height" }

        val dstCorners = listOf(
            Point(0.0, 0.0),
            Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0),
            Point(0.0, height - 1.0),
        )
        val transform = Geometry.perspectiveTransform(corners, dstCorners)
        val out = Mat()
        try {
            Imgproc.warpPerspective(
                image,
                out,
                transform,
                Size(width.toDouble(), height.toDouble()),
                interpolation,
                Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(255.0, 255.0, 255.0)
            )
            if (autoSkewCorrection) {
                val leveled = correctSkew(out)
                if (leveled !== out) {
                    out.release()
                    return leveled
                }
            }
            return out
        } finally {
            transform.release()
        }
    }

    /**
     * Corrects subtle residual text rotation on a rectangular warped document
     * by detecting the dominant text line angle and counter-rotating.
     */
    fun correctSkew(
        warped: Mat,
        maxAngleDegrees: Double = 15.0,
        minCorrectionThreshold: Double = 0.4
    ): Mat {
        if (warped.empty()) return warped
        val skewAngle = Geometry.detectSkewAngle(warped, maxAngleDegrees)
        if (kotlin.math.abs(skewAngle) < minCorrectionThreshold || kotlin.math.abs(skewAngle) > maxAngleDegrees) {
            return warped
        }

        val center = Point(warped.cols() / 2.0, warped.rows() / 2.0)
        val rotMatrix = Imgproc.getRotationMatrix2D(center, skewAngle, 1.0)
        val leveled = Mat()
        try {
            Imgproc.warpAffine(
                warped,
                leveled,
                rotMatrix,
                warped.size(),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE
            )
            return leveled
        } finally {
            rotMatrix.release()
        }
    }

    /**
     * Convenience for the detection-then-warp flow when the caller holds corners
     * in SOURCE coordinates (the [com.veilframe.app.cv.geometry.QuadDetector]
     * contract). Kept as a named alias for readability.
     */
    fun correctWithSourceCorners(
        fullResolutionImage: Mat,
        sourceCorners: List<Point>,
        outputSize: Pair<Int, Int>? = null,
    ): Mat = correct(fullResolutionImage, sourceCorners, outputSize)
}
