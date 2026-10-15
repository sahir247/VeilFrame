package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.UpscaleInferenceEngine
import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelGenre
import com.veilframe.app.upscale.model.ModelTier
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import org.junit.Assert.fail
import org.junit.Test

class UpscaleInferenceEngineTest {

    @Test
    fun testCodeFormer_RejectsArbitraryResolutionBelowMinimum() {
        val codeFormer = UpscaleModelRegistry.CODEFORMER
        // 200x200 is below minInputDimension (512)
        try {
            UpscaleInferenceEngine.validateExecutionPlan(
                srcW = 200,
                srcH = 200,
                model = codeFormer,
                targetScale = 1
            )
            fail("Expected IllegalArgumentException for 200x200 image on CodeFormer (minInputDimension is 512)")
        } catch (e: IllegalArgumentException) {
            assert(e.message?.contains("minimum required dimensions") == true)
        }
    }

    @Test
    fun testCodeFormer_RejectsArbitraryTiledSuperResolution() {
        val codeFormer = UpscaleModelRegistry.CODEFORMER
        // 1024x768 is above 512, but CodeFormer has tileCompatible = false and capability = FACE_RESTORATION
        try {
            UpscaleInferenceEngine.validateExecutionPlan(
                srcW = 1024,
                srcH = 768,
                model = codeFormer,
                targetScale = 1
            )
            fail("Expected IllegalArgumentException for arbitrary image dimensions on non-tile-compatible Face Restoration model")
        } catch (e: IllegalArgumentException) {
            assert(e.message?.contains("Arbitrary tiled super-resolution is rejected before inference") == true)
        }
    }

    @Test
    fun testCodeFormer_AllowsExact512x512AlignedFaceCrop() {
        val codeFormer = UpscaleModelRegistry.CODEFORMER
        // 512x512 aligned portrait crop should pass capability validation
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 512,
            srcH = 512,
            model = codeFormer,
            targetScale = 1
        )
    }

    @Test
    fun testRealEsrganGeneral4x_AllowsSupported4xAnd8x() {
        val model = UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X

        // 4x native should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 4
        )

        // 8x hybrid (4x AI + 2x Lanczos) should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 8
        )
    }

    @Test
    fun testRealEsrganGeneral4x_RejectsUnsupportedScales() {
        val model = UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X

        try {
            UpscaleInferenceEngine.validateExecutionPlan(
                srcW = 256,
                srcH = 256,
                model = model,
                targetScale = 3
            )
            fail("Expected IllegalArgumentException for unsupported scale 3x on Real-ESRGAN General 4x")
        } catch (e: IllegalArgumentException) {
            assert(e.message?.contains("Output scale 3× is not supported") == true)
        }
    }

    @Test
    fun testAlgorithmicLanczos_AllowsAllScales() {
        val lanczos = UpscaleModelRegistry.LANCZOS
        for (scale in listOf(2, 4, 8)) {
            UpscaleInferenceEngine.validateExecutionPlan(
                srcW = 128,
                srcH = 128,
                model = lanczos,
                targetScale = scale
            )
        }
    }

    @Test
    fun testNcnnRealEsrgan4x_AllowsSupported4xAnd8x() {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        // 4x native should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 4
        )
        // 8x hybrid refinement should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 8
        )
    }

    @Test
    fun testNcnnAnimeVideo2x_AllowsSupported2xAnd4x() {
        val model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2
        // 2x native should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 2
        )
        // 4x hybrid refinement should pass
        UpscaleInferenceEngine.validateExecutionPlan(
            srcW = 256,
            srcH = 256,
            model = model,
            targetScale = 4
        )
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
