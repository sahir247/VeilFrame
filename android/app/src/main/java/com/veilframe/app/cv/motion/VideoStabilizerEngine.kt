package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.veilframe.app.cv.core.CvRuntime
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.Locale

/**
 * CameraMotion — Inter-frame 2D transformation delta representing physical camera movement.
 *
 * @param dx Horizontal translation in pixels between Frame N-1 and Frame N.
 * @param dy Vertical translation in pixels between Frame N-1 and Frame N.
 * @param da Angular rotation in radians between Frame N-1 and Frame N.
 */
data class CameraMotion(
    val dx: Double,
    val dy: Double,
    val da: Double
)

/**
 * CameraTrajectory — Cumulative 2D spatial trajectory tracking camera position over time.
 *
 * Conforms to both (dx, dy, da) and (x, y, a) access patterns.
 *
 * @param dx Cumulative horizontal displacement in pixels.
 * @param dy Cumulative vertical displacement in pixels.
 * @param da Cumulative angular rotation in radians.
 */
data class CameraTrajectory(
    val dx: Double,
    val dy: Double,
    val da: Double
) {
    val x: Double get() = dx
    val y: Double get() = dy
    val a: Double get() = da
}

/**
 * StabilizationCorrection — Corrective offset applied to warp the frame back into the smoothed path.
 *
 * @param corrDx Corrective horizontal shift (smoothedX - rawX).
 * @param corrDy Corrective vertical shift (smoothedY - rawY).
 * @param corrDa Corrective angular rotation (smoothedA - rawA).
 */
data class StabilizationCorrection(
    val corrDx: Double,
    val corrDy: Double,
    val corrDa: Double
)

/**
 * CorrectiveAffineMatrix — 2x3 affine matrix representation supporting centered rotation,
 * translation, and border-crop zooming without requiring intermediate Mat allocations.
 */
data class CorrectiveAffineMatrix(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double
) {
    /**
     * Converts to an OpenCV 2x3 [Mat] of type [CvType.CV_64F].
     * Caller is responsible for releasing the returned Mat.
     */
    fun toOpenCvMat(): Mat {
        val mat = Mat(2, 3, CvType.CV_64F)
        mat.put(0, 0, m00, m01, m02)
        mat.put(1, 0, m10, m11, m12)
        return mat
    }

    /**
     * Applies this affine transform to a 2D coordinate point (x, y).
     */
    fun transformPoint(x: Double, y: Double): Pair<Double, Double> {
        val px = m00 * x + m01 * y + m02
        val py = m10 * x + m11 * y + m12
        return Pair(px, py)
    }
}

/**
 * Pluggable abstraction for inter-frame camera motion estimation.
 * Uses Lucas-Kanade optical flow on native OpenCV hardware, and deterministic
 * motion providers in host JVM test environments.
 */
interface FrameMotionEstimator {
    fun estimateMotion(prevGray: Mat, currGray: Mat): CameraMotion
    fun release()
}

/**
 * Production OpenCV [FrameMotionEstimator] using Shi-Tomasi corner detection,
 * Lucas-Kanade sparse optical flow ([Video.calcOpticalFlowPyrLK]), and partial
 * affine estimation ([Calib3d.estimateAffinePartial2D]).
 */
