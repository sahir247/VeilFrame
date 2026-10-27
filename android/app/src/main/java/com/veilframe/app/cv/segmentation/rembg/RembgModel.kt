package com.veilframe.app.cv.segmentation.rembg

import java.util.Locale

/**
 * Supported NCNN neural background removal models.
 * U^2-Net-P is bundled directly within the APK assets (2.28 MB) for instant zero-network operation.
 * MODNet (~13.5 MB) is downloadable on demand for high-definition portrait & hair matting.
 */
enum class RembgModel(
    val id: String,
    val displayName: String,
    val description: String,
    val fileName: String,
    val paramFileName: String,
    val downloadUrl: String = "",
    val paramDownloadUrl: String = "",
    val minBytes: Long = 0L,
    val estimatedSizeBytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val isBundled: Boolean = false,
    val isRecommended: Boolean = false,
    val needsSigmoidOutput: Boolean = false,
    val mean: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    val std: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
    val sha256: String = "",
) {
    U2NETP(
        id = "u2netp",
        displayName = "U^2-Net-P (Bundled)",
        description = "Instant offline portrait & object matting (Bundled in APK)",
        fileName = "u2netp.ncnn.bin",
        paramFileName = "u2netp.ncnn.param",
        estimatedSizeBytes = 2_350_000L,
        inputWidth = 320,
        inputHeight = 320,
        isBundled = true,
        isRecommended = true,
    ),
    MODNET(
        id = "modnet",
        displayName = "MODNet (HD Matting)",
        description = "High-definition human portrait & fine-hair matting (Downloadable)",
        fileName = "modnet.ncnn.bin",
        paramFileName = "modnet.ncnn.param",
        downloadUrl = "https://raw.githubusercontent.com/FeiGeChuanShu/ncnn_Android_matting/main/android/app/src/main/assets/mobilenetv2.bin",
        paramDownloadUrl = "https://raw.githubusercontent.com/FeiGeChuanShu/ncnn_Android_matting/main/android/app/src/main/assets/mobilenetv2.param",
        minBytes = 12_000_000L,
        estimatedSizeBytes = 12_959_008L,
        inputWidth = 512,
        inputHeight = 512,
        isBundled = false,
        isRecommended = false,
    );

    val sizeMbFormatted: String
        get() = String.format(Locale.US, "%.1f MB", estimatedSizeBytes / (1024.0 * 1024.0))

    companion object {
        val DEFAULT = U2NETP

        // Compatibility aliases for legacy tests and references
        val BIREFNET_GENERAL_LITE = U2NETP
        val ISNET_GENERAL = MODNET
        val U2NET_HUMAN = U2NETP

        fun fromId(id: String): RembgModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
