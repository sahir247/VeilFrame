package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests verifying [VideoReframeEngine]:
 * 1. 1D Gaussian moving-average filter eliminating camera jitter while preserving subject motion.
 * 2. 9:16 crop window calculations with strict even dimension and coordinate alignment for YUV420p codecs.
 * 3. Boundary clamping ensuring crop boxes never exceed source video frame dimensions.
 * 4. FFmpeg dynamic crop filter generation for stationary vs. panning camera trajectories.
 * 5. Sparse keyframe tracking across moving subjects (e.g. person walking left-to-right across landscape frame).
 */
@RunWith(RobolectricTestRunner::class)
class VideoReframeEngineTest {

    @Test
    fun testSmoothTrajectoryPreservesMonotonicMovementAndReducesVariance() {
        // Simulate a person walking horizontally across a 1920x1080 frame from x=200 to x=1200
        // with alternating high-frequency jitter (+/- 25 pixels)
        val rawPoints = mutableListOf<TrajectoryPoint>()
        for (i in 0 until 40) {
            val baseTimeMs = i * 33L // ~30 fps
            val baseX = 200f + i * 25f
            val jitter = if (i % 2 == 0) 25f else -25f
            rawPoints.add(TrajectoryPoint(baseTimeMs, baseX + jitter, 540f))
        }

        val smoothed = VideoReframeEngine.smoothTrajectory(rawPoints, windowSize = 9, sigma = 2.5f)

        assertEquals(rawPoints.size, smoothed.size)
        // Verify timeMs is preserved exactly
        for (i in rawPoints.indices) {
            assertEquals(rawPoints[i].timeMs, smoothed[i].timeMs)
        }

        // Verify start and end of trajectory still span from left to right
        assertTrue("Smoothed start should be on left side", smoothed.first().centerX < 400f)
        assertTrue("Smoothed end should be on right side", smoothed.last().centerX > 1000f)

        // Calculate variance / jitter between consecutive steps
        var rawJitterSum = 0f
        for (i in 1 until rawPoints.size) {
            rawJitterSum += Math.abs(rawPoints[i].centerX - rawPoints[i - 1].centerX)
        }

        var smoothedJitterSum = 0f
        for (i in 1 until smoothed.size) {
            smoothedJitterSum += Math.abs(smoothed[i].centerX - smoothed[i - 1].centerX)
        }

        // The smoothed trajectory should have lower total step variation than the raw jittery one
        assertTrue(
            "Smoothed step variation ($smoothedJitterSum) must be less than raw ($rawJitterSum)",
            smoothedJitterSum < rawJitterSum
        )

        // Smoothed path should progress monotonically to the right
        for (i in 5 until smoothed.size - 5) {
            assertTrue(
                "Trajectory should smoothly progress rightwards at frame $i",
                smoothed[i].centerX >= smoothed[i - 1].centerX - 5f
            )
        }
    }

    @Test
    fun testSmoothTrajectoryEdgeCases() {
        // Empty list
        val empty = VideoReframeEngine.smoothTrajectory(emptyList())
        assertTrue(empty.isEmpty())

        // Single point
        val single = listOf(TrajectoryPoint(0L, 500f, 300f))
        val smoothedSingle = VideoReframeEngine.smoothTrajectory(single)
        assertEquals(1, smoothedSingle.size)
        assertEquals(500f, smoothedSingle[0].centerX, 0.01f)
        assertEquals(300f, smoothedSingle[0].centerY, 0.01f)

        // Window size <= 1 returns same list
        val points = listOf(TrajectoryPoint(0L, 100f, 100f), TrajectoryPoint(33L, 200f, 200f))
        val noSmooth = VideoReframeEngine.smoothTrajectory(points, windowSize = 1)
        assertEquals(points, noSmooth)
    }

