package com.veilframe.app.cv.denoise

import com.veilframe.app.cv.preprocess.DenoiseMethod
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * NoiseReducer — fast + high-quality denoising with content-aware defaults.
 *
 * Fast tier:      Gaussian / Median / Bilateral
 * Quality tier:   fastNlMeansDenoising / fastNlMeansDenoisingColored
 *
 * Selection policy (deterministic, inspectable via [analyze]):
 *   JPEG/noise-heavy image  → NLM
 *   edges & text dominant   → bilateral
 *   salt-and-pepper spikes  → median
 */
object NoiseReducer {

    data class NoiseAnalysis(
        val sigma: Double,
        val impulseRatio: Double,
        val edgeDensity: Double,
        val suggestedMethod: DenoiseMethod,
        val suggestHighQuality: Boolean,
    )

    enum class Quality { FAST, HIGH }

    /** Exact median (index n/2 of the sorted values) of an 8-bit histogram. */
    private fun medianOfHistogram(histogram: IntArray, total: Long): Double {
        val target = total / 2
        var cumulative = 0L
        for (v in histogram.indices) {
            cumulative += histogram[v]
            if (cumulative > target) return v.toDouble()
        }
        return 255.0
    }

    /** Analyse content and recommend a strategy. */
    fun analyze(source: Mat): NoiseAnalysis {
        val gray = gray8(source)
        val blurred = Mat()
        val residual = Mat()
        val edges = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Core.absdiff(gray, blurred, residual)

            // Exact 8-bit histogram via row-buffered streaming — avoids
            // allocating a full-resolution 12 MP ByteArray on the JVM heap.
            val rows = gray.rows()
            val cols = gray.cols()
            val rowBuf = ByteArray(cols)
            val histogram = IntArray(256)
            for (r in 0 until rows) {
                residual.get(r, 0, rowBuf)
                for (c in 0 until cols) {
                    histogram[rowBuf[c].toInt() and 0xFF]++
                }
            }
            val total = rows.toLong() * cols.toLong()
            val sigma = medianOfHistogram(histogram, total) / 0.6745

            // Impulse noise: pixels far beyond 4*sigma of the local median.
            val threshold = (4.0 * sigma).coerceAtLeast(12.0)
            var impulse = 0L
            for (v in (threshold.toInt() + 1)..255) impulse += histogram[v]
            val impulseRatio = if (total > 0L) impulse.toDouble() / total else 0.0

            Imgproc.Canny(gray, edges, 60.0, 120.0)
            val edgeCount = Core.countNonZero(edges)
            val edgeDensity = edgeCount.toDouble() / (edges.rows().toLong() * edges.cols())

            val method = when {
                impulseRatio > 0.01 -> DenoiseMethod.MEDIAN
                edgeDensity > 0.12 -> DenoiseMethod.BILATERAL
                sigma > 8.0 -> DenoiseMethod.BILATERAL
                else -> DenoiseMethod.GAUSSIAN
            }
            return NoiseAnalysis(
                sigma = sigma,
                impulseRatio = impulseRatio,
                edgeDensity = edgeDensity,
                suggestedMethod = method,
                suggestHighQuality = sigma > 6.0 && impulseRatio <= 0.01,
            )
        } finally {
            gray.release()
            blurred.release()
            residual.release()
            edges.release()
        }
    }

    /**
     * Denoises [source]. When [method] is null the content-aware default from
     * [analyze] is used. NLM is comparatively slow — use [Quality.FAST] for
     * interactive paths.
     */
    fun reduce(
        source: Mat,
        method: DenoiseMethod? = null,
        quality: Quality = Quality.FAST,
        strength: Int = 7,
    ): Mat {
        val chosen = method ?: analyze(source).suggestedMethod
        return when {
            quality == Quality.HIGH && chosen == DenoiseMethod.BILATERAL && source.channels() in 1..3 ->
                nlm(source, strength)
            quality == Quality.HIGH -> nlm(source, strength)
            chosen == DenoiseMethod.GAUSSIAN -> {
                val out = Mat()
                Imgproc.GaussianBlur(source, out, Size(0.0, 0.0), 1.0)
                out
            }
            chosen == DenoiseMethod.MEDIAN -> {
                val out = Mat()
                Imgproc.medianBlur(source, out, (strength or 1).coerceAtMost(9))
                out
            }
            else -> {
                val out = Mat()
                Imgproc.bilateralFilter(source, out, 0, 50.0, 50.0)
                out
            }
        }
    }

    /** Non-local means — higher quality, higher cost. Falls back to bilateralFilter when Photo is unavailable. */
    fun nlm(source: Mat, strength: Int = 7): Mat {
        val out = Mat()
        return try {
            val clazz = Class.forName("org.opencv.photo.Photo")
            when (source.channels()) {
                1 -> {
                    val method = clazz.getMethod("fastNlMeansDenoising", Mat::class.java, Mat::class.java, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    method.invoke(null, source, out, strength.toFloat(), 7, 21)
                }
                3 -> {
                    val method = clazz.getMethod("fastNlMeansDenoisingColored", Mat::class.java, Mat::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    method.invoke(null, source, out, strength.toFloat(), strength.toFloat(), 7, 21)
                }
                4 -> {
                    val bgr = Mat()
                    try {
                        Imgproc.cvtColor(source, bgr, Imgproc.COLOR_BGRA2BGR)
                        val method = clazz.getMethod("fastNlMeansDenoisingColored", Mat::class.java, Mat::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        method.invoke(null, bgr, out, strength.toFloat(), strength.toFloat(), 7, 21)
                    } finally {
                        bgr.release()
                    }
                }
                else -> throw UnsupportedOperationException("unsupported channel count ${source.channels()}")
            }
            out
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val target = e.targetException
            if (target is org.opencv.core.CvException) {
                Imgproc.bilateralFilter(source, out, 9, 75.0, 75.0)
                out
            } else if (target is RuntimeException) {
                throw target
            } else if (target is Error) {
                throw target
            } else {
                throw RuntimeException("Photo.fastNlMeansDenoising invocation failed", target)
            }
        } catch (e: ReflectiveOperationException) {
            Imgproc.bilateralFilter(source, out, 9, 75.0, 75.0)
            out
        } catch (e: org.opencv.core.CvException) {
            Imgproc.bilateralFilter(source, out, 9, 75.0, 75.0)
            out
        }
    }

    private fun gray8(source: Mat): Mat = when {
        source.channels() == 1 && source.type() == CvType.CV_8UC1 -> source.clone()
        else -> {
            val out = Mat()
            if (source.channels() == 1) source.convertTo(out, CvType.CV_8UC1)
            else Imgproc.cvtColor(source, out, if (source.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY)
            out
        }
    }
}
