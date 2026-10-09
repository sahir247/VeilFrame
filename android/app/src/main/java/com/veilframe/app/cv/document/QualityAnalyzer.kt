package com.veilframe.app.cv.document

import com.veilframe.app.cv.core.CvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Image quality metrics for live document capture gating.
 */
data class QualityMetrics(
    val isSharp: Boolean,
    val sharpnessVariance: Double,
    val isTooDark: Boolean,
    val meanLuma: Double,
    val hasGlare: Boolean,
    val glarePercentage: Double
) {
    companion object {
        val DEFAULT = QualityMetrics(
            isSharp = true,
            sharpnessVariance = 100.0,
            isTooDark = false,
            meanLuma = 128.0,
            hasGlare = false,
            glarePercentage = 0.0
        )
    }
}

/**
 * Zero-allocation real-time quality gate analyzer for document viewfinder frames.
 * Measures Exposure (Mean Luma), Sharpness (Laplacian Variance), and Glare (Specular Highlights)
 * reusing pooled Mats.
 */
object QualityAnalyzer {

    const val MIN_LUMA_THRESHOLD = 45.0
    const val SHARPNESS_VARIANCE_THRESHOLD = 150.0
    const val GLARE_PERCENTAGE_THRESHOLD = 3.0
    const val SPECULAR_HIGH_THRESHOLD = 240.0

    /**
     * Pure logic evaluator for thresholds and verdicts — JVM unit-testable without OpenCV native.
     */
    fun evaluate(
        meanLuma: Double,
        lapVariance: Double,
        glarePct: Double,
        minLuma: Double = MIN_LUMA_THRESHOLD,
        minSharpness: Double = SHARPNESS_VARIANCE_THRESHOLD,
        maxGlare: Double = GLARE_PERCENTAGE_THRESHOLD
    ): QualityMetrics {
        return QualityMetrics(
            isSharp = lapVariance >= minSharpness,
            sharpnessVariance = lapVariance,
            isTooDark = meanLuma < minLuma,
            meanLuma = meanLuma,
            hasGlare = glarePct > maxGlare,
            glarePercentage = glarePct
        )
    }

    /**
     * Analyzes quality metrics for [source] using pre-allocated pooled Mats.
     * Reuses [meanMat] and [stddevMat] across luma and Laplacian calculations.
     */
    fun analyze(
        source: Mat,
        laplacianMat: Mat,
        glareMaskMat: Mat,
        meanMat: MatOfDouble,
        stddevMat: MatOfDouble,
    ): QualityMetrics {
        if (!CvRuntime.isNativeAvailable || source.empty() || meanMat.empty() || stddevMat.empty()) {
            return QualityMetrics.DEFAULT
        }

        // 1. Exposure (Mean Luma)
        // Calculates the average brightness of the Y-plane (0-255)
        Core.meanStdDev(source, meanMat, stddevMat)
        val lumaArr = meanMat.toArray()
        val luma = if (lumaArr.isNotEmpty()) lumaArr[0] else 128.0

        // 2. Sharpness (Laplacian Variance)
        // A high variance means strong edges (sharp text). A low variance means blur.
        Imgproc.Laplacian(source, laplacianMat, CvType.CV_32F)
        Core.meanStdDev(laplacianMat, meanMat, stddevMat)
        val sdArr = stddevMat.toArray()
        val sd = if (sdArr.isNotEmpty()) sdArr[0] else 10.0
        val lapVariance = sd * sd

        // 3. Glare (Specular Highlights)
        // Finds pixels that are almost pure white (> 240) in the Y-plane
        Core.inRange(source, Scalar(SPECULAR_HIGH_THRESHOLD), Scalar(255.0), glareMaskMat)
        val glarePixels = Core.countNonZero(glareMaskMat)
        val totalPixels = source.rows() * source.cols()
        val glarePct = if (totalPixels > 0) (glarePixels.toDouble() / totalPixels) * 100.0 else 0.0

        return evaluate(
            meanLuma = luma,
            lapVariance = lapVariance,
            glarePct = glarePct
        )
    }
}
