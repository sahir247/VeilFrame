package com.veilframe.app.upscale

import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelResolver
import com.veilframe.app.upscale.preset.UpscalePresetRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UpscaleModelResolverTest {

    @Test
    fun `resolveModel routes neural presets to NCNN and standard to Lanczos`() {
        assertEquals(
            UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS,
            UpscaleModelResolver.resolveModel("photo")
        )
        assertEquals(
            UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS,
            UpscaleModelResolver.resolveModel("photo_quality")
        )
        assertEquals(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
            UpscaleModelResolver.resolveModel("anime")
        )
        assertEquals(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
            UpscaleModelResolver.resolveModel("illustration")
        )
        assertEquals(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2,
            UpscaleModelResolver.resolveModel("fast")
        )
        assertEquals(
            UpscaleModelRegistry.LANCZOS,
            UpscaleModelResolver.resolveModel("standard")
        )
    }

    @Test
    fun `resolveModel honors explicit model override over preset default`() {
        assertEquals(
            UpscaleModelRegistry.LANCZOS,
            UpscaleModelResolver.resolveModel(
                presetId = "anime",
                overrideModelId = "lanczos-3"
            )
        )
        assertEquals(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
            UpscaleModelResolver.resolveModel(
                presetId = "photo",
                overrideModelId = "ncnn-realesr-animevideov3-x4"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown preset must not silently fall back to Lanczos`() {
        UpscaleModelResolver.resolveModel("unknown-preset")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown model override must be rejected`() {
        UpscaleModelResolver.resolveModel(
            presetId = "photo",
            overrideModelId = "does-not-exist"
        )
    }

    @Test
    fun `every preset points to a registered model and only Standard is algorithmic`() {
        UpscalePresetRegistry.ALL_PRESETS.forEach { preset ->
            val resolved = UpscaleModelResolver.resolveModel(preset.id)
            assertNotNull("Preset ${preset.id} must resolve to a valid model", resolved)
            if (preset.id == "standard") {
                assertEquals(UpscaleModelRegistry.LANCZOS, resolved)
            } else {
                assertEquals(
                    "Preset ${preset.id} must not silently select an algorithmic model",
                    com.veilframe.app.upscale.model.ModelType.AI_NCNN,
                    resolved.type
                )
            }
        }
    }
}
