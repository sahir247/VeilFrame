package com.veilframe.app.cv.motion

import com.veilframe.app.media.VideoEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests verifying [VideoStabilizerEngine]:
 * 1. Motion accumulation and trajectory extraction across consecutive frames.
 * 2. 1D Gaussian moving-average filter smoothing camera jitter while preserving intentional panning.
 * 3. Exact mathematical inverse property of stabilization corrections.
 * 4. Center-anchored rotation, translation, and auto-zoom scale composition in 2x3 affine matrix.
 * 5. Elimination of black border triangles via 5% zoom border-crop guarantee.
 * 6. FFmpeg deshake filter string generation with even-aligned scale and crop expressions.
 * 7. State wiring and lifecycle behavior in [VideoEditState].
 */
@RunWith(RobolectricTestRunner::class)
class VideoStabilizerEngineTest {

    @Test
    fun testTrajectoryAccumulationFromMotions() {
        val motions = listOf(
            CameraMotion(10.0, 5.0, 0.05),
            CameraMotion(-2.0, 3.0, -0.01),
            CameraMotion(5.0, -4.0, 0.02)
        )

        val engine = VideoStabilizerEngine()
        val trajectory = engine.extractTrajectoryFromMotions(motions)

        // Trajectory contains initial origin frame (0, 0, 0) + accumulated frames
        assertEquals(4, trajectory.size)

        // Origin frame
        assertEquals(0.0, trajectory[0].dx, 1e-6)
        assertEquals(0.0, trajectory[0].dy, 1e-6)
        assertEquals(0.0, trajectory[0].da, 1e-6)
        assertEquals(0.0, trajectory[0].x, 1e-6)
        assertEquals(0.0, trajectory[0].y, 1e-6)
        assertEquals(0.0, trajectory[0].a, 1e-6)

        // Frame 1: (10.0, 5.0, 0.05)
        assertEquals(10.0, trajectory[1].dx, 1e-6)
        assertEquals(5.0, trajectory[1].dy, 1e-6)
        assertEquals(0.05, trajectory[1].da, 1e-6)

        // Frame 2: (10.0 - 2.0 = 8.0, 5.0 + 3.0 = 8.0, 0.05 - 0.01 = 0.04)
        assertEquals(8.0, trajectory[2].dx, 1e-6)
        assertEquals(8.0, trajectory[2].dy, 1e-6)
        assertEquals(0.04, trajectory[2].da, 1e-6)

        // Frame 3: (8.0 + 5.0 = 13.0, 8.0 - 4.0 = 4.0, 0.04 + 0.02 = 0.06)
        assertEquals(13.0, trajectory[3].dx, 1e-6)
        assertEquals(4.0, trajectory[3].dy, 1e-6)
        assertEquals(0.06, trajectory[3].da, 1e-6)
    }

