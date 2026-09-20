package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.Backend
import com.veilframe.app.upscale.inference.DeviceCapabilityProfile
import com.veilframe.app.upscale.inference.InferenceAccelerationMode
import com.veilframe.app.upscale.inference.InferencePrecisionMode
import com.veilframe.app.upscale.inference.SessionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityProfileTest {

    @Test
    fun testDeviceCapabilityProfileInstantiationAndDefaults() {
        val profile = DeviceCapabilityProfile(
            manufacturer = "Qualcomm",
            model = "Snapdragon 8 Gen 3 Test",
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            totalMemoryBytes = 12L * 1024 * 1024 * 1024,
            availableMemoryBytes = 8L * 1024 * 1024 * 1024,
            apiLevel = 34,
            lowRamDevice = false,
            supportsNnapi = true,
            supportsNnapiFp16 = true,
            supportsQnnBuild = false,
            supportsXnnpack = false,
            thermalStatus = 0
        )

        assertEquals("Qualcomm", profile.manufacturer)
        assertEquals(8, profile.cpuCores)
        assertTrue(profile.supportsNnapi)
        assertTrue(profile.supportsNnapiFp16)
        assertFalse(profile.supportsQnnBuild)
        assertFalse(profile.lowRamDevice)
        assertTrue(profile.javaHeapBudget > 0L)
        assertTrue(profile.nativeProcessBudget > 0L)
    }

    @Test
    fun testQnnCapabilityFlagTruthfulReporting() {
        // Standard ORT builds do not bundle QNN native libraries
        val standardOrtProfile = DeviceCapabilityProfile(
            manufacturer = "Samsung",
            model = "Galaxy S24",
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            totalMemoryBytes = 8L * 1024 * 1024 * 1024,
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            apiLevel = 34,
            lowRamDevice = false,
            supportsNnapi = true,
            supportsNnapiFp16 = true,
            supportsQnnBuild = false,
            supportsXnnpack = false,
            thermalStatus = 0
        )
        assertFalse("Standard ORT build must not report QNN as supported", standardOrtProfile.supportsQnnBuild)

        // Custom QNN-enabled build
        val customQnnProfile = standardOrtProfile.copy(supportsQnnBuild = true)
        assertTrue("Custom QNN build correctly reports QNN support", customQnnProfile.supportsQnnBuild)
    }

    @Test
    fun testLowRamDetectionThreshold() {
        val lowRamProfile = DeviceCapabilityProfile(
            manufacturer = "Generic",
            model = "Entry 2GB",
            cpuCores = 4,
            supportedAbis = listOf("armeabi-v7a"),
            totalMemoryBytes = 2L * 1024 * 1024 * 1024,
            availableMemoryBytes = 512L * 1024 * 1024,
            apiLevel = 28,
            lowRamDevice = true,
            supportsNnapi = true,
            supportsNnapiFp16 = false,
            supportsQnnBuild = false,
            supportsXnnpack = false,
            thermalStatus = 0
        )
        assertTrue(lowRamProfile.lowRamDevice)
        assertFalse(lowRamProfile.supportsNnapiFp16)
    }
}
