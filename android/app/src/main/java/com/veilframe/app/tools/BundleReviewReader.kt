package com.veilframe.app.tools

import java.io.File
import java.io.IOException

/** Local, bounded review of the exact UTF-8 artifact, including all later pages.
 * Offsets are UTF-8 byte positions at complete code point boundaries.
 */
internal object BundleReviewReader {
    data class Page(val text: String, val nextOffset: Long?)

    /** An owned private copy, never rewritten by a new bundle job or workspace reset. */
    fun freeze(source: File, destination: File, checkpoint: () -> Unit = {}) {
        val initialLength = source.length()
        val initialModified = source.lastModified()
        require(source.isFile && initialLength in 1L..(32L * 1024 * 1024)) { "Invalid review source" }
        val directory = requireNotNull(destination.parentFile)
        check(directory.mkdir()) { "Cannot create private review directory" }
        try {
            var written = 0L
            source.inputStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) throw IOException("Review source made no progress")
                        written += n
                        if (written > initialLength) throw IOException("Bundle changed while snapshotting")
                        output.write(buffer, 0, n)
                    }
                }
            }
            checkpoint()
            if (written != initialLength || source.length() != initialLength ||
                source.lastModified() != initialModified) throw IOException("Bundle changed while snapshotting")
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    /** Direct, bounded seek: each page reads at most pageSize + 3 bytes. */
    fun read(file: File, offset: Long, pageSize: Int = 12_000): Page {
        require(offset >= 0 && pageSize in 2..48_000)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        java.io.RandomAccessFile(file, "r").use { reader ->
            val size = reader.length()
            if (offset > size) throw IOException("Bundle review offset is outside the file")
            reader.seek(offset)
            val count = minOf((pageSize + 3).toLong(), size - offset).toInt()
            val bytes = ByteArray(count)
            reader.readFully(bytes)
            var length = minOf(pageSize, count)
            // Complete the code point if the nominal boundary falls inside it.
            while (length < count && (bytes[length].toInt() and 0xC0) == 0x80) length++
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, length)).toString()
            val next = offset + length
            return Page(text, if (next == size) null else next)
        }
    }
}
