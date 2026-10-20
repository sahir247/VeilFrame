package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.UpscaleInferenceEngine
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import org.junit.Assert.fail
import org.junit.Test

class UpscaleInferenceEngineTest {

    @Test
    fun testAlgorithmicLanczos_AllowsSupportedScales() {
        val lanczos = UpscaleModelRegistry.LANCZOS
        for (scale in listOf(2, 3, 4)) {
            UpscaleInferenceEngine.validateExecutionPlan(
                srcW = 128,
                srcH = 128,
                model = lanczos,
                targetScale = scale
            )
        }
    }

    @Test
    fun testAlgorithmicLanczos_RejectsUnsupportedScales() {
        val lanczos = UpscaleModelRegistry.LANCZOS
        for (scale in listOf(1, 5, 8)) {
            try {
                UpscaleInferenceEngine.validateExecutionPlan(
                    srcW = 128,
                    srcH = 128,
                    model = lanczos,
                    targetScale = scale
                )
                fail("Expected IllegalArgumentException for scale $scale on Lanczos")
            } catch (e: IllegalArgumentException) {
                assert(e.message?.contains("is not supported") == true)
            }
        }
    }

    @Test
    fun testNcnnRealEsrgan4x_AllowsSupported4x() {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        // 4x native should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 4
        )
    }

    @Test
    fun testNcnnRealEsrgan4x_RejectsUnsupportedScales() {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        for (scale in listOf(2, 3, 8)) {
            try {
                UpscaleInferenceEngine.validateExecutionPlan(
                    srcW = 256,
                    srcH = 256,
                    model = model,
                    targetScale = scale
                )
                fail("Expected IllegalArgumentException for scale $scale on NCNN Real-ESRGAN 4x")
            } catch (e: IllegalArgumentException) {
                assert(e.message?.contains("is not supported") == true)
            }
        }
    }

    @Test
    fun testNcnnAnimeVideo2x_RestrictedTo2x() {
        val model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2
        // 2x native should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 2
        )
        // 3x and 4x are not supported for 2x model
        for (scale in listOf(3, 4)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UpscaleInferenceEngine.validateExecutionPlan(
                    srcW = 256,
                    srcH = 256,
                    model = model,
                    targetScale = scale
                )
            }
        }
    }

    @Test
    fun testNcnnAnimeVideo3x_RestrictedTo3x() {
        val model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X3
        // 3x native should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 3
        )
        // 2x and 4x are not supported for 3x model
        for (scale in listOf(2, 4)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UpscaleInferenceEngine.validateExecutionPlan(
                    srcW = 256,
                    srcH = 256,
                    model = model,
                    targetScale = scale
                )
            }
        }
    }

    @Test
    fun testSourceAccess_AutoCloseableContract() {
        var closed = false
        val customSource = object : com.veilframe.app.upscale.inference.SourceAccess {
            override val width: Int = 100
            override val height: Int = 100
            override fun region(x: Int, y: Int, width: Int, height: Int): android.graphics.Bitmap =
                throw UnsupportedOperationException()
            override fun full(): android.graphics.Bitmap =
                throw UnsupportedOperationException()
            override fun close() {
                closed = true
            }
        }
        customSource.use {
            assert(!closed)
        }
        assert(closed)
    }
}
