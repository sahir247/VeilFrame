package com.veilframe.app.media

/**
 * Output format mode for video/animation pipelines.
 */
enum class VideoOutputMode {
    VIDEO,
    GIF;

    companion object {
        fun fromFormat(format: String): VideoOutputMode {
            return if (format.equals("gif", ignoreCase = true)) GIF else VIDEO
        }
    }
}

/**
 * Categorized audio handling mode for video processing.
 */
enum class AudioProcessingMode {
    KEEP,
    MUTE,
    ENCODE;
}

/**
 * Spatial anchor positions for text watermark overlays.
 */
enum class WatermarkPosition(val idString: String) {
    TOP_LEFT("top-left"),
    TOP_CENTER("top-center"),
    TOP_RIGHT("top-right"),
    CENTER_LEFT("center-left"),
    CENTER("center"),
    CENTER_RIGHT("center-right"),
    BOTTOM_LEFT("bottom-left"),
    BOTTOM_CENTER("bottom-center"),
    BOTTOM_RIGHT("bottom-right");

    companion object {
        fun fromId(id: String): WatermarkPosition {
            val clean = id.lowercase().replace("_", "-")
            return entries.firstOrNull { it.idString == clean } ?: BOTTOM_RIGHT
        }
    }
}

/**
 * Canonical configuration for rendering text watermark overlays.
 */
data class TextWatermarkConfig(
    val text: String,
    val fontSizeSp: Float = 24f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    val opacity: Float = 1.0f
)

/**
 * Audio handling modes for video processing.
 */
enum class AudioMode {
    KEEP,
    MUTE,
    COMPRESS_AAC_128K,
    VOICE_64K,
    HIGH_FIDELITY_256K;

    val processingMode: AudioProcessingMode
        get() = when (this) {
            MUTE -> AudioProcessingMode.MUTE
            KEEP -> AudioProcessingMode.KEEP
            else -> AudioProcessingMode.ENCODE
        }

    companion object {
        fun fromLabel(label: String): AudioMode {
            return when {
                label.contains("Mute", ignoreCase = true) || label.contains("Strip", ignoreCase = true) -> MUTE
                label.contains("256", ignoreCase = true) -> HIGH_FIDELITY_256K
                label.contains("128", ignoreCase = true) -> COMPRESS_AAC_128K
                label.contains("64", ignoreCase = true) -> VOICE_64K
                else -> KEEP
            }
        }
    }
}

/**
 * Live preview state for image editing.
 * Transformations are non-destructive and strictly rendered from the original source bitmap.
 */
data class ImageEditState(
    var cropAspect: String = "Free",
    var cropLeft: Float = 0f,
    var cropTop: Float = 0f,
    var cropRight: Float = 1f,
    var cropBottom: Float = 1f,
    var resizeScale: Int = 100,
    var resizeWidth: Int = 0,
    var resizeHeight: Int = 0,
    var keepAspect: Boolean = true,
    var rotationAngle: Float = 0f,
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    var filter: String = "Default",
    var bgType: String = "Transparent",
    var watermarkText: String = "",
    var watermarkPosition: String = "bottom-right",
    var watermarkSize: Int = 32,
    var watermarkColor: String = "#FFFFFF",
    var watermarkOpacity: Float = 1.0f,
    var watermarkFont: String = "Sans-Serif",
    var stripExif: Boolean = true,
    var exifMake: String = "",
    var exifModel: String = "",
    var exifSoftware: String = "",
    var exifDateTime: String = "",
    var exifGps: String = ""
) {
    fun isCropped(): Boolean {
        return (cropAspect != "Free" && cropAspect != "Original") ||
                (cropLeft > 0.001f || cropTop > 0.001f || cropRight < 0.999f || cropBottom < 0.999f)
    }

    fun hasEdits(): Boolean {
        return isCropped() ||
                (resizeScale != 100) ||
                (resizeWidth > 0 && resizeHeight > 0) ||
                (rotationAngle != 0f) ||
                flipH ||
                flipV ||
                (filter != "Default" && filter != "None") ||
                (bgType != "Transparent") ||
                watermarkText.isNotEmpty() ||
                (!stripExif) ||
                exifMake.isNotEmpty() ||
                exifModel.isNotEmpty() ||
                exifSoftware.isNotEmpty() ||
                exifDateTime.isNotEmpty() ||
                exifGps.isNotEmpty()
    }

    fun reset() {
        cropAspect = "Free"
        cropLeft = 0f
        cropTop = 0f
        cropRight = 1f
        cropBottom = 1f
        resizeScale = 100
        resizeWidth = 0
        resizeHeight = 0
        keepAspect = true
        rotationAngle = 0f
        flipH = false
        flipV = false
        filter = "Default"
        bgType = "Transparent"
        watermarkText = ""
        watermarkPosition = "bottom-right"
        watermarkSize = 32
        watermarkColor = "#FFFFFF"
        watermarkOpacity = 1.0f
        watermarkFont = "Sans-Serif"
        stripExif = true
        exifMake = ""
        exifModel = ""
        exifSoftware = ""
        exifDateTime = ""
        exifGps = ""
    }

    fun deepCopy(): ImageEditState {
        return this.copy()
    }
}

