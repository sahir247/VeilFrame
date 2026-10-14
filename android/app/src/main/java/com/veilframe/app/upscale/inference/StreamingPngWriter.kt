package com.veilframe.app.upscale.inference

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Streaming PNG writer supporting 8-bit RGBA (color type 6) and 8-bit Truecolor RGB (color type 2), filter type 0.
 *
 * This is the enabler for band-streaming compose: output scanlines are
 * deflated straight to disk as each tile-row band completes, so a large
 * output never exists as a complete bitmap in RAM.
 *
 * Format notes: PNG = signature, IHDR, IDAT (zlib stream of filtered
 * scanlines), IEND. Each scanline is prefixed with one filter byte (0 = None).
 * CRC-32 covers chunk type + data. Big-endian lengths. Written against the
 * PNG 1.2 spec; decodable by BitmapFactory, libpng, and modern browsers.
 */
class StreamingPngWriter(
    private val file: File,
    val width: Int,
    val height: Int,
    val colorType: ColorType = ColorType.RGBA,
) : AutoCloseable {

    companion object {
        const val MAX_DIMENSION = 65_536
        const val IDAT_CHUNK_BUFFER_SIZE = 32 * 1024

        internal fun putInt(dst: ByteArray, offset: Int, value: Int) {
            dst[offset] = ((value ushr 24) and 0xFF).toByte()
            dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            dst[offset + 3] = (value and 0xFF).toByte()
        }
    }

    enum class ColorType(val pngValue: Byte, val channels: Int) {
        RGB(2, 3),
        RGBA(6, 4)
    }

    enum class State { OPEN, FINALIZING, COMPLETED, ABORTED }

    @Volatile
    var state: State = State.OPEN
        private set

    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "invalid PNG dimensions ${width}x${height} (allowed: 1..$MAX_DIMENSION)"
        }
        val bytesPerPixel = colorType.channels
        val requiredBufSize = 1L + width.toLong() * bytesPerPixel.toLong()
        require(requiredBufSize <= Int.MAX_VALUE) {
            "scanline buffer overflow for width $width"
        }
    }

    private val out: OutputStream = FileOutputStream(file).buffered(64 * 1024)
    private val idatChunkStream: IdatChunkOutputStream
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val idat: DeflaterOutputStream
    private val rowBuf = ByteArray((1L + width.toLong() * colorType.channels.toLong()).toInt())
    private var rowsWritten = 0

    init {
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = colorType.pngValue   // color type: 2 (RGB) or 6 (RGBA)
        ihdr[10] = 0  // compression
        ihdr[11] = 0  // filter
        ihdr[12] = 0  // interlace
        writeChunk("IHDR", ihdr)
        out.flush()

        idatChunkStream = IdatChunkOutputStream(out, IDAT_CHUNK_BUFFER_SIZE)
        idat = DeflaterOutputStream(idatChunkStream, deflater, IDAT_CHUNK_BUFFER_SIZE)
    }

    /** Writes one scanline from ARGB_8888 ints (Bitmap.getPixels order). */
    fun writeRow(pixels: IntArray, offset: Int = 0) {
        check(state == State.OPEN) { "writer is in state $state, cannot write row" }
        check(rowsWritten < height) { "all $height rows already written" }
        check(pixels.size - offset >= width) { "row buffer too small: need $width from offset $offset, but size is ${pixels.size}" }
        rowBuf[0] = 0 // filter: None
        var b = 1
        if (colorType == ColorType.RGBA) {
            for (i in 0 until width) {
                val p = pixels[offset + i]
                rowBuf[b] = ((p shr 16) and 0xFF).toByte()      // R
                rowBuf[b + 1] = ((p shr 8) and 0xFF).toByte()   // G
                rowBuf[b + 2] = (p and 0xFF).toByte()           // B
                rowBuf[b + 3] = ((p shr 24) and 0xFF).toByte()  // A
                b += 4
            }
        } else {
            for (i in 0 until width) {
                val p = pixels[offset + i]
                rowBuf[b] = ((p shr 16) and 0xFF).toByte()      // R
                rowBuf[b + 1] = ((p shr 8) and 0xFF).toByte()   // G
                rowBuf[b + 2] = (p and 0xFF).toByte()           // B
                b += 3
            }
        }
        idat.write(rowBuf, 0, b)
        rowsWritten++
    }

    /**
     * Writes one scanline directly from a [ByteBuffer] (direct or heap).
     * The buffer must contain at least `width * colorType.channels` bytes remaining.
     * Advances buffer position by the scanline byte count.
     */
    fun writeRow(buffer: ByteBuffer) {
        check(state == State.OPEN) { "writer is in state $state, cannot write row" }
        check(rowsWritten < height) { "all $height rows already written" }
        val rowBytes = width * colorType.channels
        check(buffer.remaining() >= rowBytes) {
            "buffer remaining (${buffer.remaining()}) less than required row bytes ($rowBytes)"
        }
        rowBuf[0] = 0 // filter: None
        buffer.get(rowBuf, 1, rowBytes)
        idat.write(rowBuf, 0, 1 + rowBytes)
        rowsWritten++
    }

    /**
     * Writes one pre-packed scanline from a raw byte array matching [colorType].
     */
    fun writeRow(rawRow: ByteArray, offset: Int = 0) {
        check(state == State.OPEN) { "writer is in state $state, cannot write row" }
        check(rowsWritten < height) { "all $height rows already written" }
        val rowBytes = width * colorType.channels
        check(rawRow.size - offset >= rowBytes) {
            "rawRow size (${rawRow.size - offset}) less than required row bytes ($rowBytes)"
        }
        rowBuf[0] = 0 // filter: None
        System.arraycopy(rawRow, offset, rowBuf, 1, rowBytes)
        idat.write(rowBuf, 0, 1 + rowBytes)
        rowsWritten++
    }

    /** Rows written so far (for progress + invariant checks). */
    fun progress(): Float = if (height == 0) 1f else rowsWritten.toFloat() / height.toFloat()

    override fun close() {
        if (state == State.COMPLETED || state == State.ABORTED) return
        if (rowsWritten < height) {
            val written = rowsWritten
            abort()
            error("Incomplete PNG: wrote $written of $height rows")
        }
        state = State.FINALIZING
        try {
            idat.finish()
            idatChunkStream.finish()
            writeChunk("IEND", ByteArray(0))
            out.flush()
            idat.close()
            out.close()
            state = State.COMPLETED
        } catch (t: Throwable) {
            abort()
            throw t
        } finally {
            runCatching { deflater.end() }
        }
    }

    /** Abandon without producing a valid file (used on failures). */
    fun abort() {
        if (state == State.ABORTED) return
        state = State.ABORTED
        runCatching { idat.close() }
        runCatching { idatChunkStream.close() }
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

    /**
     * Bounded IDAT chunk framer: packages deflated zlib bytes into proper PNG IDAT chunks.
     * Prevents unbounded in-memory buffering while ensuring strictly valid PNG framing.
     */
    private class IdatChunkOutputStream(
        private val target: OutputStream,
        private val maxChunkSize: Int
    ) : OutputStream() {
        private val chunkBuffer = ByteArray(maxChunkSize)
        private var bufferLen = 0
        private val crc = CRC32()
        private val lenBytes = ByteArray(4)
        private val crcBytes = ByteArray(4)
        private val idatType = "IDAT".toByteArray(Charsets.US_ASCII)
        private var isFinished = false
        private var isClosed = false

        override fun write(b: Int) {
            check(!isClosed) { "Stream closed" }
            chunkBuffer[bufferLen++] = b.toByte()
            if (bufferLen == maxChunkSize) {
                flushChunk()
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!isClosed) { "Stream closed" }
            var currentOff = off
            var remaining = len
            while (remaining > 0) {
                val available = maxChunkSize - bufferLen
                val toCopy = minOf(remaining, available)
                System.arraycopy(b, currentOff, chunkBuffer, bufferLen, toCopy)
                bufferLen += toCopy
                currentOff += toCopy
                remaining -= toCopy
                if (bufferLen == maxChunkSize) {
                    flushChunk()
                }
            }
        }

        private fun flushChunk() {
            if (bufferLen == 0) return
            putInt(lenBytes, 0, bufferLen)
            target.write(lenBytes)
            target.write(idatType)
            target.write(chunkBuffer, 0, bufferLen)

            crc.reset()
            crc.update(idatType)
            crc.update(chunkBuffer, 0, bufferLen)
            putInt(crcBytes, 0, (crc.value and 0xFFFFFFFFL).toInt())
            target.write(crcBytes)

            bufferLen = 0
        }

        fun finish() {
            if (isFinished || isClosed) return
            flushChunk()
            target.flush()
            isFinished = true
        }

        override fun flush() {
            target.flush()
        }

        override fun close() {
            if (!isClosed) {
                if (!isFinished) {
                    finish()
                }
                isClosed = true
                target.close()
            }
        }
    }
}
