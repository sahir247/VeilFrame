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
}