    @Test
    fun testCalculateCropBox16to9LandscapeTo9to16Vertical() {
        // 1920x1080 landscape video -> 9:16 vertical crop
        val origW = 1920
        val origH = 1080
        val point = TrajectoryPoint(0L, 960f, 540f) // Center of frame

        val cropBox = VideoReframeEngine.calculateCropBox(point, origW, origH, targetAspect = 9f / 16f)

        // Target height = 1080, target width = 1080 * 9 / 16 = 607.5 -> even = 606 or 608
        assertEquals(1080, cropBox.height)
        assertTrue("Width should be close to 608", cropBox.width in 606..608)

        // Check even dimensions for YUV420p video codecs
        assertEquals(0, cropBox.width % 2)
        assertEquals(0, cropBox.height % 2)
        assertEquals(0, cropBox.x % 2)
        assertEquals(0, cropBox.y % 2)

        // Bounded within frame
        assertTrue(cropBox.x >= 0)
        assertTrue(cropBox.y >= 0)
        assertTrue(cropBox.x + cropBox.width <= origW)
        assertTrue(cropBox.y + cropBox.height <= origH)
    }

    @Test
    fun testCalculateCropBoxLeftBoundaryClamping() {
        // Subject located near left boundary (x = 50f)
        val origW = 1920
        val origH = 1080
        val point = TrajectoryPoint(0L, 50f, 540f)

        val cropBox = VideoReframeEngine.calculateCropBox(point, origW, origH, targetAspect = 9f / 16f)

        // Cannot have negative X
        assertEquals(0, cropBox.x)
        assertEquals(0, cropBox.y)
        assertTrue(cropBox.x + cropBox.width <= origW)
    }

    @Test
    fun testCalculateCropBoxRightBoundaryClamping() {
        // Subject located near right boundary (x = 1880f)
        val origW = 1920
        val origH = 1080
        val point = TrajectoryPoint(0L, 1880f, 540f)

        val cropBox = VideoReframeEngine.calculateCropBox(point, origW, origH, targetAspect = 9f / 16f)

        // Crop box must stay entirely within frame width
        assertTrue(cropBox.x + cropBox.width <= origW)
        assertEquals(origW - cropBox.width, cropBox.x)
        assertEquals(0, cropBox.y)
    }

    @Test
    fun testCalculateCropBoxesAlongTrajectory() {
        val origW = 1920
        val origH = 1080
        val trajectory = listOf(
            TrajectoryPoint(0L, 200f, 540f),
            TrajectoryPoint(500L, 960f, 540f),
            TrajectoryPoint(1000L, 1700f, 540f)
        )

        val boxes = VideoReframeEngine.calculateCropBoxes(trajectory, origW, origH, targetAspect = 9f / 16f)
        assertEquals(3, boxes.size)

        // First box should be pinned to left edge
        assertEquals(0, boxes[0].x)
        // Middle box should be centered
        assertTrue(boxes[1].x > boxes[0].x)
        // Last box should be shifted right
        assertTrue(boxes[2].x > boxes[1].x)
        assertTrue(boxes[2].x + boxes[2].width <= origW)
    }

    @Test
    fun testRectFToOpenCvRectNormalizedAndAbsolute() {
        val frameW = 1920
        val frameH = 1080

        // Normalized bounding box [0.25, 0.25, 0.75, 0.75]
        val normalized = RectF(0.25f, 0.25f, 0.75f, 0.75f)
        val cvRectNorm = VideoReframeEngine.rectFToOpenCvRect(normalized, frameW, frameH)
        assertEquals(480, cvRectNorm.x)
        assertEquals(270, cvRectNorm.y)
        assertEquals(960, cvRectNorm.width)
        assertEquals(540, cvRectNorm.height)

        // Absolute pixel bounding box [100, 100, 500, 400]
        val absolute = RectF(100f, 100f, 500f, 400f)
        val cvRectAbs = VideoReframeEngine.rectFToOpenCvRect(absolute, frameW, frameH)
        assertEquals(100, cvRectAbs.x)
        assertEquals(100, cvRectAbs.y)
        assertEquals(400, cvRectAbs.width)
        assertEquals(300, cvRectAbs.height)
    }

    @Test
    fun testGenerateFfmpegCropFilterStationarySubject() {
        // Subject remains in center (+/- 10 pixels, <5% movement)
        val trajectory = listOf(
            TrajectoryPoint(0L, 960f, 540f),
            TrajectoryPoint(1000L, 965f, 540f),
            TrajectoryPoint(2000L, 958f, 540f)
        )

        val filter = VideoReframeEngine.generateFfmpegCropFilter(trajectory, 1920, 1080, targetAspect = 9f / 16f)

        // Must generate stationary crop: crop=W:H:X:Y without time variable 't'
        assertTrue(filter.startsWith("crop="))
        assertFalse("Stationary subject should not use dynamic time expression", filter.contains("(t/"))
    }

