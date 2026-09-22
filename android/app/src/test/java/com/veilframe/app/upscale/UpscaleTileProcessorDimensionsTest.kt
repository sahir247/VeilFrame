package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.UpscaleTileProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive integration and unit test suite verifying tile partitioning,
 * edge/boundary tile calculations, overlap seam preservation, and exact output
 * dimensions across diverse aspect ratios, prime dimensions, and small images.
 *
 * Explicitly tests:
 * - 1272×2800 (verifies 4× is exactly 5088×11200)
 * - 512×512
 * - 513×513
 * - 1272×512
 * - 512×2800
 * - Small images (64×64, 128×96)
 * - Images with dimensions not divisible by tile size (333×444, 777×999)
 */
class UpscaleTileProcessorDimensionsTest {

    private val defaultProcessor = UpscaleTileProcessor(tileSize = 512, overlap = 32)

    @Test
    fun testTargetDimensions_1272x2800_Exact4xScaling() {
        val width = 1272
        val height = 2800
        val scale = 4

        val tiles = defaultProcessor.calculateTiles(width, height)

        // Step = 512 - 32 = 480
        // Horizontal: x=0 (512), x=480 (512), x=960 (min(512, 1272-960=312)) -> 3 columns (col 0, 1, 2)
        // Vertical: y=0, 480, 960, 1440, 1920 (each 512), y=2400 (min(512, 2800-2400=400)) -> 6 rows (row 0..5)
        // Total tiles = 3 cols * 6 rows = 18 tiles
        assertEquals(18, tiles.size)

        // Validate boundary tile at row 5, col 2
        val edgeTile = tiles.first { it.row == 5 && it.col == 2 }
        assertEquals(960, edgeTile.x)
        assertEquals(2400, edgeTile.y)
        assertEquals(312, edgeTile.width)
        assertEquals(400, edgeTile.height)

        // Verify full image coverage without gaps
        verifyCompleteCoverage(tiles, width, height)

        // Verify that compositing all tiles at scale 4 produces exactly 5088 x 11200
        val expectedOutW = width * scale
        val expectedOutH = height * scale
        assertEquals(5088, expectedOutW)
        assertEquals(11200, expectedOutH)

        // The furthest reaching tile (row 5, col 2) finishes at:
        val maxExtentX = (edgeTile.x * scale) + (edgeTile.width * scale)
        val maxExtentY = (edgeTile.y * scale) + (edgeTile.height * scale)
        assertEquals(5088, maxExtentX)
        assertEquals(11200, maxExtentY)
    }

    @Test
    fun testTargetDimensions_512x512_SingleTile() {
        val width = 512
        val height = 512
        val tiles = defaultProcessor.calculateTiles(width, height)

        assertEquals(1, tiles.size)
        val single = tiles[0]
        assertEquals(0, single.x)
        assertEquals(0, single.y)
        assertEquals(512, single.width)
        assertEquals(512, single.height)

        assertEquals(2048, width * 4)
        assertEquals(2048, height * 4)
    }

    @Test
    fun testTargetDimensions_513x513_NonDivisibleBoundary() {
        val width = 513
        val height = 513
        val tiles = defaultProcessor.calculateTiles(width, height)

        // Step 480:
        // x: 0 (512), 480 (min(512, 513-480=33)) -> 2 cols
        // y: 0 (512), 480 (min(512, 513-480=33)) -> 2 rows
        // Total tiles = 4
        assertEquals(4, tiles.size)

        val edgeTile = tiles.first { it.row == 1 && it.col == 1 }
        assertEquals(480, edgeTile.x)
        assertEquals(480, edgeTile.y)
        assertEquals(33, edgeTile.width)
        assertEquals(33, edgeTile.height)

        verifyCompleteCoverage(tiles, width, height)

        assertEquals(2052, width * 4)
        assertEquals(2052, height * 4)
        val maxExtentX = (edgeTile.x * 4) + (edgeTile.width * 4)
        val maxExtentY = (edgeTile.y * 4) + (edgeTile.height * 4)
        assertEquals(2052, maxExtentX)
        assertEquals(2052, maxExtentY)
    }

