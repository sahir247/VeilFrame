package com.veilframe.app.media.preview

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.widget.ImageView

data class VideoColorProfile(
    val id: String,
    val name: String,
    val description: String,
    val category: String
)

/**
 * High-performance color grading and profiling helper for video preview and FFmpeg export.
 * Provides instantaneous 60fps hardware-accelerated preview rendering via Android ColorMatrix,
 * alongside exact matching FFmpeg filter strings.
 */
object VideoColorFilterHelper {

    val PROFILES = listOf(
        VideoColorProfile("Original", "Original", "No color adjustments applied", "Natural"),
        VideoColorProfile("Vivid", "Vivid", "Boosted vibrant saturation & lively contrast", "Vibrant"),
        VideoColorProfile("Cinematic Warm", "Cinematic Warm", "Warm golden hues with soft filmic contrast", "Cinematic"),
        VideoColorProfile("Cool Blue", "Cool Blue", "Nordic cool shadows with crisp cyan highlights", "Cinematic"),
        VideoColorProfile("Teal & Orange", "Teal & Orange", "Hollywood blockbuster color separation", "Cinematic"),
        VideoColorProfile("B&W Dramatic", "B&W Dramatic", "High-contrast monochrome with crushed blacks", "Monochrome"),
        VideoColorProfile("B&W Classic", "B&W Classic", "Neutral balanced grayscale film look", "Monochrome"),
        VideoColorProfile("Sepia", "Sepia", "Antique brown-warm vintage photograph tint", "Vintage"),
        VideoColorProfile("Cyberpunk", "Cyberpunk", "Electric neon magenta & deep cyan highlights", "Stylized"),
        VideoColorProfile("Moody Film", "Moody Film", "Lifted matte blacks and muted film tones", "Vintage"),
        VideoColorProfile("Bleach Bypass", "Bleach Bypass", "Silver-retention desaturated harsh contrast", "Stylized"),
        VideoColorProfile("Retro 90s", "Retro 90s", "Nostalgic home video VHS color saturation", "Vintage"),
        VideoColorProfile("Sunset Glow", "Sunset Glow", "Rich crimson & amber golden hour wash", "Vibrant"),
        VideoColorProfile("Forest Green", "Forest Green", "Organic verdant foliage with rich soil contrast", "Natural"),
        VideoColorProfile("HDR Punch", "HDR Punch", "Expanded perceptual dynamic range and sharpness", "Vibrant")
    )

