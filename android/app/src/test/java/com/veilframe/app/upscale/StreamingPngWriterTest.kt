package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.StreamingPngWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class StreamingPngWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidPngStreamingCompleteOutput() {
        val file = tempFolder.newFile("test_complete.png")
        val width = 4
        val height = 3

        StreamingPngWriter(file, width, height).use { writer ->
            assertEquals(StreamingPngWriter.State.OPEN, writer.state)
            val rowPixels = intArrayOf(
                0xFF0000FF.toInt(), // Blue
                0xFF00FF00.toInt(), // Green
                0xFFFF0000.toInt(), // Red
                0xFFFFFFFF.toInt(), // White
            )
            for (y in 0 until height) {
                writer.writeRow(rowPixels)
            }
            assertEquals(1f, writer.progress(), 0.001f)
        }

        assertTrue("Complete PNG file must exist on disk", file.exists())
        assertTrue("PNG file size must be non-zero", file.length() > 0)

        // Validate PNG signature: 89 50 4E 47 0D 0A 1A 0A
        val bytes = file.readBytes()
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals(0x50.toByte(), bytes[1])
        assertEquals(0x4E.toByte(), bytes[2])
        assertEquals(0x47.toByte(), bytes[3])
        assertEquals(0x0D.toByte(), bytes[4])
        assertEquals(0x0A.toByte(), bytes[5])
        assertEquals(0x1A.toByte(), bytes[6])
        assertEquals(0x0A.toByte(), bytes[7])

        // Independent decoder validation
        val decoded = decodePng(file)
        assertEquals("Decoded image width must match", width, decoded.width)
        assertEquals("Decoded image height must match", height, decoded.height)
        assertEquals(6, decoded.colorType)

        for (y in 0 until height) {
            assertEquals("Pixel (0, $y) Blue channel match", 0xFF0000FF.toInt(), decoded.getPixel(0, y))
            assertEquals("Pixel (1, $y) Green channel match", 0xFF00FF00.toInt(), decoded.getPixel(1, y))
            assertEquals("Pixel (2, $y) Red channel match", 0xFFFF0000.toInt(), decoded.getPixel(2, y))
            assertEquals("Pixel (3, $y) White channel match", 0xFFFFFFFF.toInt(), decoded.getPixel(3, y))
        }
    }

    @Test
    fun testValidPngStreamingRgbOutput() {
        val file = tempFolder.newFile("test_rgb_complete.png")
        val width = 4
        val height = 2

        StreamingPngWriter(file, width, height, colorType = StreamingPngWriter.ColorType.RGB).use { writer ->
            assertEquals(StreamingPngWriter.ColorType.RGB, writer.colorType)
            val rowPixels = intArrayOf(
                0xFF0000FF.toInt(), // Blue
                0xFF00FF00.toInt(), // Green
                0xFFFF0000.toInt(), // Red
                0xFFFFFFFF.toInt(), // White
            )
            for (y in 0 until height) {
                writer.writeRow(rowPixels)
            }
            assertEquals(1f, writer.progress(), 0.001f)
        }

        assertTrue(file.exists())
        val decoded = decodePng(file)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals("Must be Color Type 2 (RGB)", 2, decoded.colorType)

        for (y in 0 until height) {
            assertEquals("Blue match", 0xFF0000FF.toInt(), decoded.getPixel(0, y))
            assertEquals("Green match", 0xFF00FF00.toInt(), decoded.getPixel(1, y))
            assertEquals("Red match", 0xFFFF0000.toInt(), decoded.getPixel(2, y))
            assertEquals("White match", 0xFFFFFFFF.toInt(), decoded.getPixel(3, y))
        }
    }

    @Test
    fun testValidPngStreamingDirectByteBufferRgb() {
        val file = tempFolder.newFile("test_direct_bb_rgb.png")
        val width = 3
        val height = 2

        val directBuffer = ByteBuffer.allocateDirect(width * 3)
        StreamingPngWriter(file, width, height, colorType = StreamingPngWriter.ColorType.RGB).use { writer ->
            for (y in 0 until height) {
                directBuffer.clear()
                // Row y: 3 pixels RGB: Red, Green, Blue
                directBuffer.put(byteArrayOf(
                    0xFF.toByte(), 0x00, 0x00,       // Red
                    0x00, 0xFF.toByte(), 0x00,       // Green
                    0x00, 0x00, 0xFF.toByte()        // Blue
                ))
                directBuffer.flip()
                writer.writeRow(directBuffer)
                assertEquals(0, directBuffer.remaining())
            }
        }

        val decoded = decodePng(file)
        assertEquals(2, decoded.colorType)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (y in 0 until height) {
            assertEquals(0xFFFF0000.toInt(), decoded.getPixel(0, y))
            assertEquals(0xFF00FF00.toInt(), decoded.getPixel(1, y))
            assertEquals(0xFF0000FF.toInt(), decoded.getPixel(2, y))
        }
    }

    @Test
    fun testValidPngStreamingDirectByteBufferRgba() {
        val file = tempFolder.newFile("test_direct_bb_rgba.png")
        val width = 2
        val height = 2

        val directBuffer = ByteBuffer.allocateDirect(width * 4)
        StreamingPngWriter(file, width, height, colorType = StreamingPngWriter.ColorType.RGBA).use { writer ->
            for (y in 0 until height) {
                directBuffer.clear()
                // Pixel 0: Semi-transparent Red (R=255, G=0, B=0, A=128)
                // Pixel 1: Opaque Yellow (R=255, G=255, B=0, A=255)
                directBuffer.put(byteArrayOf(
                    0xFF.toByte(), 0x00, 0x00, 0x80.toByte(),
                    0xFF.toByte(), 0xFF.toByte(), 0x00, 0xFF.toByte()
                ))
                directBuffer.flip()
                writer.writeRow(directBuffer)
            }
        }

        val decoded = decodePng(file)
        assertEquals(6, decoded.colorType)
        for (y in 0 until height) {
            assertEquals(0x80FF0000.toInt(), decoded.getPixel(0, y))
            assertEquals(0xFFFFFF00.toInt(), decoded.getPixel(1, y))
        }
    }

    @Test
    fun testValidPngStreamingByteArray() {
        val fileRgb = tempFolder.newFile("test_byte_array_rgb.png")
        val width = 2
        val height = 2
        val rgbBytes = byteArrayOf(
            0xFF.toByte(), 0x55, 0x11,
            0x22, 0x88.toByte(), 0xEE.toByte()
        )

        StreamingPngWriter(fileRgb, width, height, colorType = StreamingPngWriter.ColorType.RGB).use { writer ->
            writer.writeRow(rgbBytes)
            writer.writeRow(rgbBytes)
        }

        val decoded = decodePng(fileRgb)
        assertEquals(2, decoded.colorType)
        assertEquals(0xFFFF5511.toInt(), decoded.getPixel(0, 0))
        assertEquals(0xFF2288EE.toInt(), decoded.getPixel(1, 0))
    }

    @Test
    fun testByteBufferUnderflowCheck() {
        val file = tempFolder.newFile("test_bb_underflow.png")
        val writer = StreamingPngWriter(file, 4, 2, colorType = StreamingPngWriter.ColorType.RGB)
        val tinyBuffer = ByteBuffer.allocate(5) // needs 4 * 3 = 12 bytes
        try {
            writer.writeRow(tinyBuffer)
            fail("Passing buffer with insufficient bytes must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("buffer remaining") == true)
        } finally {
            writer.abort()
        }
    }

    @Test
    fun testCloseWithZeroRowsThrowsAndAborts() {
        val file = tempFolder.newFile("test_zero_rows.png")
        val width = 4
        val height = 4

        val writer = StreamingPngWriter(file, width, height)
        assertEquals(0f, writer.progress(), 0.001f)

        try {
            writer.close()
            fail("Closing without writing any rows must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Incomplete PNG: wrote 0 of 4 rows") == true)
        }

        assertEquals(StreamingPngWriter.State.ABORTED, writer.state)
        assertFalse("Incomplete PNG file must be deleted upon abort", file.exists())
    }

    @Test
    fun testCloseAfterSomeRowsThrowsAndAborts() {
        val file = tempFolder.newFile("test_some_rows.png")
        val width = 4
        val height = 4

        val writer = StreamingPngWriter(file, width, height)
        val rowPixels = IntArray(width) { 0xFF000000.toInt() }

        // Write 2 of 4 rows
        writer.writeRow(rowPixels)
        writer.writeRow(rowPixels)
        assertEquals(0.5f, writer.progress(), 0.001f)

        try {
            writer.close()
            fail("Closing incomplete StreamingPngWriter must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Incomplete PNG: wrote 2 of 4 rows") == true)
        }

        assertEquals(StreamingPngWriter.State.ABORTED, writer.state)
        assertFalse("Incomplete PNG file must be deleted upon abort", file.exists())
    }

    @Test
    fun testWriteBeyondImageHeightThrows() {
        val file = tempFolder.newFile("test_overflow_rows.png")
        val width = 2
        val height = 1

        val writer = StreamingPngWriter(file, width, height)
        writer.writeRow(IntArray(width))

        try {
            writer.writeRow(IntArray(width))
            fail("Writing beyond image height must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("all 1 rows already written") == true)
        }

        // Clean close since exactly height rows were written
        writer.close()
        assertEquals(StreamingPngWriter.State.COMPLETED, writer.state)
        assertTrue(file.exists())
    }

    @Test
    fun testRowBufferUnderflowCheck() {
        val file = tempFolder.newFile("test_buffer_check.png")
        val width = 4
        val height = 2

        val writer = StreamingPngWriter(file, width, height)
        try {
            writer.writeRow(IntArray(2)) // too small
            fail("Passing smaller buffer must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("row buffer too small") == true)
        } finally {
            writer.abort()
        }
    }

    @Test
    fun testExplicitAbortCleansUpPartialFile() {
        val file = tempFolder.newFile("test_abort.png")
        val width = 2
        val height = 2

        val writer = StreamingPngWriter(file, width, height)
        writer.writeRow(IntArray(width))
        assertTrue("File exists before abort", file.exists())

        writer.abort()
        assertEquals(StreamingPngWriter.State.ABORTED, writer.state)
        assertFalse("File must be deleted after abort()", file.exists())

        // Subsequent writeRow must fail
        try {
            writer.writeRow(IntArray(width))
            fail("writeRow after abort must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("writer is in state ABORTED") == true)
        }

        // Idempotent abort
        writer.abort()
        assertEquals(StreamingPngWriter.State.ABORTED, writer.state)
    }

    @Test
    fun testInvalidDimensionsRejectedImmediately() {
        val dummy = File(tempFolder.root, "invalid.png")

        try {
            StreamingPngWriter(dummy, 0, 10)
            fail("Width 0 must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid PNG dimensions") == true)
        }

        try {
            StreamingPngWriter(dummy, -5, 10)
            fail("Negative width must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid PNG dimensions") == true)
        }

        try {
            StreamingPngWriter(dummy, StreamingPngWriter.MAX_DIMENSION + 1, 10)
            fail("Exceeding MAX_DIMENSION must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid PNG dimensions") == true)
        }

        assertFalse("No file should be created on invalid dimensions", dummy.exists())
    }

    @Test
    fun testMultiChunkStreamingOutputDecodesSuccessfully() {
        val file = tempFolder.newFile("test_multichunk.png")
        // 256x256 image with pseudo-random high-entropy noise to exceed 32KB chunk buffer multiple times
        val width = 256
        val height = 256
        val rng = java.util.Random(42)
        val pixels = IntArray(width * height) { rng.nextInt() }

        StreamingPngWriter(file, width, height).use { writer ->
            val row = IntArray(width)
            for (y in 0 until height) {
                System.arraycopy(pixels, y * width, row, 0, width)
                writer.writeRow(row)
            }
            assertEquals(StreamingPngWriter.State.OPEN, writer.state)
        }

        assertTrue(file.exists())
        assertTrue("Multi-chunk file should exceed 32KB to prove chunk boundaries", file.length() > 32 * 1024)

        // Decode independently with reference PNG decoder
        val decoded = decodePng(file)
        assertTrue("Must emit multiple IDAT chunks for streaming", decoded.idatChunkCount > 1)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val expected = pixels[y * width + x]
                val actual = decoded.getPixel(x, y)
                assertEquals("Pixel ($x, $y) mismatch", expected, actual)
            }
        }
    }

    @Test
    fun testPngChunkFramingAndCrcIntegrity() {
        val file = tempFolder.newFile("test_framing.png")
        val width = 16
        val height = 8
        StreamingPngWriter(file, width, height).use { writer ->
            val row = IntArray(width) { 0xFF123456.toInt() }
            for (y in 0 until height) {
                writer.writeRow(row)
            }
        }

        val bytes = file.readBytes()
        // Signature check
        val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0 until 8) {
            assertEquals(expectedSig[i], bytes[i])
        }

        var offset = 8
        val chunkTypes = mutableListOf<String>()
        val crcCalculator = java.util.zip.CRC32()

        while (offset < bytes.size) {
            assertTrue("Must have at least 12 bytes for chunk header/trailer", offset + 12 <= bytes.size)
            val len = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4

            val typeBytes = bytes.copyOfRange(offset, offset + 4)
            val type = String(typeBytes, Charsets.US_ASCII)
            chunkTypes.add(type)
            offset += 4

            val dataBytes = bytes.copyOfRange(offset, offset + len)
            offset += len

            val storedCrc = ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
            offset += 4

            crcCalculator.reset()
            crcCalculator.update(typeBytes)
            crcCalculator.update(dataBytes)
            assertEquals("CRC mismatch for chunk $type", crcCalculator.value, storedCrc)
        }

        assertEquals("All bytes in PNG file must belong to valid chunks", bytes.size, offset)
        assertEquals("First chunk must be IHDR", "IHDR", chunkTypes.first())
        assertEquals("Last chunk must be IEND", "IEND", chunkTypes.last())
        assertTrue("Must contain at least one IDAT chunk", chunkTypes.contains("IDAT"))
    }

    /**
     * Independent reference PNG decoder to validate that written files adhere strictly
     * to the PNG 1.2 specification and can be decoded by any standard PNG decoder.
     */
    data class DecodedPng(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val idatChunkCount: Int,
        val colorType: Int = 6
    ) {
        fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]
    }

    private fun decodePng(file: File): DecodedPng {
        val bytes = file.readBytes()
        require(bytes.size >= 8) { "File too small for PNG signature" }
        val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0 until 8) {
            assertEquals("Invalid PNG signature byte $i", expectedSig[i], bytes[i])
        }

        var offset = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        val idatStreams = mutableListOf<ByteArray>()
        val crc = java.util.zip.CRC32()
        var seenIhdr = false
        var seenIend = false

        while (offset < bytes.size) {
            assertTrue("Unexpected EOF reading chunk header", offset + 8 <= bytes.size)
            val len = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4

            val typeBytes = bytes.copyOfRange(offset, offset + 4)
            val type = String(typeBytes, Charsets.US_ASCII)
            offset += 4

            assertTrue("Chunk data out of bounds for chunk $type", offset + len + 4 <= bytes.size)
            val data = bytes.copyOfRange(offset, offset + len)
            offset += len

            val storedCrc = ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
            offset += 4

            crc.reset()
            crc.update(typeBytes)
            crc.update(data)
            assertEquals("CRC mismatch in chunk $type", storedCrc, crc.value)

            when (type) {
                "IHDR" -> {
                    check(!seenIhdr) { "Duplicate IHDR" }
                    seenIhdr = true
                    assertEquals("IHDR length must be 13", 13, len)
                    width = ((data[0].toInt() and 0xFF) shl 24) or
                        ((data[1].toInt() and 0xFF) shl 16) or
                        ((data[2].toInt() and 0xFF) shl 8) or
                        (data[3].toInt() and 0xFF)
                    height = ((data[4].toInt() and 0xFF) shl 24) or
                        ((data[5].toInt() and 0xFF) shl 16) or
                        ((data[6].toInt() and 0xFF) shl 8) or
                        (data[7].toInt() and 0xFF)
                    bitDepth = data[8].toInt() and 0xFF
                    colorType = data[9].toInt() and 0xFF
                }
                "IDAT" -> {
                    check(seenIhdr) { "IDAT before IHDR" }
                    check(!seenIend) { "IDAT after IEND" }
                    idatStreams.add(data)
                }
                "IEND" -> {
                    check(seenIhdr) { "IEND before IHDR" }
                    assertEquals("IEND length must be 0", 0, len)
                    seenIend = true
                }
            }
        }

        assertTrue("IHDR missing", seenIhdr)
        assertTrue("IEND missing", seenIend)
        assertTrue("IDAT missing", idatStreams.isNotEmpty())
        assertEquals("Offset must match file size (no trailing bytes)", bytes.size, offset)
        assertEquals("Bit depth must be 8", 8, bitDepth)
        assertTrue("Color type must be 2 (RGB) or 6 (RGBA)", colorType == 2 || colorType == 6)

        val bytesPerPixel = if (colorType == 6) 4 else 3

        // Concatenate all IDAT payloads
        val totalCompressed = idatStreams.sumOf { it.size }
        val compressed = ByteArray(totalCompressed)
        var pos = 0
        for (chunk in idatStreams) {
            System.arraycopy(chunk, 0, compressed, pos, chunk.size)
            pos += chunk.size
        }

        // Decompress with Inflater
        val scanlineBytes = 1 + width * bytesPerPixel
        val uncompressed = ByteArray(scanlineBytes * height)
        val inflater = java.util.zip.Inflater()
        inflater.setInput(compressed)
        val inflatedBytes = inflater.inflate(uncompressed)
        assertEquals("Inflated byte count must match scanline buffer size", uncompressed.size, inflatedBytes)
        assertTrue("Inflater must have reached end of zlib stream", inflater.finished())
        inflater.end()

        // Unfilter scanlines and build ARGB pixel array
        val pixels = IntArray(width * height)
        val prevRow = ByteArray(width * bytesPerPixel)
        val curRow = ByteArray(width * bytesPerPixel)

        for (y in 0 until height) {
            val rowOffset = y * scanlineBytes
            val filter = uncompressed[rowOffset].toInt() and 0xFF
            for (x in 0 until width * bytesPerPixel) {
                val rawByte = uncompressed[rowOffset + 1 + x].toInt() and 0xFF
                val left = if (x >= bytesPerPixel) curRow[x - bytesPerPixel].toInt() and 0xFF else 0
                val up = prevRow[x].toInt() and 0xFF
                val upLeft = if (x >= bytesPerPixel) prevRow[x - bytesPerPixel].toInt() and 0xFF else 0

                val uncur = when (filter) {
                    0 -> rawByte // None
                    1 -> (rawByte + left) and 0xFF // Sub
                    2 -> (rawByte + up) and 0xFF // Up
                    3 -> (rawByte + (left + up) / 2) and 0xFF // Average
                    4 -> { // Paeth
                        val p = left + up - upLeft
                        val pa = kotlin.math.abs(p - left)
                        val pb = kotlin.math.abs(p - up)
                        val pc = kotlin.math.abs(p - upLeft)
                        val pr = if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
                        (rawByte + pr) and 0xFF
                    }
                    else -> error("Unknown filter type: $filter")
                }
                curRow[x] = uncur.toByte()
            }

            if (colorType == 6) {
                for (x in 0 until width) {
                    val r = curRow[x * 4].toInt() and 0xFF
                    val g = curRow[x * 4 + 1].toInt() and 0xFF
                    val b = curRow[x * 4 + 2].toInt() and 0xFF
                    val a = curRow[x * 4 + 3].toInt() and 0xFF
                    pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            } else {
                for (x in 0 until width) {
                    val r = curRow[x * 3].toInt() and 0xFF
                    val g = curRow[x * 3 + 1].toInt() and 0xFF
                    val b = curRow[x * 3 + 2].toInt() and 0xFF
                    pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            System.arraycopy(curRow, 0, prevRow, 0, curRow.size)
        }

        return DecodedPng(width, height, pixels, idatStreams.size, colorType)
    }
}
