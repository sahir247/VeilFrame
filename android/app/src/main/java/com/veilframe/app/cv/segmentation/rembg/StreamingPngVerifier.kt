package com.veilframe.app.cv.segmentation.rembg

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Strict read-back of the private encoder's own format, not a general PNG parser.
 * Validates every chunk CRC, zlib termination and exact scanline count without
 * allocating a whole image. Never accepts a header-only or truncated artifact.
 */
internal object StreamingPngVerifier {
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private const val MAX_CHUNK = 64 * 1024

    fun verify(input: InputStream, width: Int, height: Int, checkpoint: () -> Unit = {}) {
        require(width in 1..32768 && height in 1..32768)
        val data = DataInputStream(input)
        val actualSignature = ByteArray(8)
        data.readFully(actualSignature)
        checkPng(actualSignature.contentEquals(signature), "Invalid PNG signature")
        val rowBytes = 1L + width * 4L
        val expectedBytes = rowBytes * height
        var decodedBytes = 0L
        val inflater = Inflater()
        val payload = ByteArray(MAX_CHUNK)
        val output = ByteArray(64 * 1024)
        val name = ByteArray(4)
        var state = 0 // IHDR, sRGB, IDAT+, IEND
        try {
            while (true) {
                checkpoint()
                val length = data.readInt()
                checkPng(length in 0..MAX_CHUNK, "Invalid encoder chunk length")
                data.readFully(name)
                data.readFully(payload, 0, length)
                val crc = CRC32().apply { update(name); update(payload, 0, length) }
                checkPng(data.readInt() == crc.value.toInt(), "PNG chunk CRC mismatch")
                when (String(name, Charsets.US_ASCII)) {
                    "IHDR" -> {
                        checkPng(state == 0 && length == 13, "Invalid IHDR")
                        val h = DataInputStream(payload.inputStream(0, length))
                        checkPng(h.readInt() == width && h.readInt() == height &&
                            h.readUnsignedByte() == 8 && h.readUnsignedByte() == 6 &&
                            h.readUnsignedByte() == 0 && h.readUnsignedByte() == 0 &&
                            h.readUnsignedByte() == 0, "Unexpected PNG header")
                        state = 1
                    }
                    "sRGB" -> {
                        checkPng(state == 1 && length == 1 && payload[0] == 0.toByte(), "Invalid sRGB chunk")
                        state = 2
                    }
                    "IDAT" -> {
                        checkPng((state == 2 || state == 3) && length > 0 && !inflater.finished() &&
                            inflater.needsInput(), "Invalid IDAT sequence")
                        state = 3
                        inflater.setInput(payload, 0, length)
                        while (true) {
                            checkpoint()
                            val n = inflater.inflate(output)
                            checkPng(n.toLong() <= expectedBytes - decodedBytes, "Too much inflated PNG data")
                            // Inspect every scanline's filter byte, even across IDAT/buffer boundaries.
                            var offset = (rowBytes - decodedBytes % rowBytes) % rowBytes
                            while (offset < n) {
                                checkPng(output[offset.toInt()].toInt() in 0..4, "Invalid PNG row filter")
                                offset += rowBytes
                            }
                            decodedBytes += n
                            if (inflater.finished()) {
                                checkPng(inflater.remaining == 0, "Trailing bytes in PNG zlib stream")
                                break
                            }
                            if (inflater.needsInput()) break
                            checkPng(n > 0 && !inflater.needsDictionary(), "PNG inflater made no progress")
                        }
                    }
                    "IEND" -> {
                        checkPng(state == 3 && length == 0 && inflater.finished() &&
                            decodedBytes == expectedBytes, "Incomplete PNG zlib stream or scanlines")
                        checkPng(data.read() == -1, "Trailing data after PNG IEND")
                        return
                    }
                    else -> throw IOException("Unexpected chunk in private PNG output")
                }
            }
        } catch (failure: DataFormatException) {
            throw IOException("Invalid PNG compressed data", failure)
        } finally {
            inflater.end()
        }
    }

    private fun checkPng(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
