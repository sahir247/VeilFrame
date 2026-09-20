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
        name = "Real-ESRGAN General 4× (Legacy Standard)",
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

    // 2025-2026 Modern SOTA Lightweight & Mobile Models
    val SAT_LIGHT_2X = UpscaleModel(
        id = "sat-light-2x",
        name = "SAT-light 2×",
        description = "Space-Aggregation Transformer for lightweight super-resolution. Pure INT8/FP16 native execution with high edge sharpness.",
        type = ModelType.AI_ONNX,
        nativeScale = 2,
        sizeBytes = 4_850_000L,
        downloadUrl = "${HF_UPSCALE_BASE}SAT_light_x2.ort",
        sha256 = "",
        supportedOutputScales = listOf(2, 4),
        recommendedPresetIds = listOf("photo", "fast"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Apache-2.0",
        qualityFocus = "Lightweight Space-Aggregation"
    )

    val SAT_LIGHT_4X = UpscaleModel(
        id = "sat-light-4x",
        name = "SAT-light 4×",
        description = "Space-Aggregation Transformer 4× offering high PSNR restoration at ultra-low mobile memory footprints.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 5_200_000L,
        downloadUrl = "${HF_UPSCALE_BASE}SAT_light_x4.ort",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("fast_mobile", "photo_fidelity"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Apache-2.0",
        qualityFocus = "Lightweight Space-Aggregation"
    )

    val SAFMN_V3_2X = UpscaleModel(
        id = "safmn-v3-2x",
        name = "SAFMNv3 2×",
        description = "Spatially-Adaptive Feature Modulation Network v3 with enhanced receptive field and minimal FLOPs.",
        type = ModelType.AI_ONNX,
        nativeScale = 2,
        sizeBytes = 2_800_000L,
        downloadUrl = "${HF_UPSCALE_BASE}SAFMNv3_x2.ort",
        sha256 = "",
        supportedOutputScales = listOf(2, 4),
        recommendedPresetIds = listOf("fast"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "MIT",
        qualityFocus = "Spatially-Adaptive Feature Modulation"
    )

    val SAFMN_V3_4X = UpscaleModel(
        id = "safmn-v3-4x",
        name = "SAFMNv3 4×",
        description = "Spatially-Adaptive Feature Modulation Network v3 4× with sub-3M parameter footprint and rapid mobile inference.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 3_150_000L,
        downloadUrl = "${HF_UPSCALE_BASE}SAFMNv3_x4.ort",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("fast_mobile"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "MIT",
        qualityFocus = "Ultra-Fast Adaptive Modulation"
    )

    val REAL_SAFMN_PLUS_4X = UpscaleModel(
        id = "real-safmn-plus-4x",
        name = "Real-SAFMN++ 4×",
        description = "Compact blind super-resolution model tailored for real-world degraded mobile photos with noise reduction.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 6_400_000L,
        downloadUrl = "${HF_UPSCALE_BASE}RealSAFMN_plus_x4.ort",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_quality"),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "MIT",
        qualityFocus = "Blind Super-Resolution + Denoising"
    )

    val ESPAN_4X = UpscaleModel(
        id = "espan-4x",
        name = "ESPAN 4×",
        description = "Efficient Pixel Attention Network designed for resource-constrained edge devices with high structural retention.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 5_800_000L,
        downloadUrl = "${HF_UPSCALE_BASE}ESPAN_x4.ort",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_balanced"),
        isBuiltIn = false,
        genre = ModelGenre.LIGHTWEIGHT_MOBILE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Apache-2.0",
        qualityFocus = "Efficient Edge Attention"
    )

    val PFT_LIGHT_4X = UpscaleModel(
        id = "pft-light-4x",
        name = "PFT-light 4×",
        description = "Progressive Frequency Transformer separating low and high frequency image components for crisp texture recovery.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 12_600_000L,
        downloadUrl = "${HF_UPSCALE_BASE}PFT_light_x4.ort",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = listOf("photo_fidelity"),
        isBuiltIn = false,
        genre = ModelGenre.PHOTO_FIDELITY,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Apache-2.0",
        qualityFocus = "Progressive Frequency Decomposition"
    )

    // Tier C: High-Parameter Reference / Server-Grade Models (Not selectable for Android native execution)
    val DRCT_4X = UpscaleModel(
        id = "drct-4x",
        name = "DRCT 4× (Dense Residual Channel Transformer)",
        description = "State-of-the-art dense residual channel transformer. High-parameter architecture for workstation and server-grade benchmarks.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 86_000_000L,
        downloadUrl = "",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.PHOTO_FIDELITY,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_C_DESKTOP,
        deploymentStatus = DeploymentStatus.REFERENCE_ONLY,
        license = "Apache-2.0",
        qualityFocus = "Workstation SOTA Benchmark"
    )

    val PLAIN_USR_4X = UpscaleModel(
        id = "plain-usr-4x",
        name = "PlainUSR 4× (Unconstrained SR)",
        description = "Heavyweight unconstrained super-resolution model with dense self-attention. Benchmark baseline for workstation GPUs.",
        type = ModelType.AI_ONNX,
        nativeScale = 4,
        sizeBytes = 94_000_000L,
        downloadUrl = "",
        sha256 = "",
        supportedOutputScales = listOf(4, 8),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.PHOTO_FIDELITY,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_C_DESKTOP,
        deploymentStatus = DeploymentStatus.REFERENCE_ONLY,
        license = "Apache-2.0",
        qualityFocus = "Server-Grade Unconstrained Attention"
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
        SAT_LIGHT_2X,
        SAT_LIGHT_4X,
        SAFMN_V3_2X,
        SAFMN_V3_4X,
        REAL_SAFMN_PLUS_4X,
        ESPAN_4X,
        PFT_LIGHT_4X,
        DRCT_4X,
        PLAIN_USR_4X,
        CODEFORMER
    )

    /**
     * Selectable models for Android on-device execution.
     * Excludes Tier C / Reference-Only workstation models.
     */
    val SELECTABLE_MODELS: List<UpscaleModel> = ALL_MODELS.filter {
        it.deploymentStatus != DeploymentStatus.REFERENCE_ONLY && it.tier != ModelTier.TIER_C_DESKTOP
    }

    val AI_MODELS: List<UpscaleModel> = SELECTABLE_MODELS.filter { it.type == ModelType.AI_ONNX }

    val BUILT_IN_MODELS: List<UpscaleModel> = ALL_MODELS.filter { it.isBuiltIn }

    val REFERENCE_MODELS: List<UpscaleModel> = ALL_MODELS.filter {
        it.deploymentStatus == DeploymentStatus.REFERENCE_ONLY || it.tier == ModelTier.TIER_C_DESKTOP
    }

    fun getModelById(id: String): UpscaleModel? {
        return ALL_MODELS.find { it.id.equals(id, ignoreCase = true) }
    }
}