    fun getColorMatrix(profileId: String): ColorMatrix? {
        val clean = profileId.trim().lowercase()
        if (clean == "original" || clean == "none" || clean.isEmpty()) return null

        return when (clean) {
            "vivid" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.35f)
                val contrast = ColorMatrix(floatArrayOf(
                    1.15f, 0f, 0f, 0f, -15f,
                    0f, 1.15f, 0f, 0f, -15f,
                    0f, 0f, 1.15f, 0f, -15f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(contrast)
                cm
            }
            "cinematic warm" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.10f)
                val tint = ColorMatrix(floatArrayOf(
                    1.12f, 0f, 0f, 0f, 15f,
                    0f, 1.05f, 0f, 0f, 6f,
                    0f, 0f, 0.90f, 0f, -18f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(tint)
                cm
            }
            "cool blue" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.05f)
                val tint = ColorMatrix(floatArrayOf(
                    0.92f, 0f, 0f, 0f, -12f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 1.18f, 0f, 22f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(tint)
                cm
            }
            "teal & orange", "teal and orange" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.20f)
                val tint = ColorMatrix(floatArrayOf(
                    1.18f, 0f, 0f, 0f, 18f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.88f, 0f, 15f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(tint)
                cm
            }
            "b&w dramatic", "bw dramatic" -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val contrast = ColorMatrix(floatArrayOf(
                    1.35f, 0f, 0f, 0f, -30f,
                    0f, 1.35f, 0f, 0f, -30f,
                    0f, 0f, 1.35f, 0f, -30f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(contrast)
                cm
            }
            "b&w classic", "bw classic" -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val contrast = ColorMatrix(floatArrayOf(
                    1.10f, 0f, 0f, 0f, -10f,
                    0f, 1.10f, 0f, 0f, -10f,
                    0f, 0f, 1.10f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(contrast)
                cm
            }
            "sepia" -> {
                ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            "cyberpunk" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.45f)
                val neon = ColorMatrix(floatArrayOf(
                    1.25f, 0f, 0f, 0f, 25f,
                    0f, 0.95f, 0f, 0f, -10f,
                    0f, 0f, 1.35f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(neon)
                cm
            }
            "moody film" -> {
                val cm = ColorMatrix()
                cm.setSaturation(0.90f)
                val matte = ColorMatrix(floatArrayOf(
                    1.05f, 0f, 0f, 0f, 12f,
                    0f, 1.05f, 0f, 0f, 12f,
                    0f, 0f, 1.02f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(matte)
                cm
            }
            "bleach bypass" -> {
                val cm = ColorMatrix()
                cm.setSaturation(0.55f)
                val harsh = ColorMatrix(floatArrayOf(
                    1.40f, 0f, 0f, 0f, -35f,
                    0f, 1.40f, 0f, 0f, -35f,
                    0f, 0f, 1.40f, 0f, -35f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(harsh)
                cm
            }
            "retro 90s" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.25f)
                val vhs = ColorMatrix(floatArrayOf(
                    1.12f, 0f, 0f, 0f, 8f,
                    0f, 1.08f, 0f, 0f, 6f,
                    0f, 0f, 0.95f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(vhs)
                cm
            }
            "sunset glow" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.25f)
                val sunset = ColorMatrix(floatArrayOf(
                    1.28f, 0f, 0f, 0f, 25f,
                    0f, 1.08f, 0f, 0f, 8f,
                    0f, 0f, 0.85f, 0f, -25f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(sunset)
                cm
            }
            "forest green" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.15f)
                val forest = ColorMatrix(floatArrayOf(
                    0.95f, 0f, 0f, 0f, -8f,
                    0f, 1.20f, 0f, 0f, 18f,
                    0f, 0f, 0.95f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(forest)
                cm
            }
            "hdr punch" -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.20f)
                val punch = ColorMatrix(floatArrayOf(
                    1.22f, 0f, 0f, 0f, -18f,
                    0f, 1.22f, 0f, 0f, -18f,
                    0f, 0f, 1.22f, 0f, -18f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(punch)
                cm
            }
            else -> null
        }
    }

    fun applyColorProfileToView(view: View, profileId: String) {
        val cm = getColorMatrix(profileId)
        if (cm == null) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    fun applyColorProfileToImageView(imageView: ImageView, profileId: String) {
        val cm = getColorMatrix(profileId)
        if (cm == null) {
            imageView.colorFilter = null
        } else {
            imageView.colorFilter = ColorMatrixColorFilter(cm)
        }
    }

    fun getFFmpegFilter(profileId: String): String? {
        val clean = profileId.trim().lowercase()
        if (clean == "original" || clean == "none" || clean.isEmpty()) return null

        return when (clean) {
            "vivid" -> "eq=contrast=1.15:saturation=1.35"
            "cinematic warm" -> "eq=contrast=1.10:saturation=1.10,colorbalance=rs=0.08:gs=0.02:bs=-0.08"
            "cool blue" -> "eq=contrast=1.08:saturation=1.05,colorbalance=rs=-0.06:bs=0.10:bh=0.05"
            "teal & orange", "teal and orange" -> "eq=contrast=1.15:saturation=1.20,colorbalance=rs=0.12:gs=0.02:bs=-0.12:rm=-0.05:gm=0.02:bm=0.10"
            "b&w dramatic", "bw dramatic" -> "hue=s=0,eq=contrast=1.35:brightness=-0.02"
            "b&w classic", "bw classic" -> "hue=s=0,eq=contrast=1.10"
            "sepia" -> "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
            "cyberpunk" -> "eq=contrast=1.25:saturation=1.45,colorbalance=rs=0.15:gs=-0.05:bs=0.20:rm=-0.10:bm=0.15"
            "moody film" -> "eq=contrast=1.08:saturation=0.90:brightness=0.02,curves=all='0/0.05 0.5/0.48 1/0.95'"
            "bleach bypass" -> "eq=contrast=1.40:saturation=0.55"
            "retro 90s" -> "eq=contrast=1.10:saturation=1.25:brightness=0.02,colorbalance=rs=0.06:gs=0.04:bs=-0.04"
            "sunset glow" -> "eq=contrast=1.12:saturation=1.25,colorbalance=rs=0.18:gs=0.06:bs=-0.14"
            "forest green" -> "eq=contrast=1.10:saturation=1.15,colorbalance=rs=-0.06:gs=0.12:bs=-0.04"
            "hdr punch" -> "eq=contrast=1.22:saturation=1.20:brightness=-0.02"
            else -> null
        }
    }
}
