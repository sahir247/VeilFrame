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
    val paramSha256: String,
    val binSha256: String,
    val paramCdnUrl: String? = null,
    val binCdnUrl: String? = null,
    val paramFallbackUrl: String? = null,
    val binFallbackUrl: String? = null,
    val estimatedBytes: Long,
    val maxParamBytes: Long = 150_000L,
    val maxBinBytes: Long = 20_000_000L,
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
        paramUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.25/flownet.param",
        binUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.25/flownet.bin",
        paramSha256 = "6ba231fb00e4ae82b120f938d9b2df91db32fbf322bd110f29450efaf61848d6",
        binSha256 = "10de487a095e61cb2971c39e3b5e17005a70fba6201c77fb96e063f4423b583f",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.25/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.25/flownet.bin",
        paramFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.25/flownet.param",
        binFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.25/flownet.bin",
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
        paramUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.26/flownet.param",
        binUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.26/flownet.bin",
        paramSha256 = "79f16c28903f93f8308f0c4c947f8c7e0c17d99a57b85473e5f298dd578d137b",
        binSha256 = "94d58e30b75d7c7609cfa6f3bdad524deddd14f5f75e85c36d2f827ef5c64731",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.26/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.26/flownet.bin",
        paramFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.26/flownet.param",
        binFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.26/flownet.bin",
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
        paramUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.22-lite/flownet.param",
        binUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.22-lite/flownet.bin",
        paramSha256 = "091119e19a5891a5477de8f90b3c9e979baece5ee57c27d4e4138a6cc2552cc3",
        binSha256 = "792da5d62937199886bf8bb781ab8e0eddf663854d57e4a43cd7b32e4e81b29b",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.22-lite/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.22-lite/flownet.bin",
        paramFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.22-lite/flownet.param",
        binFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.22-lite/flownet.bin",
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
        paramUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.15-lite/flownet.param",
        binUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/v2.3.1/models/rife/rife-v4.15-lite/flownet.bin",
        paramSha256 = "9f1018d743a40d774b5b2276bf7fdcf99417df2ef1486563f61723ae15bc149d",
        binSha256 = "700f6d15fc365dfd99a8fb7b4f9986708d4e9fb89844f3ddc4b56e7f30d16cda",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.15-lite/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/Sahir247/VeilFrame@v2.3.1/models/rife/rife-v4.15-lite/flownet.bin",
        paramFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.15-lite/flownet.param",
        binFallbackUrl = "https://raw.githubusercontent.com/Sahir247/VeilFrame/main/models/rife/rife-v4.15-lite/flownet.bin",
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
        paramUrl = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/master/models/rife-v4/flownet.param",
        binUrl = "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/master/models/rife-v4/flownet.bin",
        paramSha256 = "1fec6c821c62f9d9d81f529b6a44d678b2e3d354131251e7ca4dbd36fc2f0577",
        binSha256 = "f307230e32bffeaef5d27a1ea48ec4a67371f99e363ffde1f0f62016a1f725b4",
        paramCdnUrl = "https://cdn.jsdelivr.net/gh/nihui/rife-ncnn-vulkan@master/models/rife-v4/flownet.param",
        binCdnUrl = "https://cdn.jsdelivr.net/gh/nihui/rife-ncnn-vulkan@master/models/rife-v4/flownet.bin",
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

