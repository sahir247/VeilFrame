package com.veilframe.app.media.compression

import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.media.CompressionResult
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.ImageOutputConfig
import com.veilframe.app.media.metadata.ImageMetadataWriter
import com.veilframe.app.privacy.ImageMetadataSanitizer
import java.io.File
import java.util.Locale

/**
 * Dedicated compression engine for Image Studio.
 * Handles:
 * - Multi-format encoding (JPEG, PNG, WEBP, BMP, TIFF, GIF, HEIF, HEIC, AVIF)
 * - Iterative binary-search target-size solver (retains best under target + dimension fallback)
 * - Post-encode size verification
 * - Metadata dispatch (Sanitizer for strip, Writer for custom EXIF)
 */
object ImageCompressionEngine {

    private const val TAG = "VeilFrame.ImageCompressionEngine"

    fun compress(
        srcFile: File,
        outFile: File,
        editState: ImageEditState,
        outputConfig: ImageOutputConfig,
        processedBitmap: Bitmap
    ): CompressionResult {
        return try {
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()

            val targetFormat = ImageFormatEncoder.Format.fromString(outputConfig.format)
            val targetSizeKb = outputConfig.targetSizeKb ?: 250
            val isTargetSizeMode = outputConfig.compressionMode == "target_size" && targetSizeKb > 0
            val targetBytes = targetSizeKb.toLong() * 1024L

            var workingBmp = processedBitmap
            val tempProbeFile = File(outFile.parentFile, "probe_${System.currentTimeMillis()}.${targetFormat.extension}")

            if (isTargetSizeMode) {
                var bestFileUnderTarget = false
                var optimalQuality = outputConfig.quality

                if (targetFormat.supportsQuality) {
                    var low = 5
                    var high = 95
                    for (iter in 0 until 8) {
                        val mid = (low + high) / 2
                        tempProbeFile.delete()
                        val ok = ImageFormatEncoder.encodeImage(workingBmp, tempProbeFile, targetFormat, mid)
                        if (ok && tempProbeFile.exists() && tempProbeFile.length() <= targetBytes) {
                            tempProbeFile.copyTo(outFile, overwrite = true)
                            bestFileUnderTarget = true
                            optimalQuality = mid
                            low = mid + 1 // try higher quality
                        } else {
                            high = mid - 1 // reduce quality
                        }
                        if (low > high) break
                    }
                }

                // If quality iteration was not enough or format is lossless, downscale dimensions
                if (!bestFileUnderTarget || (outFile.exists() && outFile.length() > targetBytes) || !outFile.exists()) {
                    var scale = 0.85f
                    for (downscaleAttempt in 0 until 6) {
                        val newW = (workingBmp.width * scale).toInt().coerceAtLeast(32)
                        val newH = (workingBmp.height * scale).toInt().coerceAtLeast(32)
                        val downscaled = try {
                            Bitmap.createScaledBitmap(workingBmp, newW, newH, true)
                        } catch (_: Exception) { null }

                        if (downscaled != null) {
                            if (workingBmp != processedBitmap) workingBmp.recycle()
                            workingBmp = downscaled

                            tempProbeFile.delete()
                            val probeQ = if (targetFormat.supportsQuality) 30 else 100
                            val ok = ImageFormatEncoder.encodeImage(workingBmp, tempProbeFile, targetFormat, probeQ)
                            if (ok && tempProbeFile.exists() && tempProbeFile.length() <= targetBytes) {
                                tempProbeFile.copyTo(outFile, overwrite = true)
                                bestFileUnderTarget = true
                                break
                            }
                        }
                        scale -= 0.15f
                    }
                }

                tempProbeFile.delete()

                if (!bestFileUnderTarget || !outFile.exists() || outFile.length() > targetBytes) {
                    // Try one final minimum encode to report actual smallest achievable size
                    tempProbeFile.delete()
                    ImageFormatEncoder.encodeImage(workingBmp, tempProbeFile, targetFormat, 15)
                    val minBytes = if (tempProbeFile.exists()) tempProbeFile.length() else workingBmp.byteCount.toLong()
                    tempProbeFile.delete()
                    if (workingBmp != processedBitmap) {
                        try { workingBmp.recycle() } catch (_: Exception) {}
                    }
                    val formattedMin = if (minBytes < 1024) "$minBytes B" else if (minBytes < 1024 * 1024) "${minBytes / 1024} KB" else String.format(Locale.US, "%.1f MB", minBytes / (1024.0 * 1024.0))
                    return CompressionResult(
                        success = false,
                        outputPath = outFile.absolutePath,
                        sizeBytes = minBytes,
                        error = "Target size ${targetSizeKb} KB could not be achieved (smallest possible for ${targetFormat.displayName} was $formattedMin). Try selecting a larger target size or resizing image dimensions."
                    )
                }
            } else {
                // Quality mode: direct encode
                ImageFormatEncoder.encodeImage(workingBmp, outFile, targetFormat, outputConfig.quality)
            }

            if (workingBmp != processedBitmap) {
                try { workingBmp.recycle() } catch (_: Exception) {}
            }

            // Metadata handling
            if (editState.stripExif && outFile.exists()) {
                ImageMetadataSanitizer.stripExifLossless(outFile, outFile)
            } else if (!editState.stripExif && outFile.exists() && targetFormat == ImageFormatEncoder.Format.JPEG) {
                ImageMetadataWriter.applyMetadata(outFile, editState)
            }

            val targetBytesLimit = if (isTargetSizeMode) targetSizeKb.toLong() * 1024L else Long.MAX_VALUE
            val isSuccess = outFile.exists() && outFile.length() > 0L && outFile.length() <= targetBytesLimit
            if (isSuccess) {
                val outSize = outFile.length()
                val origSize = srcFile.length()
                val savings = if (origSize > 0) ((origSize - outSize).toDouble() / origSize.toDouble() * 100.0) else 0.0
                CompressionResult(
                    success = true,
                    outputPath = outFile.absolutePath,
                    sizeBytes = outSize,
                    savingsPercent = savings,
                    error = null
                )
            } else {
                val outSize = if (outFile.exists()) outFile.length() else 0L
                try { outFile.delete() } catch (_: Exception) {}
                val errMsg = if (isTargetSizeMode && outSize > targetBytesLimit) {
                    "Target ceiling exceeded: output was ${outSize / 1024} KB (requested target: ${targetSizeKb} KB)"
                } else {
                    "Failed to write encoded image file"
                }
                CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = 0L,
                    savingsPercent = 0.0,
                    error = errMsg
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed: ${e.message}", e)
            try { outFile.delete() } catch (_: Exception) {}
            CompressionResult(
                success = false,
                outputPath = outFile.absolutePath,
                sizeBytes = 0L,
                savingsPercent = 0.0,
                error = e.message ?: "Unknown compression error"
            )
        }
    }

    /**
     * Computes empirical, high-precision size estimate for display in UI.
     */
    fun estimateOutputBytes(
        originalBytes: Long,
        origWidth: Int,
        origHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        outputConfig: ImageOutputConfig
    ): Long {
        if (outputConfig.compressionMode == "target_size") {
            val targetKb = outputConfig.targetSizeKb ?: 250
            return (targetKb * 1024L).coerceAtLeast(1024L)
        }

        val targetFormat = ImageFormatEncoder.Format.fromString(outputConfig.format)
        val targetPixels = (targetWidth.toLong() * targetHeight.toLong()).coerceAtLeast(1L)
        val origPixels = (origWidth.toLong() * origHeight.toLong()).coerceAtLeast(1L)
        val dimRatio = targetPixels.toDouble() / origPixels.toDouble()
        val quality = outputConfig.quality.coerceIn(5, 100)

        return when (targetFormat) {
            ImageFormatEncoder.Format.BMP -> {
                // Exact standard 24-bit uncompressed Windows DIB bitmap:
                // Row size is padded to 4-byte boundary: (width * 3 + 3) & ~3
                val rowBytes = ((targetWidth * 3L + 3) and 3.inv().toLong())
                rowBytes * targetHeight + 54L
            }
            ImageFormatEncoder.Format.JPEG -> {
                val qFactor = if (quality >= 85) {
                    1.0 + (quality - 85) * 0.035
                } else {
                    Math.pow(quality / 85.0, 1.35)
                }
                if (originalBytes > 0) {
                    (originalBytes * dimRatio * qFactor).toLong().coerceIn(1024L, (targetPixels * 1.5).toLong())
                } else {
                    (targetPixels * 0.22 * qFactor).toLong().coerceAtLeast(1024L)
                }
            }
            ImageFormatEncoder.Format.WEBP -> {
                val qFactor = if (quality >= 85) {
                    1.0 + (quality - 85) * 0.03
                } else {
                    Math.pow(quality / 85.0, 1.3)
                }
                val base = if (originalBytes > 0) (originalBytes * dimRatio * 0.72) else (targetPixels * 0.16)
                (base * qFactor).toLong().coerceAtLeast(1024L)
            }
            ImageFormatEncoder.Format.PNG -> {
                // Deflate lossless compression for photographic content averages ~1.4 bytes/pixel
                if (originalBytes > 0) {
                    val candidate = (originalBytes * dimRatio * 2.2).toLong()
                    minOf(candidate, (targetPixels * 1.4).toLong()).coerceAtLeast(1024L)
                } else {
                    (targetPixels * 1.4).toLong().coerceAtLeast(1024L)
                }
            }
            ImageFormatEncoder.Format.HEIF, ImageFormatEncoder.Format.HEIC, ImageFormatEncoder.Format.AVIF -> {
                // Advanced intra-coding achieves ~45-55% smaller size than JPEG
                val qFactor = Math.pow(quality / 85.0, 1.4)
                val base = if (originalBytes > 0) (originalBytes * dimRatio * 0.50) else (targetPixels * 0.12)
                (base * qFactor).toLong().coerceAtLeast(1024L)
            }
            ImageFormatEncoder.Format.TIFF -> {
                // Uncompressed or LZW TIFF
                (targetPixels * 2.0).toLong().coerceAtLeast(1024L)
            }
            ImageFormatEncoder.Format.GIF -> {
                // 8-bit palette indexed GIF
                (targetPixels * 0.65).toLong().coerceAtLeast(1024L)
            }
        }
    }
}
