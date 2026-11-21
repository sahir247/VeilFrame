package com.veilframe.app.media.refine

import org.junit.Assert.assertEquals
import org.junit.Test

class BrushBandIndexTest {
    @Test fun sweepMatchesBruteForceInJournalOrderIncludingRewindAndCrop() {
        val random = java.util.Random(456)
        val strokes = List(4096) { index ->
            CutoutStroke(1024, 512, 100f, random.nextFloat() * 512,
                120f, random.nextFloat() * 512, 1f + random.nextFloat() * 40, index % 2 == 0)
        }
        val height = 2048
        val index = BrushBandIndex(strokes, 4096, height)
        val ranges = (0 until height step 63).map { it to minOf(63, height - it) } +
            listOf(131 to 19, 250 to 70, 410 to 20, 411 to 2, 0 to 1)
        for ((y, rows) in ranges) {
            val expected = strokes.filter {
                val scale = height.toFloat() / it.height
                (maxOf(it.y1, it.y2) + it.radius) * scale >= y - 1f &&
                    (minOf(it.y1, it.y2) - it.radius) * scale <= y + rows + 1f
            }
            assertEquals(expected, index.intersecting(y, rows).map { it.stroke })
        }
    }
}
