package com.veilframe.app.pdf

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream

/** Owns temporary export until the save picker explicitly accepts ownership. */
internal object PdfQuickExportStage {
    private const val MAX_LEGACY_BYTES = 16L*1024*1024

    suspend fun generate(directory: File, generate: suspend (OutputStream)->Boolean,
        publish: suspend (Boolean,File)->Boolean) {
        val file = File.createTempFile("veilframe_pdf_",".pdf",directory)
        var transferred = false
        try {
            val success = file.outputStream().buffered().use { generate(it) }
            currentCoroutineContext().ensureActive()
            transferred = publish(success && file.length()>0,file)
        } finally {
            if (!transferred) file.delete()
        }
    }

    fun legacyBytes(file: File): ByteArray {
        val length = file.length()
        require(length in 1..MAX_LEGACY_BYTES) { "This export requires disk-backed saving (16 MiB legacy limit)" }
        return file.inputStream().use { input ->
            val expected = length.toInt()
            val bytes = ByteArray(expected)
            var offset = 0
            while (offset < expected) {
                val n = input.read(bytes,offset,expected-offset)
                check(n>0) { "Export changed before transfer" }
                offset += n
            }
            check(input.read() == -1) { "Export changed before transfer" }
            bytes
        }
    }
}
