package com.veilframe.app.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class FolderScannerReadContractTest {
    @Test fun actualBytesBoundHashAndTextReads() {
        val payload = ByteArray(100_000) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val content = ByteArrayOutputStream()
        assertEquals(100_000L, FolderScannerEngine.readAuditBytes(payload.inputStream(), 100_000, 100_000, 100_000, digest, content))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), digest.digest())
        assertArrayEquals(payload, content.toByteArray())
        assertNull(FolderScannerEngine.readAuditBytes(payload.inputStream(), 99_999, 99_999, 0, null, null))
    }

    @Test fun findingsAfterOldLineLimitAreMaskedAndTruncationIsExplicit() {
        val text = "x\n".repeat(5001) + "password=1234567890abcdef\npassword=abcdefghijklmnop"
        val (matches, limited) = FolderScannerEngine.scanMaskedFindings(text, 1)
        assertEquals(listOf("[REDACTED]"), matches)
        assertTrue(limited)
    }
}
