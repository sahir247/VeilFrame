package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.InsufficientDiskSpaceException
import com.veilframe.app.upscale.inference.ResourceLimitExceededException
import com.veilframe.app.upscale.inference.UpscaleResourceEstimator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpscaleResourceEstimatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidDimensions_DefaultPlanning() {
        val plan = UpscaleResourceEstimator.estimateAndPlan(
            srcW = 1920,
            srcH = 1080,
            scale = 4,
            channels = 3
        )

        assertEquals(1920, plan.srcW)
        assertEquals(1080, plan.srcH)
        assertEquals(7680, plan.outW)
        assertEquals(4320, plan.outH)
        assertEquals(4, plan.scale)
        assertEquals(UpscaleResourceEstimator.DEFAULT_TILE_SIZE, plan.tileSize)
        assertTrue(plan.estimatedBandBytes <= UpscaleResourceEstimator.MAX_BAND_MEMORY_BYTES)
        assertTrue(plan.estimatedHostWorkingBytes <= UpscaleResourceEstimator.MAX_HOST_WORKING_BYTES)
        assertTrue(plan.estimatedTotalWorkingBytes <= UpscaleResourceEstimator.MAX_TOTAL_WORKING_BYTES)
        assertTrue(plan.estimatedInputStripBytes > 0)
        assertTrue(plan.estimatedVulkanBandBytes > plan.estimatedBandBytes)
    }

    @Test
    fun testNegativeOrZeroDimensions_ThrowsResourceLimitExceeded() {
        try {
            UpscaleResourceEstimator.estimateAndPlan(srcW = 0, srcH = 100, scale = 4, channels = 3)
            fail("Expected ResourceLimitExceededException for srcW <= 0")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("Invalid source dimensions") == true)
        }

        try {
            UpscaleResourceEstimator.estimateAndPlan(srcW = 100, srcH = -5, scale = 4, channels = 3)
            fail("Expected ResourceLimitExceededException for srcH <= 0")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("Invalid source dimensions") == true)
        }
    }

    @Test
    fun testInvalidScaleOrChannels_ThrowsResourceLimitExceeded() {
        try {
            UpscaleResourceEstimator.estimateAndPlan(srcW = 100, srcH = 100, scale = 0, channels = 3)
            fail("Expected ResourceLimitExceededException for scale <= 0")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("Invalid scale") == true)
        }

        try {
            UpscaleResourceEstimator.estimateAndPlan(srcW = 100, srcH = 100, scale = 4, channels = 1)
            fail("Expected ResourceLimitExceededException for channels == 1")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("Unsupported channel count") == true)
        }
    }

    @Test
    fun testSourceDimensionExceedsLimit_ThrowsResourceLimitExceeded() {
        try {
            UpscaleResourceEstimator.estimateAndPlan(
                srcW = (UpscaleResourceEstimator.MAX_SOURCE_DIMENSION + 1).toInt(),
                srcH = 1000,
                scale = 2,
                channels = 3
            )
            fail("Expected ResourceLimitExceededException for exceeding MAX_SOURCE_DIMENSION")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("exceed maximum supported dimension") == true)
        }
    }

    @Test
    fun testOutputDimensionExceedsLimit_ThrowsResourceLimitExceeded() {
        // srcW = 9000, scale = 4 -> outW = 36000 > MAX_OUTPUT_DIMENSION (32768)
        try {
            UpscaleResourceEstimator.estimateAndPlan(
                srcW = 9000,
                srcH = 1000,
                scale = 4,
                channels = 3
            )
            fail("Expected ResourceLimitExceededException for outW > MAX_OUTPUT_DIMENSION")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("exceed maximum safe output dimension") == true)
        }
    }

    @Test
    fun testTotalPixelsExceedsLimit_ThrowsResourceLimitExceeded() {
        // 6000x6000 at 4x -> 24000x24000 = 576,000,000 px > 100,000,000 px
        try {
            UpscaleResourceEstimator.estimateAndPlan(
                srcW = 6000,
                srcH = 6000,
                scale = 4,
                channels = 3
            )
            fail("Expected ResourceLimitExceededException for total pixels > 100 MP")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("exceeds maximum safe pixel budget") == true)
        }
    }

    @Test
    fun testAdaptiveTileDerivation_StepsDownTileSizeForWideImages() {
        // Case: wide image where tileSize=160 with 4x scale and 4 channels exceeds 64 MiB:
        // outW = 4000 * 4 = 16000
        // If tileSize = 160, bandH = 640. Band bytes = 16000 * 640 * 4 = 40,960,000 (fits in 64MB)
        // Let's take outW = 7500 (srcW = 1875, scale = 4, outW = 7500, channels = 4):
        // tileSize 160: 7500 * 640 * 4 = 19,200,000 (fits)
        // What if srcW = 7000, scale = 4, outW = 28000, channels = 4?
        // tileSize 160: 28000 * 640 * 4 = 71,680,000 bytes > 67,108,864 (exceeds 64 MiB!)
        // tileSize 128: 28000 * 512 * 4 = 57,344,000 bytes <= 64 MiB (fits!)
        val plan = UpscaleResourceEstimator.estimateAndPlan(
            srcW = 7000,
            srcH = 500,
            scale = 4,
            channels = 4
        )

        assertEquals(128, plan.tileSize)
        assertTrue(plan.estimatedBandBytes <= UpscaleResourceEstimator.MAX_BAND_MEMORY_BYTES)
    }

    @Test
    fun testExtremelyWideImage_EvenMinTileExceedsBudget_ThrowsResourceLimitExceeded() {
        // With a 16 MiB band budget, an 8000x500 image upscaled 4x with 4 channels
        // requires 32 MiB even at the absolute minimum tile size of 64 px.
        // It must throw ResourceLimitExceededException before allocating anything.
        try {
            UpscaleResourceEstimator.estimateAndPlan(
                srcW = 8000,
                srcH = 500,
                scale = 4,
                channels = 4,
                maxBandBytes = 16L * 1024L * 1024L // 16 MiB limit
            )
            fail("Expected ResourceLimitExceededException when minimum tile size exceeds band budget")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("No supported tile fits") == true)
        }
    }

    @Test
    fun testTotalWorkingSetLimitIsEnforced() {
        try {
            UpscaleResourceEstimator.estimateAndPlan(
                srcW = 1920,
                srcH = 1080,
                scale = 4,
                channels = 3,
                maxTotalWorkingBytes = 1L
            )
            fail("Expected ResourceLimitExceededException when total working-set cap is too small")
        } catch (e: ResourceLimitExceededException) {
            assertTrue(e.message?.contains("total working set") == true)
        }
    }

    @Test
    fun testRuntimeMemoryBudgetTracksAvailableMemoryAndLowMemorySignal() {
        val gib = 1024L * 1024L * 1024L
        val mib = 1024L * 1024L

        assertEquals(
            UpscaleResourceEstimator.MAX_TOTAL_WORKING_BYTES,
            UpscaleResourceEstimator.totalBudgetForAvailableMemory(2L * gib, lowMemory = false)
        )
        assertEquals(75L * mib, UpscaleResourceEstimator.totalBudgetForAvailableMemory(300L * mib, lowMemory = false))
        assertEquals(50L * mib, UpscaleResourceEstimator.totalBudgetForAvailableMemory(300L * mib, lowMemory = true))
        assertEquals(0L, UpscaleResourceEstimator.totalBudgetForAvailableMemory(0L, lowMemory = false))
    }

    @Test
    fun testDiskSpace_SufficientSpacePasses() {
        val cacheDir = tempFolder.newFolder("cache")
        val plan = UpscaleResourceEstimator.estimateAndPlan(
            srcW = 100,
            srcH = 100,
            scale = 4,
            channels = 3,
            cacheDir = cacheDir
        )
        assertNotNull(plan)
    }
}
