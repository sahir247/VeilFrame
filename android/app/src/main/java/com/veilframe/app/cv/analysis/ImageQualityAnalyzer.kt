package com.veilframe.app.cv.analysis

import com.veilframe.app.cv.edges.EdgeDetector
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * ImageQualityAnalyzer — reusable quality measurement over 8-bit images.
 *
 * Produces the report shape from the CV plan:
 *
 *   IMAGE QUALITY
 *   ──────────────
 *   Sharpness       91
 *   Exposure        87
 *   Contrast        93
 *   Noise           82
 *   Dynamic Range   88
 *   ...
 *   Overall         89
 *
 * Measurement happens here; policy/normalisation lives in [QualityScore]
 * (pure Kotlin, unit-tested on the JVM).
 */
object ImageQualityAnalyzer {

    /** Full quality report for one image. */
    fun analyze(source: Mat, weights: QualityWeights = QualityWeights()): QualityReport {
        require(!source.empty()) { "source is empty" }
        val luma = luma8(source)
        try {
            val raw = QualityRawMeasurements(
                varianceOfLaplacian = varianceOfLaplacian(luma),
                noiseSigma = estimateNoiseSigma(luma),
                meanLuma = Core.mean(luma).`val`[0],
                clippedHighlightRatio = clippedRatio(luma, high = true),
                clippedShadowRatio = clippedRatio(luma, high = false),
                contrastRms = contrastRms(luma),
                lumaSpreadP5P95 = percentileSpread(luma),
                saturationMean = meanSaturation(source),
                blockiness = blockiness(luma),
                ringing = ringing(luma),
            )
            return QualityScore.evaluate(raw, weights)
        } finally {
            luma.release()
        }
    }

    /** Blur metrics for the [BlurAnalyzer] combiner. */
    fun measureBlur(source: Mat): BlurAnalyzer.RawMetrics {
        val luma = luma8(source)
        try {
            val (energyX, energyY) = EdgeDetector.directionalGradientEnergy(luma)
            return BlurAnalyzer.RawMetrics(
                varianceOfLaplacian = varianceOfLaplacian(luma),
                gradientEnergy = (energyX + energyY) / 2.0,
                tenengrad = tenengrad(luma),
                gradientEnergyX = energyX,
                gradientEnergyY = energyY,
            )
        } finally {
            luma.release()
        }
    }

    // ---------------------------------------------------------------- metrics

    /** Variance of the Laplacian — the classic focus measure. */
    fun varianceOfLaplacian(luma: Mat): Double {
        val lap = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Imgproc.Laplacian(luma, lap, CvType.CV_64F, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
            Core.meanStdDev(lap, mean, stddev)
            val sd = stddev.toArray()[0]
            return sd * sd
        } finally {
            lap.release()
            mean.release()
            stddev.release()
        }
    }

