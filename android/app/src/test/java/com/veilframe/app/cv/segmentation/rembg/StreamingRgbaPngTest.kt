package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class StreamingRgbaPngTest {
    @Test fun roundTripAcrossMultipleIdatChunksPreservesStraightAlphaAndScrubsHiddenRgb() {
        val width = 400
        val height = 200
        val random = java.util.Random(47)
        val expected = IntArray(width * height) { random.nextInt().let { if (it ushr 24 == 0) 0 else it } }
        expected[0] = 0
        expected[1] = 0x80FF0000.toInt()
        val output = ByteArrayOutputStream()
        StreamingRgbaPng.write(output, width, height) { y, row ->
            expected.copyInto(row, 0, y * width, (y + 1) * width)
            if (y == 0) row[0] = 0x00ABCDEF
        }
        val image = ImageIO.read(output.toByteArray().inputStream())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        assertArrayEquals(expected, image.getRGB(0, 0, width, height, null, 0, width))
    }

    @Test fun originalAlphaIsMultipliedOnlyOnceAndMaskUsesHalfPixelInterpolation() {
        val matte = RembgAlphaMatte.fromAlpha(2, 1, byteArrayOf(0, 255.toByte()))
        val row = intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0x80FF0000.toInt())
        matte.sampler(4, 1).apply(row, 0)
        assertArrayEquals(intArrayOf(0, 64, 191, 128), row.map { it ushr 24 }.toIntArray())
        assertEquals(0, row[0])
    }

    @Test fun orientationMappingCoversAllEightExifTransforms() {
        val expected = listOf("abcdef", "cbafed", "fedcba", "defabc", "adbecf", "daebfc", "fcebda", "cfbead")
        val source = "abcdef"
        for (orientation in 1..8) {
            val geometry = ExifRasterGeometry(3, 2, orientation)
            val actual = buildString {
                for (y in 0 until geometry.height) for (x in 0 until geometry.width) {
                    append(source[geometry.rawY(x, y) * 3 + geometry.rawX(x, y)])
                }
            }
            assertEquals("orientation $orientation", expected[orientation - 1], actual)
        }
    }
}
