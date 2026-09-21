package com.veilframe.app.media.whatsapp

/**
 * WhatsApp Status Rate Control.
 * Pure mathematical functions for base maxrate, duration-dependent buffer size,
 * and 16 MiB hard ceiling enforcement.
 *
 * CRITICAL RULE:
 * -maxrate = fixed base bitrate (1900k for HD, 3800k for FHD)
 * -bufsize = duration-derived bitrate value
 * The duration formula calculates bufsize ONLY, never maxrate!
 *
 * SIZE POLICY: WhatsApp Status has a hard 16 MiB limit.
 * effectiveMaxRate = min(resolution.baseMaxRate, sizeCeilingKbps)
 */
object WhatsappStatusRateControl {

    /** Hard ceiling for WhatsApp Status file size in bytes (16 MiB) */
    const val WHATSAPP_STATUS_SIZE_CEILING_BYTES = 16L * 1024L * 1024L

    /** Safety target used for retry calculation (15.2 MiB leaves margin) */
    private const val WHATSAPP_STATUS_SAFE_TARGET_BYTES = 15.2 * 1024.0 * 1024.0

    /** Container overhead reservation in bits */
    private const val CONTAINER_RESERVE_BITS = 256L * 1024L * 8L

    /** Maximum retry attempts for size-constrained encoding */
    const val MAX_SIZE_RETRIES = 2

    /**
     * Returns the verified base maxrate in kbps for the given Status resolution.
     */
    fun baseMaxRate(resolution: WhatsappStatusResolution): Int {
        return resolution.baseMaxRateKbps
    }

    /**
     * Calculates the maximum video bitrate (kbps) that would keep the file under 16 MiB,
     * given the expected duration and audio bitrate.
     *
     * Formula:
     * sizeCeilingKbps = ((16 MiB * 8) - (audioKbps * 1000 * durationSec) - containerReserveBits) / (durationSec * 1000)
     */
    fun sizeCeilingKbps(durationSec: Double, audioKbps: Int = WhatsappStatusConstants.AUDIO_BITRATE_KBPS): Int {
        val safeDur = if (durationSec.isNaN() || durationSec <= 0.1) 10.0 else durationSec
        val totalBudgetBits = WHATSAPP_STATUS_SIZE_CEILING_BYTES * 8L
        val audioBits = audioKbps.toLong() * 1000L * safeDur.toLong()
        val availableVideoBits = totalBudgetBits - audioBits - CONTAINER_RESERVE_BITS
        return if (availableVideoBits > 0) {
            (availableVideoBits / (safeDur * 1000.0)).toInt().coerceAtLeast(100)
        } else {
            100 // Minimum fallback
        }
    }

    /**
     * Returns the effective max rate, clamped to both the resolution base rate
     * and the 16 MiB size ceiling.
     */
    fun effectiveMaxRate(
        resolution: WhatsappStatusResolution,
        durationSec: Double,
        audioKbps: Int = WhatsappStatusConstants.AUDIO_BITRATE_KBPS
    ): Int {
        val baseRate = baseMaxRate(resolution)
        val ceiling = sizeCeilingKbps(durationSec, audioKbps)
        return minOf(baseRate, ceiling)
    }

    /**
     * Calculates a retry bitrate after an oversized output.
     * newRate = currentRate * (safeTarget / actualSize) * safetyFactor
     */
    fun retryRate(currentRateKbps: Int, actualSizeBytes: Long, safetyFactor: Double = 0.92): Int {
        if (actualSizeBytes <= 0) return currentRateKbps
        val ratio = WHATSAPP_STATUS_SAFE_TARGET_BYTES / actualSizeBytes.toDouble()
        return (currentRateKbps * ratio * safetyFactor).toInt().coerceAtLeast(100)
    }

    /**
     * Checks whether the given file size exceeds the 16 MiB ceiling.
     */
    fun exceedsSizeCeiling(sizeBytes: Long): Boolean {
        return sizeBytes > WHATSAPP_STATUS_SIZE_CEILING_BYTES
    }

    /**
     * Calculates the duration-dependent buffer size in kbps.
     *
     * Exact duration bucket comparisons:
     * duration < 6.0s        -> base / 2
     * 6.0s <= duration < 11.0s -> floor(base / 1.5)
     * 11.0s <= duration < 16.0s -> base
     * duration >= 16.0s       -> floor(base * 1.5)
     *
     * Boundary guarantees:
     * 5.999s -> bucket 1 (<6s)
     * 6.000s -> bucket 2 (6-<11s)
     * 10.999s -> bucket 2 (6-<11s)
     * 11.000s -> bucket 3 (11-<16s)
     * 15.999s -> bucket 3 (11-<16s)
     * 16.000s -> bucket 4 (>=16s)
     */
    fun calculateBufSize(baseKbps: Int, durationSec: Double): Int {
        val safeDur = if (durationSec.isNaN() || durationSec <= 0.0) 0.0 else durationSec
        return when {
            safeDur < 6.0 -> baseKbps / 2
            safeDur < 11.0 -> (baseKbps / 1.5).toInt()
            safeDur < 16.0 -> baseKbps
            else -> (baseKbps * 1.5).toInt()
        }
    }
}
