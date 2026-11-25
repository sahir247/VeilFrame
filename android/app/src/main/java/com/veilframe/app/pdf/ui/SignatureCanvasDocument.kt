package com.veilframe.app.pdf.ui

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** One private, immutable-by-contract preview file and its renderer. */
internal class SignatureCanvasDocument private constructor(
    private val file: File, val renderer: PdfRenderer, val sha256: String
) : java.io.Closeable {
    override fun close() {
        try { renderer.close() } finally { file.delete() }
    }

    companion object {
        private const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_PAGES = 10_000

        suspend fun open(context: Context, uri: Uri): SignatureCanvasDocument {
            val job = currentCoroutineContext()
            val file = File.createTempFile("canvas_view_", ".pdf", context.cacheDir)
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var transferred = false
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open signature source")
                var size = 0L
                input.use { source ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("Signature source made no read progress")
                            size += count
                            require(size <= MAX_BYTES) { "Signature preview exceeds 256 MiB" }
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(size > 0) { "Empty signature source" }
                job.ensureActive()
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val opened = PdfRenderer(descriptor)
                renderer = opened
                descriptor = null // PdfRenderer owns it after successful construction.
                require(opened.pageCount in 1..MAX_PAGES) { "Unsupported signature page count" }
                job.ensureActive()
                val digest = hash.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
                return SignatureCanvasDocument(file, opened, digest).also { transferred = true }
            } finally {
                if (!transferred) {
                    try { renderer?.close() } finally {
                        try { descriptor?.close() } finally { file.delete() }
                    }
                }
            }
        }
    }
}
