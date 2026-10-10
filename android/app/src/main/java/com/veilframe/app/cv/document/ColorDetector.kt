package com.veilframe.app.cv.document

import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.geometry.Geometry
import com.veilframe.app.cv.segmentation.DocumentSegmentation
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.CvType.CV_8UC1
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Color modes supported by the document scanner pipeline.
 */
enum class DocumentColorMode {
    COLOR,
    GRAYSCALE,
    BLACK_AND_WHITE,
    AUTO
}

/**
 * ColorDetector — ports FairScan's CIELab chroma & Gray World automatic color detection.
 * Determines whether a document contains colored elements (e.g., logos, stamps, signatures, photos)
 * or is pure monochrome/grayscale text.
 */
object ColorDetector {

    /**
     * Determines whether the document inside [quad] contains color.
     *
     * @param img Full BGR OpenCV Mat.
     * @param quad 4 detected corner points in image coordinates.
     * @param segmentation Optional neural segmentation mask.
     * @param chromaThreshold CIELab chroma threshold (default 17.5).
     * @param proportionThreshold Minimum fraction of colored pixels to declare COLOR (default 0.0003).
     * @param luminanceMin Lower bound on L channel to reject dark ink (default 40.0).
     * @param luminanceMax Upper bound on L channel to reject white paper highlights (default 180.0).
     */
    fun autoColorMode(
        img: Mat,
        quad: List<Point>,
        segmentation: DocumentSegmentation? = null,
        chromaThreshold: Double = 17.5,
        proportionThreshold: Double = 0.0003,
        luminanceMin: Double = 40.0,
        luminanceMax: Double = 180.0
    ): DocumentColorMode {
        if (!CvRuntime.isNativeAvailable || img.nativeObj == 0L || img.empty() || quad.size != 4) {
            return DocumentColorMode.COLOR
        }

        // 0) Resize to work size for fast, consistent analysis
        val maxPixels = 1024.0 * 768.0
        val scale = if (img.cols() * img.rows() > maxPixels) {
            kotlin.math.sqrt(maxPixels / (img.cols() * img.rows()))
        } else {
            1.0
        }

        val workSize = Size((img.cols() * scale).roundToInt().toDouble(), (img.rows() * scale).roundToInt().toDouble())
        val resizedImg = Mat()
        Imgproc.resize(img, resizedImg, workSize, 0.0, 0.0, Imgproc.INTER_AREA)

        // 1) Compute document mask
        val docMask = createDocumentMask(resizedImg.size(), quad, scale, segmentation)

        val totalPixels = Core.countNonZero(docMask)
        if (totalPixels == 0) {
            docMask.release()
            resizedImg.release()
            return DocumentColorMode.GRAYSCALE
        }

        // 2) Apply Gray World white balance inside document mask
        val whiteBalanced = applyGrayWorldToDocument(resizedImg, docMask)

        // 3) Convert to CIELab
        val lab = Mat()
        Imgproc.cvtColor(whiteBalanced, lab, Imgproc.COLOR_BGR2Lab)

        // 4) Split Lab channels
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val luminance = channels[0]
        val a = channels[1]
        val b = channels[2]

        // 5) Compute Chroma = sqrt((a - 128)^2 + (b - 128)^2)
        val chromaMat = computeChroma(a, b)

        val colorMask = Mat()
        Imgproc.threshold(chromaMat, colorMask, chromaThreshold, 255.0, Imgproc.THRESH_BINARY)
        colorMask.convertTo(colorMask, CvType.CV_8U)

        // 6) Create luminance mask L in [luminanceMin..luminanceMax]
        val luminanceMask = Mat()
        Core.inRange(luminance, Scalar(luminanceMin), Scalar(luminanceMax), luminanceMask)

        // 7) Intersect colorMask & luminanceMask & docMask
        val tmp = Mat()
        Core.bitwise_and(colorMask, luminanceMask, tmp)

        val restrictedMask = Mat()
        Core.bitwise_and(tmp, docMask, restrictedMask)

        val coloredPixels = Core.countNonZero(restrictedMask)

        // Cleanup
        resizedImg.release()
        docMask.release()
        whiteBalanced.release()
        lab.release()
        channels.forEach { it.release() }
        chromaMat.release()
        colorMask.release()
        luminanceMask.release()
        tmp.release()
        restrictedMask.release()

        val proportion = coloredPixels.toDouble() / totalPixels.toDouble()
        return if (proportion > proportionThreshold) {
            DocumentColorMode.COLOR
        } else {
            DocumentColorMode.GRAYSCALE
        }
    }

