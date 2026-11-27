package com.veilframe.app.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class OfficeSourceSnapshotTest {
    @Test fun sameLengthMutationCannotPassPreviewIdentity() {
        val output = ByteArrayOutputStream()
        val first = OfficeSourceSnapshot.copy("ABCD".byteInputStream(),output)
        assertArrayEquals("ABCD".toByteArray(),output.toByteArray())
        assertEquals(first,OfficeSourceSnapshot.copy("ABCD".byteInputStream(),ByteArrayOutputStream()))
        val changed = OfficeSourceSnapshot.copy("WXYZ".byteInputStream(),ByteArrayOutputStream())
        assertEquals(first.bytes,changed.bytes)
        assertTrue(runCatching { first.requireSame(changed) }.exceptionOrNull() is IOException)
    }

    @Test fun sizeAndReadProgressFailClosed() {
        val stalled = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray,off: Int,len: Int) = 0
        }
        assertTrue(runCatching { OfficeSourceSnapshot.copy(stalled,ByteArrayOutputStream()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { OfficeSourceSnapshot.copy("ABCD".byteInputStream(),ByteArrayOutputStream(),maximum=3) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { OfficeSourceSnapshot.copy("".byteInputStream(),ByteArrayOutputStream()) }.exceptionOrNull() is IOException)
    }
}
