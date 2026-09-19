package com.veilframe.app.upscale.model

/**
 * Authoritative registry of all models supported by VeilFrame Image Upscaler.
 * Strictly adheres to Point 7 model lineup:
 * Built-in: Lanczos, Bicubic, Nearest
 * Downloadable AI: Real-ESRGAN General 2x, Real-ESRGAN General 4x, Real-ESRGAN Anime 4x
 */
object UpscaleModelRegistry {

    private const val HF_UPSCALE_BASE = "https://huggingface.co/T8RIN/imagetoolbox-models/resolve/main/onnx/enhance/upscale/"

    val LANCZOS = UpscaleModel(
        id = "lanczos-3",
        name = "Lanczos (3-Lobe)",
        description = "High-quality mathematical sinc reconstruction with edge preservation. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedScales = listOf(2, 4, 8),
        recommendedPresetIds = listOf("standard"),
        isBuiltIn = true
    )

    val BICUBIC = UpscaleModel(
        id = "bicubic",
        name = "Bicubic Spline",
        description = "Smooth cubic interpolation suitable for gradients and continuous tones. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedScales = listOf(2, 4, 8),
        recommendedPresetIds = emptyList(),
        isBuiltIn = true
    )

    val NEAREST = UpscaleModel(
        id = "nearest",
        name = "Nearest Neighbor",
        description = "Pixel-exact scaling without anti-aliasing or smoothing. Ideal for pixel art. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedScales = listOf(2, 4, 8),
        recommendedPresetIds = emptyList(),
        isBuiltIn = true
    )

    val REAL_ESRGAN_GENERAL_2X = UpscaleModel(
        id = "realesrgan-general-2x",
        name = "Real-ESRGAN General 2×",
        description = "Neural network super-resolution optimized for real-world photographs, textures, and details.",
        type = ModelType.AI_ONNX,
        nativeScale = 2,
        sizeBytes = 35_456_784L,
        downloadUrl = "${HF_UPSCALE_BASE}RealESRGAN_x2plus.ort",
        sha256 = "cd6986ac4bd2d10d460c281dcd4f1df638fddb241f4810b53cb0eb98cfb420cd",
        supportedScales = listOf(2, 4),
        recommendedPresetIds = listOf("photo", "fast"),
        isBuiltIn = false
    )

    val REAL_ESRGAN_GENERAL_4X = UpscaleModel(
        id = "realesrgan-general-4x",
        name = "Real-ESRGAN General 4×",
        description = "Maximum photographic clarity and realistic texture restoration with deep residual in residual dense blocks.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 35_437_480L,
        downloadUrl = "${HF_UPSCALE_BASE}RealESRGAN_x4plus.ort",
        sha256 = "110818e1a29309d1da6087e8bbe201f50e43ea7679483ebca1df95ab333d2a66",
        supportedScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_quality"),
        isBuiltIn = false
    )

    val REAL_ESRGAN_ANIME_4X = UpscaleModel(
        id = "realesrgan-anime-4x",
        name = "Real-ESRGAN Anime 4×",
        description = "Optimized for anime, manga, and digital illustrations. Produces razor-sharp clean lines and vibrant colors.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 9_488_256L,
        downloadUrl = "${HF_UPSCALE_BASE}RealESRGAN_x4plus_anime_6B.ort",
        sha256 = "e73004a743169923b28434b1487ed2f8c329fa99c057ffa15018502612b8bd36",
        supportedScales = listOf(4, 8),
        recommendedPresetIds = listOf("anime", "illustration"),
        isBuiltIn = false
    )

    val ALL_MODELS: List<UpscaleModel> = listOf(
        LANCZOS,
        BICUBIC,
        NEAREST,
        REAL_ESRGAN_GENERAL_2X,
        REAL_ESRGAN_GENERAL_4X,
        REAL_ESRGAN_ANIME_4X
    )

    val AI_MODELS: List<UpscaleModel> = listOf(
        REAL_ESRGAN_GENERAL_2X,
        REAL_ESRGAN_GENERAL_4X,
        REAL_ESRGAN_ANIME_4X
    )

    val BUILT_IN_MODELS: List<UpscaleModel> = listOf(
        LANCZOS,
        BICUBIC,
        NEAREST
    )

    fun getModelById(id: String): UpscaleModel? {
        return ALL_MODELS.find { it.id.equals(id, ignoreCase = true) }
    }
}
