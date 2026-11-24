package com.veilframe.app.media.compression

import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.media.CompressionResult
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.ImageOutputConfig
import com.veilframe.app.media.metadata.ImageMetadataWriter
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
        var workingBmp = processedBitmap
        var tempProbeFile: File? = null
        var committed = false
        return try {
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) check(outFile.delete()) { "Cannot replace old encoded output" }

            val targetFormat = ImageFormatEncoder.Format.fromString(outputConfig.format)
            val targetSizeKb = outputConfig.targetSizeKb ?: 250
            val isTargetSizeMode = outputConfig.compressionMode == "target_size" && targetSizeKb > 0
            val targetBytes = targetSizeKb.toLong() * 1024L

            val probe = File.createTempFile("vf_probe_", ".${targetFormat.extension}", outFile.parentFile)
            tempProbeFile = probe

            if (isTargetSizeMode) {
                var bestFileUnderTarget = false
                var optimalQuality = outputConfig.quality

                if (targetFormat.supportsQuality) {
                    var low = 5
                    var high = 95
                    for (iter in 0 until 8) {
                        val mid = (low + high) / 2
                        probe.delete()
                        val ok = ImageFormatEncoder.encodeImage(workingBmp, probe, targetFormat, mid)
                        if (ok && ImageContainerContract.matches(probe, targetFormat) && probe.length() <= targetBytes) {
                            probe.copyTo(outFile, overwrite = true)
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
                        // Every trial derives from the original processed pixels rather than
                        // successively resampling an already degraded image.
                        val newW = (processedBitmap.width * scale).toInt().coerceIn(1, processedBitmap.width)
                        val newH = (processedBitmap.height * scale).toInt().coerceIn(1, processedBitmap.height)
                        val downscaled = try {
                            Bitmap.createScaledBitmap(processedBitmap, newW, newH, true)
                        } catch (_: Exception) { null }

                        if (downscaled != null) {
                            if (workingBmp !== processedBitmap && workingBmp !== downscaled) workingBmp.recycle()
                            workingBmp = downscaled

                            probe.delete()
                            val probeQ = if (targetFormat.supportsQuality) 30 else 100
                            val ok = ImageFormatEncoder.encodeImage(workingBmp, probe, targetFormat, probeQ)
                            if (ok && ImageContainerContract.matches(probe, targetFormat) && probe.length() <= targetBytes) {
                                probe.copyTo(outFile, overwrite = true)
                                bestFileUnderTarget = true
                                break
                            }
                        }
                        scale -= 0.15f
                    }
                }

                probe.delete()

                if (!bestFileUnderTarget || !outFile.exists() || outFile.length() > targetBytes) {
                    // Try one final minimum encode to report actual smallest achievable size
                    probe.delete()
                    ImageFormatEncoder.encodeImage(workingBmp, probe, targetFormat, 15)
                    val minBytes = if (probe.exists()) probe.length() else workingBmp.byteCount.toLong()
                    probe.delete()
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
                check(ImageFormatEncoder.encodeImage(workingBmp, outFile, targetFormat, outputConfig.quality)) {
                    "Encoder failed for ${targetFormat.displayName}"
                }
            }

            check(ImageContainerContract.matches(outFile, targetFormat)) {
                "Encoder output is not the requested ${targetFormat.displayName} container"
            }
            // This is a fresh pixel encode, never a source-file copy. Do not run the
            // generic EXIF scrubber's format-changing pixel re-encode fallback.
            // Only explicitly requested custom metadata is written to supported JPEG.
            if (!editState.stripExif && ImageMetadataWriter.hasCustomTags(editState)) {
                check(targetFormat == ImageFormatEncoder.Format.JPEG) { "Custom EXIF is supported only for JPEG export" }
                check(ImageMetadataWriter.applyMetadata(outFile, editState)) { "Requested EXIF metadata could not be written" }
            }
            check(ImageContainerContract.matches(outFile, targetFormat)) { "Metadata operation changed the requested image container" }

            val targetBytesLimit = if (isTargetSizeMode) targetSizeKb.toLong() * 1024L else Long.MAX_VALUE
            val isSuccess = outFile.exists() && outFile.length() > 0L && outFile.length() <= targetBytesLimit
            if (isSuccess) {
                val outSize = outFile.length()
                val origSize = srcFile.length()
                val savings = if (origSize > 0) ((origSize - outSize).toDouble() / origSize.toDouble() * 100.0) else 0.0
                committed = true
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
        } finally {
            if (workingBmp !== processedBitmap && !workingBmp.isRecycled) workingBmp.recycle()
            tempProbeFile?.delete()
            if (!committed) outFile.delete()
        }
    }

    /**
     * Performs a fast probe encode of a rendered bitmap (such as 600x600 Passport)
     * using the exact codec, quality, and metadata parameters to measure concrete size.
     */
    fun probeEncodeBytes(
        bitmap: Bitmap,
        outputConfig: ImageOutputConfig
    ): Long {
        val targetFormat = ImageFormatEncoder.Format.fromString(outputConfig.format)
        val quality = outputConfig.quality.coerceIn(5, 100)
        val tempFile = File.createTempFile("vf_probe_", ".${targetFormat.extension}")
        return try {
            val ok = ImageFormatEncoder.encodeImage(bitmap, tempFile, targetFormat, quality)
            if (ok && tempFile.exists()) tempFile.length() else 0L
        } catch (_: Throwable) {
            0L
        } finally {
            try { tempFile.delete() } catch (_: Throwable) {}
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