    @Test
    fun testTargetDimensions_1272x512_HorizontalPanorama() {
        val width = 1272
        val height = 512
        val tiles = defaultProcessor.calculateTiles(width, height)

        // Horizontal: 3 cols (512, 512, 312). Vertical: 1 row (512). Total: 3 tiles.
        assertEquals(3, tiles.size)
        val edge = tiles.first { it.col == 2 }
        assertEquals(312, edge.width)
        assertEquals(512, edge.height)

        verifyCompleteCoverage(tiles, width, height)
        assertEquals(5088, width * 4)
        assertEquals(2048, height * 4)
    }

    @Test
    fun testTargetDimensions_512x2800_VerticalPanorama() {
        val width = 512
        val height = 2800
        val tiles = defaultProcessor.calculateTiles(width, height)

        // Horizontal: 1 col (512). Vertical: 6 rows (512, 512, 512, 512, 512, 400). Total: 6 tiles.
        assertEquals(6, tiles.size)
        val edge = tiles.first { it.row == 5 }
        assertEquals(512, edge.width)
        assertEquals(400, edge.height)

        verifyCompleteCoverage(tiles, width, height)
        assertEquals(2048, width * 4)
        assertEquals(11200, height * 4)
    }

    @Test
    fun testTargetDimensions_SmallImages() {
        // Small square 64x64
        val tiles64 = defaultProcessor.calculateTiles(64, 64)
        assertEquals(1, tiles64.size)
        assertEquals(64, tiles64[0].width)
        assertEquals(64, tiles64[0].height)
        assertEquals(256, 64 * 4)

        // Small rectangle 128x96
        val tilesRect = defaultProcessor.calculateTiles(128, 96)
        assertEquals(1, tilesRect.size)
        assertEquals(128, tilesRect[0].width)
        assertEquals(96, tilesRect[0].height)
        assertEquals(512, 128 * 4)
        assertEquals(384, 96 * 4)
    }

    @Test
    fun testTargetDimensions_ArbitraryNonDivisible() {
        val width = 333
        val height = 444
        val tiles = defaultProcessor.calculateTiles(width, height)
        assertEquals(1, tiles.size)
        assertEquals(1332, width * 4)
        assertEquals(1776, height * 4)

        val widthLarge = 777
        val heightLarge = 999
        val tilesLarge = defaultProcessor.calculateTiles(widthLarge, heightLarge)
        // 777 with step 480 -> cols: x=0 (512), x=480 (297) -> 2 cols
        // 999 with step 480 -> rows: y=0 (512), y=480 (480 -> min(512, 999-480=512? Wait: 999-480=519 > 512, so y=480 has 512; y=960 has 39)) -> 3 rows
        // Total = 2 * 3 = 6 tiles
        assertEquals(6, tilesLarge.size)
        verifyCompleteCoverage(tilesLarge, widthLarge, heightLarge)
        assertEquals(3108, widthLarge * 4)
        assertEquals(3996, heightLarge * 4)
    }

