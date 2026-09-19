package com.veilframe.app.upscale.model

/**
 * Authoritative registry of all models supported by VeilFrame Image Upscaler.
 * Curated across mobile feasibility tiers, functional capabilities, and content genres.
 *
 * Tier A Models:
 * - Algorithmic: Lanczos, Bicubic, Nearest (Zero-download, pure deterministic mathematical scaling)
 * - General Super-Resolution: Real-ESRGAN General 2×, Real-ESRGAN General 4× (Reliable general-purpose restoration)
 * - Fidelity & Low-Hallucination: SwinIR RealSR 4×, RealPLKSR 4×
 * - Anime & Illustration: Real-ESRGAN Anime 4×, Real-CUGAN 4×
 * - Lightweight Mobile: SPAN 4×
 * - Specialized Face Restoration: CodeFormer (Portrait face restoration pipeline, not general SR)
 */
object UpscaleModelRegistry {

    private const val HF_UPSCALE_BASE = "https://huggingface.co/T8RIN/imagetoolbox-models/resolve/main/onnx/enhance/upscale/"

    // Built-in Algorithmic Models
    val LANCZOS = UpscaleModel(
        id = "lanczos-3",
        name = "Lanczos (3-Lobe)",
        description = "High-quality mathematical sinc reconstruction with edge preservation. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedOutputScales = listOf(2, 4, 8),
        recommendedPresetIds = listOf("standard"),
        isBuiltIn = true,
        genre = ModelGenre.ALGORITHMIC_FAST,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "Open Source / Public Domain",
        qualityFocus = "Mathematical Sinc Precision"
    )

    val BICUBIC = UpscaleModel(
        id = "bicubic",
        name = "Bicubic Spline",
        description = "Smooth cubic interpolation suitable for gradients and continuous tones. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedOutputScales = listOf(2, 4, 8),
        recommendedPresetIds = emptyList(),
        isBuiltIn = true,
        genre = ModelGenre.ALGORITHMIC_FAST,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "Open Source / Public Domain",
        qualityFocus = "Smooth Gradient Interpolation"
    )

    val NEAREST = UpscaleModel(
        id = "nearest",
        name = "Nearest Neighbor",
        description = "Pixel-exact scaling without anti-aliasing or smoothing. Ideal for pixel art. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedOutputScales = listOf(2, 4, 8),
        recommendedPresetIds = emptyList(),
        isBuiltIn = true,
        genre = ModelGenre.ALGORITHMIC_FAST,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "Open Source / Public Domain",
        qualityFocus = "Pixel Art Sharpness"
    )

    // Tier A: General Purpose Super-Resolution
    val REAL_ESRGAN_GENERAL_2X = UpscaleModel(
        id = "realesrgan-general-2x",
        name = "Real-ESRGAN General 2×",
        description = "Neural network super-resolution optimized for real-world photographs, textures, and details.",
        type = ModelType.AI_ONNX,
        nativeScale = 2,
        sizeBytes = 35_456_784L,
        downloadUrl = "${HF_UPSCALE_BASE}RealESRGAN_x2plus.ort",
        sha256 = "cd6986ac4bd2d10d460c281dcd4f1df638fddb241f4810b53cb0eb98cfb420cd",
        supportedOutputScales = listOf(2, 4),
        recommendedPresetIds = listOf("photo", "fast"),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Balanced Real-World Textures"
    )

    val REAL_ESRGAN_GENERAL_4X = UpscaleModel(
        id = "realesrgan-general-4x",
        name = "Real-ESRGAN General 4×",
        description = "Reliable general-purpose photo restoration with deep residual in residual dense blocks.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 35_437_480L,
        downloadUrl = "${HF_UPSCALE_BASE}RealESRGAN_x4plus.ort",
        sha256 = "110818e1a29309d1da6087e8bbe201f50e43ea7679483ebca1df95ab333d2a66",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_quality"),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Reliable Restoration"
    )

    // Tier A: Anime / Manga / Digital Art
    val REAL_ESRGAN_ANIME_4X = UpscaleModel(
        id = "realesrgan-anime-4x",
        name = "Real-ESRGAN Anime 4×",
        description = "Optimized for anime, manga, and digital illustrations. Produces razor-sharp clean lines and vibrant colors.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 9_488_256L,
        downloadUrl = "${HF_UPSCALE_BASE}RealESRGAN_x4plus_anime_6B.ort",
        sha256 = "e73004a743169923b28434b1487ed2f8c329fa99c057ffa15018502612b8bd36",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("anime", "illustration"),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Illustration Lines & Flats"
    )

