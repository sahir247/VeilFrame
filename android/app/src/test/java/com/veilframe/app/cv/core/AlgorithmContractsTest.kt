package com.veilframe.app.cv.core

import com.veilframe.app.cv.crop.SmartAutoCrop
import com.veilframe.app.cv.motion.FlowEstimator
import com.veilframe.app.cv.motion.FrameSynthesizer
import com.veilframe.app.cv.segmentation.BackgroundRemover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmContractsTest {

    @Test
    fun smartAutoCropRejectsInvalidCustomRatio() {
        try {
            SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = null)
            org.junit.Assert.fail("Expected exception for null customRatio")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = -1.0)
            org.junit.Assert.fail("Expected exception for negative customRatio")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = Double.NaN)
            org.junit.Assert.fail("Expected exception for NaN customRatio")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun smartAutoCropAcceptsValidCustomRatio() {
        val options = SmartAutoCrop.Options(aspect = SmartAutoCrop.Aspect.CUSTOM, customRatio = 1.33)
        assertEquals(1.33, options.customRatio!!, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun smartAutoCropRejectsInvalidMargin() {
        SmartAutoCrop.Options(margin = 0.6) // > 0.5
    }

    @Test(expected = IllegalArgumentException::class)
    fun smartAutoCropRejectsInvalidCenterBias() {
        SmartAutoCrop.Options(centerBias = 1.2) // > 1.0
    }

    @Test(expected = IllegalArgumentException::class)
    fun backgroundRemoverRejectsEvenCleanupKernel() {
        BackgroundRemover.Options(cleanupKernel = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun backgroundRemoverRejectsNegativeFeatherRadius() {
        BackgroundRemover.Options(featherRadius = -0.5)
    }

    @Test
    fun backgroundRemoverAcceptsValidOptions() {
        val options = BackgroundRemover.Options(cleanupKernel = 7, featherRadius = 3.5)
        assertEquals(7, options.cleanupKernel)
        assertEquals(3.5, options.featherRadius, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameSynthesizerRejectsZeroArtifactFactor() {
        FrameSynthesizer.Options(artifactFactor = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameSynthesizerRejectsNegativeMinConfidence() {
        FrameSynthesizer.Options(minConfidence = -0.1)
    }

    @Test
    fun flowScalePreservesIndependentAxes() {
        val scale = FlowEstimator.FlowScale(x = 0.5, y = 0.75)
        assertEquals(0.5, scale.x, 1e-6)
        assertEquals(0.75, scale.y, 1e-6)
        assertFalse(scale.isUniform)

        val uniformScale = FlowEstimator.FlowScale(x = 0.5, y = 0.5)
        assertTrue(uniformScale.isUniform)
    }

    private fun allocateDummyMat(): org.opencv.core.Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, org.opencv.core.Mat::class.java) as org.opencv.core.Mat
    }

    @Test
    fun backgroundRemoverReturnsNullMaskOnNullSegmenter() {
        val image = allocateDummyMat()
        val nullSegmenter = BackgroundRemover.ForegroundSegmenter { null }
        val mask = BackgroundRemover.resolveRawMask(
            image = image,
            segmenter = nullSegmenter,
            shapeValidator = { _, _ -> true },
            maskReleaser = { },
        )
        org.junit.Assert.assertNull(mask)
    }

    @Test
    fun backgroundRemoverDegradesOnExpectedSegmenterPluginException() {
        val image = allocateDummyMat()
        val throwingSegmenter = BackgroundRemover.ForegroundSegmenter {
            throw RuntimeException("Inference execution failed in model runtime")
        }
        val mask = BackgroundRemover.resolveRawMask(
            image = image,
            segmenter = throwingSegmenter,
            shapeValidator = { _, _ -> true },
            maskReleaser = { },
        )
        org.junit.Assert.assertNull("Expected plugin failure must degrade to null rawMask (identity)", mask)
    }

    @Test(expected = OutOfMemoryError::class)
    fun backgroundRemoverPropagatesOutOfMemoryError() {
        val image = allocateDummyMat()
        val oomSegmenter = BackgroundRemover.ForegroundSegmenter {
            throw OutOfMemoryError("OOM loading segmentation tensor")
        }
        BackgroundRemover.resolveRawMask(
            image = image,
            segmenter = oomSegmenter,
            shapeValidator = { _, _ -> true },
            maskReleaser = { },
        )
    }

    @Test(expected = LinkageError::class)
    fun backgroundRemoverPropagatesLinkageError() {
        val image = allocateDummyMat()
        val linkageSegmenter = BackgroundRemover.ForegroundSegmenter {
            throw UnsatisfiedLinkError("Cannot load onnxruntime.so")
        }
        BackgroundRemover.resolveRawMask(
            image = image,
            segmenter = linkageSegmenter,
            shapeValidator = { _, _ -> true },
            maskReleaser = { },
        )
    }

    @Test
    fun backgroundRemoverReleasesAndDegradesOnInvalidMaskShape() {
        val image = allocateDummyMat()
        val invalidMask = allocateDummyMat()
        var maskReleased = false
        val invalidSegmenter = BackgroundRemover.ForegroundSegmenter { invalidMask }

        val resolved = BackgroundRemover.resolveRawMask(
            image = image,
            segmenter = invalidSegmenter,
            shapeValidator = { _, _ -> false }, // shape mismatch!
            maskReleaser = { maskReleased = true },
        )

        org.junit.Assert.assertNull("Shape mismatch must reject rawMask", resolved)
        assertTrue("Incompatible mask must be released", maskReleased)
    }

    @Test
    fun backgroundRemoverAcceptsValidMaskShape() {
        val image = allocateDummyMat()
        val validMask = allocateDummyMat()
        val segmenter = BackgroundRemover.ForegroundSegmenter { validMask }

        val resolved = BackgroundRemover.resolveRawMask(
            image = image,
            segmenter = segmenter,
            shapeValidator = { _, _ -> true },
            maskReleaser = { },
        )

        org.junit.Assert.assertSame(validMask, resolved)
    }

    @Test
    fun flowScaleNumericalRegressionOnAnisotropicDownscale() {
        // Frame: 640x360 -> Working resolution: 320x120 (anisotropic scaling)
        val origW = 640.0
        val origH = 360.0
        val workW = 320.0
        val workH = 120.0

        val scaleX = workW / origW // 0.5
        val scaleY = workH / origH // 0.3333333333333333
        val flowScale = FlowEstimator.FlowScale(x = scaleX, y = scaleY)

        // Synthetic displacement in full-res: dx = +20.0, dy = +7.0
        val expectedDx = 20.0
        val expectedDy = 7.0

        // Displacements at working resolution:
        val workDx = expectedDx * scaleX // 10.0
        val workDy = expectedDy * scaleY // 2.3333333333333335

        // Independent reconstruction:
        val reconstructedDx = workDx / flowScale.x
        val reconstructedDy = workDy / flowScale.y

        assertEquals(expectedDx, reconstructedDx, 1e-6)
        assertEquals(expectedDy, reconstructedDy, 1e-6)

        // Prove that uniform scalar scaling along X would distort Y:
        val flawedUniformDy = workDy / flowScale.x // using scaleX for Y
        assertEquals(4.6666667, flawedUniformDy, 1e-5)
        assertTrue(kotlin.math.abs(flawedUniformDy - expectedDy) > 2.0)
    }

    @Test
    fun smartAutoCropIntegralEnergyCoverageSemantics() {
        // Synthetic 100 x 100 grid:
        // Background has energy 1.0 per cell (100 * 100 = 10,000)
        // High-importance subject is 40 x 40 located at (20, 30) with an additional 9.0 per cell (total 10.0 per cell)
        // Hotspot total energy = 40 * 40 * 9.0 = 14,400
        // Total energy = 10,000 + 14,400 = 24,400
        val w = 100
        val h = 100
        val grid = Array(h) { DoubleArray(w) { 1.0 } }
        for (y in 30 until 70) {
            for (x in 20 until 60) {
                grid[y][x] += 9.0
            }
        }

        // Build integral image: I(y+1, x+1)
        val integral = Array(h + 1) { DoubleArray(w + 1) { 0.0 } }
        for (y in 0 until h) {
            for (x in 0 until w) {
                integral[y + 1][x + 1] = grid[y][x] + integral[y][x + 1] + integral[y + 1][x] - integral[y][x]
            }
        }

        fun sumRect(rx: Int, ry: Int, rw: Int, rh: Int): Double {
            return integral[ry + rh][rx + rw] - integral[ry][rx + rw] - integral[ry + rh][rx] + integral[ry][rx]
        }

        val totalEnergy = sumRect(0, 0, w, h)
        assertEquals(24_400.0, totalEnergy, 1e-6)

        // Target crop size: 40 x 40
        val cropW = 40
        val cropH = 40
        var bestX = 0
        var bestY = 0
        var bestEnergy = -1.0

        for (y in 0..h - cropH) {
            for (x in 0..w - cropW) {
                val energy = sumRect(x, y, cropW, cropH)
                if (energy > bestEnergy) {
                    bestEnergy = energy
                    bestX = x
                    bestY = y
                }
            }
        }

        // Must locate the exact hotspot at (20, 30)
        assertEquals(20, bestX)
        assertEquals(30, bestY)
        // Hotspot energy: 40 * 40 * 10.0 = 16,000.0
        assertEquals(16_000.0, bestEnergy, 1e-6)

        // Energy coverage ratio is exact:
        val coverage = (bestEnergy / totalEnergy).coerceIn(0.0, 1.0)
        assertEquals(16_000.0 / 24_400.0, coverage, 1e-6)
        assertTrue(coverage in 0.65..0.66)
    }
}