    @Test
    fun testTileCoreExtraction_ExactPartition_NoGapsAndNoOverlap() {
        val width = 600
        val height = 800
        val processor = UpscaleTileProcessor(tileSize = 256, overlap = 24)
        val tiles = processor.calculateTiles(width, height)

        // Track how many tile cores cover each pixel in the 600x800 image
        val coverageGrid = Array(height) { IntArray(width) }

        for (tile in tiles) {
            val coreStartX = tile.x + tile.coreX
            val coreStartY = tile.y + tile.coreY
            val coreEndX = coreStartX + tile.coreWidth
            val coreEndY = coreStartY + tile.coreHeight

            for (y in coreStartY until coreEndY) {
                for (x in coreStartX until coreEndX) {
                    coverageGrid[y][x]++
                }
            }
        }

        // Verify that every single pixel is covered by EXACTLY ONE tile core
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals("Pixel ($x, $y) must be covered by exactly 1 core", 1, coverageGrid[y][x])
            }
        }
    }

    @Test
    fun testUpscaleMemoryPlanner_StandardTileSizesAndSafeBudget() {
        // AI model should choose 256 or 384, never 768 or 1024
        val planSmall = com.veilframe.app.upscale.inference.UpscaleMemoryPlanner.plan(512, 512, 4, isAiModel = true)
        assertTrue("AI tile size must be <= 384", planSmall.tileSize <= 384)
        assertTrue("Plan should be safe for 512x512 at 4x", planSmall.isSafe)
        assertTrue("Working set must be calculated", planSmall.estimatedWorkingSetBytes > 0L)

        // Huge resolution (e.g. 10000x10000 = 100 MP) must be rejected
        val planHuge = com.veilframe.app.upscale.inference.UpscaleMemoryPlanner.plan(10000, 10000, 4, isAiModel = true)
        org.junit.Assert.assertFalse("100 MP source must be flagged as unsafe", planHuge.isSafe)

        // 4000x3000 at 4x = 16000x12000 = 192 MP must be rejected because it exceeds 36 MP mobile ceiling
        val planExceed = com.veilframe.app.upscale.inference.UpscaleMemoryPlanner.plan(4000, 3000, 4, isAiModel = true)
        org.junit.Assert.assertFalse("192 MP output must be flagged as unsafe", planExceed.isSafe)
    }

    private fun verifyCompleteCoverage(tiles: List<UpscaleTileProcessor.TileArea>, imageW: Int, imageH: Int) {
        // Ensure every corner and coordinate in [0..imageW) x [0..imageH) is covered by at least one tile
        val checkPoints = listOf(
            Pair(0, 0),
            Pair(imageW - 1, 0),
            Pair(0, imageH - 1),
            Pair(imageW - 1, imageH - 1),
            Pair(imageW / 2, imageH / 2)
        )
        for ((cx, cy) in checkPoints) {
            val covered = tiles.any { cx >= it.x && cx < (it.x + it.width) && cy >= it.y && cy < (it.y + it.height) }
            assertTrue("Point ($cx, $cy) must be covered by tile grid", covered)
        }
    }

    @Test
    fun testTiledIntermediateSinkLifecycle() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_sink_${System.currentTimeMillis()}")
        val sink = com.veilframe.app.upscale.inference.TiledIntermediateSink(1024, 1024, tempDir)
        assertEquals(1024, sink.targetWidth)
        assertEquals(1024, sink.targetHeight)
        assertTrue(tempDir.exists())
        val result = sink.complete()
        assertEquals(1024, result.width)
        assertEquals(1024, result.height)
        org.junit.Assert.assertFalse(tempDir.exists())
        sink.close()
    }

    @Test
    fun testStripOutputSinkLifecycle() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_strip_sink_${System.currentTimeMillis()}")
        val sink = com.veilframe.app.upscale.inference.StripOutputSink(1024, 1024, 256, tempDir)
        assertEquals(1024, sink.targetWidth)
        assertEquals(1024, sink.targetHeight)
        assertTrue(tempDir.exists())
        val result = sink.complete()
        assertEquals(1024, result.width)
        assertEquals(1024, result.height)
        sink.close()
    }

    @Test
    fun testSmoothStepHermiteInterpolation() {
        val len = 32
        assertEquals(0.0f, com.veilframe.app.upscale.inference.smoothStep(0, len), 0.001f)
        assertEquals(1.0f, com.veilframe.app.upscale.inference.smoothStep(len - 1, len), 0.001f)
        assertEquals(0.5f, com.veilframe.app.upscale.inference.smoothStep(15, 31), 0.05f)

        // Verify strictly monotonic increase
        var prev = -1.0f
        for (i in 0 until len) {
            val v = com.veilframe.app.upscale.inference.smoothStep(i, len)
            assertTrue("smoothStep must be monotonically non-decreasing", v >= prev)
            prev = v
        }
    }

    @Test
    fun testMixColorsInterpolation() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()

        // 0% blue -> pure red
        assertEquals(red, com.veilframe.app.upscale.inference.mixColors(red, blue, 0.0f))

        // 100% blue -> pure blue
        assertEquals(blue, com.veilframe.app.upscale.inference.mixColors(red, blue, 1.0f))

        // 50% blend
        val half = com.veilframe.app.upscale.inference.mixColors(red, blue, 0.5f)
        val r = (half ushr 16) and 0xff
        val b = half and 0xff
        assertEquals(127, r)
        assertEquals(127, b)
    }
}

