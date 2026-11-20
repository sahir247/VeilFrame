package com.veilframe.app.cv.segmentation.rembg

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Lossless non-interlaced RGBA PNG writer with bounded row and IDAT buffers. */
internal object StreamingRgbaPng {
    private val SHIFTS = intArrayOf(24, 16, 8, 0)
    fun write(output: OutputStream, width: Int, height: Int,
              checkpoint: () -> Unit = {}, readRow: (Int, IntArray) -> Unit) {
        require(width in 1..32768 && height in 1..32768)
        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = java.io.ByteArrayOutputStream(13)
        DataOutputStream(header).use {
            it.writeInt(width); it.writeInt(height)
            it.writeByte(8); it.writeByte(6); it.writeByte(0); it.writeByte(0); it.writeByte(0)
        }
        chunk(data, "IHDR", header.toByteArray(), 13)
        val idat = IdatSink(data)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val compressed = DeflaterOutputStream(idat, deflater, 32 * 1024)
            val pixels = IntArray(width)
            val filtered = ByteArray(1 + width * 4)
            filtered[0] = 1 // PNG Sub filter, one 4-byte previous pixel.
            for (y in 0 until height) {
                checkpoint()
                readRow(y, pixels)
                var previous = 0
                var at = 1
                for (pixel in pixels) {
                    val a = pixel ushr 24
                    val rgba = if (a == 0) 0 else (pixel shl 8) or a
                    for (shift in SHIFTS) {
                        filtered[at++] = ((rgba ushr shift and 255) - (previous ushr shift and 255)).toByte()
                    }
                    previous = rgba
                }
                compressed.write(filtered)
            }
            compressed.finish()
            idat.finish()
        } finally {
            deflater.end()
        }
        chunk(data, "IEND", ByteArray(0), 0)
        data.flush()
    }

    private fun chunk(output: DataOutputStream, type: String, bytes: ByteArray, length: Int) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(name)
        crc.update(bytes, 0, length)
        output.writeInt(length)
        output.write(name)
        output.write(bytes, 0, length)
        output.writeInt(crc.value.toInt())
    }

    private class IdatSink(private val output: DataOutputStream) : OutputStream() {
        private val buffer = ByteArray(64 * 1024)
        private var used = 0
        override fun write(value: Int) {
            buffer[used++] = value.toByte()
            if (used == buffer.size) finish()
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var start = offset
            var left = length
            while (left > 0) {
                val n = minOf(left, buffer.size - used)
                bytes.copyInto(buffer, used, start, start + n)
                used += n
                start += n
                left -= n
                if (used == buffer.size) finish()
            }
        }
        fun finish() {
            if (used > 0) {
                chunk(output, "IDAT", buffer, used)
                used = 0
            }
        }
    }
}
