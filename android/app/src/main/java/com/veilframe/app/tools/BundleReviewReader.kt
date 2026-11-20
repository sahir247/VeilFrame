package com.veilframe.app.tools

import java.io.File
import java.io.IOException

/** Local, bounded review of the exact UTF-8 artifact, including all later pages.
 * Offsets are UTF-16 character positions in the Reader, never byte offsets.
 */
internal object BundleReviewReader {
    data class Page(val text: String, val nextOffset: Long?)

    fun read(file: File, offset: Long, pageSize: Int = 12_000): Page {
        require(offset >= 0 && pageSize in 2..48_000)
        file.bufferedReader(Charsets.UTF_8).use { reader ->
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
