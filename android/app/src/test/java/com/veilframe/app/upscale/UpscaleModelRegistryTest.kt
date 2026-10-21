package com.veilframe.app.upscale

import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelGenre
import com.veilframe.app.upscale.model.ModelTier
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.NcnnEngineKind
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleModelRegistryTest {

    @Test
    fun testAlgorithmicModelsAreBuiltIn() {
        listOf(UpscaleModelRegistry.LANCZOS, UpscaleModelRegistry.BICUBIC, UpscaleModelRegistry.NEAREST).forEach { model ->
            assertTrue(model.isBuiltIn)
            assertEquals(0L, model.sizeBytes)
            assertEquals(ModelType.ALGORITHMIC, model.type)
            assertEquals(ModelGenre.ALGORITHMIC_FAST, model.genre)
            assertEquals(listOf(2, 3, 4), model.supportedOutputScales)
        }
    }

    @Test
    fun testNcnnModelsHaveCompletePinnedArtifactContracts() {
        val models = UpscaleModelRegistry.NCNN_MODELS
        assertEquals(15, models.size)

        models.forEach { model ->
            assertEquals(ModelType.AI_NCNN, model.type)
            assertEquals(NcnnEngineKind.REALSR_ESRGAN, model.engineKind)
            model.validateNcnnArtifactContract()
            assertTrue(model.paramDownloadUrl!!.endsWith(".param"))
            assertTrue(model.binDownloadUrl!!.endsWith(".bin"))
            assertEquals(model.paramSizeBytes + model.binSizeBytes, model.sizeBytes)
            assertTrue(model.paramSha256!!.matches(Regex("^[A-Fa-f0-9]{64}$")))
            assertTrue(model.binSha256!!.matches(Regex("^[A-Fa-f0-9]{64}$")))
        }

        assertEquals(4, UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS.supportedOutputScales)

        assertEquals(2, UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2.nativeScale)
        assertEquals(listOf(2), UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2.supportedOutputScales)

        assertEquals(3, UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X3.nativeScale)
        assertEquals(listOf(3), UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X3.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_REALSR_DF2K_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_REALSR_DF2K_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_REALSR_DF2K_JPEG_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_REALSR_DF2K_JPEG_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_REAL_ESRGAN_HFA2K_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_REAL_ESRGAN_HFA2K_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_ULTRASHARP_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_ULTRASHARP_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_ANIMESHARP_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_ANIMESHARP_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_NOMOS8KSC_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_NOMOS8KSC_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_REMACRI_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_REMACRI_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_REAL_ESRGAN_GENERAL_V3_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_REAL_ESRGAN_GENERAL_V3_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_ULTRAMIX_BALANCED_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_ULTRAMIX_BALANCED_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_ANIMESHARP_LITE_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_ANIMESHARP_LITE_X4.supportedOutputScales)

        assertEquals(4, UpscaleModelRegistry.NCNN_NICKELBACK_X4.nativeScale)
        assertEquals(listOf(4), UpscaleModelRegistry.NCNN_NICKELBACK_X4.supportedOutputScales)
    }

    @Test
    fun testDynamicCustomModelRegistration() {
        UpscaleModelRegistry.clearCustomModels()

        val customModel = UpscaleModel(
            id = "custom-test-model",
            name = "Test Custom Model",
            description = "Imported test model",
            type = ModelType.AI_NCNN,
            nativeScale = 4,
            sizeBytes = 12345L,
            paramSha256 = "35330ececcea33b6c397a72548e788d5d53becee4734c50b7fada36e89f10a86",
            binSha256 = "713ee713b0353afaa27976f0563a64a5043bd70b9bd8936c2e26e25ebcdbcddf",
            supportedOutputScales = listOf(4),
            genre = ModelGenre.CUSTOM_IMPORTED,
            isImported = true,
            engineKind = NcnnEngineKind.REALSR_ESRGAN
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

    @Test(expected = IllegalArgumentException::class)
    fun testNcnnContractRejectsMissingPairMetadata() {
        UpscaleModel(
            id = "broken-ncnn-model",
            name = "Broken NCNN Model",
            description = "Test fixture",
            type = ModelType.AI_NCNN,
            nativeScale = 4,
            sizeBytes = 10L,
            engineKind = NcnnEngineKind.REALSR_ESRGAN
        ).validateNcnnArtifactContract()
    }

    @Test
    fun testSelectableModelsAreStrictlyBuiltInOrPinnedNcnn() {
        val selectable = UpscaleModelRegistry.SELECTABLE_MODELS
        assertTrue(selectable.isNotEmpty())
        selectable.forEach { model ->
            assertTrue(
                "Selectable model ${model.id} must be built-in algorithmic or verified NCNN with SHA-256",
                model.isBuiltIn || (model.type == ModelType.AI_NCNN && !model.paramSha256.isNullOrBlank() && !model.binSha256.isNullOrBlank())
            )
        }
        val onnxModels = UpscaleModelRegistry.ALL_MODELS.filter { it.type.name.contains("ONNX") }
        assertTrue("Zero ONNX models must exist in UpscaleModelRegistry", onnxModels.isEmpty())
    }
}
