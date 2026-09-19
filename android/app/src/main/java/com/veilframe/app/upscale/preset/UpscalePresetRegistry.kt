package com.veilframe.app.upscale.preset

/**
 * Built-in presets for VeilFrame Image Upscaler.
 */
object UpscalePresetRegistry {

    val PHOTO = UpscalePreset(
        id = "photo",
        name = "Photo",
        description = "Balanced natural photographic upscaling preserving real textures.",
        recommendedModelId = "realesrgan-general-2x",
        defaultScale = 2
    )

    val PHOTO_QUALITY = UpscalePreset(
        id = "photo_quality",
        name = "Photo (High Quality)",
        description = "Maximum photographic clarity and realistic texture restoration.",
        recommendedModelId = "realesrgan-general-4x",
        defaultScale = 4
    )

    val ANIME = UpscalePreset(
        id = "anime",
        name = "Anime",
        description = "Optimized for anime and manga with crisp lines and vibrant colors.",
        recommendedModelId = "realesrgan-anime-4x",
        defaultScale = 4
    )

    val ILLUSTRATION = UpscalePreset(
        id = "illustration",
        name = "Illustration & Art",
        description = "Preserves digital illustrations, graphics, and hand-drawn art.",
        recommendedModelId = "realesrgan-anime-4x",
        defaultScale = 4
    )

    val FAST = UpscalePreset(
        id = "fast",
        name = "Fast (AI)",
        description = "Fast 2× neural upscaling with low memory footprint.",
        recommendedModelId = "realesrgan-general-2x",
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
        description = "Manual configuration with custom model and scaling controls.",
        recommendedModelId = "realesrgan-general-2x",
        defaultScale = 2
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
