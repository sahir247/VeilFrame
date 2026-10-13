package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.OnnxSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class OnnxSessionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testComputeContentDigestComputesValidSha256() {
        val payload = "VeilFrame ONNX Model Weights Content 2026"
        val modelFile = tempFolder.newFile("test_model.ort").apply {
            writeBytes(payload.toByteArray(Charsets.UTF_8))
        }

        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val digest = OnnxSessionManager.computeContentDigest(modelFile)
        assertNotNull("Digest must be non-null for valid file", digest)
        assertEquals("Computed digest must match cryptographic SHA-256", expectedSha, digest)
    }

    @Test
    fun testComputeContentDigestFailsClosedForMissingOrDirectoryFile() {
        val nonExistent = File(tempFolder.root, "does_not_exist.ort")
        assertNull("Missing file must return null (fail closed)", OnnxSessionManager.computeContentDigest(nonExistent))

        val directory = tempFolder.newFolder("folder_as_file")
        assertNull("Directory must return null (fail closed)", OnnxSessionManager.computeContentDigest(directory))
    }

    @Test
    fun testGetCacheKeyReturnsNullWhenContentDigestFails() {
        val nonExistent = File(tempFolder.root, "does_not_exist.ort")
        val cacheKey = OnnxSessionManager.getCacheKey(nonExistent)
        assertNull("Cache key must be null when content digest fails (remains uncached)", cacheKey)
    }

    @Test
    fun testGetCacheKeyFormatWithValidFile() {
        val modelFile = tempFolder.newFile("realesrgan_x4.ort").apply {
            writeBytes("model bytes".toByteArray(Charsets.UTF_8))
        }
        val cacheKey = OnnxSessionManager.getCacheKey(modelFile)
        assertNotNull(cacheKey)
        assertTrue("Cache key must start with ep_backend_", cacheKey!!.startsWith("ep_backend_realesrgan_x4.ort_"))
    }
}
