package com.veilframe.app.media.rife

import android.content.Context
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
}
