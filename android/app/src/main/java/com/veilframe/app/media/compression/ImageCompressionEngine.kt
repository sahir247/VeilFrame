package com.veilframe.app.media.compression

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.veilframe.app.media.CompressionResult
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.ImageOutputConfig
import com.veilframe.app.media.metadata.ImageMetadataWriter
import com.veilframe.app.privacy.ImageMetadataSanitizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Dedicated compression engine for Image Studio.
 * Handles:
 * - Quality-based compression (JPEG, PNG, WEBP)
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

            val compressFormat = when (outputConfig.format.uppercase()) {
                "PNG" -> Bitmap.CompressFormat.PNG
                "WEBP" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }

            val targetSizeKb = outputConfig.targetSizeKb ?: 250
            val isTargetSizeMode = outputConfig.compressionMode == "target_size" &&
                    compressFormat != Bitmap.CompressFormat.PNG &&
                    targetSizeKb > 0

            var workingBmp = processedBitmap

            if (isTargetSizeMode) {
                val targetBytes = targetSizeKb.toLong() * 1024L
                var bestBytesUnderTarget: ByteArray? = null
                var low = 5
                var high = 95

                // Binary search for optimal quality level
                for (iter in 0 until 8) {
                    val mid = (low + high) / 2
                    val stream = ByteArrayOutputStream()
                    workingBmp.compress(compressFormat, mid, stream)
                    val data = stream.toByteArray()

                    if (data.size <= targetBytes) {
                        bestBytesUnderTarget = data
                        low = mid + 1 // try higher quality
                    } else {
                        high = mid - 1 // reduce quality
                    }
                    if (low > high) break
                }

                // If even minimum quality exceeds target bytes, downscale dimensions
                if (bestBytesUnderTarget == null || bestBytesUnderTarget.size > targetBytes) {
                    var scale = 0.85f
                    for (downscaleAttempt in 0 until 5) {
                        val newW = (workingBmp.width * scale).toInt().coerceAtLeast(32)
                        val newH = (workingBmp.height * scale).toInt().coerceAtLeast(32)
                        val downscaled = try {
                            Bitmap.createScaledBitmap(workingBmp, newW, newH, true)
                        } catch (_: Exception) { null }

                        if (downscaled != null) {
                            if (workingBmp != processedBitmap) workingBmp.recycle()
                            workingBmp = downscaled

                            val stream = ByteArrayOutputStream()
                            workingBmp.compress(compressFormat, 25, stream)
                            val data = stream.toByteArray()
                            if (data.size <= targetBytes) {
                                bestBytesUnderTarget = data
                                break
                            }
                        }
                        scale -= 0.15f
                    }
                }

                if (bestBytesUnderTarget != null) {
                    FileOutputStream(outFile).use { fos ->
                        fos.write(bestBytesUnderTarget)
                    }
                } else {
                    val stream = ByteArrayOutputStream()
                    workingBmp.compress(compressFormat, 15, stream)
                    val minBytes = stream.size().toLong()
                    if (workingBmp != processedBitmap) {
                        try { workingBmp.recycle() } catch (_: Exception) {}
                    }
                    val formattedMin = if (minBytes < 1024) "$minBytes B" else if (minBytes < 1024 * 1024) "${minBytes / 1024} KB" else String.format(Locale.US, "%.1f MB", minBytes / (1024.0 * 1024.0))
                    return CompressionResult(
                        success = false,
                        outputPath = outFile.absolutePath,
                        sizeBytes = minBytes,
                        error = "Target size ${targetSizeKb} KB could not be achieved (smallest possible was $formattedMin). Try selecting a larger target size or resizing image dimensions."
                    )
                }
            } else {
                FileOutputStream(outFile).use { fos ->
                    workingBmp.compress(compressFormat, outputConfig.quality, fos)
                }
            }

            if (workingBmp != processedBitmap) {
                try { workingBmp.recycle() } catch (_: Exception) {}
            }

            // Metadata handling
            if (editState.stripExif && outFile.exists()) {
                ImageMetadataSanitizer.stripExifLossless(outFile, outFile)
            } else if (!editState.stripExif && outFile.exists() && compressFormat == Bitmap.CompressFormat.JPEG) {
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
                    error = errMsg
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed: ${e.message}", e)
            CompressionResult(
                success = false,
                outputPath = outFile.absolutePath,
                sizeBytes = 0L,
                error = e.message ?: "Unknown compression error"
            )
        }
    }

    /**
     * Computes empirical size estimate for display in UI.
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
            return (targetKb * 1024L).coerceAtMost(originalBytes.coerceAtLeast(1024L))
        }

        if (outputConfig.format.equals("PNG", ignoreCase = true)) {
            val scaleFactor = (targetWidth.toDouble() * targetHeight.toDouble()) /
                    (origWidth.toDouble() * origHeight.toDouble()).coerceAtLeast(1.0)
            return (originalBytes * scaleFactor).toLong().coerceAtLeast(1024L)
        }

        // Empirical model for JPEG/WEBP
        val dimRatio = (targetWidth.toDouble() * targetHeight.toDouble()) /
                (origWidth.toDouble() * origHeight.toDouble()).coerceAtLeast(1.0)
        val qRatio = Math.pow(outputConfig.quality.toDouble() / 100.0, 1.3)
        val est = (originalBytes.toDouble() * dimRatio * qRatio * 0.7).toLong()
        return est.coerceIn(1024L, (originalBytes * 1.5).toLong())
    }
}