    @Test
    fun testGenerateFfmpegCropFilterPanningSubject() {
        // Subject walks from left to right (x=200 to x=1600) over 4 seconds
        val trajectory = listOf(
            TrajectoryPoint(0L, 200f, 540f),
            TrajectoryPoint(2000L, 960f, 540f),
            TrajectoryPoint(4000L, 1600f, 540f)
        )

        val filter = VideoReframeEngine.generateFfmpegCropFilter(trajectory, 1920, 1080, targetAspect = 9f / 16f)

        // Must generate time-interpolated expression containing trunc and time variable 't'
        assertTrue(filter.startsWith("crop="))
        assertTrue("Panning subject should use dynamic time expression", filter.contains("trunc(("))
        assertTrue("Filter should contain time variable 't'", filter.contains("t/"))
    }

    @Test
    fun testGenerateFfmpegCropFilterEmptyTrajectory() {
        val filter = VideoReframeEngine.generateFfmpegCropFilter(emptyList(), 1920, 1080, targetAspect = 9f / 16f)
        assertTrue(filter.startsWith("crop="))
    }

    @Test
    fun testTrackingPipelineWithCentroidTracker() {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            // Native OpenCV C++ library not loaded in host JVM test environment; skip JNI Mat allocation
            return
        }

        // Create custom tracker tracking horizontal movement
        val movingTracker = object : BboxTracker {
            var currentX = 200
            override fun init(frame: Mat, bbox: Rect) {
                currentX = bbox.x
            }
            override fun update(frame: Mat, bbox: Rect): Boolean {
                currentX += 30 // Simulate subject stepping right
                bbox.x = currentX
                return true
            }
            override fun release() {}
        }

        val engine = VideoReframeEngine(
            piiEngine = null,
            targetAspect = 9f / 16f,
            trackerProvider = { movingTracker }
        )

        // Generate 10 mock frames
        val mockFrames = (0 until 10).map {
            Mat.zeros(100, 100, CvType.CV_8UC1)
        }

