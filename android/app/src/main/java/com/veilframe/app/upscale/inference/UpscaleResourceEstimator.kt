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
 * - Bounded estimated total working set: host buffers + Vulkan band + tile activation allowance <= [MAX_TOTAL_WORKING_BYTES]
 * - Uses checked arithmetic for every intermediate estimate
 * - Reserves for a conservative PNG size bound (not an assumed compression ratio)
 * - This is an admission estimate, not a guarantee about opaque NCNN/driver allocations
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

    /**
     * Runtime admission heuristic: cap estimated native+host work to 25% of currently
     * available memory, or 1/6 while Android reports low memory. This is intentionally
     * below availMem because the OS and unrelated processes still need headroom.
     */
    fun totalBudgetForAvailableMemory(
        availableMemoryBytes: Long,
        lowMemory: Boolean,
        maxBudgetBytes: Long = MAX_TOTAL_WORKING_BYTES
    ): Long {
        if (availableMemoryBytes <= 0L || maxBudgetBytes <= 0L) return 0L
        val pressureBudget = availableMemoryBytes / if (lowMemory) 6L else 4L
        return minOf(maxBudgetBytes, pressureBudget)
    }

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
        maxHostWorkingBytes: Long = MAX_HOST_WORKING_BYTES,
        maxTotalWorkingBytes: Long = MAX_TOTAL_WORKING_BYTES
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
        // Never silently exceed the user's requested tile size. Decrease in deterministic steps only.
        val candidateTileSizes = (listOf(chosenTile) + listOf(160, 128, 96, 64).filter { it < chosenTile }).distinct()
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

            // Callback conversion scratch: ARGB IntArray + packed source scanline. Include
            // the PNG filter byte in its scanline allocation, not just the pixel payload.
            val conversionScratchBytes = Math.addExact(
                Math.multiplyExact(srcWLong, 4L),
                Math.multiplyExact(srcWLong, channels.toLong())
            )
            val hostWorkingBytes = Math.addExact(
                Math.addExact(Math.addExact(bandBytes, stripBytes), stripBitmapBytes),
                Math.addExact(Math.addExact(scanlineBytes, 1L), conversionScratchBytes)
            )

            // NCNN Vulkan image/band working allocation proxy: fp32 bytes per output channel.
            val vulkanBandBytes = Math.multiplyExact(Math.multiplyExact(outWLong, bandH), (channels * 4).toLong())
            // Conservative tile activation allowance. NCNN and driver internals remain estimates.
            val scaledStripH = Math.multiplyExact(stripH, scale.toLong())
            val tileWorkBytes = Math.multiplyExact(
                Math.multiplyExact(Math.multiplyExact(scaledStripH, scaledStripH), channels.toLong()), 8L
            )
            val totalWorkingBytes = Math.addExact(Math.addExact(hostWorkingBytes, vulkanBandBytes), tileWorkBytes)

            if (bandBytes <= maxBandBytes &&
                hostWorkingBytes <= maxHostWorkingBytes &&
                totalWorkingBytes <= maxTotalWorkingBytes
            ) {
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
            val minStripBitmapBytes = Math.multiplyExact(Math.multiplyExact(srcWLong, minStripH), 4L)
            val minScanlineBytes = Math.addExact(Math.multiplyExact(outWLong, channels.toLong()), 1L)
            val minScratchBytes = Math.addExact(Math.multiplyExact(srcWLong, 4L), Math.multiplyExact(srcWLong, channels.toLong()))
            val minHostWorkingBytes = Math.addExact(
                Math.addExact(Math.addExact(minBandBytes, minStripBytes), minStripBitmapBytes),
                Math.addExact(minScanlineBytes, minScratchBytes)
            )

            throw ResourceLimitExceededException(
                "No supported tile fits the configured memory budgets for ${srcW}x${srcH} at ${scale}x. " +
                    "Minimum-tile host estimate is ${minHostWorkingBytes / (1024 * 1024)} MiB " +
                    "(host limit ${maxHostWorkingBytes / (1024 * 1024)} MiB); estimated total working set " +
                    "must not exceed ${maxTotalWorkingBytes / (1024 * 1024)} MiB. Reduce source width or scale factor."
            )
        }

        // PNG compression is data-dependent. Do not assume compression; budget raw pixels,
        // one filter byte per row, a conservative zlib expansion allowance, and chunk overhead.
        val rawOutputBytes = Math.multiplyExact(totalPixels, channels.toLong())
        val deflateBoundAllowance = Math.addExact(rawOutputBytes / 16L, 128L * 1024L)
        val chunkCount = Math.addExact(rawOutputBytes, 32L * 1024L - 1L) / (32L * 1024L)
        val pngChunkOverhead = Math.multiplyExact(chunkCount, 12L)
        val estimatedDiskBytes = Math.addExact(
            Math.addExact(Math.addExact(rawOutputBytes, outHLong), deflateBoundAllowance),
            pngChunkOverhead
        )
        val requiredFreeSpace = Math.addExact(estimatedDiskBytes, 50L * 1024L * 1024L)

        if (cacheDir != null) {
            val usable = if (cacheDir.exists() && cacheDir.isDirectory && cacheDir.canWrite()) cacheDir.usableSpace else 0L
            if (usable <= 0L || usable < requiredFreeSpace) {
                throw InsufficientDiskSpaceException(
                    "Insufficient or unavailable cache storage: $usable bytes available, but at least " +
                        "$requiredFreeSpace bytes are required for a conservative streaming PNG bound."
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