/**
 * Output compression parameters for image encoding.
 */
data class ImageOutputConfig(
    var format: String = "JPG",
    var quality: Int = 85,
    var compressionMode: String = "percentage", // "percentage" or "target_size"
    var targetSizeKb: Int? = null,
    var targetSizeBytes: Long? = null,
    var outputFileName: String = ""
)

/**
 * Live preview and edit state for video editing.
 */
data class VideoEditState(
    var trimStartMs: Long = 0L,
    var trimEndMs: Long = 0L,
    var durationMs: Long = 0L,
    var originalWidth: Int = 1920,
    var originalHeight: Int = 1080,
    var scalePreset: String = "Original (No scaling)",
    var aspect: String = "Original",
    var speed: Float = 1.0f,
    var audioMode: AudioMode = AudioMode.KEEP,
    var audioVolume: Float = 1.0f,
    var audioChannels: String = "keep", // "keep", "stereo", "mono"
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    var rotationAngle: Int = 0, // 0, 90, 180, 270
    var customCropPercent: Int = 0,
    var fps: Int? = null,
    var colorProfile: String = "Original"
) {
    fun hasEdits(): Boolean {
        return (trimStartMs > 0L) ||
                (durationMs > 0L && trimEndMs < durationMs) ||
                (scalePreset != "Original (No scaling)" && scalePreset != "Original") ||
                (speed != 1.0f) ||
                (aspect != "Original") ||
                (colorProfile != "Original" && colorProfile != "None") ||
                (audioMode != AudioMode.KEEP) ||
                (audioVolume != 1.0f) ||
                (audioChannels != "keep") ||
                flipH ||
                flipV ||
                (rotationAngle != 0) ||
                (customCropPercent > 0) ||
                (fps != null)
    }

    fun hasVideoTransforms(): Boolean {
        return (scalePreset != "Original (No scaling)" && scalePreset != "Original") ||
                (aspect != "Original") ||
                (speed != 1.0f) ||
                (colorProfile != "Original" && colorProfile != "None") ||
                flipH ||
                flipV ||
                (rotationAngle != 0) ||
                (customCropPercent > 0) ||
                (fps != null && fps!! > 0)
    }

    val trimmedDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    val trimStartSeconds: Double
        get() = trimStartMs / 1000.0

    val trimmedDurationSeconds: Double
        get() = trimmedDurationMs / 1000.0

    fun reset(totalDurationMs: Long = 0L) {
        trimStartMs = 0L
        trimEndMs = totalDurationMs
        durationMs = totalDurationMs
        scalePreset = "Original (No scaling)"
        aspect = "Original"
        speed = 1.0f
        colorProfile = "Original"
        audioMode = AudioMode.KEEP
        audioVolume = 1.0f
        audioChannels = "keep"
        flipH = false
        flipV = false
        rotationAngle = 0
        customCropPercent = 0
        fps = null
    }
}

/**
 * Output compression parameters for video encoding.
 */
data class VideoOutputConfig(
    var format: String = "MP4",
    var codec: String = "H.264",
    var crf: Int = 28,
    var targetPreset: String = "Auto (Balanced CRF 28)",
    var targetMb: Float? = null,
    var compressionPreset: String = "slow", // "slow" (Max Quality, Smaller Size), "medium" (Balanced), "fast" (Quick Export)
    var audioCodec: String = "aac",
    var audioBitrateKbps: Int? = null,
    var outputMode: VideoOutputMode = VideoOutputMode.VIDEO,
    var outputFileName: String = "",
    var whatsappStatusResolution: com.veilframe.app.media.whatsapp.WhatsappStatusResolution = com.veilframe.app.media.whatsapp.WhatsappStatusResolution.HD_720P
)
