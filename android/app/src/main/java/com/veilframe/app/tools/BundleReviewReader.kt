package com.veilframe.app.tools

import java.io.File
import java.io.IOException

/** Local, bounded review of the exact UTF-8 artifact, including all later pages.
 * Offsets are UTF-16 character positions in the Reader, never byte offsets.
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

    fun read(file: File, offset: Long, pageSize: Int = 12_000): Page {
        require(offset >= 0 && pageSize in 2..48_000)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        java.io.InputStreamReader(file.inputStream(), decoder).buffered().use { reader ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = reader.skip(remaining)
                if (skipped <= 0) throw IOException("Bundle review offset is outside the file")
                remaining -= skipped
            }
            val chars = CharArray(pageSize + 1)
            var count = 0
            while (count < chars.size) {
                val n = reader.read(chars, count, chars.size - count)
                if (n < 0) break
                count += n
            }
            if (count <= pageSize) return Page(String(chars, 0, count), null)
            // Do not split a supplementary Unicode code point between pages.
            val length = if (Character.isHighSurrogate(chars[pageSize - 1]) &&
                Character.isLowSurrogate(chars[pageSize])) pageSize - 1 else pageSize
            return Page(String(chars, 0, length), offset + length)
        }
    }
}