    private fun computeChroma(a: Mat, b: Mat): Mat {
        val aFloat = Mat()
        val bFloat = Mat()
        a.convertTo(aFloat, CvType.CV_32F)
        b.convertTo(bFloat, CvType.CV_32F)

        val aShifted = Mat()
        val bShifted = Mat()
        Core.subtract(aFloat, Scalar(128.0), aShifted)
        Core.subtract(bFloat, Scalar(128.0), bShifted)

        val chroma = Mat()
        Core.magnitude(aShifted, bShifted, chroma)

        aFloat.release()
        bFloat.release()
        aShifted.release()
        bShifted.release()

        return chroma
    }

    private fun createDocumentMask(
        workSize: Size,
        quad: List<Point>,
        scale: Double,
        segmentation: DocumentSegmentation?
    ): Mat {
        val quadMask = Mat.zeros(workSize, CV_8UC1)
        val scaledPoints = quad.map { Point(it.x * scale, it.y * scale) }
        val pts = MatOfPoint(*scaledPoints.toTypedArray())
        Imgproc.fillConvexPoly(quadMask, pts, Scalar(255.0))
        pts.release()

        // Erode quad edges to exclude background bleeding
        val edgeLengths = listOf(
            kotlin.math.hypot(scaledPoints[0].x - scaledPoints[1].x, scaledPoints[0].y - scaledPoints[1].y),
            kotlin.math.hypot(scaledPoints[1].x - scaledPoints[2].x, scaledPoints[1].y - scaledPoints[2].y),
            kotlin.math.hypot(scaledPoints[2].x - scaledPoints[3].x, scaledPoints[2].y - scaledPoints[3].y),
            kotlin.math.hypot(scaledPoints[3].x - scaledPoints[0].x, scaledPoints[3].y - scaledPoints[0].y)
        )
        val minDim = edgeLengths.minOrNull() ?: 100.0
        var k = (minDim * 0.02).roundToInt().coerceIn(3, 15)
        if (k % 2 == 0) k += 1

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        val erodedQuadMask = Mat()
        Imgproc.morphologyEx(quadMask, erodedQuadMask, Imgproc.MORPH_ERODE, kernel)
        quadMask.release()
        kernel.release()

        if (segmentation != null && CvRuntime.isNativeAvailable) {
            val segBin = segmentation.toBinaryMat(0.5f)
            val resizedSeg = Mat()
            Imgproc.resize(segBin, resizedSeg, workSize, 0.0, 0.0, Imgproc.INTER_AREA)
            segBin.release()

            val combined = Mat()
            Core.bitwise_and(erodedQuadMask, resizedSeg, combined)
            erodedQuadMask.release()
            resizedSeg.release()
            return combined
        }

        return erodedQuadMask
    }

    private fun applyGrayWorldToDocument(img: Mat, docMask: Mat): Mat {
        val nonZero = Core.countNonZero(docMask)
        if (nonZero == 0) return img.clone()

        val meanScalar = Core.mean(img, docMask)
        val meanB = meanScalar.`val`[0].coerceAtLeast(1e-6)
        val meanG = meanScalar.`val`[1].coerceAtLeast(1e-6)
        val meanR = meanScalar.`val`[2].coerceAtLeast(1e-6)

        val meanGray = (meanB + meanG + meanR) / 3.0
        val scaleB = meanGray / meanB
        val scaleG = meanGray / meanG
        val scaleR = meanGray / meanR

        val imgF = Mat()
        img.convertTo(imgF, CvType.CV_32FC3)

        val scales = Scalar(scaleB, scaleG, scaleR)
        val scaledF = Mat()
        Core.multiply(imgF, scales, scaledF)
        imgF.release()

        val scaled8 = Mat()
        scaledF.convertTo(scaled8, CvType.CV_8UC3)
        scaledF.release()

        val result = img.clone()
        scaled8.copyTo(result, docMask)
        scaled8.release()

        return result
    }
}
