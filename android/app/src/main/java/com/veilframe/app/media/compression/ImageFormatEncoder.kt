package com.veilframe.app.media.compression

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Multi-format image encoder supporting:
 * - JPEG, PNG, WEBP (Native Android Bitmap.compress)
 * - BMP (High-performance native uncompressed 24-bit/32-bit BMP header + pixel writer)
 * - TIFF (Lossless archival TIFF via FFmpegKit)
 * - GIF (High-compatibility GIF with palette handling via FFmpegKit)
 * - HEIF / HEIC (Android API 29+ Bitmap.CompressFormat.HEIF + FFmpegKit libx265 fallback)
 * - AVIF (Modern web format via FFmpegKit / high-efficiency container)
 */
object ImageFormatEncoder {

    private const val TAG = "VeilFrame.ImageEncoder"

    enum class Format(val extension: String, val displayName: String, val supportsQuality: Boolean) {
        JPEG("jpg", "JPEG", true),
        PNG("png", "PNG", false),
        WEBP("webp", "WebP", true),
        BMP("bmp", "BMP", false),
        TIFF("tiff", "TIFF", false),
        GIF("gif", "GIF", false),
        HEIF("heif", "HEIF", true),
        HEIC("heic", "HEIC", true),
        AVIF("avif", "AVIF", true);

        companion object {
            fun fromString(formatStr: String): Format {
                return when (formatStr.trim().lowercase(Locale.US)) {
                    "jpg", "jpeg" -> JPEG
                    "png" -> PNG
                    "webp" -> WEBP
                    "bmp" -> BMP
                    "tiff", "tif" -> TIFF
                    "gif" -> GIF
                    "heif" -> HEIF
                    "heic" -> HEIC
                    "avif" -> AVIF
                    else -> JPEG
                }
            }
        }
    }

