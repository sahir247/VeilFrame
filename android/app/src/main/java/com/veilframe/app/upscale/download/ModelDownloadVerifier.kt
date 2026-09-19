package com.veilframe.app.upscale.download

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Validates integrity of downloaded model files using cryptographic SHA-256 hashes.
 */
object ModelDownloadVerifier {

    fun calculateSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private val SHA256_REGEX = Regex("^[A-Fa-f0-9]{64}$")

    fun verify(file: File, expectedSha256: String): Boolean {
        val expected = expectedSha256.trim()
        if (!SHA256_REGEX.matches(expected)) return false
        if (!file.isFile || file.length() == 0L) return false

        val actual = calculateSha256(file)
        if (actual.isEmpty()) return false

        return MessageDigest.isEqual(
            actual.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8),
            expected.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8)
        )
    }
}
