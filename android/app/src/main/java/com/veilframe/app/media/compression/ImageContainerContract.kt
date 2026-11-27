package com.veilframe.app.media.compression

import java.io.File
import java.io.RandomAccessFile

/** Bounded container identity check, not a full decoder or payload integrity proof. */
internal object ImageContainerContract {
    fun matches(file: File, format: ImageFormatEncoder.Format): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                val bytes = ByteArray(minOf(input.length(), 4096L).toInt())
                input.readFully(bytes)
                matchesHeader(bytes, input.length(), format)
            }
        } catch (_: java.io.IOException) { false }
    }

    internal fun matchesHeader(bytes: ByteArray, length: Long, format: ImageFormatEncoder.Format): Boolean {
        fun ascii(offset: Int, size: Int): String? =
            if (offset >= 0 && size >= 0 && offset <= bytes.size - size)
                String(bytes, offset, size, Charsets.US_ASCII) else null
        fun u(index: Int): Int = bytes.getOrNull(index)?.toInt()?.and(255) ?: -1
        fun be32(offset: Int): Long =
            if (offset > bytes.size - 4) -1L else (0..3).fold(0L) { value, i -> (value shl 8) or u(offset + i).toLong() }
        return when (format) {
            ImageFormatEncoder.Format.JPEG -> u(0) == 255 && u(1) == 216 && u(2) == 255
            ImageFormatEncoder.Format.PNG -> listOf(137,80,78,71,13,10,26,10).withIndex().all { u(it.index) == it.value }
            ImageFormatEncoder.Format.WEBP -> ascii(0,4) == "RIFF" && ascii(8,4) == "WEBP"
            ImageFormatEncoder.Format.BMP -> ascii(0,2) == "BM" && length >= 54
            ImageFormatEncoder.Format.TIFF -> (ascii(0,2) == "II" && u(2) == 42 && u(3) == 0) ||
                (ascii(0,2) == "MM" && u(2) == 0 && u(3) == 42)
            ImageFormatEncoder.Format.GIF -> ascii(0,6) in setOf("GIF87a","GIF89a")
            ImageFormatEncoder.Format.HEIF, ImageFormatEncoder.Format.HEIC, ImageFormatEncoder.Format.AVIF -> {
                val box = be32(0)
                if (ascii(4,4) != "ftyp" || box < 16 || box > bytes.size || box > length || (box - 16) % 4 != 0L) return false
                val brands = mutableSetOf(ascii(8,4))
                for (offset in 16 until box.toInt() step 4) brands.add(ascii(offset,4))
                val avif = brands.any { it == "avif" || it == "avis" }
                val hevc = brands.any { it in setOf("heic","heix","hevc","hevx","heim","heis","hevm","hevs") }
                when (format) {
                    ImageFormatEncoder.Format.AVIF -> avif
                    ImageFormatEncoder.Format.HEIC -> hevc && !avif
                    else -> !avif && (hevc || "mif1" in brands || "msf1" in brands)
                }
            }
        }
    }
}
