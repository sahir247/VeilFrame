package com.veilframe.app.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class PdfFileExportTest {

    @Test
    fun streamsPayloadAndDeletesTemporarySource() {
        val source = File.createTempFile("veilframe-export-", ".pdf")
        val payload = ByteArray(131_073) { (it % 251).toByte() }
        source.writeBytes(payload)
        val target = ByteArrayOutputStream()
        val copied = PdfFileExport.copyAndDelete(source) { target }
        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, target.toByteArray())
        assertFalse(source.exists())
    }

    @Test
    fun deletesTemporarySourceWhenDestinationCannotOpen() {
        val source = File.createTempFile("veilframe-export-", ".pdf")
        source.writeText("test")
        try {
            PdfFileExport.copyAndDelete(source) {
                throw IOException("SAF provider unavailable")
            }
            throw AssertionError("Expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("SAF"))
        }
        assertFalse(source.exists())
    }

    @Test
    fun deletesTemporarySourceWhenWriteFails() {
        val source = File.createTempFile("veilframe-export-", ".pdf")
        source.writeText("test")
        try {
            PdfFileExport.copyAndDelete(source) {
                object : OutputStream() {
                    override fun write(b: Int) {
                        throw IOException("Destination full")
                    }
                }
            }
            throw AssertionError("Expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Destination full"))
        }
        assertFalse(source.exists())
    }

    @Test
    fun refusesEmptyExportAndRemovesIt() {
        val source = File.createTempFile("veilframe-export-", ".pdf")
        try {
            PdfFileExport.copyAndDelete(source) { ByteArrayOutputStream() }
            throw AssertionError("Expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("empty"))
        }
        assertFalse(source.exists())
    }
}
