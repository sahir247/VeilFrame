package com.veilframe.app.cv.segmentation.rembg

/**
 * Supported ONNX neural background removal models from rembg / Cel.
 * None of these models are shipped with the APK; all are downloaded on-demand.
 */
enum class RembgModel(
    val id: String,
    val displayName: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val minBytes: Long,
    val estimatedSizeBytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val needsSigmoidOutput: Boolean = false,
    val isRecommended: Boolean = false,
) {
    BIREFNET_GENERAL_LITE(
        id = "birefnet-general-lite",
        displayName = "BiRefNet Lite",
        description = "Best quality and speed • SOTA high-resolution matting",
        fileName = "birefnet-general-lite.onnx",
        downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-general-bb_swin_v1_tiny-epoch_232.onnx",
        minBytes = 180_000_000L,
        estimatedSizeBytes = 197_000_000L,
        inputWidth = 1024,
        inputHeight = 1024,
        mean = floatArrayOf(0.485f, 0.456f, 0.406f),
        std = floatArrayOf(0.229f, 0.224f, 0.225f),
        needsSigmoidOutput = true,
        isRecommended = true,
    ),
    ISNET_GENERAL(
        id = "isnet-general-use",
        displayName = "ISNet General",
        description = "Great for people, hair, and fine edges",
        fileName = "isnet-general-use.onnx",
        downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx",
        minBytes = 140_000_000L,
        estimatedSizeBytes = 175_000_000L,
        inputWidth = 1024,
        inputHeight = 1024,
        mean = floatArrayOf(0.5f, 0.5f, 0.5f),
        std = floatArrayOf(1.0f, 1.0f, 1.0f),
    ),
    U2NET_HUMAN(
        id = "u2net_human_seg",
        displayName = "U2Net Human",
        description = "Optimized for human portraits (lightweight 320px)",
        fileName = "u2net_human_seg.onnx",
        downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net_human_seg.onnx",
        minBytes = 140_000_000L,
        estimatedSizeBytes = 176_000_000L,
        inputWidth = 320,
        inputHeight = 320,
        mean = floatArrayOf(0.485f, 0.456f, 0.406f),
        std = floatArrayOf(0.229f, 0.224f, 0.225f),
    );

    val sizeMbFormatted: String
        get() = String.format(java.util.Locale.US, "%.1f MB", estimatedSizeBytes / (1024.0 * 1024.0))

    companion object {
        val DEFAULT = BIREFNET_GENERAL_LITE

        fun fromId(id: String): RembgModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
