package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.Backend
import com.veilframe.app.upscale.inference.CachedPerformanceProfile
import com.veilframe.app.upscale.inference.DeviceCapabilityProfile
import com.veilframe.app.upscale.inference.ExecutionProfile
import com.veilframe.app.upscale.inference.InferencePrecisionMode
import com.veilframe.app.upscale.inference.PerformanceProfileKey
import com.veilframe.app.upscale.inference.SessionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfileCacheTest {

    @Test
    fun testPerformanceProfileKeyGeneration() {
        val device = DeviceCapabilityProfile(
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            cpuCores = 9,
            supportedAbis = listOf("arm64-v8a"),
            totalMemoryBytes = 12L * 1024 * 1024 * 1024,
            availableMemoryBytes = 6L * 1024 * 1024 * 1024,
            apiLevel = 34,
            lowRamDevice = false,
            supportsNnapi = true,
            supportsNnapiFp16 = true,
            supportsXnnpack = false,
            thermalStatus = 0
        )

        val key = PerformanceProfileKey.forDeviceAndModel(
            device = device,
            modelId = "realesrgan_x4plus",
            modelScale = 4,
            ortVersion = "1.17.0"
        )

        assertEquals("Google Pixel 8 Pro", key.deviceModel)
        assertEquals(34, key.androidApi)
        assertEquals("arm64-v8a", key.abi)
        assertEquals("realesrgan_x4plus", key.modelId)
        assertEquals(4, key.modelScale)

        val keyStr = key.toKeyString()
        assertTrue(keyStr.contains("Google_Pixel_8_Pro"))
        assertTrue(keyStr.contains("api34"))
        assertTrue(keyStr.contains("arm64-v8a"))
        assertTrue(keyStr.contains("realesrgan_x4plus"))
        assertTrue(keyStr.contains("scale4"))
    }

    @Test
    fun testCachedPerformanceProfileDataContract() {
        val key = PerformanceProfileKey(
            deviceModel = "Qualcomm Reference",
            androidApi = 34,
            abi = "arm64-v8a",
            ortVersion = "1.17.0",
            modelId = "realesrgan_x2plus",
            modelScale = 2
        )

        val ep = ExecutionProfile(
            backend = Backend.NNAPI,
            precision = InferencePrecisionMode.FP16_RELAXED,
            workers = 2,
            intraOpThreads = null,
            interOpThreads = null,
            tileSize = 384,
            overlap = 24,
            sessionStrategy = SessionStrategy.SHARED_SESSION
        )

        val cached = CachedPerformanceProfile(
            key = key,
            executionProfile = ep,
            measuredMpPerSecond = 14.85,
            observedPeakMemoryBytes = 320L * 1024 * 1024,
            sampleCount = 3
        )

        assertEquals(key, cached.key)
        assertEquals(ep, cached.executionProfile)
        assertEquals(14.85, cached.measuredMpPerSecond, 0.001)
        assertEquals(320L * 1024 * 1024, cached.observedPeakMemoryBytes)
        assertEquals(320L * 1024 * 1024, cached.measuredPeakMemoryBytes)
        assertEquals(3, cached.sampleCount)
        assertEquals(Backend.NNAPI, cached.executionProfile.backend)
        assertEquals(InferencePrecisionMode.FP16_RELAXED, cached.executionProfile.precision)
    }

    @Test
    fun testPerformanceProfileKeyWithProviderAndHash() {
        val device = DeviceCapabilityProfile(
            manufacturer = "Qualcomm",
            model = "SM8650",
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            totalMemoryBytes = 12L * 1024 * 1024 * 1024,
            availableMemoryBytes = 6L * 1024 * 1024 * 1024,
            apiLevel = 34,
            lowRamDevice = false,
            supportsNnapi = true,
            supportsNnapiFp16 = true,
            supportsXnnpack = true,
            thermalStatus = 0
        )

        val key = PerformanceProfileKey.forDeviceAndModel(
            device = device,
            modelId = "realesrgan_x4plus",
            modelScale = 4,
            ortVersion = "1.27.0",
            providerBuildId = "standard",
            providerConfigurationId = "nnapi_fp16",
            modelHash = "a1b2c3d4e5f6"
        )

        assertEquals("Qualcomm SM8650", key.deviceModel)
        assertEquals("standard", key.providerBuildId)
        assertEquals("nnapi_fp16", key.providerConfigurationId)
        assertEquals("a1b2c3d4e5f6", key.modelHash)

        val keyStr = key.toKeyString()
        assertTrue(keyStr.contains("pbstandard"))
        assertTrue(keyStr.contains("pcnnapi_fp16"))
        assertTrue(keyStr.contains("_ha1b2c3d4"))
    }

    @Test
    fun testVersion4KeyStringFormat() {
        val key = PerformanceProfileKey(
            deviceModel = "Pixel 8",
            androidApi = 34,
            abi = "arm64-v8a",
            ortVersion = "1.27.0",
            modelId = "realesrgan_x4plus",
            modelScale = 4,
            benchmarkVersion = 4
        )
        assertTrue(key.toKeyString().endsWith("_v4"))
    }

    @Test
    fun testConfigurationFingerprintGeneration() {
        val profile = ExecutionProfile(
            backend = Backend.NNAPI,
            precision = InferencePrecisionMode.FP16_RELAXED,
            workers = 4,
            tileSize = 384,
            overlap = 24,
            acceleratorConfiguration = com.veilframe.app.upscale.inference.AcceleratorConfiguration(nnapiUseNchw = true)
        )
        val fingerprint = CachedPerformanceProfile.generateConfigurationFingerprint(profile)
        assertEquals("nnapi_fp16_relaxed_nchw_t384_o24_w4", fingerprint)
    }

    @Test
    fun testPathologicalProfileDetection() {
        val key = PerformanceProfileKey(
            deviceModel = "Device",
            androidApi = 34,
            abi = "arm64-v8a",
            ortVersion = "1.27.0",
            modelId = "model",
            modelScale = 4
        )
        val profile = ExecutionProfile(
            backend = Backend.NNAPI,
            precision = InferencePrecisionMode.DEFAULT,
            workers = 1,
            tileSize = 256,
            overlap = 16
        )
        val pathological = CachedPerformanceProfile(
            key = key,
            executionProfile = profile,
            measuredMpPerSecond = 0.0105
        )
        assertTrue("Throughput 0.0105 MP/s must be flagged as pathological", pathological.isPathological)

        val healthy = CachedPerformanceProfile(
            key = key,
            executionProfile = profile,
            measuredMpPerSecond = 2.5
        )
        org.junit.Assert.assertFalse("Throughput 2.5 MP/s must not be flagged as pathological", healthy.isPathological)
    }
}