    @Test
    fun testSmoothTrajectoryReducesHighFrequencyJitterWhilePreservingPan() {
        // Simulate a camera panning rightward at +20px per frame,
        // contaminated with high-frequency handheld physical shake (+/- 15px alternating)
        val rawList = mutableListOf<CameraTrajectory>()
        var sumX = 0.0
        var sumY = 0.0
        var sumA = 0.0

        for (i in 0 until 50) {
            val panX = 20.0
            val panY = 5.0
            val panA = 0.005
            val jitterX = if (i % 2 == 0) 15.0 else -15.0
            val jitterY = if (i % 2 == 0) -10.0 else 10.0
            val jitterA = if (i % 2 == 0) 0.03 else -0.03

            sumX += panX + jitterX
            sumY += panY + jitterY
            sumA += panA + jitterA
            rawList.add(CameraTrajectory(sumX, sumY, sumA))
        }

        val smoothed = VideoStabilizerEngine.smoothTrajectory(rawList, windowSize = 15, sigma = 4.0)

        assertEquals(rawList.size, smoothed.size)

        // 1. Verify intentional pan is preserved
        assertTrue("Camera should pan rightwards", smoothed.last().dx > smoothed.first().dx + 500.0)
        assertTrue("Camera should pan downwards", smoothed.last().dy > smoothed.first().dy + 100.0)

        // 2. Compute inter-frame acceleration / jerkiness variation (|Δ_i - Δ_{i-1}|)
        var rawJerkSumX = 0.0
        var rawJerkSumY = 0.0
        var rawJerkSumA = 0.0
        for (i in 2 until rawList.size) {
            val rawStepX2 = rawList[i].dx - rawList[i - 1].dx
            val rawStepX1 = rawList[i - 1].dx - rawList[i - 2].dx
            rawJerkSumX += Math.abs(rawStepX2 - rawStepX1)

            val rawStepY2 = rawList[i].dy - rawList[i - 1].dy
            val rawStepY1 = rawList[i - 1].dy - rawList[i - 2].dy
            rawJerkSumY += Math.abs(rawStepY2 - rawStepY1)

            val rawStepA2 = rawList[i].da - rawList[i - 1].da
            val rawStepA1 = rawList[i - 1].da - rawList[i - 2].da
            rawJerkSumA += Math.abs(rawStepA2 - rawStepA1)
        }

        var smoothedJerkSumX = 0.0
        var smoothedJerkSumY = 0.0
        var smoothedJerkSumA = 0.0
        for (i in 2 until smoothed.size) {
            val smoothStepX2 = smoothed[i].dx - smoothed[i - 1].dx
            val smoothStepX1 = smoothed[i - 1].dx - smoothed[i - 2].dx
            smoothedJerkSumX += Math.abs(smoothStepX2 - smoothStepX1)

            val smoothStepY2 = smoothed[i].dy - smoothed[i - 1].dy
            val smoothStepY1 = smoothed[i - 1].dy - smoothed[i - 2].dy
            smoothedJerkSumY += Math.abs(smoothStepY2 - smoothStepY1)

            val smoothStepA2 = smoothed[i].da - smoothed[i - 1].da
            val smoothStepA1 = smoothed[i - 1].da - smoothed[i - 2].da
            smoothedJerkSumA += Math.abs(smoothStepA2 - smoothStepA1)
        }

        assertTrue(
            "Smoothed X jerk ($smoothedJerkSumX) must be significantly lower than raw ($rawJerkSumX)",
            smoothedJerkSumX < rawJerkSumX * 0.3
        )
        assertTrue(
            "Smoothed Y jerk ($smoothedJerkSumY) must be significantly lower than raw ($rawJerkSumY)",
            smoothedJerkSumY < rawJerkSumY * 0.3
        )
        assertTrue(
            "Smoothed A jerk ($smoothedJerkSumA) must be significantly lower than raw ($rawJerkSumA)",
            smoothedJerkSumA < rawJerkSumA * 0.3
        )
    }

    @Test
    fun testSmoothTrajectoryEdgeCases() {
        val empty = VideoStabilizerEngine.smoothTrajectory(emptyList())
        assertTrue(empty.isEmpty())

        val single = listOf(CameraTrajectory(50.0, -30.0, 0.1))
        val smoothedSingle = VideoStabilizerEngine.smoothTrajectory(single)
        assertEquals(1, smoothedSingle.size)
        assertEquals(50.0, smoothedSingle[0].dx, 1e-6)
        assertEquals(-30.0, smoothedSingle[0].dy, 1e-6)

        val twoPoints = listOf(CameraTrajectory(0.0, 0.0, 0.0), CameraTrajectory(10.0, 10.0, 0.0))
        val smoothedTwo = VideoStabilizerEngine.smoothTrajectory(twoPoints, windowSize = 5)
        assertEquals(twoPoints, smoothedTwo)

        val windowOne = VideoStabilizerEngine.smoothTrajectory(twoPoints, windowSize = 1)
        assertEquals(twoPoints, windowOne)
    }

