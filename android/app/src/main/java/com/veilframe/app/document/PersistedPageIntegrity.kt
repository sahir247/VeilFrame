package com.veilframe.app.document

import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Byte-integrity contract for committed scanner originals and derived renders. */
internal object PersistedPageIntegrity {
    private const val MAX_ENCODED_BYTES = 256L * 1024 * 1024

    fun digest(file: File): String {
        val expectedLength = file.length()
        val expectedModified = file.lastModified()
        require(file.isFile && expectedLength in 1..MAX_ENCODED_BYTES) { "Page file exceeds the recovery byte budget or is missing" }
        val sha = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0 || n.toLong() > MAX_ENCODED_BYTES - count) throw IOException("Invalid page file stream")
                count += n
                sha.update(buffer, 0, n)
            }
        }
        if (count != expectedLength || file.length() != expectedLength || file.lastModified() != expectedModified) throw IOException("Page file changed during verification")
        return sha.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }

    fun verify(file: File, expected: String): Boolean =
        expected.matches(Regex("[a-f0-9]{64}")) && try { digest(file) == expected } catch (_: Exception) { false }

    /** Legacy manifests have no digest. Require a bounded sampled decode before migration. */
    fun legacyDecodes(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_ENCODED_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outMimeType !in setOf("image/png", "image/jpeg")) return false
        var sample = 1
        while ((bounds.outWidth.toLong() + sample - 1) / sample > 1024 ||
            (bounds.outHeight.toLong() + sample - 1) / sample > 1024) {
            if (sample >= (1 shl 29)) return false
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        return try { bitmap.width > 0 && bitmap.height > 0 } finally { bitmap.recycle() }
    }
}
