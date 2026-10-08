package com.veilframe.app.upscale.inference

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal streaming PNG writer — 8-bit RGBA (color type 6), filter type 0.
 *
 * This is the enabler for F2 band-streaming compose: output scanlines are
 * deflated straight to disk as each tile-row band completes, so a 384 MP
 * output never exists as a bitmap in RAM (the old path OOM-crashed at ~60 MP
 * of output on 512 MB heaps).
 *
 * Format notes: PNG = signature, IHDR, IDAT (zlib stream of filtered
 * scanlines), IEND. Each scanline is prefixed with one filter byte (0 = None).
 * CRC-32 covers chunk type + data. Big-endian lengths. Written against the
 * PNG 1.2 spec; decodable by BitmapFactory, libpng, and every browser.
 */
class StreamingPngWriter(
    private val file: File,
    val width: Int,
    val height: Int,
) : AutoCloseable {

    private val out: OutputStream = FileOutputStream(file).buffered(64 * 1024)
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val idat: DeflaterOutputStream = DeflaterOutputStream(out, deflater, 64 * 1024)
    private val rowBuf = ByteArray(1 + width * 4)
    private val transparentRow = IntArray(width)
    private var rowsWritten = 0
    private var closed = false

    init {
        require(width > 0 && height > 0) { "invalid PNG dimensions ${width}x${height}" }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 6   // color type: RGBA
        ihdr[10] = 0  // compression
        ihdr[11] = 0  // filter
        ihdr[12] = 0  // interlace
        writeChunk("IHDR", ihdr)
    }

    /** Writes one scanline from ARGB_8888 ints (Bitmap.getPixels order). */
    fun writeRow(pixels: IntArray, offset: Int = 0) {
        check(!closed) { "writer closed" }
        check(rowsWritten < height) { "all $height rows already written" }
        check(pixels.size - offset >= width) { "row buffer too small" }
        rowBuf[0] = 0 // filter: None
        var b = 1
        for (i in 0 until width) {
            val p = pixels[offset + i]
            rowBuf[b] = ((p shr 16) and 0xFF).toByte()      // R
            rowBuf[b + 1] = ((p shr 8) and 0xFF).toByte()   // G
            rowBuf[b + 2] = (p and 0xFF).toByte()           // B
            rowBuf[b + 3] = ((p shr 24) and 0xFF).toByte()  // A
            b += 4
        }
        idat.write(rowBuf, 0, b)
        rowsWritten++
    }

    /** Rows written so far (for progress + invariant checks). */
    fun progress(): Float = if (height == 0) 1f else rowsWritten.toFloat() / height.toFloat()

    override fun close() {
        if (closed) return
        closed = true
        try {
            // Defensive: a valid PNG must contain exactly `height` scanlines.
            while (rowsWritten < height) {
                writeRow(transparentRow)
            }
            idat.finish()
            writeChunk("IEND", ByteArray(0))
            idat.close() // finishes the zlib stream flush and closes `out`
        } finally {
            deflater.end()
        }
    }

    /** Abandon without producing a valid file (used on failures). */
    fun abort() {
        if (closed) return
        closed = true
        runCatching { idat.close() }
        runCatching { out.close() }
        runCatching { deflater.end() }
        runCatching { file.delete() }
    }

    private fun writeChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        putInt(crcBytes, 0, (crc.value and 0xFFFFFFFFL).toInt())
        out.write(crcBytes)
    }

    private fun putInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }
}
