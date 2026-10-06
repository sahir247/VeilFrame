package com.veilframe.app.cv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.cv.color.ColorEngine
import com.veilframe.app.cv.core.MatPool
import com.veilframe.app.cv.geometry.PerspectiveCorrector
import com.veilframe.app.cv.geometry.QuadDetector
import com.veilframe.app.cv.motion.FlowEstimator
import com.veilframe.app.cv.preprocess.Preprocessor
import com.veilframe.app.cv.segmentation.BackgroundRemover
import com.veilframe.app.cv.segmentation.MaskOps
import com.veilframe.app.cv.template.TemplateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * On-device native OpenCV subsystem suite.
 *
 * Verifies that the native OpenCV C++ binaries (libopencv_java4.so),
 * JNI bindings, buffer leasing, and computer-vision primitives execute
 * correctly and leak-free on real ARM64 and x86_64 runtimes.
 */
@RunWith(AndroidJUnit4::class)
class CvNativeSubsystemTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initOpenCv() {
            val loaded = OpenCVLoader.initLocal()
            assertTrue("OpenCV native library must be loaded for device tests", loaded)
        }
    }

    @Test
    fun matPoolNativeAcquireReleaseAndRecycling() {
        val pool = MatPool(maxRetainedBytes = 16L * 1024 * 1024, maxPerClass = 2)

        // Lease 1: 100 x 100 CV_8UC3
        val lease1 = pool.acquire(100, 100, CvType.CV_8UC3)
        assertEquals(100, lease1.rows)
        assertEquals(100, lease1.cols)
        assertFalse(lease1.mat.empty())
        lease1.mat.setTo(Scalar(128.0, 64.0, 32.0))
        val rawPtr1 = lease1.mat.nativeObj
        lease1.close()

        assertEquals(1, pool.stats().retainedBuffers)

        // Lease 2: exact match should reuse buffer
        val lease2 = pool.acquire(100, 100, CvType.CV_8UC3)
        assertEquals(rawPtr1, lease2.mat.nativeObj)
        assertEquals(1, pool.stats().hits)
        lease2.close()

        pool.trim()
        assertEquals(0, pool.stats().retainedBuffers)
        assertEquals(0L, pool.stats().retainedBytes)
    }

    @Test
    fun templateMatcherNativeExecution() {
        val scene = Mat(200, 200, CvType.CV_8UC1, Scalar(50.0))
        val template = Mat(40, 40, CvType.CV_8UC1, Scalar(220.0))

        // Embed template in scene at (80, 80)
        val roi = scene.submat(80, 120, 80, 120)
        template.copyTo(roi)
        roi.release()

        val matcher = TemplateMatcher()
        val match = matcher.match(scene, template)

        assertNotNull("TemplateMatcher must find embedded synthetic pattern", match)
        assertEquals(80.0, match!!.rect.x.toDouble(), 1.0)
        assertEquals(80.0, match.rect.y.toDouble(), 1.0)
        assertEquals(40, match.rect.width)
        assertEquals(40, match.rect.height)
        assertTrue("Confidence must be near 1.0", match.confidence >= 0.98)

        scene.release()
        template.release()
    }

    @Test
    fun quadDetectorAndPerspectiveCorrectionNativeExecution() {
        val image = Mat(400, 400, CvType.CV_8UC1, Scalar(0.0))
        // Draw white quad polygon
        val pts = org.opencv.core.MatOfPoint(
            Point(50.0, 50.0),
            Point(350.0, 60.0),
            Point(340.0, 340.0),
            Point(60.0, 350.0),
        )
        Imgproc.fillPoly(image, listOf(pts), Scalar(255.0))
        pts.release()

        val detector = QuadDetector()
        val quad = detector.detect(image)
        assertNotNull("QuadDetector must find high-contrast polygon", quad)

        val corrector = PerspectiveCorrector()
        val warped = corrector.warp(image, quad!!)
        assertFalse(warped.empty())
        assertTrue(warped.rows() > 0 && warped.cols() > 0)
        val meanVal = org.opencv.core.Core.mean(warped).`val`[0]
        assertTrue("Unwarped quad must be predominantly white (>200.0 mean brightness), was $meanVal", meanVal > 200.0)

        image.release()
        warped.release()
    }

    @Test
    fun maskOpsAndBackgroundRemoverNativeExecution() {
        val mask = Mat(100, 100, CvType.CV_8UC1, Scalar(0.0))
        val center = mask.submat(25, 75, 25, 75)
        center.setTo(Scalar(255.0))
        center.release()

        val cleaned = MaskOps.cleanup(mask, kernelSize = 3)
        assertFalse(cleaned.empty())

        val feathered = MaskOps.feather(cleaned, radius = 2.0)
        assertFalse(feathered.empty())

        val src = Mat(100, 100, CvType.CV_8UC3, Scalar(100.0, 150.0, 200.0))
        val bg = Mat(100, 100, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        val composited = MaskOps.composite(src, bg, feathered)
        assertEquals(CvType.CV_8UC3, composited.type())

        // Verify composited pixel values: center should be foreground src, boundary should be bg
        val centerPixel = composited.get(50, 50)
        assertEquals(100.0, centerPixel[0], 2.0)
        assertEquals(150.0, centerPixel[1], 2.0)
        assertEquals(200.0, centerPixel[2], 2.0)
        val outerPixel = composited.get(5, 5)
        assertEquals(0.0, outerPixel[0], 2.0)
        assertEquals(0.0, outerPixel[1], 2.0)
        assertEquals(0.0, outerPixel[2], 2.0)

        // Degraded mode when segmenter returns null
        val nullResult = BackgroundRemover.removeBackground(
            image = src,
            segmenter = BackgroundRemover.ForegroundSegmenter { null },
        )
        assertTrue(nullResult.degraded)
        assertEquals(src.rows(), nullResult.output.rows())
        assertEquals(src.cols(), nullResult.output.cols())

        mask.release()
        cleaned.release()
        feathered.release()
        src.release()
        bg.release()
        composited.release()
        nullResult.output.release()
        nullResult.mask.release()
    }

    @Test
    fun preprocessorAndColorEngineNativeExecution() {
        val src = Mat(120, 120, CvType.CV_8UC3, Scalar(120.0, 130.0, 140.0))
        val gray = Preprocessor.toGray(src)
        assertEquals(1, gray.channels())
        // OpenCV BGR to Gray: 0.114*120 + 0.587*130 + 0.299*140 = 131.85 ~ 132
        val grayVal = gray.get(60, 60)[0].toInt() and 0xFF
        assertEquals(132, grayVal)

        val blurred = Preprocessor.blur(gray, Preprocessor.BlurMethod.GAUSSIAN, kernelSize = 5)
        assertEquals(gray.size(), blurred.size())

        val balanced = ColorEngine.autoWhiteBalance(src)
        assertEquals(src.size(), balanced.size())
        assertEquals(src.type(), balanced.type())

        src.release()
        gray.release()
        blurred.release()
        balanced.release()
    }

    @Test
    fun opticalFlowNativeFarnebackExecution() {
        val frame1 = Mat(160, 160, CvType.CV_8UC1, Scalar(40.0))
        val frame2 = Mat(160, 160, CvType.CV_8UC1, Scalar(40.0))

        // Draw rectangle shifted by +10px in X and +5px in Y
        Imgproc.rectangle(frame1, Point(40.0, 40.0), Point(80.0, 80.0), Scalar(230.0), -1)
        Imgproc.rectangle(frame2, Point(50.0, 45.0), Point(90.0, 85.0), Scalar(230.0), -1)

        val estimator = FlowEstimator(algorithm = FlowEstimator.Algorithm.FARNEBACK, workingMaxEdge = 160)
        val flow = estimator.estimate(frame1, frame2)

        assertFalse(flow.forward.flow.empty())
        assertEquals(CvType.CV_32FC2, flow.forward.flow.type())
        assertEquals(160, flow.forward.flow.rows())
        assertEquals(160, flow.forward.flow.cols())

        // Numerical verification: sample vector at center of moved object (60, 60)
        val shiftVec = FloatArray(2)
        flow.forward.flow.get(60, 60, shiftVec)
        val dx = shiftVec[0]
        val dy = shiftVec[1]
        assertTrue("Estimated horizontal flow dx ($dx) should be positive and near +10px", dx in 7.0f..13.0f)
        assertTrue("Estimated vertical flow dy ($dy) should be positive and near +5px", dy in 2.5f..7.5f)

        // Numerical verification: static background (10, 10) must be near zero
        val bgVec = FloatArray(2)
        flow.forward.flow.get(10, 10, bgVec)
        assertTrue("Background flow dx (${bgVec[0]}) should be near 0", Math.abs(bgVec[0]) < 1.0f)
        assertTrue("Background flow dy (${bgVec[1]}) should be near 0", Math.abs(bgVec[1]) < 1.0f)

        flow.release()
        frame1.release()
        frame2.release()
    }
}
