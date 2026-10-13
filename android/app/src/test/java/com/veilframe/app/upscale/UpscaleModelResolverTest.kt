package com.veilframe.app.upscale

import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelResolver
import com.veilframe.app.upscale.preset.UpscalePresetRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UpscaleModelResolverTest {

    @Test
    fun `resolveModel resolves expected model for all standard presets`() {
        assertEquals(
            UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X,
            UpscaleModelResolver.resolveModel("photo")
        )
        assertEquals(
            UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X,
            UpscaleModelResolver.resolveModel("photo_quality")
        )
        assertEquals(
            UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X,
            UpscaleModelResolver.resolveModel("anime")
        )
        assertEquals(
            UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X,
            UpscaleModelResolver.resolveModel("illustration")
        )
        assertEquals(
            UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X,
            UpscaleModelResolver.resolveModel("fast")
        )
        assertEquals(
            UpscaleModelRegistry.LANCZOS,
            UpscaleModelResolver.resolveModel("standard")
        )
    }

    @Test
    fun `resolveModel honors explicit overrideModelId over preset default`() {
        val overrideLanczos = UpscaleModelResolver.resolveModel(
            presetId = "anime",
            overrideModelId = "lanczos-3"
        )
        assertEquals(UpscaleModelRegistry.LANCZOS, overrideLanczos)

        val overrideAnime = UpscaleModelResolver.resolveModel(
            presetId = "photo",
            overrideModelId = "realesrgan-anime-4x"
        )
        assertEquals(UpscaleModelRegistry.REAL_ESRGAN_ANIME_4X, overrideAnime)
    }

    @Test
    fun `all presets have valid non-null recommended models registered`() {
        UpscalePresetRegistry.ALL_PRESETS.forEach { preset ->
            val resolved = UpscaleModelResolver.resolveModel(preset.id)
            assertNotNull("Preset ${preset.id} must resolve to a valid model", resolved)
        }
    }
}
