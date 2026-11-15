package com.veilframe.app.media.rife

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RifeModelRegistryAndManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testRifeModelRegistryExcludesLegacyV24() {
        val allIds = RifeModelRegistry.ALL_MODELS.map { it.id }
        assertFalse("Legacy RIFE v2.4 must be completely excluded", allIds.any { it.contains("2.4") })
    }

    @Test
    fun testRifeModelRegistryPaddingConstraints() {
        // v4.25 and v4.26 strictly require 64-pixel alignment
        assertEquals(64, RifeModelRegistry.RIFE_V4_25.requiredPadding)
        assertEquals(64, RifeModelRegistry.RIFE_V4_26.requiredPadding)

        // v4.0, v4.15-lite, and v4.22-lite use 32-pixel alignment
        assertEquals(32, RifeModelRegistry.RIFE_V4_22_LITE.requiredPadding)
        assertEquals(32, RifeModelRegistry.RIFE_V4_15_LITE.requiredPadding)
        assertEquals(32, RifeModelRegistry.RIFE_V4_0.requiredPadding)
    }

    @Test
    fun testAllRifeModelUrlsAreSecurityApproved() {
        for (model in RifeModelRegistry.ALL_MODELS) {
            assertTrue("Param URL for ${model.id} must be approved", RifeModelManager.isAllowedUrl(model.paramUrl))
            assertTrue("Bin URL for ${model.id} must be approved", RifeModelManager.isAllowedUrl(model.binUrl))
            model.paramCdnUrl?.let {
                assertTrue("Param CDN URL for ${model.id} must be approved", RifeModelManager.isAllowedUrl(it))
            }
            model.binCdnUrl?.let {
                assertTrue("Bin CDN URL for ${model.id} must be approved", RifeModelManager.isAllowedUrl(it))
            }
            model.paramFallbackUrl?.let {
                assertTrue("Param Fallback URL for ${model.id} must be approved", RifeModelManager.isAllowedUrl(it))
            }
            model.binFallbackUrl?.let {
                assertTrue("Bin Fallback URL for ${model.id} must be approved", RifeModelManager.isAllowedUrl(it))
            }
        }
    }

    @Test
    fun testFindByIdReturnsCorrectOrFallback() {
        assertEquals("rife-v4.25", RifeModelRegistry.findById("rife-v4.25").id)
        assertEquals("rife-v4.26", RifeModelRegistry.findById("rife-v4.26").id)
        assertEquals("rife-v4.22-lite", RifeModelRegistry.findById("rife-v4.22-lite").id)
        assertEquals("rife-v4.15-lite", RifeModelRegistry.findById("rife-v4.15-lite").id)
        assertEquals("rife-v4.0", RifeModelRegistry.findById("rife-v4.0").id)

        // Unknown or null falls back to flagship RIFE_V4_25
        assertEquals(RifeModelRegistry.RIFE_V4_25, RifeModelRegistry.findById(null))
        assertEquals(RifeModelRegistry.RIFE_V4_25, RifeModelRegistry.findById("unknown-model"))
    }

    @Test
    fun testRifeModelManagerSelectionAndPadding() {
        val manager = RifeModelManager(context)

        // Select RIFE v4.26
        manager.setSelectedModel(RifeModelRegistry.RIFE_V4_26)
        assertEquals(RifeModelRegistry.RIFE_V4_26.id, manager.getSelectedModel().id)
        assertEquals(64, manager.requiredPadding)

        // Select RIFE v4.15 Lite
        manager.setSelectedModel(RifeModelRegistry.RIFE_V4_15_LITE)
        assertEquals(RifeModelRegistry.RIFE_V4_15_LITE.id, manager.getSelectedModel().id)
        assertEquals(32, manager.requiredPadding)

        // Select RIFE v4.25 Gold
        manager.setSelectedModel(RifeModelRegistry.RIFE_V4_25)
        assertEquals(RifeModelRegistry.RIFE_V4_25.id, manager.getSelectedModel().id)
        assertEquals(64, manager.requiredPadding)
    }

    @Test
    fun testRifeModelSha256DigestsArePresentAndValid() {
        val shaRegex = Regex("^[a-f0-9]{64}$")
        for (model in RifeModelRegistry.ALL_MODELS) {
            assertTrue("Param SHA256 for ${model.id} must be valid 64-char hex", shaRegex.matches(model.paramSha256.lowercase()))
            assertTrue("Bin SHA256 for ${model.id} must be valid 64-char hex", shaRegex.matches(model.binSha256.lowercase()))
            assertTrue("Max param size must be positive", model.maxParamBytes > 1000)
            assertTrue("Max bin size must be positive", model.maxBinBytes >= 5_000_000)
        }
    }

    @Test
    fun testAllowedHostsStrictlyExcludesWildcardsAndUnapprovedDomains() {
        assertFalse("Amazon AWS wildcard must be rejected", RifeModelManager.isAllowedUrl("https://s3.amazonaws.com/malicious/flownet.bin"))
        assertFalse("Unapproved CDN must be rejected", RifeModelManager.isAllowedUrl("https://unpkg.com/rife/flownet.bin"))
        assertFalse("HTTP scheme must be rejected", RifeModelManager.isAllowedUrl("http://raw.githubusercontent.com/rife/flownet.bin"))
        assertFalse("Arbitrary host must be rejected", RifeModelManager.isAllowedUrl("https://evil-mirror.org/flownet.bin"))

        assertTrue("GitHub raw must be allowed", RifeModelManager.isAllowedUrl("https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.25/flownet.bin"))
        assertTrue("GitHub release object must be allowed", RifeModelManager.isAllowedUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/flownet.bin"))
        assertTrue("jsDelivr CDN must be allowed", RifeModelManager.isAllowedUrl("https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.25/flownet.bin"))
    }

    @Test
    fun testCachedRifeModelTamperingIsRejectedByChecksum() {
        val manager = RifeModelManager(context)
        val model = RifeModelRegistry.RIFE_V4_26
        val modelDir = File(context.filesDir, "models/${model.id}").apply { mkdirs() }
        val paramF = File(modelDir, "flownet.param")
        val binF = File(modelDir, "flownet.bin")

        // Create dummy files within size bounds but with mismatched content (tampered)
        paramF.writeBytes(ByteArray(2000) { 0x55.toByte() })
        binF.writeBytes(ByteArray(5_000_000) { 0xAA.toByte() })

        // Checksum verification must catch the tampering and return false
        assertFalse("Tampered cached param/bin must be rejected by isModelDownloaded", manager.isModelDownloaded(model, verifyChecksums = true))
        assertFalse("Tampered cached model must not be considered ready for inference", manager.verifyModelIntegrity(model))

        // Basic size check without verifyChecksums might return true, but trust boundary enforces verifyChecksums
        assertTrue("Basic size check passes without checksum verification", manager.isModelDownloaded(model, verifyChecksums = false))

        // Cleanup
        paramF.delete()
        binF.delete()
        modelDir.delete()
        RifeModelManager.invalidateChecksumCache()
    }

    @Test
    fun testFreshDigestRejectsSameMetadataTampering() {
        val manager = RifeModelManager(context)
        val model = RifeModelRegistry.RIFE_V4_26
        val modelDir = File(context.filesDir, "models/${model.id}").apply { mkdirs() }
        val paramF = File(modelDir, "flownet.param")
        val binF = File(modelDir, "flownet.bin")

        // Write files with exact same length but tampered bytes
        paramF.writeBytes(ByteArray(2000) { 0x12.toByte() })
        binF.writeBytes(ByteArray(5_000_000) { 0x34.toByte() })

        // Direct fresh digest check must fail
        assertFalse("Fresh digest verification must reject tampered param/bin", manager.verifyModelIntegrity(model, forceFreshDigest = true))

        paramF.delete()
        binF.delete()
        modelDir.delete()
        RifeModelManager.invalidateChecksumCache()
    }
}