    @Test
    fun testComputeCorrectionsMathematicalInverses() {
        val raw = listOf(
            CameraTrajectory(100.0, 50.0, 0.2),
            CameraTrajectory(150.0, 45.0, 0.25)
        )
        val smoothed = listOf(
            CameraTrajectory(90.0, 55.0, 0.15),
            CameraTrajectory(140.0, 48.0, 0.22)
        )

        val corrections = VideoStabilizerEngine.computeCorrections(raw, smoothed)
        assertEquals(2, corrections.size)

        // Frame 0:
        // corrDx = 90 - 100 = -10
        // corrDy = 55 - 50 = +5
        // corrDa = 0.15 - 0.2 = -0.05
        assertEquals(-10.0, corrections[0].corrDx, 1e-6)
        assertEquals(5.0, corrections[0].corrDy, 1e-6)
        assertEquals(-0.05, corrections[0].corrDa, 1e-6)

        // Adding correction to raw yields exactly the smoothed trajectory:
        // Raw + Correction == Smoothed
        assertEquals(smoothed[0].dx, raw[0].dx + corrections[0].corrDx, 1e-6)
        assertEquals(smoothed[0].dy, raw[0].dy + corrections[0].corrDy, 1e-6)
        assertEquals(smoothed[0].da, raw[0].da + corrections[0].corrDa, 1e-6)

        assertEquals(smoothed[1].dx, raw[1].dx + corrections[1].corrDx, 1e-6)
        assertEquals(smoothed[1].dy, raw[1].dy + corrections[1].corrDy, 1e-6)
        assertEquals(smoothed[1].da, raw[1].da + corrections[1].corrDa, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testComputeCorrectionsThrowsOnSizeMismatch() {
        val raw = listOf(CameraTrajectory(0.0, 0.0, 0.0))
        val smoothed = listOf(CameraTrajectory(0.0, 0.0, 0.0), CameraTrajectory(1.0, 1.0, 0.0))
        VideoStabilizerEngine.computeCorrections(raw, smoothed)
    }

    @Test
    fun testBuildCorrectiveMatrixCenterInvarianceAndScale() {
        val frameW = 1920
        val frameH = 1080
        val cx = frameW / 2.0 // 960.0
        val cy = frameH / 2.0 // 540.0

        // Case 1: Pure translation, no rotation, no zoom
        val corrTrans = StabilizationCorrection(corrDx = 25.0, corrDy = -15.0, corrDa = 0.0)
        val matTrans = VideoStabilizerEngine.buildCorrectiveMatrix(corrTrans, frameW, frameH, zoomFactor = 1.0)

        // Transform center point: should shift by exactly (25, -15)
        val (transCx, transCy) = matTrans.transformPoint(cx, cy)
        assertEquals(cx + 25.0, transCx, 1e-6)
        assertEquals(cy - 15.0, transCy, 1e-6)

        // Case 2: Pure zoom (5%), no translation, no rotation
        val corrZoom = StabilizationCorrection(corrDx = 0.0, corrDy = 0.0, corrDa = 0.0)
        val matZoom = VideoStabilizerEngine.buildCorrectiveMatrix(corrZoom, frameW, frameH, zoomFactor = 1.05)

        // Center must be completely invariant under centered zoom
        val (zoomCx, zoomCy) = matZoom.transformPoint(cx, cy)
        assertEquals(cx, zoomCx, 1e-6)
        assertEquals(cy, zoomCy, 1e-6)

        // Point offset 100px from center should scale by 1.05x to 105px
        val (scaledPx, scaledPy) = matZoom.transformPoint(cx + 100.0, cy)
        assertEquals(cx + 105.0, scaledPx, 1e-6)
        assertEquals(cy, scaledPy, 1e-6)

        // Case 3: Pure 90° rotation (da = PI/2)
        val corrRot = StabilizationCorrection(corrDx = 0.0, corrDy = 0.0, corrDa = Math.PI / 2.0)
        val matRot = VideoStabilizerEngine.buildCorrectiveMatrix(corrRot, frameW, frameH, zoomFactor = 1.0)

        // Center must remain invariant under centered rotation
        val (rotCx, rotCy) = matRot.transformPoint(cx, cy)
        assertEquals(cx, rotCx, 1e-6)
        assertEquals(cy, rotCy, 1e-6)

        // Point (cx + 100, cy) rotated +90° counter-clockwise becomes (cx, cy + 100)
        val (rotPx, rotPy) = matRot.transformPoint(cx + 100.0, cy)
        assertEquals(cx, rotPx, 1e-5)
        assertEquals(cy + 100.0, rotPy, 1e-5)
    }

    @Test
    fun testBuildCorrectiveMatrixBorderCropGuarantees() {
        val frameW = 1280
        val frameH = 720
        val zoomFactor = 1.05 // 5% auto-zoom border fix

        val correction = StabilizationCorrection(corrDx = 10.0, corrDy = -5.0, corrDa = 0.02)
        val matrix = VideoStabilizerEngine.buildCorrectiveMatrix(correction, frameW, frameH, zoomFactor)

        // Coefficients verify s * cos(da) and s * sin(da)
        val expectedAlpha = zoomFactor * Math.cos(0.02)
        val expectedBeta = zoomFactor * Math.sin(0.02)

        assertEquals(expectedAlpha, matrix.m00, 1e-6)
        assertEquals(-expectedBeta, matrix.m01, 1e-6)
        assertEquals(expectedBeta, matrix.m10, 1e-6)
        assertEquals(expectedAlpha, matrix.m11, 1e-6)

        // Top-left corner (0, 0) should be pushed outside [0, 0] boundary
        // due to 5% zoom expansion, ensuring black margins are clipped
        val (cornerX, cornerY) = matrix.transformPoint(0.0, 0.0)
        assertTrue("Top-left X should be negative to eliminate border gaps", cornerX < 0.0)
        assertTrue("Top-left Y should be negative to eliminate border gaps", cornerY < 0.0)
    }

    @Test
    fun testGenerateFfmpegDeshakeFilterFormat() {
        // Default 5% zoom
        val filter5 = VideoStabilizerEngine.generateFfmpegDeshakeFilter(zoomPercent = 5)
        assertTrue("Should contain deshake filter", filter5.contains("deshake=edge=mirror"))
        assertTrue("Should contain search radius rx=32", filter5.contains("rx=32"))
        assertTrue("Should contain blocksize=32", filter5.contains("blocksize=32"))
        assertTrue("Should contain scale expression for 1.05x zoom", filter5.contains("scale=trunc(iw*1.05/2)*2"))
        assertTrue("Should contain crop expression to revert back to original frame", filter5.contains("crop=trunc(iw/1.05/2)*2"))

        // Zero percent zoom: pure deshake
        val filter0 = VideoStabilizerEngine.generateFfmpegDeshakeFilter(zoomPercent = 0)
        assertEquals("deshake=edge=mirror:rx=32:ry=32:blocksize=32", filter0)
        assertFalse("Should not include scale when zoom is 0", filter0.contains("scale="))

        // Clamping check: 30% requested is clamped to max 20%
        val filterClamped = VideoStabilizerEngine.generateFfmpegDeshakeFilter(zoomPercent = 30)
        assertTrue("Max zoom should clamp to 1.20", filterClamped.contains("iw*1.2/2"))
    }

    @Test
    fun testMockMotionEstimatorExtraction() {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable) {
            // Native OpenCV C++ library not loaded in host JVM test environment; skip JNI Mat allocation
            return
        }

        val scriptedMotions = listOf(
            CameraMotion(4.0, 2.0, 0.01),
            CameraMotion(-1.0, 3.0, -0.005),
            CameraMotion(2.0, -1.0, 0.0)
        )
        val mockEstimator = MockMotionEstimator(scriptedMotions)
        val engine = VideoStabilizerEngine(motionEstimatorProvider = { mockEstimator })

        // Create 4 dummy 100x100 Mats
        val frames = listOf(
            Mat(100, 100, CvType.CV_8UC1),
            Mat(100, 100, CvType.CV_8UC1),
            Mat(100, 100, CvType.CV_8UC1),
            Mat(100, 100, CvType.CV_8UC1)
        )

        try {
            val trajectory = engine.extractTrajectory(frames.asSequence())
            assertEquals(4, trajectory.size)

            // Origin
            assertEquals(0.0, trajectory[0].dx, 1e-6)
            assertEquals(0.0, trajectory[0].dy, 1e-6)

            // Step 1: (4, 2, 0.01)
            assertEquals(4.0, trajectory[1].dx, 1e-6)
            assertEquals(2.0, trajectory[1].dy, 1e-6)
            assertEquals(0.01, trajectory[1].da, 1e-6)

            // Step 2: (4-1=3, 2+3=5, 0.01-0.005=0.005)
            assertEquals(3.0, trajectory[2].dx, 1e-6)
            assertEquals(5.0, trajectory[2].dy, 1e-6)
            assertEquals(0.005, trajectory[2].da, 1e-6)

            // Step 3: (3+2=5, 5-1=4, 0.005)
            assertEquals(5.0, trajectory[3].dx, 1e-6)
            assertEquals(4.0, trajectory[3].dy, 1e-6)
            assertEquals(0.005, trajectory[3].da, 1e-6)
        } finally {
            frames.forEach { it.release() }
        }
    }

    @Test
    fun testVideoEditStateStabilizationWiring() {
        val state = VideoEditState()
        assertFalse("Default stabilization should be false", state.isStabilized)
        assertEquals(5, state.stabilizationMarginPercent)
        assertFalse("Clean state should not report edits", state.hasEdits())
        assertFalse("Clean state should not report video transforms", state.hasVideoTransforms())

        // Enable stabilization
        state.isStabilized = true
        assertTrue("Stabilized state must report hasEdits == true", state.hasEdits())
        assertTrue("Stabilized state must report hasVideoTransforms == true", state.hasVideoTransforms())

        // Reset
        state.reset(totalDurationMs = 10000L)
        assertFalse("Reset must clear isStabilized", state.isStabilized)
        assertEquals(5, state.stabilizationMarginPercent)
        assertFalse("Reset state should not report transforms", state.hasVideoTransforms())
    }
}
