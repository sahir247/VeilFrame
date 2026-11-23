package com.veilframe.app.cv.motion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.cv.core.CvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * Targeted device/emulator regression. This is not a host JVM native test:
 * OpenCV's Java bindings require a loaded ABI-matching libopencv_java4.
 */
@RunWith(AndroidJUnit4::class)
class MotionFlowReuseDeviceTest {
    @Test fun cachedFlowMatchesIndependentSubframeSynthesisAndPreservesOwnership() {
        CvRuntime.requireAvailable()
        val a = Mat(48, 64, CvType.CV_8UC3, Scalar(25.0, 85.0, 135.0))
        val b = Mat(48, 64, CvType.CV_8UC3, Scalar(85.0, 25.0, 65.0))
        val estimator = FlowEstimator(algorithm = FlowEstimator.Algorithm.FARNEBACK)
        val options = FrameSynthesizer.Options(estimator = estimator)
        val flows = estimator.estimate(a, b)
        try {
            for (t in listOf(0.25, 0.5, 0.75)) {
                val reused = FrameSynthesizer.synthesizeWithFlows(a, b, t, flows, options)
                val independent = FrameSynthesizer.synthesize(a, b, t, options)
                try {
                    assertEquals(a.rows(), reused.frame.rows())
                    assertEquals(a.cols(), reused.frame.cols())
                    assertEquals(CvType.CV_8UC3, reused.frame.type())
                    assertTrue("Prepared-flow output differs from original code path",
                        Core.norm(reused.frame, independent.frame, Core.NORM_INF) <= 1.0)
                    assertTrue("Prepared flows released before last fraction", !flows.forward.flow.empty())
                } finally {
                    reused.release()
                    independent.release()
                }
            }
        } finally {
            flows.release()
            a.release()
            b.release()
        }
    }

    @Test fun exceptionDoesNotConsumeBorrowedFlowOrReleaseInputMats() {
        CvRuntime.requireAvailable()
        val gray = Mat(48, 64, CvType.CV_8UC1, Scalar(32.0))
        val bgr = Mat(48, 64, CvType.CV_8UC3, Scalar(32.0, 64.0, 96.0))
        val estimator = FlowEstimator(algorithm = FlowEstimator.Algorithm.FARNEBACK)
        val flows = estimator.estimate(gray, bgr)
        try {
            org.junit.Assert.assertThrows(Exception::class.java) {
                FrameSynthesizer.synthesizeWithFlows(
                    gray, bgr, 0.5, flows, FrameSynthesizer.Options(estimator = estimator))
            }
            assertTrue(!flows.forward.flow.empty())
            assertTrue(!flows.backward.flow.empty())
            assertTrue(!gray.empty() && !bgr.empty())
        } finally {
            flows.release()
            gray.release()
            bgr.release()
        }
    }
}
