package com.veilframe.app.upscale.inference

import java.io.File

/**
 * Verified resource admission planner and memory estimator for NCNN super-resolution (P0.2).
 *
 * Replaces single-pixel admission caps with checked, overflow-safe arithmetic bounding the entire working set:
 * - Checks source dimensions: [srcW], [srcH] <= [MAX_SOURCE_DIMENSION] (16384 px)
 * - Checks output dimensions: [outW], [outH] <= [MAX_OUTPUT_DIMENSION] (32768 px)
 * - Checks total output pixels <= [MAX_SAFE_TOTAL_PIXELS] (100,000,000 px)
 * - Bounded band memory: derives optimal tile height so host band buffer <= [MAX_BAND_MEMORY_BYTES] (64 MiB)
 * - Bounded total host working set: bounds host band + input strip + strip bitmap + scanline <= [MAX_HOST_WORKING_BYTES] (96 MiB)
 * - Estimates Vulkan output band (fp32) and model activations working set
 * - Checks available cache storage space against estimated output file size
 */
object UpscaleResourceEstimator {
    const val MAX_SOURCE_DIMENSION = 16384L
    const val MAX_OUTPUT_DIMENSION = 32768L
    const val MAX_SAFE_TOTAL_PIXELS = 100_000_000L
    const val MAX_BAND_MEMORY_BYTES = 64L * 1024L * 1024L // 64 MiB host band limit
    const val MAX_HOST_WORKING_BYTES = 96L * 1024L * 1024L // 96 MiB total host working memory
    const val MAX_TOTAL_WORKING_BYTES = 384L * 1024L * 1024L // 384 MiB system working memory including Vulkan
    const val MIN_TILE_SIZE = 64
    const val MAX_TILE_SIZE = 384
    const val DEFAULT_TILE_SIZE = 160
    const val DEFAULT_PREPADDING = 10

    data class ResourcePlan(
        val srcW: Int,
        val srcH: Int,
        val outW: Int,
        val outH: Int,
        val scale: Int,
        val channels: Int,
        val tileSize: Int,
        val prepadding: Int,
        val estimatedBandBytes: Long,
        val estimatedInputStripBytes: Long,
        val estimatedHostWorkingBytes: Long,
        val estimatedVulkanBandBytes: Long,
        val estimatedTotalWorkingBytes: Long,
        val estimatedDiskBytes: Long
    )

