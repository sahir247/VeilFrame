package com.veilframe.app.pdf

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Streams a generated file to a SAF destination without building a second
 * complete copy in the Java heap. Takes ownership of [file] and deletes it
 * on success, write failure, or cancellation.
 */
internal object PdfFileExport {
    private const val BUFFER_BYTES = 64 * 1024

    fun copyAndDelete(file: File, openDestination: () -> OutputStream): Long {
        try {
            if (!file.isFile || file.length() == 0L) {
                throw IOException("Generated output is missing or empty")
            }
            return openDestination().use { destination ->
                file.inputStream().buffered(BUFFER_BYTES).use { source ->
                    source.copyTo(destination, BUFFER_BYTES)
                }
            }
        } finally {
            file.delete()
        }
    }
}
