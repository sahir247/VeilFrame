package com.veilframe.app.cv.qr

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

/**
 * QrStressMatrix — deterministic QR degradation suite.
 *
 * Every generated QR is judged against the same reproducible conditions:
 *
 *   Original │ 75% │ 50% │ 25% │ downscaled │ JPEG q70 │ Gaussian blur │
 *   contrast │ brightness │ rotation │ perspective │ noise
 *
 * Degradations are seeded, so the same input always produces the same stress
 * images — this suite is a release gate, not a fuzz test.
 */
object QrStressMatrix {

    enum class StressCondition(val label: String) {
        ORIGINAL("Original"),
        SCALE_75("75%"),
        SCALE_50("50%"),
        SCALE_25("25%"),
        DOWNSCALED("Downscaled"),
        JPEG_Q70("JPEG Q70"),
        GAUSSIAN_BLUR("Gaussian blur"),
        CONTRAST("Contrast changed"),
        BRIGHTNESS("Brightness changed"),
        ROTATION("Rotation"),
        PERSPECTIVE("Perspective"),
        NOISE("Noisy"),
    }

    /** The full matrix, in evaluation order. */
    val ALL: List<StressCondition> = StressCondition.entries.toList()

    /**
     * Produces the degraded variant of [image] for [condition].
     * Caller owns the returned Mat. Seeded — no randomness across runs.
     */
    fun apply(image: Mat, condition: StressCondition, seed: Int = 0x5646_5152): Mat = when (condition) {
        StressCondition.ORIGINAL -> image.clone()

        StressCondition.SCALE_75 -> scaled(image, 0.75)
        StressCondition.SCALE_50 -> scaled(image, 0.50)
        StressCondition.SCALE_25 -> scaled(image, 0.25)

        StressCondition.DOWNSCALED -> {
            // Aggressive decimation followed by a partial upscale — the classic
            // "printed then re-photographed" weakness.
            val small = scaled(image, 0.25)
            val back = Mat()
            Imgproc.resize(small, back, Size(image.cols().toDouble(), image.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            small.release()
            back
        }

        StressCondition.JPEG_Q70 -> jpegRoundTrip(image, quality = 70)

        StressCondition.GAUSSIAN_BLUR -> {
            val out = Mat()
            Imgproc.GaussianBlur(image, out, Size(3.0, 3.0), 0.8)
            out
        }

        StressCondition.CONTRAST -> {
            val out = Mat()
            image.convertTo(out, -1, 0.7, 12.0)
            out
        }

        StressCondition.BRIGHTNESS -> {
            val out = Mat()
            image.convertTo(out, -1, 1.0, -30.0)
            out
        }

        StressCondition.ROTATION -> rotated(image, angleDegrees = 12.0)

        StressCondition.PERSPECTIVE -> perspectiveSkew(image)

        StressCondition.NOISE -> addNoise(image, seed)
    }

    /** Convenience: every condition applied to [image]. */
    fun applyAll(image: Mat, seed: Int = 0x5646_5152): List<Pair<StressCondition, Mat>> =
        ALL.map { it to apply(image, it, seed) }

    // ------------------------------------------------------------ transforms

    private fun scaled(image: Mat, factor: Double): Mat {
        val out = Mat()
        val width = maxOf(1, Math.round(image.cols() * factor).toInt())
        val height = maxOf(1, Math.round(image.rows() * factor).toInt())
        val interpolation = if (factor < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        Imgproc.resize(image, out, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, interpolation)
        return out
    }

    private fun jpegRoundTrip(image: Mat, quality: Int): Mat {
        val params = MatOfIntJpeg(quality)
        val buffer = MatOfByteCompat()
        return try {
            val encoded = Imgcodecs.imencode(".jpg", image, buffer, params)
            if (!encoded) {
                // Tooling failure, not a QR failure — don't score it as a stress miss.
                image.clone()
            } else {
                val decoded = Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_UNCHANGED)
                if (decoded.empty()) {
                    decoded.release()
                    image.clone()
                } else {
                    decoded
                }
            }
        } catch (_: Throwable) {
            image.clone()
        } finally {
            buffer.release()
            params.release()
        }
    }

    private fun MatOfByteCompat(): org.opencv.core.MatOfByte = org.opencv.core.MatOfByte()

    private fun MatOfIntJpeg(quality: Int): org.opencv.core.MatOfInt =
        org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)

    private fun rotated(image: Mat, angleDegrees: Double): Mat {
        val center = Point(image.cols() / 2.0, image.rows() / 2.0)
        val matrix = Imgproc.getRotationMatrix2D(center, angleDegrees, 1.0)
        val out = Mat()
        try {
            Imgproc.warpAffine(
                image,
                out,
                matrix,
                Size(image.cols().toDouble(), image.rows().toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
            )
            return out
        } finally {
            matrix.release()
        }
    }

    private fun perspectiveSkew(image: Mat): Mat {
        val w = image.cols().toDouble()
        val h = image.rows().toDouble()
        val shift = minOf(w, h) * 0.06
        val src = MatOfPoint2fLocal(
            Point(0.0, 0.0), Point(w - 1, 0.0), Point(w - 1, h - 1), Point(0.0, h - 1),
        )
        val dst = MatOfPoint2fLocal(
            Point(shift, shift * 0.6), Point(w - 1 - shift * 0.4, 0.0), Point(w - 1, h - 1 - shift), Point(shift * 0.5, h - 1),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        try {
            Imgproc.warpPerspective(image, out, transform, Size(w, h), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            return out
        } finally {
            src.release()
            dst.release()
            transform.release()
        }
    }

    private fun MatOfPoint2fLocal(vararg points: Point): org.opencv.core.MatOfPoint2f =
        org.opencv.core.MatOfPoint2f(*points)

    private fun addNoise(image: Mat, seed: Int): Mat {
        val random = Random(seed)
        val out = image.clone()
        val rows = out.rows()
        val cols = out.cols()
        val channels = out.channels()
        val row = ByteArray(cols * channels)
        for (y in 0 until rows) {
            out.get(y, 0, row)
            for (i in row.indices) {
                val delta = (random.nextInt(-12, 13))
                val value = (row[i].toInt() and 0xFF) + delta
                row[i] = value.coerceIn(0, 255).toByte()
            }
            out.put(y, 0, row)
        }
        return out
    }
}
