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
    val baseMaxRateKbps: Int,
    val maxLongSide: Int = if (width > height) width else height,
    val maxShortSide: Int = if (width < height) width else height
) {
    HD_720P("HD 720p", 720, 1280, 1900, maxLongSide = 1280, maxShortSide = 720),
    FHD_1080P("FHD 1080p", 1080, 1920, 3800, maxLongSide = 1920, maxShortSide = 1080);

    /**
     * Deterministically calculates output pixel dimensions bounded by HD/FHD targets.
     * Preserves Display Aspect Ratio (DAR) in "Original" mode without crop or stretching.
     * In explicit aspect mode, crops to target aspect ratio then scales inside bounds.
     * Always returns even integers required by H.264 / yuv420p encoders.
     */
    fun calculateBoundedDimensions(
        srcWidth: Int,
        srcHeight: Int,
        aspect: String = "Original"
    ): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return Pair(width, height)

        val isOriginal = aspect.equals("Original", ignoreCase = true) || aspect.isBlank()

        if (isOriginal) {
            val isPortrait = srcHeight > srcWidth
            val isSquare = srcHeight == srcWidth

            val boundW = if (isSquare) maxShortSide else if (isPortrait) maxShortSide else maxLongSide
            val boundH = if (isSquare) maxShortSide else if (isPortrait) maxLongSide else maxShortSide

            val scaleFactor = Math.min(boundW.toDouble() / srcWidth, boundH.toDouble() / srcHeight)
            val rawTargetW = Math.round(srcWidth * scaleFactor).toInt()
            val rawTargetH = Math.round(srcHeight * scaleFactor).toInt()

            val evenW = Math.max(2, (rawTargetW / 2) * 2)
            val evenH = Math.max(2, (rawTargetH / 2) * 2)
            return Pair(evenW, evenH)
        } else {
            // Explicit aspect ratio target (e.g., "9:16", "16:9", "1:1", "4:5", "4:3")
            val targetRatio: Double = when (aspect) {
                "9:16" -> 9.0 / 16.0
                "16:9" -> 16.0 / 9.0
                "1:1" -> 1.0
                "4:5" -> 4.0 / 5.0
                "4:3" -> 4.0 / 3.0
                else -> {
                    val parts = aspect.split(":")
                    if (parts.size == 2) {
                        val num = parts[0].toDoubleOrNull() ?: 1.0
                        val den = parts[1].toDoubleOrNull() ?: 1.0
                        if (den > 0) num / den else 1.0
                    } else {
                        srcWidth.toDouble() / srcHeight
                    }
                }
            }

            val boundW: Int
            val boundH: Int
            if (targetRatio < 1.0) {
                // Portrait target
                boundW = maxShortSide
                boundH = maxLongSide
            } else if (targetRatio > 1.0) {
                // Landscape target
                boundW = maxLongSide
                boundH = maxShortSide
            } else {
                // Square target (1:1)
                boundW = maxShortSide
                boundH = maxShortSide
            }

            val rawTargetW: Int
            val rawTargetH: Int
            if (boundW / targetRatio <= boundH) {
                rawTargetW = boundW
                rawTargetH = Math.round(boundW / targetRatio).toInt()
            } else {
                rawTargetH = boundH
                rawTargetW = Math.round(boundH * targetRatio).toInt()
            }

            val evenW = Math.max(2, (rawTargetW / 2) * 2)
            val evenH = Math.max(2, (rawTargetH / 2) * 2)
            return Pair(evenW, evenH)
        }
    }

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
