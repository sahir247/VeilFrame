package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.DeviceCapabilityProfile
import com.veilframe.app.upscale.inference.ModelExecutionCapabilities
import com.veilframe.app.upscale.inference.TileSizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSizePolicyTest {

    private val baseDevice = DeviceCapabilityProfile(
        manufacturer = "Test",
        model = "Device",
        cpuCores = 8,
        supportedAbis = listOf("arm64-v8a"),
        totalMemoryBytes = 8L * 1024 * 1024 * 1024,
        availableMemoryBytes = 4L * 1024 * 1024 * 1024,
        apiLevel = 34,
        lowRamDevice = false,
        supportsNnapi = true,
        supportsNnapiFp16 = true,
        supportsXnnpack = true,
        thermalStatus = 0
    )

    @Test
    fun testFixedGeometryModelAdheresToModelConstraint() {
        val modelCaps = ModelExecutionCapabilities(
            nativeScale = 4,
            fixedWidth = 128,
            fixedHeight = 128
        )

        val candidates = TileSizePolicy.candidates(
            modelCaps = modelCaps,
            deviceProfile = baseDevice,
            javaBudgetBytes = 512L * 1024 * 1024,
            nativeBudgetBytes = 1024L * 1024 * 1024
        )

        assertEquals(listOf(128), candidates)
    }

    @Test
    fun testStandardMemoryReturnsBaselineCandidates() {
        val modelCaps = ModelExecutionCapabilities(
            nativeScale = 4,
            supportsFp16 = true
        )

        val candidates = TileSizePolicy.candidates(
            modelCaps = modelCaps,
            deviceProfile = baseDevice,
            javaBudgetBytes = 384L * 1024 * 1024,
            nativeBudgetBytes = 1024L * 1024 * 1024
        )

        assertTrue(candidates.contains(256))
        assertTrue(candidates.all { it <= 512 })
    }

    @Test
    fun testFlagshipMemoryReturnsExtendedCandidates() {
        val modelCaps = ModelExecutionCapabilities(
            nativeScale = 2,
            supportsFp16 = true
        )

        // Flagship thresholds: nativeBudget >= 1.5GB, javaBudget >= 512MB
        val candidates = TileSizePolicy.candidates(
            modelCaps = modelCaps,
            deviceProfile = baseDevice,
            javaBudgetBytes = 768L * 1024 * 1024,
            nativeBudgetBytes = 2048L * 1024 * 1024
        )

        assertTrue(candidates.contains(256))
        assertTrue(candidates.contains(384))
        assertTrue(candidates.contains(512))
        assertTrue(candidates.contains(640))
        assertTrue(candidates.contains(768))
    }
}
