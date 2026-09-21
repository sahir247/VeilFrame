package com.veilframe.app.media

/**
 * Encapsulates size policy constraints for media compression and export pipelines.
 */
sealed class OutputSizePolicy {
    /** No explicit file size constraint */
    object Unconstrained : OutputSizePolicy()

    /** Hard ceiling that must not be exceeded (e.g. WhatsApp Status 16 MiB) */
    data class HardCeiling(val maxBytes: Long) : OutputSizePolicy()

    /** Target size with an allowable tolerance margin */
    data class TargetSize(val targetBytes: Long, val toleranceMargin: Double = 0.05) : OutputSizePolicy()

    companion object {
        /** Hard 16 MiB ceiling for WhatsApp Status */
        val WHATSAPP_STATUS_16MB = HardCeiling(16L * 1024L * 1024L)

        /** Standard chat attachment presets */
        val DISCORD_25MB = HardCeiling((24.8 * 1024 * 1024).toLong())
        val DISCORD_NITRO_50MB = HardCeiling((49.5 * 1024 * 1024).toLong())
        val EMAIL_8MB = HardCeiling((7.9 * 1024 * 1024).toLong())
    }
}