    /** Tenengrad: mean squared Sobel response. */
    fun tenengrad(luma: Mat): Double {
        val gx = Mat()
        val gy = Mat()
        val gx2 = Mat()
        val gy2 = Mat()
        val sum = Mat()
        try {
            Imgproc.Sobel(luma, gx, CvType.CV_64F, 1, 0, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
            Imgproc.Sobel(luma, gy, CvType.CV_64F, 0, 1, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
            Core.multiply(gx, gx, gx2)
            Core.multiply(gy, gy, gy2)
            Core.add(gx2, gy2, sum)
            return Core.mean(sum).`val`[0]
        } finally {
            gx.release()
            gy.release()
            gx2.release()
            gy2.release()
            sum.release()
        }
    }

    /**
     * Noise sigma via the median absolute deviation of a high-pass residual.
     * MAD / 0.6745 is a robust sigma estimate for Gaussian noise.
     */
    fun estimateNoiseSigma(luma: Mat): Double {
        val blurred = Mat()
        val residual = Mat()
        try {
            Imgproc.GaussianBlur(luma, blurred, Size(3.0, 3.0), 0.0)
            Core.absdiff(luma, blurred, residual)
            // Exact 8-bit histogram median — no DoubleArray + full sort (the
            // sort materialised ~96 MB of temporaries at 12 MP).
            val bytes = ByteArray(residual.rows() * residual.cols())
            residual.get(0, 0, bytes)
            val histogram = IntArray(256)
            for (b in bytes) histogram[b.toInt() and 0xFF]++
            val target = bytes.size / 2
            var cumulative = 0
            var median = 255
            for (v in histogram.indices) {
                cumulative += histogram[v]
                if (cumulative > target) {
                    median = v
                    break
                }
            }
            return median / 0.6745
        } finally {
            blurred.release()
            residual.release()
        }
    }

    private fun luma8(source: Mat): Mat = when {
        source.channels() == 1 && source.type() == CvType.CV_8UC1 -> source.clone()
        source.channels() == 1 -> {
            val out = Mat()
            source.convertTo(out, CvType.CV_8UC1)
            out
        }
        else -> {
            val out = Mat()
            Imgproc.cvtColor(
                source,
                out,
                if (source.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY,
            )
            out
        }
    }

    /** Fraction of pixels pinned at 255 (high) or 0 (low). */
    private fun clippedRatio(luma: Mat, high: Boolean): Double {
        val hist = Mat()
        val channels = MatOfInt(0)
        val histSize = MatOfInt(256)
        val ranges = MatOfFloat(0f, 256f)
        val mask = Mat()
        try {
            Imgproc.calcHist(listOf(luma), channels, mask, hist, histSize, ranges)
            val total = luma.rows().toDouble() * luma.cols()
            val clipped = hist.get(if (high) 255 else 0, 0)[0]
            return clipped / total
        } finally {
            hist.release()
            channels.release()
            histSize.release()
            ranges.release()
            mask.release()
        }
    }

    private fun contrastRms(luma: Mat): Double {
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Core.meanStdDev(luma, mean, stddev)
            return stddev.toArray()[0]
        } finally {
            mean.release()
            stddev.release()
        }
    }

    /** p5..p95 luma spread — a clipping-tolerant dynamic range proxy. */
    private fun percentileSpread(luma: Mat): Double {
        val hist = Mat()
        val channels = MatOfInt(0)
        val histSize = MatOfInt(256)
        val ranges = MatOfFloat(0f, 256f)
        val mask = Mat()
        try {
            Imgproc.calcHist(listOf(luma), channels, mask, hist, histSize, ranges)
            val total = luma.rows().toDouble() * luma.cols()
            var cumulative = 0.0
            var p5 = 0
            var p95 = 255
            for (bin in 0 until 256) {
                cumulative += hist.get(bin, 0)[0]
                if (p5 == 0 && cumulative >= total * 0.05) p5 = bin
                if (cumulative >= total * 0.95) {
                    p95 = bin
                    break
                }
            }
            return (p95 - p5).toDouble()
        } finally {
            hist.release()
            channels.release()
            histSize.release()
            ranges.release()
            mask.release()
        }
    }

    private fun meanSaturation(source: Mat): Double {
        if (source.channels() == 1) return 0.0
        val bgr = Mat()
        val hsv = Mat()
        val planes = ArrayList<Mat>()
        try {
            if (source.channels() == 4) Imgproc.cvtColor(source, bgr, Imgproc.COLOR_BGRA2BGR)
            else source.copyTo(bgr)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.split(hsv, planes)
            return Core.mean(planes[1]).`val`[0] / 255.0
        } finally {
            bgr.release()
            hsv.release()
            planes.forEach { it.release() }
        }
    }

    /** Blockiness: mean gradient discontinuity at 8x8 JPEG block boundaries. */
    private fun blockiness(luma: Mat): Double {
        val dx = Mat()
        try {
            Imgproc.Sobel(luma, dx, CvType.CV_64F, 1, 0, 1, 1.0, 0.0, Core.BORDER_REPLICATE)
            val cols = luma.cols()
            val rows = luma.rows()
            var boundary = 0.0
            var boundaryCount = 0
            var interior = 0.0
            var interiorCount = 0
            val row = DoubleArray(cols)
            for (y in 0 until rows) {
                dx.get(y, 0, row)
                for (x in row.indices) {
                    val magnitude = kotlin.math.abs(row[x])
                    if (x % 8 == 0 && x != 0) {
                        boundary += magnitude
                        boundaryCount++
                    } else {
                        interior += magnitude
                        interiorCount++
                    }
                }
            }
            if (boundaryCount == 0 || interiorCount == 0) return 0.0
            val boundaryMean = boundary / boundaryCount
            val interiorMean = interior / interiorCount
            return ((boundaryMean - interiorMean) / (boundaryMean + interiorMean + 1e-6)).coerceIn(0.0, 1.0)
        } finally {
            dx.release()
        }
    }

    /** Ringing: high-frequency energy in the halo band around strong edges. */
    private fun ringing(luma: Mat): Double {
        val blurred = Mat()
        val residual = Mat()
        val edges = Mat()
        val dilated = Mat()
        val halo = Mat()
        val ringMask = Mat()
        val masked = Mat()
        try {
            Imgproc.GaussianBlur(luma, blurred, Size(5.0, 5.0), 0.0)
            Core.absdiff(luma, blurred, residual)
            Imgproc.Canny(luma, edges, 80.0, 160.0)
            val small = Mat.ones(5, 5, CvType.CV_8U)
            Imgproc.dilate(edges, dilated, small)
            small.release()
            val wide = Mat.ones(9, 9, CvType.CV_8U)
            Imgproc.dilate(dilated, halo, wide)
            wide.release()
            Core.subtract(halo, dilated, ringMask)
            Core.bitwise_and(residual, residual, masked, ringMask)
            val ringEnergy = Core.mean(masked).`val`[0]
            val totalEnergy = Core.mean(residual).`val`[0] + 1e-6
            return (ringEnergy / totalEnergy).coerceIn(0.0, 1.0)
        } finally {
            blurred.release()
            residual.release()
            edges.release()
            dilated.release()
            halo.release()
            ringMask.release()
            masked.release()
        }
    }
}
