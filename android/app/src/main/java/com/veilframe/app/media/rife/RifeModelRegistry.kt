package com.veilframe.app.media.rife

/**
 * Specification for a RIFE neural video interpolation model.
 * Strictly modern models (v4.0 - v4.26). Legacy RIFE v2.4 is completely excluded.
 */
data class RifeModelSpec(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val description: String,
    val sizeFormatted: String,
    val requiredPadding: Int,
    val paramUrl: String,
    val binUrl: String,
    val paramCdnUrl: String? = null,
    val binCdnUrl: String? = null,
    val paramFallbackUrl: String? = null,
    val binFallbackUrl: String? = null,
    val estimatedBytes: Long,
    val isRecommended: Boolean = false,
    val benchmarkNote: String
)

object RifeModelRegistry {
    const val PREF_SELECTED_MODEL_ID = "pref_rife_selected_model_id"
    const val DEFAULT_MODEL_ID = "rife-v4.25"

    val RIFE_V4_25 = RifeModelSpec(
        id = "rife-v4.25",
        displayName = "RIFE v4.25 (Flagship Quality)",
        shortBadge = "Flagship",
        description = "Highest visual fidelity, artifact-free vectors. Gold standard in community benchmarks.",
        sizeFormatted = "10.8 MB",
        requiredPadding = 64,
        paramUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.25_ensembleFalse/flownet.param",
        binUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.25_ensembleFalse/flownet.bin",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.25_ensembleFalse/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.25_ensembleFalse/flownet.bin",
        paramFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.25_ensembleFalse/flownet.param",
        binFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.25_ensembleFalse/flownet.bin",
        estimatedBytes = 11_360_684L,
        isRecommended = true,
        benchmarkNote = "GPU: 185ms @ 720p • 64px padding"
    )

    val RIFE_V4_26 = RifeModelSpec(
        id = "rife-v4.26",
        displayName = "RIFE v4.26 (Anti-Strobe)",
        shortBadge = "Anti-Strobe",
        description = "Trained with gram loss. Prevents flicker in strobe lighting, flashes, and concerts.",
        sizeFormatted = "10.8 MB",
        requiredPadding = 64,
        paramUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.26_ensembleFalse/flownet.param",
        binUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.26_ensembleFalse/flownet.bin",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.26_ensembleFalse/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.26_ensembleFalse/flownet.bin",
        paramFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.26_ensembleFalse/flownet.param",
        binFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.26_ensembleFalse/flownet.bin",
        estimatedBytes = 11_360_684L,
        isRecommended = false,
        benchmarkNote = "GPU: 185ms @ 720p • 64px padding"
    )

    val RIFE_V4_22_LITE = RifeModelSpec(
        id = "rife-v4.22-lite",
        displayName = "RIFE v4.22 Lite (Mobile Balanced)",
        shortBadge = "Balanced",
        description = "Balanced architecture with lowest CPU overhead and high GPU rendering speed.",
        sizeFormatted = "9.3 MB",
        requiredPadding = 32,
        paramUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.22_lite_ensembleFalse/flownet.param",
        binUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.22_lite_ensembleFalse/flownet.bin",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.22_lite_ensembleFalse/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.22_lite_ensembleFalse/flownet.bin",
        paramFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.22_lite_ensembleFalse/flownet.param",
        binFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.22_lite_ensembleFalse/flownet.bin",
        estimatedBytes = 9_759_728L,
        isRecommended = false,
        benchmarkNote = "GPU: 162ms @ 720p • 32px padding"
    )

    val RIFE_V4_15_LITE = RifeModelSpec(
        id = "rife-v4.15-lite",
        displayName = "RIFE v4.15 Lite (Ultra Fast)",
        shortBadge = "Ultra Fast",
        description = "Smallest download size (5 MB) and fastest processing. Ideal for quick previews.",
        sizeFormatted = "5.0 MB",
        requiredPadding = 32,
        paramUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.15_lite_ensembleFalse/flownet.param",
        binUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4.15_lite_ensembleFalse/flownet.bin",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.15_lite_ensembleFalse/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4.15_lite_ensembleFalse/flownet.bin",
        paramFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.15_lite_ensembleFalse/flownet.param",
        binFallbackUrl = "https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/raw/master/models/rife-v4.15_lite_ensembleFalse/flownet.bin",
        estimatedBytes = 5_257_552L,
        isRecommended = false,
        benchmarkNote = "GPU: 147ms @ 720p • 32px padding"
    )

    val RIFE_V4_0 = RifeModelSpec(
        id = "rife-v4.0",
        displayName = "RIFE v4.0 (Baseline)",
        shortBadge = "Baseline",
        description = "Standard baseline RIFE v4 model.",
        sizeFormatted = "10.3 MB",
        requiredPadding = 32,
        paramUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4_ensembleFalse_fastTrue/flownet.param",
        binUrl = "https://raw.githubusercontent.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/master/models/rife-v4_ensembleFalse_fastTrue/flownet.bin",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4_ensembleFalse_fastTrue/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan@master/models/rife-v4_ensembleFalse_fastTrue/flownet.bin",
        paramFallbackUrl = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/master/models/rife-v4/flownet.param",
        binFallbackUrl = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/master/models/rife-v4/flownet.bin",
        estimatedBytes = 10_340_416L,
        isRecommended = false,
        benchmarkNote = "GPU: 112ms @ 720p • 32px padding"
    )

    val ALL_MODELS: List<RifeModelSpec> = listOf(
        RIFE_V4_25,
        RIFE_V4_26,
        RIFE_V4_22_LITE,
        RIFE_V4_15_LITE,
        RIFE_V4_0
    )

    fun findById(id: String?): RifeModelSpec {
        if (id == null) return RIFE_V4_25
        return ALL_MODELS.firstOrNull { it.id == id || it.id == "rife-$id" } ?: RIFE_V4_25
    }
}

