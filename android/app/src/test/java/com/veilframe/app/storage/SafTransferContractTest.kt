package com.veilframe.app.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class SafTransferContractTest {
    @Test fun boundedTransferChecksCancellationAndDoesNotAcceptZeroProgress() {
        val bytes = ByteArray(100_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), SafStorageManager.copyChecked(bytes.inputStream(), output))
        assertArrayEquals(bytes, output.toByteArray())
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SafStorageManager.copyChecked(bytes.inputStream(), ByteArrayOutputStream()) {
                if (++checks == 2) throw kotlinx.coroutines.CancellationException("stop")
            }
        }
        val stalled = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertThrows(IOException::class.java) { SafStorageManager.copyChecked(stalled, ByteArrayOutputStream()) }
    }

    @Test fun unsafeProviderNamesNeverLeaveTheStagingRoot() {
        val root = java.nio.file.Files.createTempDirectory("vf-saf").toFile()
        try {
            for (name in listOf("../escape", "/absolute", ".", "..", "a/b", "a\\b", "bad\u0000name")) {
                assertThrows(IllegalArgumentException::class.java) { SafStorageManager.safeStagingChild(root, name) }
            }
            assertEquals(File(root, "a b.png"), SafStorageManager.safeStagingChild(root, "a b.png"))
        } finally { root.deleteRecursively() }
    }
}
