package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.DeviceInferenceException
import com.veilframe.app.upscale.inference.ImageValidationException
import com.veilframe.app.upscale.inference.ModelNotInstalledException
import com.veilframe.app.upscale.inference.NcnnModelLoadException
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.ResourceLimitExceededException
import com.veilframe.app.upscale.inference.StreamingPngWriter
import com.veilframe.app.upscale.inference.UpscaleCancellationException
import com.veilframe.app.upscale.inference.UpscaleFailure
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NcnnUpscaleHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidatePngFile_ValidPngSucceeds() {
        val testFile = tempFolder.newFile("valid.png")
        val width = 16
        val height = 8
        val writer = StreamingPngWriter(testFile, width, height, colorType = StreamingPngWriter.ColorType.RGB)
        val rowBuffer = ByteBuffer.allocateDirect(width * 3).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            rowBuffer.clear()
            for (x in 0 until width * 3) {
                rowBuffer.put((x and 0xFF).toByte())
            }
            rowBuffer.flip()
            writer.writeRow(rowBuffer)
        }
        writer.close()

        // Must succeed without throwing
        NcnnUpscaleEngine.validatePngFile(testFile, width, height)
    }

    @Test
    fun testValidatePngFile_TruncatedOrEmptyFileThrowsImageValidationException() {
        val emptyFile = tempFolder.newFile("empty.png")
        try {
            NcnnUpscaleEngine.validatePngFile(emptyFile, 16, 8)
            fail("Expected ImageValidationException on empty file")
        } catch (e: ImageValidationException) {
            assertTrue(e.message?.contains("truncated") == true || e.message?.contains("missing") == true)
        }
    }

    @Test
    fun testValidatePngFile_CorruptSignatureThrowsImageValidationException() {
        val corruptFile = tempFolder.newFile("corrupt_sig.png")
        val bytes = ByteArray(64)
        bytes[0] = 0x50 // 'P'
        bytes[1] = 0x4B // 'K' (ZIP signature instead of PNG)
        corruptFile.writeBytes(bytes)

        try {
            NcnnUpscaleEngine.validatePngFile(corruptFile, 16, 8)
            fail("Expected ImageValidationException on invalid PNG signature")
        } catch (e: ImageValidationException) {
            assertTrue(e.message?.contains("Invalid PNG signature") == true)
        }
    }

    @Test
    fun testValidatePngFile_DimensionMismatchThrowsImageValidationException() {
        val testFile = tempFolder.newFile("valid_dims.png")
        val width = 32
        val height = 16
        val writer = StreamingPngWriter(testFile, width, height, colorType = StreamingPngWriter.ColorType.RGB)
        val rowBuffer = ByteBuffer.allocateDirect(width * 3).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            rowBuffer.clear()
            for (x in 0 until width * 3) {
                rowBuffer.put(0)
            }
            rowBuffer.flip()
            writer.writeRow(rowBuffer)
        }
        writer.close()

        try {
            // Validate with wrong expected height (e.g. 32 instead of 16)
            NcnnUpscaleEngine.validatePngFile(testFile, expectedW = 32, expectedH = 32)
            fail("Expected ImageValidationException on dimension mismatch")
        } catch (e: ImageValidationException) {
            assertTrue(e.message?.contains("dimension mismatch") == true)
        }
    }

    @Test
    fun testValidatePngFile_CorruptChunkCrcThrowsImageValidationException() {
        val testFile = tempFolder.newFile("valid_to_corrupt.png")
        val width = 16
        val height = 8
        val writer = StreamingPngWriter(testFile, width, height, colorType = StreamingPngWriter.ColorType.RGB)
        val rowBuffer = ByteBuffer.allocateDirect(width * 3).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            rowBuffer.clear()
            for (x in 0 until width * 3) {
                rowBuffer.put(0)
            }
            rowBuffer.flip()
            writer.writeRow(rowBuffer)
        }
        writer.close()

        // Read bytes and flip a byte inside the IHDR CRC to break CRC check deterministically
        val bytes = testFile.readBytes()
        bytes[31] = (bytes[31].toInt() xor 0xFF).toByte()
        testFile.writeBytes(bytes)

        try {
            NcnnUpscaleEngine.validatePngFile(testFile, width, height)
            fail("Expected ImageValidationException on corrupt chunk CRC")
        } catch (e: ImageValidationException) {
            assertTrue(e.message?.contains("CRC mismatch") == true)
        }
    }

    @Test
    fun testValidatePngFile_TrailingGarbageThrowsImageValidationException() {
        val testFile = tempFolder.newFile("trailing_garbage.png")
        val width = 16
        val height = 8
        val writer = StreamingPngWriter(testFile, width, height, colorType = StreamingPngWriter.ColorType.RGB)
        val rowBuffer = ByteBuffer.allocateDirect(width * 3).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            rowBuffer.clear()
            for (x in 0 until width * 3) {
                rowBuffer.put(0)
            }
            rowBuffer.flip()
            writer.writeRow(rowBuffer)
        }
        writer.close()

        // Append trailing garbage after IEND
        val bytes = testFile.readBytes() + byteArrayOf(0x01, 0x02, 0x03)
        testFile.writeBytes(bytes)

        try {
            NcnnUpscaleEngine.validatePngFile(testFile, width, height)
            fail("Expected ImageValidationException on trailing garbage after IEND")
        } catch (e: ImageValidationException) {
            assertTrue(e.message?.contains("trailing bytes") == true)
        }
    }

    @Test
    fun testPublishFileAtomically_SuccessfulMove() {
        val tempFile = tempFolder.newFile("test_output.part")
        tempFile.writeText("VALID_PNG_CONTENT")

        val finalFile = File(tempFolder.root, "test_output.png")
        assertFalse(finalFile.exists())

        NcnnUpscaleEngine.publishFileAtomically(tempFile, finalFile)

        assertFalse(tempFile.exists())
        assertTrue(finalFile.exists())
        assertEquals("VALID_PNG_CONTENT", finalFile.readText())
    }

    @Test
    fun testPublishFileAtomically_OverwritesExistingFileSafely() {
        val existingFinal = tempFolder.newFile("existing_output.png")
        existingFinal.writeText("OLD_CONTENT")

        val tempFile = tempFolder.newFile("new_output.part")
        tempFile.writeText("NEW_CONTENT")

        NcnnUpscaleEngine.publishFileAtomically(tempFile, existingFinal)

        assertFalse(tempFile.exists())
        assertTrue(existingFinal.exists())
        assertEquals("NEW_CONTENT", existingFinal.readText())
    }

    @Test
    fun testExceptionHierarchy_CompliesWithContracts() {
        val resourceEx = ResourceLimitExceededException("Limit exceeded")
        assertTrue(resourceEx is IllegalArgumentException)
        assertTrue(resourceEx is UpscaleFailure)

        val modelNotInstalled = ModelNotInstalledException("model1", "Model One")
        assertTrue(modelNotInstalled is IllegalStateException)
        assertTrue(modelNotInstalled is UpscaleFailure)

        val loadEx = NcnnModelLoadException(-1, "Corrupt model")
        assertTrue(loadEx is IllegalStateException)
        assertTrue(loadEx is UpscaleFailure)

        val cancelEx = UpscaleCancellationException("User cancelled")
        assertTrue(cancelEx is kotlinx.coroutines.CancellationException)
        assertTrue(cancelEx is UpscaleFailure)

        val deviceEx = DeviceInferenceException(-2, "Vulkan error", "Vulkan")
        assertEquals(-2, deviceEx.code)
        assertEquals("Vulkan", deviceEx.backend)
        assertTrue(deviceEx is UpscaleFailure)
    }
}
