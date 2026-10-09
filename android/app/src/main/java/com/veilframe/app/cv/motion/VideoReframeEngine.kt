package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import com.veilframe.app.privacy.PiiOnnxEngine
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.video.Tracker
import org.opencv.video.TrackerMIL
import java.util.Locale

/**
 * TrajectoryPoint — Temporal spatial anchor tracking the subject's center coordinates.
 *
 * @param timeMs Video timestamp in milliseconds.
 * @param centerX Normalized or absolute pixel horizontal center of the tracked subject.
 * @param centerY Normalized or absolute pixel vertical center of the tracked subject.
 */
data class TrajectoryPoint(
    val timeMs: Long,
    val centerX: Float,
    val centerY: Float
)

/**
 * Pluggable abstraction for bounding box tracking across video frames.
 * Uses native OpenCV [TrackerMIL] in production, and falls back to a deterministic
 * centroid tracker on host JVM / unit test environments.
 */
interface BboxTracker {
    fun init(frame: Mat, bbox: Rect)
    fun update(frame: Mat, bbox: Rect): Boolean
    fun release()
}

/**
 * OpenCV [TrackerMIL] implementation for native Android hardware.
 */
class OpenCvMilTracker(private val tracker: Tracker = TrackerMIL.create()) : BboxTracker {
    override fun init(frame: Mat, bbox: Rect) {
        tracker.init(frame, bbox)
    }

    override fun update(frame: Mat, bbox: Rect): Boolean {
        return tracker.update(frame, bbox)
    }

    override fun release() {}
}

/**
 * Deterministic centroid tracker for JVM / mock environments without native OpenCV libs.
 */
class CentroidTracker : BboxTracker {
    private var lastBox: Rect? = null

    override fun init(frame: Mat, bbox: Rect) {
        lastBox = Rect(bbox.x, bbox.y, bbox.width, bbox.height)
    }

    override fun update(frame: Mat, bbox: Rect): Boolean {
        val last = lastBox ?: return false
        bbox.x = last.x
        bbox.y = last.y
        bbox.width = last.width
        bbox.height = last.height
        return true
    }

    override fun release() {
        lastBox = null
    }
}

/**
 * VideoReframeEngine — Intelligent Sparse Keyframe Detection & Tracking Engine for 9:16 Auto-Reframing.
 *
 * Solves the mobile thermal/battery bottleneck:
 * 1. Runs YOLOv8 ONNX inference sparsely (only on keyframes, e.g. every 15 frames / 0.5s).
 * 2. Uses lightweight OpenCV [TrackerMIL] on intermediate frames.
 * 3. Applies a 1D Gaussian moving average filter to generate cinematic, jitter-free camera pans.
 * 4. Computes exact clamped, codec-aligned 9:16 crop boundaries.
 */