    /**
     * Encodes [bitmap] into [outFile] using the requested [format] and [quality] (1-100).
     */
    fun encodeImage(
        bitmap: Bitmap,
        outFile: File,
        format: Format,
        quality: Int = 90
    ): Boolean {
        outFile.parentFile?.mkdirs()
        val clampedQuality = quality.coerceIn(5, 100)

        return when (format) {
            Format.JPEG -> {
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, fos)
                }
            }
            Format.PNG -> {
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
            }
            Format.WEBP -> {
                FileOutputStream(outFile).use { fos ->
                    val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(webpFormat, clampedQuality, fos)
                }
            }
            Format.BMP -> {
                encodeBmp(bitmap, outFile)
            }
            Format.TIFF -> {
                encodeViaFFmpeg(bitmap, outFile, arrayOf("-c:v", "tiff"))
            }
            Format.GIF -> {
                encodeGifViaFFmpeg(bitmap, outFile)
            }
            Format.HEIF, Format.HEIC -> {
                encodeHeif(bitmap, outFile, clampedQuality)
            }
            Format.AVIF -> {
                encodeAvif(bitmap, outFile, clampedQuality)
            }
        }
    }

    /**
     * Fast native BMP encoder writing a standard uncompressed 24-bit Windows Bitmap.
     * Guaranteed zero-dependency lossless output.
     */
    fun encodeBmp(bitmap: Bitmap, outFile: File): Boolean {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            // Row size in BMP must be multiple of 4 bytes
            require(width > 0 && height > 0)
            val rowBytesLong = (width.toLong() * 3 + 3) and 3L.inv()
            val pixelDataSizeLong = Math.multiplyExact(rowBytesLong, height.toLong())
            val totalFileSizeLong = Math.addExact(54L, pixelDataSizeLong)
            require(totalFileSizeLong <= 0xFFFF_FFFFL && rowBytesLong <= Int.MAX_VALUE) {
                "BMP exceeds 32-bit container size"
            }
            val rowBytes = rowBytesLong.toInt()
            val pixelDataSize = pixelDataSizeLong.toInt()
            val fileHeaderSize = 14
            val infoHeaderSize = 40
            val totalFileSize = totalFileSizeLong.toInt()

            val header = ByteBuffer.allocate(fileHeaderSize + infoHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
            // Bitmap File Header (14 bytes)
            header.put('B'.code.toByte())
            header.put('M'.code.toByte())
            header.putInt(totalFileSize)
            header.putShort(0) // reserved 1
            header.putShort(0) // reserved 2
            header.putInt(fileHeaderSize + infoHeaderSize) // pixel data offset

            // Bitmap Info Header (40 bytes)
            header.putInt(infoHeaderSize)
            header.putInt(width)
            header.putInt(height) // positive height = bottom-up rows
            header.putShort(1) // planes
            header.putShort(24) // 24 bits per pixel (BGR)
            header.putInt(0) // BI_RGB (no compression)
            header.putInt(pixelDataSize)
            header.putInt(2835) // horizontal ppm (~72 DPI)
            header.putInt(2835) // vertical ppm (~72 DPI)
            header.putInt(0) // colors in color table
            header.putInt(0) // important color count

            // Read one row at a time; a full IntArray duplicates the entire bitmap.
            val pixels = IntArray(width)
            FileOutputStream(outFile).use { fos ->
                fos.write(header.array())
                val rowBuffer = ByteArray(rowBytes)
                // BMP is stored bottom-up
                for (y in height - 1 downTo 0) {
                    var byteIndex = 0
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    for (x in 0 until width) {
                        val pixel = pixels[x]
                        rowBuffer[byteIndex++] = (pixel and 0xFF).toByte()         // Blue
                        rowBuffer[byteIndex++] = ((pixel shr 8) and 0xFF).toByte()  // Green
                        rowBuffer[byteIndex++] = ((pixel shr 16) and 0xFF).toByte() // Red
                    }
                    while (byteIndex < rowBytes) {
                        rowBuffer[byteIndex++] = 0 // padding to 4-byte boundary
                    }
                    fos.write(rowBuffer)
                }
            }
            outFile.isFile && outFile.length() == totalFileSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Native BMP encoding failed: ${e.message}", e)
            false
        }
    }

    private fun encodeHeif(bitmap: Bitmap, outFile: File, quality: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val heicFormat = runCatching { Bitmap.CompressFormat.valueOf("HEIC") }.getOrNull()
                if (heicFormat != null) {
                    FileOutputStream(outFile).use { fos ->
                        if (bitmap.compress(heicFormat, quality, fos) && outFile.length() > 0) {
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Native HEIC compression not available on device: ${e.message}. Using FFmpeg fallback.")
            }
        }

        // Fallback: FFmpegKit x265 encoder
        val crf = (51 - ((quality / 100.0) * 35)).toInt().coerceIn(16, 45)
        return encodeViaFFmpeg(bitmap, outFile, arrayOf("-c:v", "libx265", "-crf", crf.toString(), "-preset", "veryfast"))
    }

    private fun encodeGifViaFFmpeg(bitmap: Bitmap, outFile: File): Boolean {
        val tempPng = File.createTempFile("vf_gif_src_", ".png", outFile.parentFile)
        return try {
            FileOutputStream(tempPng).use { fos ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) { "Unable to encode intermediate PNG" }
            }
            val session = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-i", tempPng.absolutePath, "-map_metadata", "-1", "-frames:v", "1",
                "-vf", "split[s0][s1];[s0]palettegen=reserve_transparent=1[p];[s1][p]paletteuse=alpha_threshold=128",
                outFile.absolutePath
            ))
            ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "GIF encoding failed: ${e.message}", e)
            false
        } finally {
            tempPng.delete()
        }
    }

    private fun encodeAvif(bitmap: Bitmap, outFile: File, quality: Int): Boolean {
        val crf = (63 - ((quality / 100.0) * 45)).toInt().coerceIn(18, 55)
        val tempPng = File.createTempFile("vf_avif_src_", ".png", outFile.parentFile)
        return try {
            FileOutputStream(tempPng).use { fos ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) { "Unable to encode intermediate PNG" }
            }
            // A failed AVIF encoder is not permission to return WebP in an AVIF container.
            val session = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-i", tempPng.absolutePath, "-map_metadata", "-1", "-frames:v", "1",
                "-c:v", "libaom-av1", "-crf", crf.toString(), "-f", "avif", outFile.absolutePath
            ))
            val valid = ReturnCode.isSuccess(session.returnCode) && ImageContainerContract.matches(outFile, Format.AVIF)
            if (!valid) outFile.delete()
            valid
        } catch (e: Exception) {
            Log.e(TAG, "AVIF encoding failed: ${e.message}", e)
            false
        } finally {
            tempPng.delete()
        }
    }

    private fun encodeViaFFmpeg(bitmap: Bitmap, outFile: File, ffmpegVideoOptions: Array<String>): Boolean {
        val tempPng = File.createTempFile("vf_encode_src_", ".png", outFile.parentFile)
        return try {
            FileOutputStream(tempPng).use { fos ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) { "Unable to encode intermediate PNG" }
            }
            val session = FFmpegKit.executeWithArguments(
                arrayOf("-y", "-i", tempPng.absolutePath, "-map_metadata", "-1", "-frames:v", "1") +
                    ffmpegVideoOptions + arrayOf(outFile.absolutePath)
            )
            ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg image encoding failed: ${e.message}", e)
            false
        } finally {
            tempPng.delete()
        }
    }
}
