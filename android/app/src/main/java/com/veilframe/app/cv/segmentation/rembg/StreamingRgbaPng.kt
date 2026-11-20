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
        checkpoint()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = java.io.ByteArrayOutputStream(13)
        DataOutputStream(header).use {
            it.writeInt(width); it.writeInt(height)
            it.writeByte(8); it.writeByte(6); it.writeByte(0); it.writeByte(0); it.writeByte(0)
        }
        chunk(data, "IHDR", header.toByteArray(), 13)
        // Android Bitmap.getPixels exposes straight sRGB values. Declare that
        // encoding rather than inheriting unspecified source ICC metadata.
        chunk(data, "sRGB", byteArrayOf(0), 1)
        val idat = IdatSink(data)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val compressed = DeflaterOutputStream(idat, deflater, 32 * 1024)
            val pixels = IntArray(width)
            var previous = ByteArray(width * 4)
            var raw = ByteArray(width * 4)
            val filtered = ByteArray(1 + width * 4)
            for (y in 0 until height) {
                checkpoint()
                readRow(y, pixels)
                var at = 0
                for (pixel in pixels) {
                    val a = pixel ushr 24
                    val rgba = if (a == 0) 0 else (pixel shl 8) or a
                    for (shift in SHIFTS) raw[at++] = (rgba ushr shift).toByte()
                }
                // Choose None, Sub or Up using signed residual magnitude.
                // Up avoids repeatedly encoding identical photographic rows.
                var none = 0L
                var sub = 0L
                var up = 0L
                for (i in raw.indices) {
                    val value = raw[i].toInt()
                    none += kotlin.math.abs(value)
                    sub += kotlin.math.abs((value - if (i >= 4) raw[i - 4].toInt() else 0).toByte().toInt())
                    up += kotlin.math.abs((value - previous[i].toInt()).toByte().toInt())
                }
                val type = if (none <= sub && none <= up) 0 else if (sub <= up) 1 else 2
                filtered[0] = type.toByte()
                for (i in raw.indices) {
                    val predictor = when (type) {
                        1 -> if (i >= 4) raw[i - 4].toInt() else 0
                        2 -> previous[i].toInt()
                        else -> 0
                    }
                    filtered[i + 1] = (raw[i].toInt() - predictor).toByte()
                }
                compressed.write(filtered)
                val swap = previous
                previous = raw
                raw = swap
            }
            checkpoint()
            compressed.finish()
            idat.finish()
            checkpoint()
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
