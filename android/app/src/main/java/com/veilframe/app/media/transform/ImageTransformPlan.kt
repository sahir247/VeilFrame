package com.veilframe.app.media.transform

import com.veilframe.app.media.CropSpec

enum class OrientationPolicy {
    NORMALIZE_EXIF,
    PRESERVE_EXIF,
    STRIP_EXIF
}

enum class MetadataPolicy {
    STRIP_ALL,
    PRESERVE_CAMERA_INFO,
    CUSTOM_EXIF
}

/**
 * Authoritative single-source-of-truth transformation plan for image processing.
 * Shared across preview rendering, size estimation, probe encoding, and actual file export.
 */
data class ImageTransformPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropRect: CropSpec? = null,
    val rotationDegrees: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val targetWidth: Int,
    val targetHeight: Int,
    val outputFormat: String = "JPG",
    val quality: Int = 85,
    val orientationPolicy: OrientationPolicy = OrientationPolicy.NORMALIZE_EXIF,
    val metadataPolicy: MetadataPolicy = MetadataPolicy.STRIP_ALL
) {
    val isCropped: Boolean
        get() = cropRect != null && (cropRect.left > 0.001f || cropRect.top > 0.001f || cropRect.right < 0.999f || cropRect.bottom < 0.999f)

    val hasGeometricTransform: Boolean
        get() = isCropped || rotationDegrees != 0f || flipH || flipV || targetWidth != sourceWidth || targetHeight != sourceHeight
}