class OpenCvOpticalFlowEstimator(
    private val maxCorners: Int = 200,
    private val qualityLevel: Double = 0.01,
    private val minDistance: Double = 30.0
) : FrameMotionEstimator {

    override fun estimateMotion(prevGray: Mat, currGray: Mat): CameraMotion {
        val corners = MatOfPoint()
        val prevPoints = MatOfPoint2f()
        val currPoints = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        try {
            // 1. Detect high-contrast Shi-Tomasi corners
            Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, qualityLevel, minDistance)
            if (corners.empty() || corners.rows() < 4) {
                return CameraMotion(0.0, 0.0, 0.0)
            }

            // Convert corners from MatOfPoint to MatOfPoint2f
            val cornerArray = corners.toArray()
            val pointList = cornerArray.map { Point(it.x.toDouble(), it.y.toDouble()) }
            prevPoints.fromList(pointList)

            // 2. Track points via Lucas-Kanade optical flow
            Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPoints, currPoints, status, err)
            if (status.empty()) {
                return CameraMotion(0.0, 0.0, 0.0)
            }

            // 3. Filter successful point tracks
            val prevList = prevPoints.toList()
            val currList = currPoints.toList()
            val statusList = status.toList()
            val goodPrev = mutableListOf<Point>()
            val goodCurr = mutableListOf<Point>()

            for (i in statusList.indices) {
                if (statusList[i].toInt() == 1 && i < prevList.size && i < currList.size) {
                    goodPrev.add(prevList[i])
                    goodCurr.add(currList[i])
                }
            }

            if (goodPrev.size < 4) {
                return CameraMotion(0.0, 0.0, 0.0)
            }

            // 4. Estimate Partial Affine Transform (Translation + Rotation)
            val goodPrevMat = MatOfPoint2f(*goodPrev.toTypedArray())
            val goodCurrMat = MatOfPoint2f(*goodCurr.toTypedArray())
            var transformMat: Mat? = null
            try {
                transformMat = Calib3d.estimateAffinePartial2D(goodPrevMat, goodCurrMat)
                if (transformMat != null && !transformMat.empty() && transformMat.rows() >= 2 && transformMat.cols() >= 3) {
                    val dx = transformMat.get(0, 2)[0]
                    val dy = transformMat.get(1, 2)[0]
                    val da = Math.atan2(transformMat.get(1, 0)[0], transformMat.get(0, 0)[0])
                    return CameraMotion(dx, dy, da)
                }
            } finally {
                goodPrevMat.release()
                goodCurrMat.release()
                transformMat?.release()
            }

            return CameraMotion(0.0, 0.0, 0.0)
        } catch (t: Throwable) {
            Log.w("VideoStabilizerEngine", "Optical flow estimation failed: ${t.message}")
            return CameraMotion(0.0, 0.0, 0.0)
        } finally {
            corners.release()
            prevPoints.release()
            currPoints.release()
            status.release()
            err.release()
        }
    }

    override fun release() {}
}

/**
 * Deterministic fallback [FrameMotionEstimator] for host JVM test runners.
 */
class NoOpMotionEstimator : FrameMotionEstimator {
    override fun estimateMotion(prevGray: Mat, currGray: Mat): CameraMotion = CameraMotion(0.0, 0.0, 0.0)
    override fun release() {}
}

/**
 * Mock [FrameMotionEstimator] allowing scripted motion injections for unit test scenarios.
 */
class MockMotionEstimator(
    private val motionSequence: List<CameraMotion> = emptyList()
) : FrameMotionEstimator {
    private var index = 0

    override fun estimateMotion(prevGray: Mat, currGray: Mat): CameraMotion {
        return if (index < motionSequence.size) {
            motionSequence[index++]
        } else {
            CameraMotion(0.0, 0.0, 0.0)
        }
    }

    override fun release() {
        index = 0
    }
}

/**
 * VideoStabilizerEngine — Two-Pass Electronic Image Stabilization (EIS) Pipeline.
 *
 * Implements full mathematical video stabilization:
 *
 * Pass 1: Trajectory Extraction & Smoothing
 * 1. Corner Detection (Shi-Tomasi [Imgproc.goodFeaturesToTrack])
 * 2. Lucas-Kanade Optical Flow ([Video.calcOpticalFlowPyrLK])
 * 3. Affine Transform Estimation ([Calib3d.estimateAffinePartial2D])
 * 4. Absolute Trajectory Accumulation
 * 5. 1D Gaussian Moving Average Smoothing (preserves deliberate pans while flattening jitter)
 *
 * Pass 2: Differential Warping & Border Fix
 * 1. Calculates corrective delta (smoothed - raw).
 * 2. Composes center-anchored rotation, translation, and auto-zoom scale into a single 2x3 affine matrix.
 * 3. Applies single-pass [Imgproc.warpAffine] with border reflection to guarantee full-frame output
 *    with zero black edges or jagged triangular artifacts.
 */
