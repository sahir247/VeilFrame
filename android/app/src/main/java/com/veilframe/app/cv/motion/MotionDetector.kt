package com.veilframe.app.cv.motion

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * MotionDetector — frame-to-frame motion regions + long-running background
 * subtraction.
 *
 *   Frame N → Frame N+1 → absdiff → threshold → morphological cleanup →
 *   connected components → motion regions
 *
 * The connected-region output is the infrastructure the FPS/interpolation
 * engine uses to know where to synthesise frames carefully.
 */
object MotionDetector {

    data class MotionRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val areaPx: Int,
    )

    data class MotionReport(
        val detected: Boolean,
        val regions: List<MotionRegion>,
        /** Fraction of pixels that changed, 0..1. */
        val changedRatio: Double,
    )

    data class Options(
        /** absdiff threshold before cleanup (0..255). */
        val threshold: Double = 25.0,
        /** Regions smaller than this fraction of the frame are ignored. */
        val minRegionFraction: Double = 0.001,
        val morphKernel: Int = 5,
        /** changedRatio above this counts as "motion detected". */
        val detectionRatio: Double = 0.002,
    )

    /** Diffs two frames and extracts motion regions. */
    fun detect(previous: Mat, current: Mat, options: Options = Options()): MotionReport {
        require(!previous.empty() && !current.empty()) { "frames must be non-empty" }
        require(previous.size() == current.size()) { "frames must have identical size" }
        val diff = Mat()
        val binary = Mat()
        val cleaned = Mat()
        try {
            Core.absdiff(current, previous, diff)
            val gray = if (diff.channels() == 1) diff else {
                val g = Mat()
                Imgproc.cvtColor(diff, g, if (diff.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY)
                g
            }
            Imgproc.threshold(gray, binary, options.threshold, 255.0, Imgproc.THRESH_BINARY)
            if (gray !== diff) gray.release()

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(options.morphKernel.toDouble(), options.morphKernel.toDouble()),
            )
            Imgproc.morphologyEx(binary, cleaned, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(cleaned, cleaned, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val componentCount = Imgproc.connectedComponentsWithStats(cleaned, labels, stats, centroids, 8, CvType.CV_32S)

            val frameArea = current.rows().toDouble() * current.cols()
            val minArea = frameArea * options.minRegionFraction
            val regions = mutableListOf<MotionRegion>()
            for (label in 1 until componentCount) {
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                if (area < minArea) continue
                regions += MotionRegion(
                    x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt(),
                    y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt(),
                    width = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt(),
                    height = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt(),
                    areaPx = area,
                )
            }
            labels.release()
            stats.release()
            centroids.release()

            val changed = Core.countNonZero(cleaned).toDouble()
            val changedRatio = changed / frameArea
            return MotionReport(
                detected = changedRatio >= options.detectionRatio && regions.isNotEmpty(),
                regions = regions,
                changedRatio = changedRatio,
            )
        } finally {
            diff.release()
            binary.release()
            cleaned.release()
        }
    }

    /**
     * Long-running motion over a camera/video stream via MOG2 background
     * subtraction. Own the returned [BackgroundModel] and call [BackgroundModel.close].
     */
    fun backgroundModel(history: Int = 300, varThreshold: Double = 25.0): BackgroundModel =
        BackgroundModel(history, varThreshold)
}

/** MOG2 background-subtraction wrapper for streaming scenarios. */
class BackgroundModel(history: Int, varThreshold: Double) : AutoCloseable {
    private val subtractor = org.opencv.video.Video.createBackgroundSubtractorMOG2(history, varThreshold, true)

    /** Updates the model with [frame] and returns the foreground mask. */
    fun apply(frame: Mat): Mat {
        val mask = Mat()
        subtractor.apply(frame, mask)
        return mask
    }

    /**
     * Drops the reference; the binding's finalizer releases the native model.
     * (Java bindings expose no public delete/close on algorithm classes.)
     */
    override fun close() {
        closed = true
    }

    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed
}
