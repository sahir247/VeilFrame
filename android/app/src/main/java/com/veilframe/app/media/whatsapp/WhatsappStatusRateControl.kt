package com.veilframe.app.media.whatsapp

/**
 * WhatsApp Status Rate Control.
 * Pure mathematical functions for base maxrate and duration-dependent buffer size.
 *
 * CRITICAL RULE:
 * -maxrate = fixed base bitrate (1900k for HD, 3800k for FHD)
 * -bufsize = duration-derived bitrate value
 * The duration formula calculates bufsize ONLY, never maxrate!
 */
object WhatsappStatusRateControl {

    /**
     * Returns the verified base maxrate in kbps for the given Status resolution.
     */
    fun baseMaxRate(resolution: WhatsappStatusResolution): Int {
        return resolution.baseMaxRateKbps
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
