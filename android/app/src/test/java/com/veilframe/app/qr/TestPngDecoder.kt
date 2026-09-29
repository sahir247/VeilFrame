package com.veilframe.app.qr

import com.veilframe.app.qr.renderer.PixelSource
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Pure Kotlin/Java PNG decoder for unit testing environments where android.graphics.BitmapFactory
 * is stubbed and java.awt / javax.imageio is excluded from the Android SDK compilation classpath.
 *
 * Decodes standard 8-bit truecolor RGBA / RGB / Grayscale PNG streams into [DecodedPngImage].
 */
data class DecodedPngImage(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray
) : PixelSource {

    override fun getPixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return pixels[y * width + x]
    }

    val hasAlpha: Boolean
        get() = pixels.any { ((it ushr 24) and 0xFF) < 255 }

    companion object {
        fun decode(inputStream: InputStream): DecodedPngImage {
            val dis = DataInputStream(BufferedInputStream(inputStream))
            val magic = dis.readLong()
            require(magic == -8552249625308161526L /* 0x89504E470D0A1A0A */) { "Stream is not a valid PNG image" }

            var width = 0
            var height = 0
            var bitDepth = 0
            var colorType = 0
            val idatBuffer = ByteArrayOutputStream()

            while (true) {
                val length = dis.readInt()
                val type = dis.readInt()
                val data = ByteArray(length)
                dis.readFully(data)
                val crc = dis.readInt()

                when (type) {
                    0x49484452 -> { // IHDR
                        val ihdrDis = DataInputStream(ByteArrayInputStream(data))
                        width = ihdrDis.readInt()
                        height = ihdrDis.readInt()
                        bitDepth = ihdrDis.readByte().toInt()
                        colorType = ihdrDis.readByte().toInt()
                    }
                    0x49444154 -> { // IDAT
                        idatBuffer.write(data)
                    }
                    0x49454E44 -> { // IEND
                        break
                    }
                }
            }

            val bytesPerPixel = when (colorType) {
                6 -> 4 // RGBA
                2 -> 3 // RGB
                4 -> 2 // Grayscale + Alpha
                else -> 1 // Grayscale
            }
            val scanlineLength = width * bytesPerPixel
            val rawIdat = idatBuffer.toByteArray()
            val inflater = InflaterInputStream(ByteArrayInputStream(rawIdat))

            val pixels = IntArray(width * height)
            val prevRow = ByteArray(scanlineLength)
            val currRow = ByteArray(scanlineLength)

            for (y in 0 until height) {
                val filter = inflater.read()
                if (filter < 0) break

                var offset = 0
                while (offset < scanlineLength) {
                    val read = inflater.read(currRow, offset, scanlineLength - offset)
                    if (read < 0) break
                    offset += read
                }

                // Unfilter scanline
                for (x in 0 until scanlineLength) {
                    val a = if (x >= bytesPerPixel) currRow[x - bytesPerPixel].toInt() and 0xFF else 0
                    val b = prevRow[x].toInt() and 0xFF
                    val c = if (x >= bytesPerPixel) prevRow[x - bytesPerPixel].toInt() and 0xFF else 0
                    val `val` = currRow[x].toInt() and 0xFF

                    val unfilt = when (filter) {
                        0 -> `val`
                        1 -> `val` + a
                        2 -> `val` + b
                        3 -> `val` + (a + b) / 2
                        4 -> { // Paeth predictor
                            val p = a + b - c
                            val pa = kotlin.math.abs(p - a)
                            val pb = kotlin.math.abs(p - b)
                            val pc = kotlin.math.abs(p - c)
                            val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                            `val` + pr
                        }
                        else -> `val`
                    }
                    currRow[x] = (unfilt and 0xFF).toByte()
                }

                // Convert bytes to ARGB packed integers
                for (x in 0 until width) {
                    val pxOffset = x * bytesPerPixel
                    val r: Int
                    val g: Int
                    val b: Int
                    val a: Int
                    when (colorType) {
                        6 -> { // RGBA
                            r = currRow[pxOffset].toInt() and 0xFF
                            g = currRow[pxOffset + 1].toInt() and 0xFF
                            b = currRow[pxOffset + 2].toInt() and 0xFF
                            a = currRow[pxOffset + 3].toInt() and 0xFF
                        }
                        2 -> { // RGB
                            r = currRow[pxOffset].toInt() and 0xFF
                            g = currRow[pxOffset + 1].toInt() and 0xFF
                            b = currRow[pxOffset + 2].toInt() and 0xFF
                            a = 255
                        }
                        0 -> { // Grayscale
                            r = currRow[pxOffset].toInt() and 0xFF
                            g = r
                            b = r
                            a = 255
                        }
                        else -> {
                            r = currRow[pxOffset].toInt() and 0xFF
                            g = r
                            b = r
                            a = currRow[pxOffset + 1].toInt() and 0xFF
                        }
                    }
                    pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                System.arraycopy(currRow, 0, prevRow, 0, scanlineLength)
            }

            return DecodedPngImage(width, height, pixels)
        }
    }
}
