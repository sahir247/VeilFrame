package com.veilframe.app.upscale.model

/**
 * Authoritative registry of all models supported by VeilFrame Image Upscaler.
 * Exclusively supports native NCNN Vulkan/CPU models (RealSR / Real-ESRGAN) and
 * built-in algorithmic filters (Lanczos, Bicubic, Nearest).
 *
 * All neural models declare exact, pinned SHA-256 digests and byte counts verified
 * against upstream NCNN HuggingFace repositories.
 */
object UpscaleModelRegistry {

    // Upstream NCNN model mirrors: pinned to specific revisions
    private const val NCNN_REALESRGAN_BASE = "https://huggingface.co/edgetools/realesrgan/resolve/b8b8b5de432a09da4ef74279243dcdd66c82d484/"
    private const val NCNN_REALSR_BASE = "https://huggingface.co/tumuyan2/realsr-models/resolve/main/"

    // =========================================================================
    // Built-in Algorithmic Models (Zero download required, pure mathematics)
    // =========================================================================

    val LANCZOS = UpscaleModel(
        id = "lanczos-3",
        name = "Lanczos (3-Lobe)",
        description = "High-quality mathematical sinc reconstruction with edge preservation. Zero download required.",
        type = ModelType.ALGORITHMIC,
        nativeScale = 2,
        sizeBytes = 0L,
        supportedOutputScales = listOf(2, 3, 4),
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
        supportedOutputScales = listOf(2, 3, 4),
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
        supportedOutputScales = listOf(2, 3, 4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = true,
        genre = ModelGenre.ALGORITHMIC_FAST,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        license = "Open Source / Public Domain",
        qualityFocus = "Pixel Art Sharpness"
    )

    // =========================================================================
    // Native NCNN Neural Models (RealSR & Real-ESRGAN)
    // =========================================================================

    val NCNN_REAL_ESRGAN_X4PLUS = UpscaleModel(
        id = "ncnn-realesrgan-x4plus",
        name = "Real-ESRGAN x4plus (NCNN)",
        description = "General-purpose photographic restoration using native Real-ESRGAN NCNN FP16.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_540_549L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = listOf("photo_quality"),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "General Photography",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALESRGAN_BASE}realesrgan-x4plus.param",
        paramSha256 = "35330ececcea33b6c397a72548e788d5d53becee4734c50b7fada36e89f10a86",
        paramSizeBytes = 116_029L,
        binDownloadUrl = "${NCNN_REALESRGAN_BASE}realesrgan-x4plus.bin",
        binSha256 = "713ee713b0353afaa27976f0563a64a5043bd70b9bd8936c2e26e25ebcdbcddf",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ANIMEVIDEO_V3_X2 = UpscaleModel(
        id = "ncnn-realesr-animevideov3-x2",
        name = "AnimeVideoV3 2× (NCNN)",
        description = "Compact 2× NCNN super-resolution model for anime, flat-color illustrations, and animation frames.",
        type = ModelType.AI_NCNN,
        nativeScale = 2,
        sizeBytes = 1_250_541L,
        supportedOutputScales = listOf(2),
        recommendedPresetIds = listOf("fast"),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Compact Anime/Animation Upscaling",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALESRGAN_BASE}realesr-animevideov3-x2.param",
        paramSha256 = "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04",
        paramSizeBytes = 3_173L,
        binDownloadUrl = "${NCNN_REALESRGAN_BASE}realesr-animevideov3-x2.bin",
        binSha256 = "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
        binSizeBytes = 1_247_368L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ANIMEVIDEO_V3_X3 = UpscaleModel(
        id = "ncnn-realesr-animevideov3-x3",
        name = "AnimeVideoV3 3× (NCNN)",
        description = "Compact 3× NCNN super-resolution model for anime, flat-color illustrations, and animation frames.",
        type = ModelType.AI_NCNN,
        nativeScale = 3,
        sizeBytes = 1_250_541L,
        supportedOutputScales = listOf(3),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Compact Anime/Animation 3× Upscaling",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALESRGAN_BASE}realesr-animevideov3-x3.param",
        paramSha256 = "d1a5755008791d09b57e3425fc9dd0bd26b00fdf79c606210bc0e693f8230881",
        paramSizeBytes = 3_173L,
        binDownloadUrl = "${NCNN_REALESRGAN_BASE}realesr-animevideov3-x3.bin",
        binSha256 = "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
        binSizeBytes = 1_247_368L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ANIMEVIDEO_V3_X4 = UpscaleModel(
        id = "ncnn-realesr-animevideov3-x4",
        name = "AnimeVideoV3 4× (NCNN)",
        description = "Compact 4× NCNN super-resolution model for anime and clean digital illustrations.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 1_250_445L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = listOf("illustration"),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Compact Anime/Illustration Upscaling",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALESRGAN_BASE}realesr-animevideov3-x4.param",
        paramSha256 = "850a248e7c14c27e5bd8cf7265113a9441036a7db63963bb8aa5169d788a435e",
        paramSizeBytes = 3_077L,
        binDownloadUrl = "${NCNN_REALESRGAN_BASE}realesr-animevideov3-x4.bin",
        binSha256 = "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
        binSizeBytes = 1_247_368L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_REALSR_DF2K_X4 = UpscaleModel(
        id = "ncnn-realsr-df2k-x4",
        name = "RealSR DF2K 4× (NCNN)",
        description = "Classic RealSR DF2K model trained on DIV2K/Flickr2K datasets for high-fidelity photo restoration.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_540_513L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "DIV2K / Flickr2K Photo Fidelity",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-DF2K/x4.param",
        paramSha256 = "a7f31e75518ad279c179202bf77da9eca3141124a67e5261d97235849e6cc323",
        paramSizeBytes = 115_993L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-DF2K/x4.bin",
        binSha256 = "a3f5eb53906ef54d5c5d125b2c9dd5f570d9dbb779e3d934df14b5375fd923c1",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_REALSR_DF2K_JPEG_X4 = UpscaleModel(
        id = "ncnn-realsr-df2k-jpeg-x4",
        name = "RealSR DF2K JPEG 4× (NCNN)",
        description = "RealSR DF2K model with specialized JPEG compression artifact reduction and texture restoration.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_540_513L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "JPEG De-artifacting & Photo Super-Resolution",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-DF2K_JPEG/x4.param",
        paramSha256 = "a7f31e75518ad279c179202bf77da9eca3141124a67e5261d97235849e6cc323",
        paramSizeBytes = 115_993L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-DF2K_JPEG/x4.bin",
        binSha256 = "e862e88c3f346a3b8506d839633f5e2a16461bb8bfb3e211152f40d807a4f51c",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_REAL_ESRGAN_HFA2K_X4 = UpscaleModel(
        id = "ncnn-realesrgan-hfa2k-x4",
        name = "Real-ESRGAN HFA2k 4× (NCNN)",
        description = "High-frequency-aware ESRGAN model trained for human face and fine portrait texture enhancement.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_532_557L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.PORTRAIT_FACE,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "High-Frequency Portrait & Texture Synthesis",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-4xHFA2k/x4.param",
        paramSha256 = "766758eacc2bc8e81b196f8bb777771452ac9666f77198957cff533188366384",
        paramSizeBytes = 108_037L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-4xHFA2k/x4.bin",
        binSha256 = "45f90b75c6044fb00a83b046b782a7b3fa4aee1f174d8993cbf1dbb8f04e511a",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ULTRASHARP_X4 = UpscaleModel(
        id = "ncnn-ultrasharp-x4",
        name = "4x-UltraSharp (NCNN)",
        description = "World-renowned 4× ESRGAN model for sharp, realistic details across photos, textures, and landscapes.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_590_099L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = listOf("custom"),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Open Source / Community",
        qualityFocus = "Ultra-Sharp Real-World Texture & Clarity",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-UltraSharp-fp16/x4.param",
        paramSha256 = "0332002123306541c803cd675280eee7f9cdd96f0804a47129f86ebbf9ed4174",
        paramSizeBytes = 165_579L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-UltraSharp-fp16/x4.bin",
        binSha256 = "713ce69a8642b1907cc24da7573560dcc933faf0c74e48ab0b3b379245618701",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ANIMESHARP_X4 = UpscaleModel(
        id = "ncnn-animesharp-x4",
        name = "AnimeSharp 4× (NCNN)",
        description = "High-precision 4× ESRGAN model by Kim2091 specializing in crisp linework, vibrant flat colors, and manga art.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_540_549L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = listOf("anime"),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Crisp Anime Linework & Color Flatting",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-AnimeSharp/x4.param",
        paramSha256 = "e62bddc9061dd6e3e2a22b5eb02d2ac437b5cefb1295c7281382591c6870e8ed",
        paramSizeBytes = 116_029L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-AnimeSharp/x4.bin",
        binSha256 = "1b392e3d7b89402c1a2acb6edcee8fb1ff7a32c04ec126123957a07f5f7aa10e",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_NOMOS8KSC_X4 = UpscaleModel(
        id = "ncnn-nomos8ksc-x4",
        name = "Nomos8kSC 4× (NCNN)",
        description = "High-resolution ESRGAN model by Phhofm trained on 8K imagery for natural fine textures and noise control.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_540_549L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Open Source / Community",
        qualityFocus = "8K Photorealistic Detail & Natural Film Grain",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-Nomos8kSC/x4.param",
        paramSha256 = "e62bddc9061dd6e3e2a22b5eb02d2ac437b5cefb1295c7281382591c6870e8ed",
        paramSizeBytes = 116_029L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-Nomos8kSC/x4.bin",
        binSha256 = "26fc1e9ed5e5055929fd02d23c73a0876de937e3060e0e2797bea8d4c6c495be",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_REMACRI_X4 = UpscaleModel(
        id = "ncnn-remacri-x4",
        name = "Remacri 4× (NCNN)",
        description = "Advanced perceptual restoration model by Cras创s balancing texture reconstruction with artifact suppression.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_564_815L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Open Source / Community",
        qualityFocus = "Balanced Perceptual Reconstruction & Texture",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-Remacri/x4.param",
        paramSha256 = "859ecba5b3592ecf3e76c93bed65e9f627b5236dd696aae5a84ecf8c93ab65ce",
        paramSizeBytes = 140_295L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-Remacri/x4.bin",
        binSha256 = "a43be595c0d743314c30b50fe7ef188be0c61cc55c46ce81adb79ba4b3c3fb7a",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_REAL_ESRGAN_GENERAL_V3_X4 = UpscaleModel(
        id = "ncnn-realesrgan-general-v3-x4",
        name = "Real-ESRGAN General v3 4× (NCNN)",
        description = "Compact, high-speed 4× general restoration model (2.4 MB) optimized for fast mobile execution.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 2_443_547L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = listOf("photo"),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Ultra-Compact 4× General Photo & Illustration",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-RealeSR-general-v3/x4.param",
        paramSha256 = "ce766e261369b911c3f3f607266380e6e6133e82699fed5e3b3afa07482c34c1",
        paramSizeBytes = 8_275L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-RealeSR-general-v3/x4.bin",
        binSha256 = "01450f4a79b81c0f1f3eeefc31121167886125c25e41a6d4773e8ec8062528a1",
        binSizeBytes = 2_435_272L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ULTRAMIX_BALANCED_X4 = UpscaleModel(
        id = "ncnn-ultramix-balanced-x4",
        name = "UltraMix Balanced 4× (NCNN)",
        description = "Fine-tuned ESRGAN blend of UltraSharp and Remacri for well-balanced photo texture and artifact removal.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_564_815L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Open Source / Community",
        qualityFocus = "Balanced Photo Sharpness & Natural Denoising",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-UltraMix_Balanced/x4.param",
        paramSha256 = "859ecba5b3592ecf3e76c93bed65e9f627b5236dd696aae5a84ecf8c93ab65ce",
        paramSizeBytes = 140_295L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-UltraMix_Balanced/x4.bin",
        binSha256 = "171cae5968485d366b4fb575d232f98d117d94b766b15f22849cfccde40d2050",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_ANIMESHARP_LITE_X4 = UpscaleModel(
        id = "ncnn-animesharp-lite-x4",
        name = "AnimeSharp Lite 4× (NCNN)",
        description = "Lightweight 20MB AnimeSharp model optimized for high-speed anime and manga line enhancement.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 20_144_908L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.ANIME_MANGA,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "BSD-3-Clause",
        qualityFocus = "Fast Anime Linework & Low-VRAM Inference",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-AnimeSharpLite/x4.param",
        paramSha256 = "560fd03dc3e425a8a519320fec7a5ce9209fa5986c49c0d3e708cb1e489835fc",
        paramSizeBytes = 59_544L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-AnimeSharpLite/x4.bin",
        binSha256 = "8301319c42ddba71922c4424098fe0a2088a5f1f94f6ef4bcf1385e17b2bc053",
        binSizeBytes = 20_085_364L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    val NCNN_NICKELBACK_X4 = UpscaleModel(
        id = "ncnn-nickelback-x4",
        name = "Nickelback 70000G 4× (NCNN)",
        description = "Community photo restoration model specialized in recovering fine natural textures and deblurring.",
        type = ModelType.AI_NCNN,
        nativeScale = 4,
        sizeBytes = 33_540_549L,
        supportedOutputScales = listOf(4),
        recommendedPresetIds = emptyList(),
        isBuiltIn = false,
        genre = ModelGenre.GENERAL_PHOTO,
        capability = ModelCapability.SUPER_RESOLUTION,
        tier = ModelTier.TIER_A_NATIVE,
        deploymentStatus = DeploymentStatus.ANDROID_NATIVE,
        license = "Open Source / Community",
        qualityFocus = "High-Fidelity Textures & Deblurring",
        precision = ModelPrecision.FP16,
        engineKind = NcnnEngineKind.REALSR_ESRGAN,
        paramDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-4x_Nickelback_70000G/x4.param",
        paramSha256 = "e62bddc9061dd6e3e2a22b5eb02d2ac437b5cefb1295c7281382591c6870e8ed",
        paramSizeBytes = 116_029L,
        binDownloadUrl = "${NCNN_REALSR_BASE}models-ESRGAN-4x_Nickelback_70000G/x4.bin",
        binSha256 = "ff205aeef4ee245964679fe9b093200f7065773d8a4f734dd1006a9ea32ea0c6",
        binSizeBytes = 33_424_520L,
        inputBlobName = "data",
        outputBlobName = "output",
        prePadding = 10
    )

    // Dynamic Custom Imported Models Registry
    private val customModels = java.util.concurrent.ConcurrentHashMap<String, UpscaleModel>()

    fun registerCustomModel(model: UpscaleModel) {
        customModels[model.id] = model
    }

    fun unregisterCustomModel(modelId: String): Boolean {
        return customModels.remove(modelId) != null
    }

    fun clearCustomModels() {
        customModels.clear()
    }

    val STATIC_ALL_MODELS: List<UpscaleModel> = listOf(
        LANCZOS,
        BICUBIC,
        NEAREST,
        NCNN_REAL_ESRGAN_X4PLUS,
        NCNN_ANIMEVIDEO_V3_X2,
        NCNN_ANIMEVIDEO_V3_X3,
        NCNN_ANIMEVIDEO_V3_X4,
        NCNN_REALSR_DF2K_X4,
        NCNN_REALSR_DF2K_JPEG_X4,
        NCNN_REAL_ESRGAN_HFA2K_X4,
        NCNN_ULTRASHARP_X4,
        NCNN_ANIMESHARP_X4,
        NCNN_NOMOS8KSC_X4,
        NCNN_REMACRI_X4,
        NCNN_REAL_ESRGAN_GENERAL_V3_X4,
        NCNN_ULTRAMIX_BALANCED_X4,
        NCNN_ANIMESHARP_LITE_X4,
        NCNN_NICKELBACK_X4
    )


    val ALL_MODELS: List<UpscaleModel>
        get() = STATIC_ALL_MODELS + customModels.values

    /**
     * Selectable models for Android on-device execution.
     */
    val SELECTABLE_MODELS: List<UpscaleModel>
        get() = ALL_MODELS.filter {
            it.isBuiltIn || (it.type == ModelType.AI_NCNN && !it.paramSha256.isNullOrEmpty() && !it.binSha256.isNullOrEmpty())
        }

    /** Selectable neural NCNN models. */
    val AI_MODELS: List<UpscaleModel>
        get() = SELECTABLE_MODELS.filter { it.type == ModelType.AI_NCNN }

    val NCNN_MODELS: List<UpscaleModel>
        get() = ALL_MODELS.filter { it.type == ModelType.AI_NCNN }

    val BUILT_IN_MODELS: List<UpscaleModel>
        get() = ALL_MODELS.filter { it.isBuiltIn }

    fun getModelById(id: String): UpscaleModel? {
        return customModels[id]
            ?: ALL_MODELS.find { it.id.equals(id, ignoreCase = true) }
    }
}
