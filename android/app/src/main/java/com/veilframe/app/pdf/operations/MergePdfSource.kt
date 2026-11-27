package com.veilframe.app.pdf.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.FilterInputStream
import java.io.IOException
import java.security.MessageDigest

/** Hash the exact stream copied by PDFBox into its disk-backed random-access buffer. */
internal class LoadedMergePdf(val document: PDDocument, val sha256: String) : java.io.Closeable {
    override fun close() = document.close()
}

internal suspend fun loadMergePdf(context: Context, uri: Uri): LoadedMergePdf {
    val jobContext = currentCoroutineContext()
    jobContext.ensureActive()
    val digest = MessageDigest.getInstance("SHA-256")
    var reachedEof = false
    var document: PDDocument? = null
    // PDFBox 2.0.27.0 catches IOException to retire its scratch buffer.
    // Translate cancellation during a read, then restore coroutine cancellation
    // after the loader has performed its own cleanup.
    fun checkRead() {
        if (!jobContext.isActive) throw IOException("PDF source inspection cancelled")
    }
    try {
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open selected PDF")
        source.use { raw ->
            val hashed = object : FilterInputStream(raw) {
                override fun read(): Int {
                    checkRead()
                    val value = raw.read()
                    if (value < 0) reachedEof = true else digest.update(value.toByte())
                    return value
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    checkRead()
                    if (length == 0) return 0
                    val count = raw.read(bytes, offset, length)
                    when {
                        count < 0 -> reachedEof = true
                        count == 0 -> throw IOException("PDF source stopped making read progress")
                        else -> digest.update(bytes, offset, count)
                    }
                    return count
                }

                override fun skip(count: Long): Long = throw IOException("Unexpected PDF source skip")
                override fun markSupported(): Boolean = false
                override fun reset(): Unit = throw IOException("Unexpected PDF source reset")
            }
            document = PDDocument.load(hashed, MemoryUsageSetting.setupTempFileOnly())
        }
        jobContext.ensureActive()
        check(reachedEof) { "PDF loader did not consume the complete source" }
        val hash = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        return LoadedMergePdf(requireNotNull(document), hash).also { document = null }
    } catch (failure: Exception) {
        jobContext.ensureActive()
        throw failure
    } finally {
        document?.close()
    }
}
