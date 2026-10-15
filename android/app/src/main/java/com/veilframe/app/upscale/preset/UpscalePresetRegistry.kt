package com.veilframe.app.upscale.preset

/**
 * Built-in presets for VeilFrame Image Upscaler.
 *
 * Neural presets now select native NCNN model IDs. The algorithmic Standard preset
 * intentionally remains Lanczos and is not an AI fallback.
 */
object UpscalePresetRegistry {

    val PHOTO = UpscalePreset(
        id = "photo",
        name = "Photo",
        description = "General-purpose photographic restoration using native NCNN Real-ESRGAN.",
        recommendedModelId = "ncnn-realesrgan-x4plus",
        defaultScale = 4
    )

    val PHOTO_QUALITY = UpscalePreset(
        id = "photo_quality",
        name = "Photo (High Quality)",
        description = "General-purpose photo restoration using the NCNN Real-ESRGAN x4plus model.",
        recommendedModelId = "ncnn-realesrgan-x4plus",
        defaultScale = 4
    )

    val ANIME = UpscalePreset(
        id = "anime",
        name = "Anime",
        description = "Compact NCNN Real-ESRGAN AnimeVideoV3 model for anime and animation frames.",
        recommendedModelId = "ncnn-realesr-animevideov3-x4",
        defaultScale = 4
    )

    val ILLUSTRATION = UpscalePreset(
        id = "illustration",
        name = "Illustration & Art",
        description = "Native NCNN AnimeVideoV3 model for clean digital illustrations and line art.",
        recommendedModelId = "ncnn-realesr-animevideov3-x4",
        defaultScale = 4
    )

    val FAST = UpscalePreset(
        id = "fast",
        name = "Fast (Anime AI)",
        description = "Compact 2× neural upscaling for anime, flat-color artwork, and animation frames.",
        recommendedModelId = "ncnn-realesr-animevideov3-x2",
        defaultScale = 2
    )

    val STANDARD = UpscalePreset(
        id = "standard",
        name = "Standard (Lanczos)",
        description = "Offline mathematical sinc filter without neural inference.",
        recommendedModelId = "lanczos-3",
        defaultScale = 2
    )

    val CUSTOM = UpscalePreset(
        id = "custom",
        name = "Custom",
        description = "Manual configuration with native NCNN Real-ESRGAN and scaling controls.",
        recommendedModelId = "ncnn-realesrgan-x4plus",
        defaultScale = 4
    )

    val ALL_PRESETS: List<UpscalePreset> = listOf(
        PHOTO,
        PHOTO_QUALITY,
        ANIME,
        ILLUSTRATION,
        FAST,
        STANDARD,
        CUSTOM
    )

    fun getPresetById(id: String): UpscalePreset? {
        return ALL_PRESETS.find { it.id.equals(id, ignoreCase = true) }
    }
}
