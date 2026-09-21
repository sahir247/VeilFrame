package com.veilframe.app.upscale

import com.veilframe.app.upscale.model.ColorOrder
import com.veilframe.app.upscale.model.DeploymentStatus
import com.veilframe.app.upscale.model.ModelRuntimeSpec
import com.veilframe.app.upscale.model.NormalizationSpec
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRuntimeSpecTest {

    @Test
    fun testModelRuntimeSpecCreation() {
        val spec = ModelRuntimeSpec(
            sha256 = "110818e1a29309d1da6087e8bbe201f50e43ea7679483ebca1df95ab333d2a66",
            scaleFactor = 4,
            inputChannels = 3,
            outputChannels = 3,
            colorOrder = ColorOrder.RGB,
            normalization = NormalizationSpec.ZERO_TO_ONE,
            opset = 17
        )

        assertEquals(4, spec.scaleFactor)
        assertEquals(3, spec.inputChannels)
        assertEquals(ColorOrder.RGB, spec.colorOrder)
        assertEquals(NormalizationSpec.ZERO_TO_ONE, spec.normalization)
        assertEquals(17, spec.opset)
    }

    @Test
    fun testEmptyShaModelsAreMarkedExperimental() {
        val experimentalModels = listOf(
            UpscaleModelRegistry.SAT_LIGHT_2X,
            UpscaleModelRegistry.SAT_LIGHT_4X,
            UpscaleModelRegistry.SAFMN_V3_2X,
            UpscaleModelRegistry.SAFMN_V3_4X,
            UpscaleModelRegistry.REAL_SAFMN_PLUS_4X,
            UpscaleModelRegistry.ESPAN_4X,
            UpscaleModelRegistry.PFT_LIGHT_4X
        )

        for (model in experimentalModels) {
            assertTrue("${model.id} with empty SHA must be experimental", model.sha256.isEmpty())
            assertEquals("${model.id} must have ANDROID_EXPERIMENTAL status", DeploymentStatus.ANDROID_EXPERIMENTAL, model.deploymentStatus)
        }

        // Native models must have verified non-empty SHA256
        val nativeModels = listOf(
            UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X,
            UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X,
            UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X,
            UpscaleModelRegistry.REAL_CUGAN_4X,
            UpscaleModelRegistry.SWINIR_REALSR_4X,
            UpscaleModelRegistry.REAL_PLKSR_4X,
            UpscaleModelRegistry.SPAN_4X,
            UpscaleModelRegistry.CODEFORMER
        )

        for (model in nativeModels) {
            assertTrue("${model.id} must have non-empty SHA", model.sha256.isNotEmpty())
            assertEquals("${model.id} must have ANDROID_NATIVE status", DeploymentStatus.ANDROID_NATIVE, model.deploymentStatus)
        }
    }

    @Test
    fun testIndependentInputOutputSpecs() {
        val spec = ModelRuntimeSpec(
            sha256 = "dummy_sha",
            scaleFactor = 4,
            inputColorOrder = ColorOrder.RGB,
            inputNormalization = NormalizationSpec.ZERO_TO_ONE,
            outputColorOrder = ColorOrder.BGR,
            outputRange = com.veilframe.app.upscale.model.OutputRangeSpec.NEG_ONE_TO_ONE
        )
        assertEquals(ColorOrder.RGB, spec.inputColorOrder)
        assertEquals(NormalizationSpec.ZERO_TO_ONE, spec.inputNormalization)
        assertEquals(ColorOrder.BGR, spec.outputColorOrder)
        assertEquals(com.veilframe.app.upscale.model.OutputRangeSpec.NEG_ONE_TO_ONE, spec.outputRange)
    }

    @Test
    fun testUpscaleModelToRuntimeSpecConversion() {
        val model = UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X
        val spec = model.toRuntimeSpec()
        assertEquals(4, spec.scaleFactor)
        assertEquals(3, spec.inputChannels)
        assertEquals(3, spec.outputChannels)
        assertEquals(ColorOrder.RGB, spec.inputColorOrder)
        assertEquals(NormalizationSpec.ZERO_TO_ONE, spec.inputNormalization)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testToRuntimeSpecRejectsUnsupportedInputChannelOrder() {
        val badModel = UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X.copy(
            inputChannelOrder = "YUV"
        )
        badModel.toRuntimeSpec()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testToRuntimeSpecRejectsUnsupportedNormalization() {
        val badModel = UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X.copy(
            inputNormalizationRange = "INVALID_RANGE"
        )
        badModel.toRuntimeSpec()
    }
}