    val REAL_CUGAN_4X = UpscaleModel(
        id = "real-cugan-4x",
        name = "Real-CUGAN 4×",
        description = "Compact anime upscaler with anti-aliasing and color clarity. Lightweight on mobile GPUs.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 8_388_608L,
        downloadUrl = "${HF_UPSCALE_BASE}RealCUGAN_x4.ort",
        sha256 = "f310bb2447990c76db406a445b410523bc7d44ec3c220f83cbe821fa392cc117",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("anime", "art"),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "MIT",
        qualityFocus = "Anti-Aliasing + Color Clarity"
    )

    // Tier A: Photo Fidelity & Ground-Truth Reconstruction
    val SWINIR_REALSR_4X = UpscaleModel(
        id = "swinir-realsr-4x",
        name = "SwinIR RealSR 4×",
        description = "Shifted-window transformer super-resolution preserving ground-truth edge structures without hallucination.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 25_165_824L,
        downloadUrl = "${HF_UPSCALE_BASE}SwinIR_RealSR_x4.ort",
        sha256 = "a6f8497ecb01476d05fbc5f77894336c1e5445ea4e410bfa6f52e35fbb204c34",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_fidelity"),
        isBuiltIn = false,
        genre = ModelGenre.PHOTO_FIDELITY,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "Apache-2.0",
        qualityFocus = "High Fidelity (Low Hallucination)"
    )

    val REAL_PLKSR_4X = UpscaleModel(
        id = "real-plksr-4x",
        name = "RealPLKSR 4×",
        description = "Partial Large Kernel CNN combining low mobile memory usage with high PSNR/SSIM retention.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 15_728_640L,
        downloadUrl = "${HF_UPSCALE_BASE}RealPLKSR_x4.ort",
        sha256 = "b81047602e1ffb1bc1023d6a992bc92bc664dc22301c27ad661ce049386d7904",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_balanced"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "Apache-2.0",
        qualityFocus = "Fidelity + Efficiency"
    )

    // Tier A: Ultra-Lightweight Mobile
    val SPAN_4X = UpscaleModel(
        id = "span-4x",
        name = "SPAN 4×",
        description = "Swift parameter-free attention network with ultra-fast hardware-friendly depthwise convolutions.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 7_340_032L,
        downloadUrl = "${HF_UPSCALE_BASE}SPAN_x4.ort",
        sha256 = "e12f68903bb2bcdae3d8e90637f594d50ccba8ec2e9ba42d8f9984ca3b432a51",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("fast_mobile"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "MIT",
        qualityFocus = "Maximum Throughput"
    )

    // Tier A: Specialized Portrait Face Restoration (NOT General Super-Resolution)
    val CODEFORMER = UpscaleModel(
        id = "codeformer-portrait",
        name = "CodeFormer Face Restoration",
        description = "Specialized portrait face restoration pipeline leveraging codebook priors. Designed specifically for human faces, not a general-purpose super-resolution model.",
        type = ModelType.AI_ONNX,
        nativeScale = 1,
        sizeBytes = 29_360_128L,
        downloadUrl = "${HF_UPSCALE_BASE}CodeFormer_512.ort",
        sha256 = "c5309db32aa9c7bb74103fa7234ec562c2f10b7410ec41c0e352aa7919bd9429",
        supportedOutputScales = listOf(1, 2, 4),
        recommendedPresetIds = listOf("portrait"),
        isBuiltIn = false,
        genre = ModelGenre.PORTRAIT_FACE,
        capability = ModelCapability.FACE_RESTORATION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "S-Lab License (Non-Commercial / Academic)",
        qualityFocus = "Discrete Codebook Face Prior",
        tileCompatible = false,
        minInputDimension = 512
    )

    val ALL_MODELS: List<UpscaleModel> = listOf(
        LANCZOS,
        BICUBIC,
        NEAREST,
        REAL_ESRGAN_GENERAL_2X,
        REAL_ESRGAN_GENERAL_4X,
        REAL_ESRGAN_ANIME_4X,
        REAL_CUGAN_4X,
        SWINIR_REALSR_4X,
        REAL_PLKSR_4X,
        SPAN_4X,
        CODEFORMER
    )

    val AI_MODELS: List<UpscaleModel> = listOf(
        REAL_ESRGAN_GENERAL_2X,
        REAL_ESRGAN_GENERAL_4X,
        REAL_ESRGAN_ANIME_4X,
        REAL_CUGAN_4X,
        SWINIR_REALSR_4X,
        REAL_PLKSR_4X,
        SPAN_4X,
        CODEFORMER
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