    fun estimateAndPlan(
        srcW: Int,
        srcH: Int,
        scale: Int,
        channels: Int,
        requestedTileSize: Int = DEFAULT_TILE_SIZE,
        cacheDir: File? = null,
        maxBandBytes: Long = MAX_BAND_MEMORY_BYTES,
        maxHostWorkingBytes: Long = MAX_HOST_WORKING_BYTES
    ): ResourcePlan {
        if (srcW <= 0 || srcH <= 0) {
            throw ResourceLimitExceededException("Invalid source dimensions: ${srcW}x${srcH}")
        }
        if (scale <= 0) {
            throw ResourceLimitExceededException("Invalid scale factor: $scale")
        }
        if (channels != 3 && channels != 4) {
            throw ResourceLimitExceededException("Unsupported channel count: $channels (must be 3 or 4)")
        }

        val srcWLong = srcW.toLong()
        val srcHLong = srcH.toLong()
        if (srcWLong > MAX_SOURCE_DIMENSION || srcHLong > MAX_SOURCE_DIMENSION) {
            throw ResourceLimitExceededException(
                "Source dimensions (${srcW}x${srcH}) exceed maximum supported dimension ($MAX_SOURCE_DIMENSION px)."
            )
        }

        val outWLong = Math.multiplyExact(srcWLong, scale.toLong())
        val outHLong = Math.multiplyExact(srcHLong, scale.toLong())
        if (outWLong > MAX_OUTPUT_DIMENSION || outHLong > MAX_OUTPUT_DIMENSION) {
            throw ResourceLimitExceededException(
                "Upscaled dimensions (${outWLong}x${outHLong}) exceed maximum safe output dimension ($MAX_OUTPUT_DIMENSION px)."
            )
        }

        val totalPixels = Math.multiplyExact(outWLong, outHLong)
        if (totalPixels > MAX_SAFE_TOTAL_PIXELS) {
            throw ResourceLimitExceededException(
                "Upscaled image size ($totalPixels pixels) exceeds maximum safe pixel budget ($MAX_SAFE_TOTAL_PIXELS pixels)."
            )
        }

        // Adaptive tile size derivation to enforce both MAX_BAND_MEMORY_BYTES and MAX_HOST_WORKING_BYTES
        val chosenTile = requestedTileSize.coerceIn(MIN_TILE_SIZE, MAX_TILE_SIZE)
        val candidateTileSizes = listOf(chosenTile, 160, 128, 96, 64).distinct().sortedDescending()
        var validTile: Int? = null
        var validBandBytes = 0L
        var validStripBytes = 0L
        var validHostWorkingBytes = 0L
        var validVulkanBandBytes = 0L
        var validTotalWorkingBytes = 0L

        for (candidate in candidateTileSizes) {
            val bandH = Math.multiplyExact(candidate.toLong(), scale.toLong())
            val bandBytes = Math.multiplyExact(Math.multiplyExact(outWLong, bandH), channels.toLong())

            // Input strip height includes prepadding on top and bottom
            val stripH = candidate.toLong() + (2L * DEFAULT_PREPADDING)
            val stripBytes = Math.multiplyExact(Math.multiplyExact(srcWLong, stripH), channels.toLong())
            // Bitmap allocated during region decoding (ARGB_8888 = 4 bytes per pixel)
            val stripBitmapBytes = Math.multiplyExact(Math.multiplyExact(srcWLong, stripH), 4L)
            // PNG scanline buffer in writer
            val scanlineBytes = Math.multiplyExact(outWLong, channels.toLong())

            val hostWorkingBytes = bandBytes + stripBytes + stripBitmapBytes + scanlineBytes

            // Vulkan output band: fp32 floats = 4 bytes per channel
            val vulkanBandBytes = Math.multiplyExact(Math.multiplyExact(outWLong, bandH), (channels * 4).toLong())
            // Intermediate tile buffer and activations headroom
            val tileWorkBytes = Math.multiplyExact(Math.multiplyExact(stripH * scale, stripH * scale), (channels * 4 * 2).toLong())
            val totalWorkingBytes = hostWorkingBytes + vulkanBandBytes + tileWorkBytes

            if (bandBytes <= maxBandBytes && hostWorkingBytes <= maxHostWorkingBytes) {
                validTile = candidate
                validBandBytes = bandBytes
                validStripBytes = stripBytes
                validHostWorkingBytes = hostWorkingBytes
                validVulkanBandBytes = vulkanBandBytes
                validTotalWorkingBytes = totalWorkingBytes
                break
            }
        }

        if (validTile == null) {
            val minBandH = (MIN_TILE_SIZE * scale).toLong()
            val minBandBytes = Math.multiplyExact(Math.multiplyExact(outWLong, minBandH), channels.toLong())
            val minStripH = MIN_TILE_SIZE.toLong() + (2L * DEFAULT_PREPADDING)
            val minStripBytes = Math.multiplyExact(Math.multiplyExact(srcWLong, minStripH), channels.toLong())
            val minHostWorkingBytes = minBandBytes + minStripBytes + (srcWLong * minStripH * 4L) + (outWLong * channels.toLong())

            throw ResourceLimitExceededException(
                "Image width (${srcW} px -> ${outWLong} px upscaled) requires ${minHostWorkingBytes / (1024 * 1024)} MiB " +
                    "host memory per strip/band, exceeding the safe host limit of ${maxHostWorkingBytes / (1024 * 1024)} MiB. " +
                    "Reduce source width or scale factor."
            )
        }

        // Estimated output PNG disk footprint (approx. 50% compression of raw bytes + 50 MiB safety headroom)
        val rawOutputBytes = Math.multiplyExact(totalPixels, channels.toLong())
        val estimatedDiskBytes = (rawOutputBytes / 2).coerceAtLeast(1024 * 1024L)
        val requiredFreeSpace = estimatedDiskBytes + (50L * 1024L * 1024L) // 50 MiB margin

        if (cacheDir != null && cacheDir.exists()) {
            val usable = cacheDir.usableSpace
            if (usable in 1 until requiredFreeSpace) {
                throw InsufficientDiskSpaceException(
                    "Insufficient storage space: $usable bytes available on cache volume, but at least " +
                        "$requiredFreeSpace bytes are required for streaming upscaler output."
                )
            }
        }

        return ResourcePlan(
            srcW = srcW,
            srcH = srcH,
            outW = outWLong.toInt(),
            outH = outHLong.toInt(),
            scale = scale,
            channels = channels,
            tileSize = validTile,
            prepadding = DEFAULT_PREPADDING,
            estimatedBandBytes = validBandBytes,
            estimatedInputStripBytes = validStripBytes,
            estimatedHostWorkingBytes = validHostWorkingBytes,
            estimatedVulkanBandBytes = validVulkanBandBytes,
            estimatedTotalWorkingBytes = validTotalWorkingBytes,
            estimatedDiskBytes = estimatedDiskBytes
        )
    }
}
