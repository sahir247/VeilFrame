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
        val encoded = output.toByteArray()
        StreamingPngVerifier.verify(encoded.inputStream(), width, height)
        val image = ImageIO.read(encoded.inputStream())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        assertArrayEquals(expected, image.getRGB(0, 0, width, height, null, 0, width))
    }

    @Test fun verifierRejectsTruncationCorruptCrcTrailingDataAndWrongGeometry() {
        val out = ByteArrayOutputStream()
        StreamingRgbaPng.write(out, 5, 3) { y, row -> row.fill(0xFF123456.toInt() + y) }
        val png = out.toByteArray()
        StreamingPngVerifier.verify(png.inputStream(), 5, 3)
        val corrupt = png.copyOf().apply { this[45] = (this[45].toInt() xor 1).toByte() }
        for (bytes in listOf(png.copyOf(png.size - 1), corrupt, png + byteArrayOf(0))) {
            assertThrows(java.io.IOException::class.java) { StreamingPngVerifier.verify(bytes.inputStream(), 5, 3) }
        }
        assertThrows(java.io.IOException::class.java) { StreamingPngVerifier.verify(png.inputStream(), 3, 5) }
    }

    @Test fun verifierRejectsMalformedZlibEvenWithValidChunkCrcs() {
        fun chunk(out: java.io.DataOutputStream, name: String, payload: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(type); update(payload) }
            out.writeInt(payload.size); out.write(type); out.write(payload); out.writeInt(crc.value.toInt())
        }
        fun compress(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
            java.util.zip.DeflaterOutputStream(bytes).use { it.write(raw) }
        }.toByteArray()
        fun png(compressed: ByteArray): ByteArray {
            val bytes = ByteArrayOutputStream()
            val output = java.io.DataOutputStream(bytes)
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream()
            java.io.DataOutputStream(header).use {
                it.writeInt(2); it.writeInt(2)
                it.write(byteArrayOf(8, 6, 0, 0, 0))
            }
            chunk(output, "IHDR", header.toByteArray())
            chunk(output, "sRGB", byteArrayOf(0))
            chunk(output, "IDAT", compressed)
            chunk(output, "IEND", byteArrayOf())
            return bytes.toByteArray()
        }
        val raw = ByteArray(2 * (2 * 4 + 1))
        val good = compress(raw)
        StreamingPngVerifier.verify(png(good).inputStream(), 2, 2)
        val invalidFilter = raw.copyOf().apply { this[9] = 5 }
        val variants = listOf(compress(raw.copyOf(raw.size - 1)), compress(raw + byteArrayOf(0)),
            compress(invalidFilter), good.copyOf(good.size - 2), good + byteArrayOf(0))
        for (variant in variants) {
            assertThrows(java.io.IOException::class.java) {
                StreamingPngVerifier.verify(png(variant).inputStream(), 2, 2)
            }
        }
    }

    @Test fun degenerateGeometryAndCancellation() {
        for ((w, h) in listOf(1 to 1, 1 to 100, 32768 to 1, 13 to 17)) {
            val out = ByteArrayOutputStream()
            StreamingRgbaPng.write(out, w, h) { y, row ->
                for (x in row.indices) row[x] = if ((x + y) % 2 == 0) 0x80123456.toInt() else 0xFFABCDEF.toInt()
            }
            val bytes = out.toByteArray()
            StreamingPngVerifier.verify(bytes.inputStream(), w, h)
            val image = ImageIO.read(bytes.inputStream())
            assertEquals(w, image.width)
            assertEquals(h, image.height)
            for (y in 0 until h) for (x in 0 until w) {
                assertEquals(if ((x + y) % 2 == 0) 0x80123456.toInt() else 0xFFABCDEF.toInt(), image.getRGB(x, y))
            }
        }
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            StreamingRgbaPng.write(ByteArrayOutputStream(), 5, 3, { if (++checks == 3) throw kotlinx.coroutines.CancellationException("stop") }) { _, row ->
                row.fill(-1)
            }
        }
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
