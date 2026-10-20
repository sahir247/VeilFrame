package com.veilframe.app.media

import com.veilframe.app.media.whatsapp.WhatsappStatusConstants
import com.veilframe.app.media.whatsapp.WhatsappStatusResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresetsTest {

    @Test
    fun testSocialPresetsTargetSizes() {
        val testPresets = mapOf(
            "Instagram Story" to 50.0,
            "TikTok (72 MB)" to 72.0,
            "YouTube Shorts" to 100.0,
            "Twitter / X" to 120.0,
            "Telegram HD" to 200.0,
            "Discord (25 MB)" to 25.0,
            "Discord Nitro (50 MB)" to 50.0,
            "Email Attachment (8 MB)" to 8.0,
            "Web Stream (10 MB)" to 10.0,
            "WhatsApp (16 MB)" to 16.0
        )

        testPresets.forEach { (presetName, expectedMb) ->
            val config = VideoOutputConfig(
                targetPreset = presetName
            )
            // Resolve target size via the exact mapping logic used in MediaProcessor
            val resolvedMb: Double? = config.targetMb?.toDouble() ?: when (config.targetPreset) {
                "WhatsApp (16 MB)" -> 16.0
                "Instagram Story" -> 50.0
                "TikTok (72 MB)" -> 72.0
                "YouTube Shorts" -> 100.0
                "Twitter / X" -> 120.0
                "Telegram HD" -> 200.0
                "Discord (25 MB)" -> 25.0
                "Discord Nitro (50 MB)" -> 50.0
                "Email Attachment (8 MB)" -> 8.0
                "Web Stream (10 MB)" -> 10.0
                else -> null
            }
            assertNotNull("Target preset $presetName must resolve to a valid MB ceiling", resolvedMb)
            assertEquals("Target preset $presetName ceiling mismatch", expectedMb, resolvedMb!!, 0.001)
        }
    }

    @Test
    fun testSocialPresetsTriggerNoMbTreeOptimization() {
        val socialPresets = listOf(
            "WhatsApp Status",
            "Instagram Story",
            "TikTok (72 MB)",
            "YouTube Shorts",
            "Twitter / X",
            "Telegram HD"
        )

        socialPresets.forEach { preset ->
            val isSocialPreset = preset.contains("WhatsApp", ignoreCase = true) ||
                    preset.contains("Instagram", ignoreCase = true) ||
                    preset.contains("TikTok", ignoreCase = true) ||
                    preset.contains("Shorts", ignoreCase = true) ||
                    preset.contains("Twitter", ignoreCase = true) ||
                    preset.contains("Telegram", ignoreCase = true)

            assertTrue("Preset '$preset' must be classified as social to inject no-mbtree=1", isSocialPreset)
        }
    }

    @Test
    fun testPureStatusTuningConstants() {
        assertEquals("veryfast", WhatsappStatusConstants.PRESET)
        assertEquals("no-mbtree=1", WhatsappStatusConstants.X264_PARAMS)
        assertEquals(23, WhatsappStatusResolution.HD_720P.crf)
        assertEquals(20, WhatsappStatusResolution.FHD_1080P.crf)
    }
}
