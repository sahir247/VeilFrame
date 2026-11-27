package com.veilframe.app.media

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Exact batch artifact for the single-file Android Save/Share contract. */
internal object ImageStudioBatchArchive {
    fun safeBaseName(raw: String): String {
        val base = File(raw).name.substringBeforeLast('.').trim()
            .map { if (it.code < 32 || it.code == 127 || it in "\\\\/:*?\"<>|") '_' else it }
            .joinToString("").take(100).trim().trim('.')
        return base.ifBlank { "compressed_image" }
    }

    private data class Stamp(val size: Long, val hash: ByteArray)

    fun writeVerified(files: List<File>, archive: File, checkpoint: () -> Unit = {}) {
        require(files.size > 1 && !archive.exists()) { "Batch archive requires new output and multiple images" }
        val parent = requireNotNull(archive.canonicalFile.parentFile)
        require(parent.isDirectory)
        val names = files.map { it.name }
        require(names.distinct().size == names.size && names.all { it.isNotBlank() && it != "." && it != ".." &&
            it.none { char -> char == '/' || char == '\\' || char.code < 32 || char.code == 127 } }) {
            "Invalid or duplicate batch archive entry names"
        }
        require(files.all { it.isFile && it.length() > 0 && it.canonicalFile.parentFile == parent &&
            it.canonicalFile != archive.canonicalFile }) { "Invalid batch image source" }
        val inputBytes = files.fold(0L) { total, file -> Math.addExact(total, file.length()) }
        // Encoded images rarely compress further. Bound temporary storage before
        // starting the ZIP and leave room for local headers/central directory.
        val required = Math.addExact(inputBytes, Math.addExact(inputBytes / 100, 2L * 1024 * 1024 + files.size * 1024L))
        check(parent.usableSpace >= required) { "Insufficient storage for the complete batch archive" }
        val stamps = linkedMapOf<String, Stamp>()
        var complete = false
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                for (file in files) {
                    checkpoint()
                    val size = file.length()
                    zip.putNextEntry(ZipEntry(file.name))
                    val stamp = file.inputStream().use { readStamp(it, size, zip, checkpoint) }
                    zip.closeEntry()
                    stamps[file.name] = stamp
                }
            }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                check(entries.size == stamps.size && entries.map { it.name }.toSet() == stamps.keys) {
                    "Batch archive inventory mismatch"
                }
                for (entry in entries) {
                    checkpoint()
                    val expected = stamps.getValue(entry.name)
                    check(!entry.isDirectory && entry.size == expected.size) { "Batch archive entry size mismatch" }
                    val observed = zip.getInputStream(entry).use { readStamp(it, expected.size, null, checkpoint) }
                    check(MessageDigest.isEqual(expected.hash, observed.hash)) { "Batch archive entry bytes mismatch" }
                }
            }
            checkpoint()
            complete = true
        } finally {
            if (!complete) archive.delete()
        }
    }

    private fun readStamp(input: InputStream, size: Long, sink: OutputStream?, checkpoint: () -> Unit): Stamp {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n < 0) break
            check(n > 0 && n.toLong() <= size - total) { "Batch image changed during archive creation" }
            sink?.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            total += n
        }
        check(total == size) { "Incomplete batch archive entry" }
        return Stamp(total, digest.digest())
    }
}
