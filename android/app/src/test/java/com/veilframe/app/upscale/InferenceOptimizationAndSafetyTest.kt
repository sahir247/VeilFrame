package com.veilframe.app.upscale

import ai.onnxruntime.OnnxJavaType
import com.veilframe.app.runtime.ThermalGovernor
import com.veilframe.app.upscale.inference.AiProcessor
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
    fun `session manager cache key invalidates when weights change with identical length and timestamp`() {
        val modelFile = File(tempFolder.root, "real_esrgan_x4.onnx")
        modelFile.writeText("weights_alpha_01") // exactly 16 bytes
        val fixedTimestamp = 1700000000000L
        modelFile.setLastModified(fixedTimestamp)

        val key1 = OnnxSessionManager.getCacheKey(modelFile)

        // Overwrite file with different bytes but exactly identical length
        modelFile.writeText("weights_beta__02") // exactly 16 bytes
        modelFile.setLastModified(fixedTimestamp)

        assertEquals("File lengths must be identical", 16L, modelFile.length())
        assertEquals("File last modified must be identical", fixedTimestamp, modelFile.lastModified())

        val key2 = OnnxSessionManager.getCacheKey(modelFile)

        assertNotEquals(
            "Cryptographic SHA-256 digest must prevent cache key collision when weights change despite same length and timestamp",
            key1,
            key2
        )
    }

    @Test
    fun `in-RAM budget strictly returns zero when available system memory is under 64 MB`() {
        val lowRamProbe = object : com.veilframe.app.cv.core.MemoryProbe {
            override fun totalMemoryBytes(): Long = 4L * 1024L * 1024L * 1024L
            override fun availableMemoryBytes(): Long = 32L * 1024L * 1024L // 32 MB < 64 MB threshold
        }

        val budget = AiProcessor.inRamOutputBudget(lowRamProbe)
        assertEquals("Budget must strictly return 0L under 64 MB available RAM (no coerceAtLeast floor)", 0L, budget)
    }

    @Test
    fun `in-RAM budget scales with available memory and heap above safe threshold`() {
        val healthyRamProbe = object : com.veilframe.app.cv.core.MemoryProbe {
            override fun totalMemoryBytes(): Long = 8L * 1024L * 1024L * 1024L
            override fun availableMemoryBytes(): Long = 2L * 1024L * 1024L * 1024L // 2 GB available
        }

        val budget = AiProcessor.inRamOutputBudget(healthyRamProbe)
        assertTrue("Budget must be positive when ample RAM is available", budget > 0L)
        assertTrue("Budget must not exceed 200 MB maximum cap", budget <= 200L * 1024L * 1024L)
    }

    @Test
    fun `resolveParallelWorkers independently clamps worker count under constrained memory and thermal throttling`() {
        val highWorkers = 8
        val tileCount = 16
        val chunkSize = 512
        val scale = 4

        // 1. Constrained available RAM probe (e.g. 50 MB available)
        val constrainedRamProbe = object : com.veilframe.app.cv.core.MemoryProbe {
            override fun totalMemoryBytes(): Long = 4L * 1024L * 1024L * 1024L
            override fun availableMemoryBytes(): Long = 50L * 1024L * 1024L
        }

        val clampedWorkers = AiProcessor.resolveParallelWorkers(
            requestedWorkers = highWorkers,
            tileCount = tileCount,
            chunkSize = chunkSize,
            scaleFactor = scale,
            memProbe = constrainedRamProbe
        )
        assertEquals("Worker count must clamp to 1 when per-tile peak memory exceeds 25% of available RAM", 1, clampedWorkers)

        // 2. Thermal throttling clamp
        try {
            ThermalGovernor.overrideThrottledForTesting = true
            val ampleRamProbe = object : com.veilframe.app.cv.core.MemoryProbe {
                override fun totalMemoryBytes(): Long = 16L * 1024L * 1024L * 1024L
                override fun availableMemoryBytes(): Long = 8L * 1024L * 1024L * 1024L
            }
            val throttledWorkers = AiProcessor.resolveParallelWorkers(
                requestedWorkers = 6,
                tileCount = tileCount,
                chunkSize = 128,
                scaleFactor = 1,
                memProbe = ampleRamProbe
            )
            assertTrue("Worker count must be clamped to <= 2 under thermal throttling", throttledWorkers <= 2)
        } finally {
            ThermalGovernor.overrideThrottledForTesting = null
        }
    }

    @Test
    fun `control input semantic filtering validates exact names, tensor types, and shapes`() {
        // Universal recognized names
        assertTrue(isRecognizedStrengthInput("strength", "generic_model.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L)))
        assertTrue(isRecognizedStrengthInput("qf", "generic_model.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L)))
        assertTrue(isRecognizedStrengthInput("quality", "generic_model.onnx", OnnxJavaType.FLOAT16, longArrayOf(1L, 1L)))

        // FBCNN recognized names
        assertTrue(isRecognizedStrengthInput("qf", "fbcnn_color.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L)))
        assertTrue(isRecognizedStrengthInput("qf_input", "fbcnn_color.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L)))
        // FBCNN rejects arbitrary scalars now
        assertFalse(isRecognizedStrengthInput("any_scalar", "fbcnn_color.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L)))
        assertFalse(isRecognizedStrengthInput("temperature", "fbcnn_color.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L)))

        // Non-float types are rejected
        assertFalse(isRecognizedStrengthInput("qf", "fbcnn_color.onnx", OnnxJavaType.INT32, longArrayOf(1L, 1L)))
        assertFalse(isRecognizedStrengthInput("strength", "generic_model.onnx", OnnxJavaType.INT64, longArrayOf(1L, 1L)))

        // Non-scalar shapes are rejected
        assertFalse(isRecognizedStrengthInput("strength", "generic_model.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 3L)))
        assertFalse(isRecognizedStrengthInput("strength", "generic_model.onnx", OnnxJavaType.FLOAT, longArrayOf(1L, 1L, 1L)))
    }

    @Test
    fun `control input semantic whitelisting rejects arbitrary unrelated scalars`() {
        assertFalse(isRecognizedStrengthInput("noise_level", "custom_esrgan.onnx"))
        assertFalse(isRecognizedStrengthInput("temperature", "custom_esrgan.onnx"))
        assertFalse(isRecognizedStrengthInput("embedding_id", "custom_esrgan.onnx"))
        assertFalse(isRecognizedStrengthInput("scale_factor", "custom_esrgan.onnx"))
    }

    @Test
    fun `computeBudget couples intraOpThreads and parallelWorkers while reserving UI core`() {
        // 8-core device -> usable = 7, workers = 3, intra = 4
        val budget8 = com.veilframe.app.upscale.inference.UpscaleInferenceParams.computeBudget(8)
        assertEquals(4, budget8.intraOpThreads)
        assertEquals(3, budget8.parallelWorkers)

        // 4-core device -> usable = 3, workers = 3, intra = 3
        val budget4 = com.veilframe.app.upscale.inference.UpscaleInferenceParams.computeBudget(4)
        assertEquals(3, budget4.intraOpThreads)
        assertEquals(3, budget4.parallelWorkers)

        // 2-core device -> usable = 2, workers = 2, intra = 2
        val budget2 = com.veilframe.app.upscale.inference.UpscaleInferenceParams.computeBudget(2)
        assertEquals(2, budget2.intraOpThreads)
        assertEquals(2, budget2.parallelWorkers)
    }

    @Test
    fun `graduated thermal response differentiates mild throttle from severe and critical`() {
        assertEquals(ThermalGovernor.ThermalResponse.NONE, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_NONE))
        assertEquals(ThermalGovernor.ThermalResponse.NONE, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_LIGHT))
        assertEquals(ThermalGovernor.ThermalResponse.MILD_THROTTLE, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_MODERATE))
        assertEquals(ThermalGovernor.ThermalResponse.FULL_THROTTLE, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_SEVERE))
        assertEquals(ThermalGovernor.ThermalResponse.ABORT, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_CRITICAL))
        assertEquals(ThermalGovernor.ThermalResponse.ABORT, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_EMERGENCY))
        assertEquals(ThermalGovernor.ThermalResponse.ABORT, ThermalGovernor.responseForStatus(ThermalGovernor.STATUS_SHUTDOWN))
    }
}
