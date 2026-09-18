package com.veilframe.app.media

/**
 * Standardized result contract for media operations.
 */
data class CompressionResult(
    val success: Boolean,
    val outputPath: String,
    val sizeBytes: Long,
    val savingsPercent: Double = 0.0,
    val duration: Double = 0.0,
    val error: String? = null
)

/**
 * Encapsulates statistics comparing before and after media operations.
 */
data class CompressionStats(
    val originalBytes: Long,
    val outputBytes: Long,
    val durationMs: Long
) {
    val savedBytes: Long
        get() = (originalBytes - outputBytes).coerceAtLeast(0L)

    val savingsPercent: Int
        get() = if (originalBytes > 0) {
            (100.0 - (outputBytes.toDouble() / originalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
        } else 0
}