class VideoReframeEngine(
    private val piiEngine: PiiOnnxEngine? = null,
    val targetAspect: Float = 9f / 16f,
    private val trackerProvider: () -> BboxTracker = { createDefaultTracker() },
    private val bitmapDetector: ((Bitmap) -> List<PiiDetection>)? = null
) {

    companion object {
        const val DEFAULT_KEYFRAME_INTERVAL = 15
        const val DEFAULT_SMOOTHING_WINDOW = 15
        const val DEFAULT_GAUSSIAN_SIGMA = 4.0f

        fun createDefaultTracker(): BboxTracker {
            return if (CvRuntime.isNativeAvailable) {
                try {
                    OpenCvMilTracker(TrackerMIL.create())
                } catch (t: Throwable) {
                    CentroidTracker()
                }
            } else {
                CentroidTracker()
            }
        }

        /**
         * Converts a normalized or absolute [RectF] to an OpenCV [Rect], clamped within frame bounds.
         */
        fun rectFToOpenCvRect(rect: RectF, frameW: Int, frameH: Int): Rect {
            val left = if (rect.right <= 1.0f && rect.bottom <= 1.0f) rect.left * frameW else rect.left
            val top = if (rect.right <= 1.0f && rect.bottom <= 1.0f) rect.top * frameH else rect.top
            val right = if (rect.right <= 1.0f && rect.bottom <= 1.0f) rect.right * frameW else rect.right
            val bottom = if (rect.right <= 1.0f && rect.bottom <= 1.0f) rect.bottom * frameH else rect.bottom

            val x = left.toInt().coerceIn(0, frameW - 1)
            val y = top.toInt().coerceIn(0, frameH - 1)
            val w = (right - left).toInt().coerceIn(1, frameW - x)
            val h = (bottom - top).toInt().coerceIn(1, frameH - y)
            return Rect(x, y, w, h)
        }

        /**
         * Applies a 1D Gaussian moving average filter to simulate a smooth, cinematic camera pan.
         * Eliminates high-frequency jitter and sudden snapping.
         */
        fun smoothTrajectory(
            raw: List<TrajectoryPoint>,
            windowSize: Int = DEFAULT_SMOOTHING_WINDOW,
            sigma: Float = DEFAULT_GAUSSIAN_SIGMA
        ): List<TrajectoryPoint> {
            if (raw.size <= 2 || windowSize <= 1) return raw
            val smoothed = mutableListOf<TrajectoryPoint>()
            val halfWindow = windowSize / 2

            // Precompute 1D Gaussian kernel
            val kernel = FloatArray(windowSize)
            var weightSum = 0f
            for (i in 0 until windowSize) {
                val dist = (i - halfWindow).toFloat()
                val w = Math.exp((-dist * dist / (2 * sigma * sigma)).toDouble()).toFloat()
                kernel[i] = w
                weightSum += w
            }
            if (weightSum > 0f) {
                for (i in 0 until windowSize) {
                    kernel[i] /= weightSum
                }
            }

            for (i in raw.indices) {
                var weightedX = 0f
                var weightedY = 0f
                var currentWeightSum = 0f

                for (k in -halfWindow..halfWindow) {
                    val idx = (i + k).coerceIn(0, raw.size - 1)
                    val w = kernel[k + halfWindow]
                    weightedX += raw[idx].centerX * w
                    weightedY += raw[idx].centerY * w
                    currentWeightSum += w
                }

                val avgX = if (currentWeightSum > 0f) weightedX / currentWeightSum else raw[i].centerX
                val avgY = if (currentWeightSum > 0f) weightedY / currentWeightSum else raw[i].centerY
                smoothed.add(TrajectoryPoint(raw[i].timeMs, avgX, avgY))
            }
            return smoothed
        }

        /**
         * Calculates an even-aligned, boundary-clamped crop box centered on a given trajectory point.
         */
        fun calculateCropBox(
            point: TrajectoryPoint,
            origW: Int,
            origH: Int,
            targetAspect: Float = 9f / 16f
        ): Rect {
            require(origW > 0 && origH > 0) { "Dimensions must be positive" }

            var targetH = origH
            var targetW = Math.round(targetH * targetAspect)

            // If target aspect is wider than original (portrait -> square), fit inside width
            if (targetW > origW) {
                targetW = origW
                targetH = Math.round(targetW / targetAspect)
            }

            // Video encoders strictly require even dimensions for YUV420p
            if (targetW % 2 != 0) targetW--
            if (targetH % 2 != 0) targetH--
            targetW = targetW.coerceAtLeast(2)
            targetH = targetH.coerceAtLeast(2)

            var x = Math.round(point.centerX - targetW / 2f)
            var y = Math.round(point.centerY - targetH / 2f)

            // Clamp inside frame boundaries
            x = x.coerceIn(0, origW - targetW)
            y = y.coerceIn(0, origH - targetH)

            // Ensure even offset for subsampled chroma planes
            if (x % 2 != 0 && x > 0) x--
            if (y % 2 != 0 && y > 0) y--

            return Rect(x, y, targetW, targetH)
        }

        /**
         * Calculates crop boxes for all points along a trajectory.
         */
        fun calculateCropBoxes(
            trajectory: List<TrajectoryPoint>,
            origW: Int,
            origH: Int,
            targetAspect: Float = 9f / 16f
        ): List<Rect> {
            return trajectory.map { point ->
                calculateCropBox(point, origW, origH, targetAspect)
            }
        }

        /**
         * Generates an FFmpeg crop filter string based on the camera trajectory.
         * For steady subjects, uses exact centered framing. For panning subjects,
         * generates a time-interpolated expression.
         */
        fun generateFfmpegCropFilter(
            trajectory: List<TrajectoryPoint>,
            origW: Int,
            origH: Int,
            targetAspect: Float = 9f / 16f
        ): String {
            if (trajectory.isEmpty()) {
                val defaultBox = calculateCropBox(
                    TrajectoryPoint(0, origW / 2f, origH / 2f),
                    origW,
                    origH,
                    targetAspect
                )
                return "crop=${defaultBox.width}:${defaultBox.height}:${defaultBox.x}:${defaultBox.y}"
            }

            val cropBoxes = calculateCropBoxes(trajectory, origW, origH, targetAspect)
            val firstBox = cropBoxes.first()
            val lastBox = cropBoxes.last()
            val minX = cropBoxes.minOf { it.x }
            val maxX = cropBoxes.maxOf { it.x }
            val targetW = firstBox.width
            val targetH = firstBox.height

            // If subject movement is minimal (<5% of width), use stationary center crop
            if ((maxX - minX) <= origW * 0.05f) {
                val avgX = cropBoxes.map { it.x }.average().toInt().let { if (it % 2 != 0) it - 1 else it }
                val avgY = cropBoxes.map { it.y }.average().toInt().let { if (it % 2 != 0) it - 1 else it }
                return "crop=$targetW:$targetH:$avgX:$avgY"
            }

            // For dynamic panning: generate a smooth time-interpolated expression
            val durSec = ((trajectory.last().timeMs - trajectory.first().timeMs) / 1000.0).coerceAtLeast(1.0)
            val x0 = firstBox.x
            val x1 = lastBox.x
            val y0 = firstBox.y

            return String.format(
                Locale.US,
                "crop=%d:%d:trunc((%d+(%d-%d)*(t/%.3f))/2)*2:%d",
                targetW,
                targetH,
                x0,
                x1,
                x0,
                durSec,
                y0
            )
        }
    }

    /**
     * Executes the Sparse Keyframe Tracking pipeline over a sequence of OpenCV [Mat] frames.
     */
    fun generateTrajectory(
        frames: Sequence<Mat>,
        fps: Double,
        frameW: Int,
        frameH: Int,
        keyframeInterval: Int = DEFAULT_KEYFRAME_INTERVAL
    ): List<TrajectoryPoint> {
        val trajectory = mutableListOf<TrajectoryPoint>()
        var activeTracker: BboxTracker? = null
        var currentBbox: Rect? = null
        var framesSinceDetection = 0

        for ((index, frame) in frames.withIndex()) {
            val timeMs = (index * 1000.0 / fps).toLong()
            val needsRedetection = activeTracker == null || framesSinceDetection >= keyframeInterval

            if (needsRedetection) {
                // 1. Heavy ONNX inference on Keyframe
                val detections = piiEngine?.detect(frame, confThreshold = 0.40f) ?: emptyList()
                val subject = selectPrimarySubject(detections)

                if (subject != null) {
                    currentBbox = rectFToOpenCvRect(subject.rect, frameW, frameH)
                    activeTracker?.release()
                    activeTracker = trackerProvider()
                    activeTracker.init(frame, currentBbox)
                    framesSinceDetection = 0
                } else {
                    framesSinceDetection++
                }
            } else {
                // 2. Lightweight tracking on intermediate frames
                if (currentBbox != null) {
                    val updatedBox = Rect(currentBbox.x, currentBbox.y, currentBbox.width, currentBbox.height)
                    val success = activeTracker.update(frame, updatedBox)
                    if (success) {
                        currentBbox = updatedBox
                    } else {
                        // Lost track; force re-detection on next frame
                        framesSinceDetection = keyframeInterval
                    }
                }
                framesSinceDetection++
            }

            val bbox = currentBbox ?: Rect(frameW / 4, frameH / 4, frameW / 2, frameH / 2)
            val centerX = bbox.x + bbox.width / 2f
            val centerY = bbox.y + bbox.height / 2f
            trajectory.add(TrajectoryPoint(timeMs, centerX, centerY))
        }

        activeTracker?.release()
        return smoothTrajectory(trajectory)
    }

    /**
     * Executes the Sparse Keyframe Tracking pipeline over a sequence of Android [Bitmap] frames.
     */
    fun generateTrajectoryFromBitmaps(
        frames: Sequence<Bitmap>,
        fps: Double,
        keyframeInterval: Int = DEFAULT_KEYFRAME_INTERVAL
    ): List<TrajectoryPoint> {
        val trajectory = mutableListOf<TrajectoryPoint>()
        var lastCenterX = -1f
        var lastCenterY = -1f

        for ((index, bmp) in frames.withIndex()) {
            val timeMs = (index * 1000.0 / fps).toLong()
            val isKeyframe = index % keyframeInterval == 0 || lastCenterX < 0f

            if (isKeyframe) {
                val detections = bitmapDetector?.invoke(bmp) ?: piiEngine?.detect(bmp, confThreshold = 0.40f) ?: emptyList()
                val subject = selectPrimarySubject(detections)
                if (subject != null) {
                    lastCenterX = (subject.rect.left + subject.rect.right) / 2f
                    lastCenterY = (subject.rect.top + subject.rect.bottom) / 2f
                } else if (lastCenterX < 0f) {
                    lastCenterX = bmp.width / 2f
                    lastCenterY = bmp.height / 2f
                }
            }
            trajectory.add(TrajectoryPoint(timeMs, lastCenterX, lastCenterY))
        }

        return smoothTrajectory(trajectory)
    }

    /**
     * Samples keyframes from a video file via [MediaMetadataRetriever] and constructs a smooth trajectory.
     */
    fun generateTrajectoryFromRetriever(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        fps: Double = 30.0,
        sampleIntervalMs: Long = 500L
    ): List<TrajectoryPoint> {
        val trajectory = mutableListOf<TrajectoryPoint>()
        var timeUs = 0L
        val stepUs = sampleIntervalMs * 1000L
        val maxUs = durationMs * 1000L

        var defaultW = 1920
        var defaultH = 1080
        var lastCenterX = defaultW / 2f
        var lastCenterY = defaultH / 2f

        while (timeUs <= maxUs) {
            val frameBmp = try {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (ignored: Exception) {
                null
            }

            if (frameBmp != null) {
                defaultW = frameBmp.width
                defaultH = frameBmp.height
                val detections = piiEngine?.detect(frameBmp, confThreshold = 0.40f) ?: emptyList()
                val subject = selectPrimarySubject(detections)

                if (subject != null) {
                    lastCenterX = (subject.rect.left + subject.rect.right) / 2f
                    lastCenterY = (subject.rect.top + subject.rect.bottom) / 2f
                }
                frameBmp.recycle()
            }

            trajectory.add(TrajectoryPoint(timeUs / 1000L, lastCenterX, lastCenterY))
            timeUs += stepUs
        }

        return smoothTrajectory(trajectory)
    }

    /**
     * Prioritizes FACES, persons, and prominent subjects from candidate detections.
     */
    internal fun selectPrimarySubject(detections: List<PiiDetection>): PiiDetection? {
        if (detections.isEmpty()) return null
        // 1. Prefer Face detections
        val face = detections.filter { it.piiClass == PiiClass.FACE }.maxByOrNull { it.score }
        if (face != null) return face

        // 2. Otherwise pick largest confidence bounding box
        return detections.maxByOrNull { it.score }
    }
}
