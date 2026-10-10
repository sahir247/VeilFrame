package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap

data class RembgSourceInfo(
    val width: Int,
    val height: Int,
    val format: String,
    val fileSize: Long,
    val warnings: List<String> = emptyList(),
)

data class RembgProgress(
    val percent: Int,
    val message: String,
)

data class RembgResult(
    val cutoutBitmap: Bitmap,
    val sourceInfo: RembgSourceInfo,
    val model: RembgModel,
    val warnings: List<String> = emptyList(),
)

enum class RembgExportFormat {
    PNG,
    JPG
}

data class RembgDownloadState(
    val model: RembgModel? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val speedBytesPerSec: Long = 0L,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0 && isDownloading) {
            (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}
