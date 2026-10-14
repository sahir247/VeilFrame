package com.veilframe.app.upscale.inference

import java.io.File

/**
 * Verified resource admission planner and memory estimator for NCNN super-resolution (P0.2).
 *
 * Replaces arbitrary single-pixel admission caps with checked, overflow-safe arithmetic:
 * - Checks source dimensions: [srcW], [srcH] <= [MAX_SOURCE_DIMENSION] (16384 px)
 * - Checks output dimensions: [outW], [outH] <= [MAX_OUTPUT_DIMENSION] (32768 px)
 * - Checks total output pixels <= [MAX_SAFE_TOTAL_PIXELS] (100,000,000 px)
 * - Bounded band memory: derives optimal tile height so host band buffer <= [MAX_BAND_MEMORY_BYTES] (64 MiB)
 * - Checks available cache storage space against estimated output file size
 */
object UpscaleResourceEstimator {
    const val MAX_SOURCE_DIMENSION = 16384L
    const val MAX_OUTPUT_DIMENSION = 32768L
    const val MAX_SAFE_TOTAL_PIXELS = 100_000_000L
    const val MAX_BAND_MEMORY_BYTES = 64L * 1024L * 1024L // 64 MiB host band limit
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
        val estimatedDiskBytes: Long
    )

    fun estimateAndPlan(
        srcW: Int,
        srcH: Int,
        scale: Int,
        channels: Int,
        requestedTileSize: Int = DEFAULT_TILE_SIZE,
        cacheDir: File? = null,
        maxBandBytes: Long = MAX_BAND_MEMORY_BYTES
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

        // Adaptive tile size derivation to enforce MAX_BAND_MEMORY_BYTES
        // Band memory equation: M_band = (srcW * scale) * (tileSize * scale) * channels
        val chosenTile = requestedTileSize.coerceIn(MIN_TILE_SIZE, MAX_TILE_SIZE)
        val candidateTileSizes = listOf(chosenTile, 160, 128, 96, 64).distinct().sortedDescending()
        var validTile: Int? = null
        var validBandBytes = 0L

        for (candidate in candidateTileSizes) {
            val bandH = Math.multiplyExact(candidate.toLong(), scale.toLong())
            val bandBytes = Math.multiplyExact(Math.multiplyExact(outWLong, bandH), channels.toLong())
            if (bandBytes <= maxBandBytes) {
                validTile = candidate
                validBandBytes = bandBytes
                break
            }
        }

        if (validTile == null) {
            // Even at minimum tile size (64 px), single horizontal band exceeds maxBandBytes
            val minBandBytes = Math.multiplyExact(Math.multiplyExact(outWLong, (MIN_TILE_SIZE * scale).toLong()), channels.toLong())
            throw ResourceLimitExceededException(
                "Image width (${srcW} px -> ${outWLong} px upscaled) requires at least " +
                    "${minBandBytes / (1024 * 1024)} MiB per horizontal band, exceeding the safe limit of " +
                    "${maxBandBytes / (1024 * 1024)} MiB. Reduce source width or scale factor."
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
            estimatedDiskBytes = estimatedDiskBytes
        )
    }
}
