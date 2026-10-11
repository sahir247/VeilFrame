package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.OnnxSessionManager
import com.veilframe.app.upscale.inference.isRecognizedStrengthInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InferenceOptimizationAndSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `session manager cache key is model content and device aware`() {
        val modelFile1 = File(tempFolder.root, "real_esrgan_x4.onnx")
        modelFile1.writeText("weights_version_1")
        modelFile1.setLastModified(1000000L)

        val key1 = OnnxSessionManager.getCacheKey(modelFile1)
        assertTrue("Key must contain model name", key1.contains("real_esrgan_x4.onnx"))

        // Modify file contents
        val modelFile2 = File(tempFolder.root, "real_esrgan_x4.onnx")
        modelFile2.writeText("weights_version_2_longer_payload")
        modelFile2.setLastModified(2000000L)

        val key2 = OnnxSessionManager.getCacheKey(modelFile2)

        // Keys must not collide when model file content / length / timestamp changes
        assertNotEquals("Cache key must invalidate when model contents change", key1, key2)
    }

    @Test
    fun `control input semantic whitelisting accepts recognized quality inputs`() {
        assertTrue(isRecognizedStrengthInput("strength", "generic_model.onnx"))
        assertTrue(isRecognizedStrengthInput("qf", "generic_model.onnx"))
        assertTrue(isRecognizedStrengthInput("q_factor", "generic_model.onnx"))
        assertTrue(isRecognizedStrengthInput("qfactor", "generic_model.onnx"))
        assertTrue(isRecognizedStrengthInput("quality", "generic_model.onnx"))
        assertTrue(isRecognizedStrengthInput("cond", "generic_model.onnx"))
        assertTrue(isRecognizedStrengthInput("any_scalar", "fbcnn_color.onnx"))
    }

    @Test
    fun `control input semantic whitelisting rejects arbitrary unrelated scalars`() {
        assertFalse(isRecognizedStrengthInput("noise_level", "custom_esrgan.onnx"))
        assertFalse(isRecognizedStrengthInput("temperature", "custom_esrgan.onnx"))
        assertFalse(isRecognizedStrengthInput("embedding_id", "custom_esrgan.onnx"))
        assertFalse(isRecognizedStrengthInput("scale_factor", "custom_esrgan.onnx"))
    }
}