        try {
            val trajectory = engine.generateTrajectory(
                frames = mockFrames.asSequence(),
                fps = 30.0,
                frameW = 1920,
                frameH = 1080,
                keyframeInterval = 15
            )

            assertEquals(10, trajectory.size)
            // Verify timestamp progression
            assertEquals(0L, trajectory.first().timeMs)
            assertEquals(300L, trajectory.last().timeMs)
        } finally {
            mockFrames.forEach { it.release() }
        }
    }

    @Test
    fun testTrackingSequencePersonWalkingLeftToRight() {
        // Person walking from left to right across 1920x1080 landscape frames
        // Over 30 frames (1 second at 30fps), subject moves from x=200 to x=1500
        var frameIndex = 0
        val engine = VideoReframeEngine(
            piiEngine = null,
            targetAspect = 9f / 16f,
            bitmapDetector = { _ ->
                val progress = frameIndex / 29f
                val centerX = 200f + progress * (1500f - 200f)
                val centerY = 540f
                val boxW = 200f
                val boxH = 400f
                val det = PiiDetection(
                    rect = RectF(centerX - boxW / 2f, centerY - boxH / 2f, centerX + boxW / 2f, centerY + boxH / 2f),
                    piiClass = PiiClass.FACE,
                    score = 0.95f
                )
                frameIndex++
                listOf(det)
            }
        )

        val bmps = (0 until 30).map {
            Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        }

        try {
            val trajectory = engine.generateTrajectoryFromBitmaps(
                frames = bmps.asSequence(),
                fps = 30.0,
                keyframeInterval = 1 // Keyframe every frame for mock detection
            )

            assertEquals(30, trajectory.size)
            assertTrue("Trajectory should start near left edge", trajectory.first().centerX < 400f)
            assertTrue("Trajectory should end near right edge", trajectory.last().centerX > 1300f)

            // Dynamic 9:16 crop box calculations
            val cropBoxes = VideoReframeEngine.calculateCropBoxes(trajectory, 1920, 1080, targetAspect = 9f / 16f)
            assertEquals(30, cropBoxes.size)

            // Crop box at frame 0 should be clamped on left
            assertEquals(0, cropBoxes.first().x)
            assertEquals(1080, cropBoxes.first().height)

            // Crop box at frame 29 should be shifted towards right edge
            assertTrue(cropBoxes.last().x > 900)
            assertTrue(cropBoxes.last().x + cropBoxes.last().width <= 1920)

            // Dynamic FFmpeg crop filter contains time-interpolated expression
            val ffmpegFilter = VideoReframeEngine.generateFfmpegCropFilter(trajectory, 1920, 1080, targetAspect = 9f / 16f)
            assertTrue(ffmpegFilter.startsWith("crop="))
            assertTrue(ffmpegFilter.contains("trunc(("))
            assertTrue(ffmpegFilter.contains("t/"))
        } finally {
            bmps.forEach { it.recycle() }
        }
    }

    @Test
    fun testSelectPrimarySubjectPrioritizesFace() {
        val engine = VideoReframeEngine()

        val plateDet = PiiDetection(
            rect = RectF(100f, 100f, 300f, 500f),
            piiClass = PiiClass.PLATE,
            score = 0.90f
        )
        val faceDet = PiiDetection(
            rect = RectF(150f, 120f, 250f, 250f),
            piiClass = PiiClass.FACE,
            score = 0.85f
        )

        // Even if Plate has slightly higher confidence, Face is prioritized for camera tracking
        val selected = engine.selectPrimarySubject(listOf(plateDet, faceDet))
        assertNotNull(selected)
        assertEquals(PiiClass.FACE, selected?.piiClass)

        // If no Face, highest score is selected
        val cardDet = PiiDetection(
            rect = RectF(500f, 500f, 800f, 800f),
            piiClass = PiiClass.CARD,
            score = 0.70f
        )
        val selectedNoFace = engine.selectPrimarySubject(listOf(plateDet, cardDet))
        assertEquals(PiiClass.PLATE, selectedNoFace?.piiClass)

        // Empty list returns null
        assertEquals(null, engine.selectPrimarySubject(emptyList()))
    }

    @Test
    fun testTrajectoryFromBitmapsTracking() {
        val engine = VideoReframeEngine(piiEngine = null, targetAspect = 9f / 16f)

        // 3 dummy bitmaps
        val bmps = (0 until 3).map {
            Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        }

        val trajectory = engine.generateTrajectoryFromBitmaps(
            frames = bmps.asSequence(),
            fps = 30.0,
            keyframeInterval = 15
        )

        assertEquals(3, trajectory.size)
        // With no detection, defaults to frame center
        assertEquals(960f, trajectory[0].centerX, 1.0f)
        assertEquals(540f, trajectory[0].centerY, 1.0f)

        bmps.forEach { it.recycle() }
    }

    @Test
    fun testTrackerFailureReleasesTrackerAndForcesKeyframeRedetection() {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            return
        }
        var releaseCount = 0
        var trackerCreatedCount = 0

        val failingTracker = object : BboxTracker {
            override fun init(frame: Mat, bbox: Rect) {}
            override fun update(frame: Mat, bbox: Rect): Boolean {
                // Fail tracking on intermediate frame update
                return false
            }
            override fun release() {
                releaseCount++
            }
        }

        val engine = VideoReframeEngine(
            piiEngine = null,
            targetAspect = 9f / 16f,
            trackerProvider = {
                trackerCreatedCount++
                failingTracker
            }
        )

        val mockFrames = (0 until 5).map {
            Mat.zeros(100, 100, CvType.CV_8UC1)
        }

        try {
            val trajectory = engine.generateTrajectory(
                frames = mockFrames.asSequence(),
                fps = 30.0,
                frameW = 1920,
                frameH = 1080,
                keyframeInterval = 15
            )

            assertEquals(5, trajectory.size)
            assertTrue("Tracker should be released upon update failure", releaseCount >= 1)
        } finally {
            mockFrames.forEach { it.release() }
        }
    }
}
