package com.veilframe.app.media

import android.graphics.RectF
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImagePiiRegionScannerTest {
    @Test fun everySourcePixelCoveredOnNonSquarePhoto() {
        val tiles = ImagePiiRegionScanner.planTiles(4032, 3024)
        assertTrue(tiles.size in 2..ImagePiiRegionScanner.MAX_TILES)
        val xs = listOf(0, 1, 159, 1279, 1280, 2016, 4031)
        val ys = listOf(0, 1279, 1280, 3023)
        for (x in xs) for (y in ys) {
            assertTrue("Pixel $x,$y not covered", tiles.any {
                x >= it.left && x < it.right && y >= it.top && y < it.bottom
            })
        }
        assertTrue(tiles.all { it.right <= 4032 && it.bottom <= 3024 })
    }

    @Test fun overlapPreservesSmallFaceStraddlingTileBoundary() {
        val tiles = ImagePiiRegionScanner.planTiles(2400, 1280)
        assertEquals(2, tiles.size)
        assertTrue(tiles[0].right > tiles[1].left)
        val detected = PiiDetection(RectF(20f, 30f, 42f, 54f), PiiClass.FACE, 0.9f)
        val shifted = ImagePiiRegionScanner.toSource(detected, tiles[1], 2400, 1280)
        assertEquals(tiles[1].left + 20f, shifted.rect.left, 0.01f)
        assertEquals(tiles[1].left + 42f, shifted.rect.right, 0.01f)
    }

    @Test fun refusesUnboundedCoverageOrInvalidLocalBoxes() {
        assertThrows(IllegalArgumentException::class.java) {
            ImagePiiRegionScanner.planTiles(100_000, 100_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePiiRegionScanner.planTiles(0, 1280)
        }
        val tile = ImagePiiRegionScanner.Tile(0, 0, 20, 20)
        assertThrows(IllegalArgumentException::class.java) {
            ImagePiiRegionScanner.toSource(
                PiiDetection(RectF(3f, 0f, 3f, 9f), PiiClass.TEXT_PII, 1f), tile, 20, 20
            )
        }
    }
}
