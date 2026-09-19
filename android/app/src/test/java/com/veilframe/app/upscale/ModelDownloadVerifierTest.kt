package com.veilframe.app.upscale

import com.veilframe.app.upscale.download.ModelDownloadVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelDownloadVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRejectsEmptyOrMalformedExpectedHash() {
        val dummyFile = tempFolder.newFile("dummy.bin").apply {
            writeBytes("test payload data".toByteArray())
        }

        // Empty hash must be rejected (not bypass verification)
        assertFalse(ModelDownloadVerifier.verify(dummyFile, ""))
        assertFalse(ModelDownloadVerifier.verify(dummyFile, "   "))
        assertFalse(ModelDownloadVerifier.verify(dummyFile, "short_hash"))
        assertFalse(ModelDownloadVerifier.verify(dummyFile, "1234567890abcdef"))
        assertFalse(ModelDownloadVerifier.verify(dummyFile, "g".repeat(64))) // Invalid hex
    }

    @Test
    fun testVerifiesValidSha256Checksum() {
        val payload = "VeilFrame Secure Model Payload Content"
        val dummyFile = tempFolder.newFile("model.ort").apply {
            writeBytes(payload.toByteArray(Charsets.UTF_8))
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val correctHash = digest.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        assertTrue(ModelDownloadVerifier.verify(dummyFile, correctHash))
        assertTrue(ModelDownloadVerifier.verify(dummyFile, correctHash.uppercase()))

        // Tampered payload
        val wrongHash = "a".repeat(64)
        assertFalse(ModelDownloadVerifier.verify(dummyFile, wrongHash))
    }
}
