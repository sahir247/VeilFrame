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
        description = "Fast, lightweight general-purpose photo restoration using native NCNN Real-ESRGAN General v3.",
        recommendedModelId = "ncnn-realesrgan-general-v3-x4",
        defaultScale = 4
    )

    val PHOTO_QUALITY = UpscalePreset(
        id = "photo_quality",
        name = "Photo (High Quality)",
        description = "Maximum fidelity photo restoration using deep residual NCNN Real-ESRGAN x4plus.",
        recommendedModelId = "ncnn-realesrgan-x4plus",
        defaultScale = 4
    )

    val ANIME = UpscalePreset(
        id = "anime",
        name = "Anime",
        description = "High-sharpness neural model specifically tuned for anime frames and manga linework.",
        recommendedModelId = "ncnn-animesharp-x4",
        defaultScale = 4
    )

    val ILLUSTRATION = UpscalePreset(
        id = "illustration",
        name = "Illustration & Art",
        description = "Native NCNN AnimeVideoV3 model for clean digital illustrations, smooth gradients, and line art.",
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
        description = "Manual configuration with versatile 4×-UltraSharp photorealistic neural model.",
        recommendedModelId = "ncnn-ultrasharp-x4",
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