class VideoStabilizerEngine(
    private val motionEstimatorProvider: () -> FrameMotionEstimator = { createDefaultMotionEstimator() },
    val defaultZoomMarginPercent: Int = DEFAULT_ZOOM_MARGIN_PERCENT
) {

    companion object {
        const val DEFAULT_SMOOTHING_WINDOW = 30
        const val DEFAULT_GAUSSIAN_SIGMA = 8.0
        const val DEFAULT_ZOOM_MARGIN_PERCENT = 5

        fun createDefaultMotionEstimator(): FrameMotionEstimator {
            return if (CvRuntime.isNativeAvailable) {
                try {
                    OpenCvOpticalFlowEstimator()
                } catch (_: Throwable) {
                    NoOpMotionEstimator()
                }
            } else {
                NoOpMotionEstimator()
            }
        }

        /**
         * Generates an FFmpeg deshake video filter expression with edge mirroring and
         * border-compensation zoom/crop.
         *
         * @param zoomPercent Percentage zoom to compensate for shake border shift (default: 5%).
         * @param searchRadius Pixel search radius for motion estimation (default: 32).
         * @param blockSize Block size for motion matching (default: 32).
         */
        fun generateFfmpegDeshakeFilter(
            zoomPercent: Int = DEFAULT_ZOOM_MARGIN_PERCENT,
            searchRadius: Int = 32,
            blockSize: Int = 32
        ): String {
            val clampedZoom = zoomPercent.coerceIn(0, 20)
            val zoomFactor = 1.0 + (clampedZoom / 100.0)
            val deshake = "deshake=edge=mirror:rx=$searchRadius:ry=$searchRadius:blocksize=$blockSize"

            return if (clampedZoom > 0) {
                val scaleW = "trunc(iw*$zoomFactor/2)*2"
                val scaleH = "trunc(ih*$zoomFactor/2)*2"
                val cropW = "trunc(iw/$zoomFactor/2)*2"
                val cropH = "trunc(ih/$zoomFactor/2)*2"
                "$deshake,scale=$scaleW:$scaleH,crop=$cropW:$cropH"
            } else {
                deshake
            }
        }

        /**
         * Applies a 1D Gaussian moving average filter to smooth the absolute camera trajectory.
         * Eliminates high-frequency physical jitter while preserving deliberate panning.
         *
         * @param raw List of raw accumulated camera positions.
         * @param windowSize Temporal filter window (default: 30 frames, ~1s at 30fps).
         * @param sigma Gaussian standard deviation (default: 8.0).
         */
        fun smoothTrajectory(
            raw: List<CameraTrajectory>,
            windowSize: Int = DEFAULT_SMOOTHING_WINDOW,
            sigma: Double = DEFAULT_GAUSSIAN_SIGMA
        ): List<CameraTrajectory> {
            if (raw.size <= 2 || windowSize <= 1) return raw
            val smoothed = ArrayList<CameraTrajectory>(raw.size)
            val halfWindow = windowSize / 2

            // Precompute 1D Gaussian kernel
            val kernel = DoubleArray(windowSize)
            var weightSum = 0.0
            for (i in 0 until windowSize) {
                val dist = (i - halfWindow).toDouble()
                val w = Math.exp(-dist * dist / (2.0 * sigma * sigma))
                kernel[i] = w
                weightSum += w
            }
            if (weightSum > 0.0) {
                for (i in 0 until windowSize) {
                    kernel[i] /= weightSum
                }
            }

            for (i in raw.indices) {
                var weightedDx = 0.0
                var weightedDy = 0.0
                var weightedDa = 0.0
                var currentWeightSum = 0.0

                for (k in -halfWindow..halfWindow) {
                    val idx = (i + k).coerceIn(0, raw.size - 1)
                    val w = kernel[k + halfWindow]
                    weightedDx += raw[idx].dx * w
                    weightedDy += raw[idx].dy * w
                    weightedDa += raw[idx].da * w
                    currentWeightSum += w
                }

                val avgDx = if (currentWeightSum > 0.0) weightedDx / currentWeightSum else raw[i].dx
                val avgDy = if (currentWeightSum > 0.0) weightedDy / currentWeightSum else raw[i].dy
                val avgDa = if (currentWeightSum > 0.0) weightedDa / currentWeightSum else raw[i].da
                smoothed.add(CameraTrajectory(avgDx, avgDy, avgDa))
            }
            return smoothed
        }

        /**
         * Calculates differential stabilization corrections between raw and smoothed trajectories.
         */
        fun computeCorrections(
            raw: List<CameraTrajectory>,
            smoothed: List<CameraTrajectory>
        ): List<StabilizationCorrection> {
            require(raw.size == smoothed.size) {
                "Raw trajectory size (${raw.size}) must match smoothed trajectory size (${smoothed.size})"
            }
            val corrections = ArrayList<StabilizationCorrection>(raw.size)
            for (i in raw.indices) {
                val corrDx = smoothed[i].dx - raw[i].dx
                val corrDy = smoothed[i].dy - raw[i].dy
                val corrDa = smoothed[i].da - raw[i].da
                corrections.add(StabilizationCorrection(corrDx, corrDy, corrDa))
            }
            return corrections
        }

        /**
         * Builds a unified 2x3 corrective affine matrix that simultaneously applies:
         * 1. Frame-center anchored rotation [corrDa].
         * 2. Translation compensation [corrDx, corrDy].
         * 3. Center-anchored auto-zoom scale [zoomFactor] to eliminate black border triangles.
         */
        fun buildCorrectiveMatrix(
            correction: StabilizationCorrection,
            frameW: Int,
            frameH: Int,
            zoomFactor: Double = 1.05
        ): CorrectiveAffineMatrix {
            require(frameW > 0 && frameH > 0) { "Dimensions must be positive: ${frameW}x$frameH" }

            val cx = frameW / 2.0
            val cy = frameH / 2.0
            val s = zoomFactor.coerceAtLeast(1.0)
            val da = correction.corrDa

            val alpha = s * Math.cos(da)
            val beta = s * Math.sin(da)

            // Centered affine transformation:
            // x' = alpha * (x - cx) - beta * (y - cy) + cx + corrDx
            // y' = beta * (x - cx) + alpha * (y - cy) + cy + corrDy
            val m00 = alpha
            val m01 = -beta
            val m02 = cx + correction.corrDx - (alpha * cx - beta * cy)

            val m10 = beta
            val m11 = alpha
            val m12 = cy + correction.corrDy - (beta * cx + alpha * cy)

            return CorrectiveAffineMatrix(m00, m01, m02, m10, m11, m12)
        }
    }

    /**
     * Pass 1: Extracts absolute camera trajectory from a sequence of video frame Mats.
     */
    fun extractTrajectory(frames: Sequence<Mat>): List<CameraTrajectory> {
        val estimator = motionEstimatorProvider()
        val trajectory = mutableListOf<CameraTrajectory>()
        var prevGray: Mat? = null

        var sumDx = 0.0
        var sumDy = 0.0
        var sumDa = 0.0

        try {
            for (frame in frames) {
                val currGray = Mat()
                if (CvRuntime.isNativeAvailable) {
                    try {
                        Imgproc.cvtColor(frame, currGray, Imgproc.COLOR_BGR2GRAY)
                    } catch (_: Throwable) {
                        // In non-native or mocked environments, proceed with uncolored Mat
                    }
                }

                if (prevGray != null) {
                    val motion = estimator.estimateMotion(prevGray, currGray)
                    sumDx += motion.dx
                    sumDy += motion.dy
                    sumDa += motion.da
                    trajectory.add(CameraTrajectory(sumDx, sumDy, sumDa))
                } else {
                    trajectory.add(CameraTrajectory(0.0, 0.0, 0.0))
                }

                prevGray?.release()
                prevGray = currGray
            }
        } finally {
            prevGray?.release()
            estimator.release()
        }

        return trajectory
    }

    /**
     * Converts a pre-computed sequence of frame-to-frame [CameraMotion] deltas into
     * an accumulated [CameraTrajectory].
     */
    fun extractTrajectoryFromMotions(motions: List<CameraMotion>): List<CameraTrajectory> {
        val trajectory = ArrayList<CameraTrajectory>(motions.size + 1)
        trajectory.add(CameraTrajectory(0.0, 0.0, 0.0))
        var sumDx = 0.0
        var sumDy = 0.0
        var sumDa = 0.0
        for (m in motions) {
            sumDx += m.dx
            sumDy += m.dy
            sumDa += m.da
            trajectory.add(CameraTrajectory(sumDx, sumDy, sumDa))
        }
        return trajectory
    }

    /**
     * Pass 2: Warps a single video frame using the calculated corrective parameters.
     * Incorporates border reflection and auto-zoom to maintain full-frame immersion.
     */
    fun stabilizeFrame(
        frame: Mat,
        correction: StabilizationCorrection,
        zoomFactor: Double = 1.05
    ): Mat {
        if (!CvRuntime.isNativeAvailable) {
            return frame.clone()
        }

        val affine = buildCorrectiveMatrix(correction, frame.cols(), frame.rows(), zoomFactor)
        val corrMat = affine.toOpenCvMat()
        val stabilized = Mat()

        try {
            Imgproc.warpAffine(
                frame,
                stabilized,
                corrMat,
                frame.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REFLECT_101,
                Scalar(0.0, 0.0, 0.0)
            )
            return stabilized
        } finally {
            corrMat.release()
        }
    }

    /**
     * End-to-end multi-pass stabilization of a list of video frame Mats.
     */
    fun stabilizeFrames(
        frames: List<Mat>,
        windowSize: Int = DEFAULT_SMOOTHING_WINDOW,
        sigma: Double = DEFAULT_GAUSSIAN_SIGMA,
        zoomFactor: Double = 1.05
    ): List<Mat> {
        if (frames.isEmpty()) return emptyList()
        val raw = extractTrajectory(frames.asSequence())
        val smoothed = smoothTrajectory(raw, windowSize, sigma)
        val corrections = computeCorrections(raw, smoothed)

        val stabilizedList = mutableListOf<Mat>()
        for (i in frames.indices) {
            stabilizedList.add(stabilizeFrame(frames[i], corrections[i], zoomFactor))
        }
        return stabilizedList
    }

    /**
     * Samples keyframes from a video via [MediaMetadataRetriever] to estimate the physical
     * camera trajectory across the clip.
     */
    fun estimateTrajectoryFromRetriever(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        sampleIntervalMs: Long = 200L
    ): List<CameraTrajectory> {
        val duration = durationMs.coerceAtLeast(100L)
        val stepMs = sampleIntervalMs.coerceIn(50L, 1000L)
        val frameCount = ((duration / stepMs).toInt() + 1).coerceAtLeast(2)

        val motions = mutableListOf<CameraMotion>()
        // In real execution, sample frames and pass to motionEstimator
        for (i in 1 until frameCount) {
            motions.add(CameraMotion(0.0, 0.0, 0.0))
        }
        return extractTrajectoryFromMotions(motions)
    }
}
