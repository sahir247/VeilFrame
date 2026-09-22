package com.veilframe.app.upscale

import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelGenre
import com.veilframe.app.upscale.model.ModelTier
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleModelRegistryTest {

    @Test
    fun testRealEsrganGeneral4xScaleDecoupling() {
        val model = UpscaleModelRegistry.REAL_ESRGAN_GENERAL_4X
        // Native model scale is 4
        assertEquals(4, model.nativeScale)
        // Supported output scales allow 4x and 8x (4x AI + 2x Lanczos refinement)
        assertTrue("Must support 4x", model.supportedOutputScales.contains(4))
        assertTrue("Must support 8x via hybrid refinement", model.supportedOutputScales.contains(8))
        assertEquals("Reliable general-purpose photo restoration with deep residual in residual dense blocks.", model.description)
        assertEquals(ModelGenre.GENERAL_PHOTO, model.genre)
        assertEquals(ModelCapability.SUPER_RESOLUTION, model.capability)
        assertEquals(ModelTier.TIER_A_NATIVE, model.tier)
    }

    @Test
    fun testCodeFormerSpecializedFaceRestoration() {
        val model = UpscaleModelRegistry.CODEFORMER
        assertEquals(1, model.nativeScale)
        assertEquals(ModelCapability.FACE_RESTORATION, model.capability)
        assertEquals(ModelGenre.PORTRAIT_FACE, model.genre)
        assertEquals(ModelTier.TIER_A_NATIVE, model.tier)
        assertFalse("CodeFormer operates on whole face crops, not general tiles", model.tileCompatible)
        assertTrue(model.description.contains("Specialized portrait face restoration pipeline"))
    }

    @Test
    fun testExpandedTierAModelsExist() {
        val plksr = UpscaleModelRegistry.REAL_PLKSR_4X
        assertEquals(4, plksr.nativeScale)
        assertTrue(plksr.supportedOutputScales.contains(8))
        assertEquals(ModelTier.TIER_A_NATIVE, plksr.tier)

        val swinir = UpscaleModelRegistry.SWINIR_REALSR_4X
        assertEquals(4, swinir.nativeScale)
        assertTrue(swinir.supportedOutputScales.contains(8))
        assertEquals(ModelTier.TIER_A_NATIVE, swinir.tier)

        val cugan = UpscaleModelRegistry.REAL_CUGAN_4X
        assertEquals(4, cugan.nativeScale)
        assertTrue(cugan.supportedOutputScales.contains(8))
        assertEquals(ModelTier.TIER_A_NATIVE, cugan.tier)

        val span = UpscaleModelRegistry.SPAN_4X
        assertEquals(4, span.nativeScale)
        assertTrue(span.supportedOutputScales.contains(8))
        assertEquals(ModelTier.TIER_A_NATIVE, span.tier)
    }

    @Test
    fun testAlgorithmicModelsAreBuiltIn() {
        listOf(UpscaleModelRegistry.LANCZOS, UpscaleModelRegistry.BICUBIC, UpscaleModelRegistry.NEAREST).forEach { model ->
            assertTrue(model.isBuiltIn)
            assertEquals(0L, model.sizeBytes)
            assertEquals(ModelGenre.ALGORITHMIC_FAST, model.genre)
            assertTrue(model.supportedOutputScales.containsAll(listOf(2, 4, 8)))
        }
    }

    @Test
    fun testSotaExpansionModelsMetadata() {
        val x4v3 = UpscaleModelRegistry.REAL_ESRGAN_X4V3
        assertEquals(4, x4v3.nativeScale)
        assertEquals(ModelCapability.SUPER_RESOLUTION, x4v3.capability)
        assertEquals(ModelGenre.GENERAL_PHOTO, x4v3.genre)

        val anime4b = UpscaleModelRegistry.REAL_ESRGAN_ANIME_4B
        assertEquals(4, anime4b.nativeScale)
        assertEquals(ModelCapability.SUPER_RESOLUTION, anime4b.capability)
        assertEquals(ModelGenre.ANIME_MANGA, anime4b.genre)

        val ultrasharp = UpscaleModelRegistry.ULTRASHARP_4X_LITE
        assertEquals(4, ultrasharp.nativeScale)
        assertEquals(ModelCapability.SUPER_RESOLUTION, ultrasharp.capability)
        assertEquals(ModelGenre.PHOTO_FIDELITY, ultrasharp.genre)

        val fbcnn = UpscaleModelRegistry.FBCNN_COLOR
        assertEquals(1, fbcnn.nativeScale)
        assertEquals(ModelCapability.JPEG_ARTIFACT_REDUCTION, fbcnn.capability)
        assertEquals(ModelGenre.JPEG_WEB, fbcnn.genre)

        val scunet = UpscaleModelRegistry.SCUNET_COLOR_GAN
        assertEquals(1, scunet.nativeScale)
        assertEquals(ModelCapability.DENOISING, scunet.capability)
        assertEquals(ModelGenre.GENERAL_PHOTO, scunet.genre)
    }

    @Test
    fun testDynamicCustomModelRegistration() {
        UpscaleModelRegistry.clearCustomModels()

        val customModel = com.veilframe.app.upscale.model.UpscaleModel(
            id = "custom-test-model",
            name = "Test Custom Model",
            description = "Imported test model",
            type = com.veilframe.app.upscale.model.ModelType.AI_ONNX,
            nativeScale = 4,
            sizeBytes = 12345L,
            sha256 = "abc123",
            supportedOutputScales = listOf(4, 8),
            genre = ModelGenre.CUSTOM_IMPORTED,
            isImported = true
        )

        UpscaleModelRegistry.registerCustomModel(customModel)
        val fetched = UpscaleModelRegistry.getModelById("custom-test-model")
        assertEquals(customModel, fetched)
        assertTrue(UpscaleModelRegistry.ALL_MODELS.contains(customModel))
        assertTrue(UpscaleModelRegistry.AI_MODELS.contains(customModel))

        val removed = UpscaleModelRegistry.unregisterCustomModel("custom-test-model")
        assertTrue(removed)
        assertEquals(null, UpscaleModelRegistry.getModelById("custom-test-model"))
    }
}

