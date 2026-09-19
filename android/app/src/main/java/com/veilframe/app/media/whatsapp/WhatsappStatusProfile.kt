package com.veilframe.app.media.whatsapp

/**
 * WhatsApp Status Resolution options.
 * Everything in this pipeline targets WhatsApp Status.
 * HD and FHD are resolution choices within the Status pipeline, not separate destinations.
 */
enum class WhatsappStatusResolution(
    val displayName: String,
    val width: Int,
    val height: Int,
    val baseMaxRateKbps: Int
) {
    HD_720P("HD 720p", 720, 1280, 1900),
    FHD_1080P("FHD 1080p", 1080, 1920, 3800);

    companion object {
        fun fromDisplayName(name: String?): WhatsappStatusResolution {
            return when {
                name == null -> HD_720P
                name.contains("FHD", ignoreCase = true) || name.contains("1080", ignoreCase = true) -> FHD_1080P
                else -> HD_720P
            }
        }
    }
}

/**
 * Common WhatsApp Status encoder specification constants.
 * Strictly adheres to reverse-engineered PureStatus findings.
 */
object WhatsappStatusConstants {
    const val CRF = 23
    const val FPS = 29.97
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_BITRATE_KBPS = 128
    const val AUDIO_BITRATE_STR = "128k"
    const val PIXEL_FORMAT = "yuv420p"
    const val CODEC = "libx264"
    const val PHOTO_STATUS_DURATION_SEC = 5.0
    const val PHOTO_STATUS_MAXRATE_KBPS = 1600
    const val PHOTO_STATUS_BUFSIZE_KBPS = 1600
}
