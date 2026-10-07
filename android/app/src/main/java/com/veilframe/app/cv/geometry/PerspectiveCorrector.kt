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
     *
     * @param outputSize target size; when null, derived from the quad edge lengths.
     */
    fun correct(
        image: Mat,
        sourceCorners: List<Point>,
        outputSize: Pair<Int, Int>? = null,
        interpolation: Int = Imgproc.INTER_LINEAR,
    ): Mat {
        require(!image.empty()) { "image is empty" }
        val corners = Geometry.ordered(sourceCorners)
        val (width, height) = outputSize ?: Geometry.outputSizeFor(corners)
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
                Core.BORDER_REPLICATE,
            )
            return out
        } finally {
            transform.release()
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
