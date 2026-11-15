package com.veilframe.app.media

/**
 * Camera path optimization algorithm supported by FFmpeg `vidstabtransform`.
 */
enum class EisCameraPathAlgorithm(val ffmpegValue: String, val label: String) {
    GAUSS("gauss", "Gaussian Smooth"),
    OPT("opt", "Optimal L1")
}

/**
 * Sub-pixel warping interpolation method supported by FFmpeg `vidstabtransform`.
 */
enum class EisInterpolationMode(val ffmpegValue: String, val label: String) {
    BILINEAR("bilinear", "Bilinear (Balanced)"),
    BICUBIC("bicubic", "Bicubic (Crisp)")
}

/**
 * Adaptive border zoom mode supported by FFmpeg `vidstabtransform` (`optzoom`).
 */
enum class EisOptZoomMode(val ffmpegValue: Int, val label: String) {
    OFF(0, "Manual Zoom Only"),
    OPTIMAL_STATIC(1, "Auto Static Zoom"),
    ADAPTIVE_DYNAMIC(2, "Adaptive Dynamic Zoom")
}

/**
 * Production stabilization presets mapping real-world shooting scenarios to tuned two-pass `libvidstab` parameters.
 */
enum class EisPresetProfile(
    val label: String,
    val description: String,
    val configFactory: () -> VideoEisConfig
) {
    HANDHELD_WALK(
        label = "Handheld Walk",
        description = "Balanced two-pass Gaussian smoothing with auto edge zoom and subtle detail recovery",
        configFactory = {
            VideoEisConfig(
                presetProfile = HANDHELD_WALK,
                shakiness = 5,
                accuracy = 15,
                stepSize = 6,
                smoothing = 20,
                zoomPercent = 0,
                cropBlackBorders = false,
                cameraPathAlgorithm = EisCameraPathAlgorithm.GAUSS,
                optZoomMode = EisOptZoomMode.OPTIMAL_STATIC,
                interpolation = EisInterpolationMode.BILINEAR,
                tripodMode = false,
                sharpenStrength = 0.8f,
                downscaleTo540p = false
            )
        }
    ),
    ACTION_SPORT(
        label = "Action / Run",
        description = "High shakiness search with L1 optimal camera path and dynamic zoom for heavy shake",
        configFactory = {
            VideoEisConfig(
                presetProfile = ACTION_SPORT,
                shakiness = 10,
                accuracy = 15,
                stepSize = 6,
                smoothing = 25,
                zoomPercent = 5,
                cropBlackBorders = false,
                cameraPathAlgorithm = EisCameraPathAlgorithm.OPT,
                optZoomMode = EisOptZoomMode.ADAPTIVE_DYNAMIC,
                interpolation = EisInterpolationMode.BICUBIC,
                tripodMode = false,
                sharpenStrength = 1.0f,
                downscaleTo540p = false
            )
        }
    ),
    GENTLE_CINEMA(
        label = "Cinema Pan",
        description = "Preserves deliberate pans while filtering micro-jitter with minimal crop",
        configFactory = {
            VideoEisConfig(
                presetProfile = GENTLE_CINEMA,
                shakiness = 5,
                accuracy = 15,
                stepSize = 12,
                smoothing = 10,
                zoomPercent = 0,
                cropBlackBorders = false,
                cameraPathAlgorithm = EisCameraPathAlgorithm.GAUSS,
                optZoomMode = EisOptZoomMode.OPTIMAL_STATIC,
                interpolation = EisInterpolationMode.BICUBIC,
                tripodMode = false,
                sharpenStrength = 0.6f,
                downscaleTo540p = false
            )
        }
    ),
    TRIPOD_LOCK(
        label = "Tripod Lock",
        description = "Virtual tripod mode locking frame geometry against fixed background reference",
        configFactory = {
            VideoEisConfig(
                presetProfile = TRIPOD_LOCK,
                shakiness = 8,
                accuracy = 15,
                stepSize = 6,
                smoothing = 30,
                zoomPercent = 5,
                cropBlackBorders = false,
                cameraPathAlgorithm = EisCameraPathAlgorithm.OPT,
                optZoomMode = EisOptZoomMode.OPTIMAL_STATIC,
                interpolation = EisInterpolationMode.BICUBIC,
                tripodMode = true,
                sharpenStrength = 0.8f,
                downscaleTo540p = false
            )
        }
    ),
    FAST_540P(
        label = "Fast 540p Mode",
        description = "Speed-optimized 540p two-pass pipeline for rapid preview or low-spec devices",
        configFactory = {
            VideoEisConfig(
                presetProfile = FAST_540P,
                shakiness = 5,
                accuracy = 8,
                stepSize = 24,
                smoothing = 15,
                zoomPercent = 0,
                cropBlackBorders = false,
                cameraPathAlgorithm = EisCameraPathAlgorithm.GAUSS,
                optZoomMode = EisOptZoomMode.OPTIMAL_STATIC,
                interpolation = EisInterpolationMode.BILINEAR,
                tripodMode = false,
                sharpenStrength = 0.8f,
                downscaleTo540p = true
            )
        }
    ),
    CUSTOM(
        label = "Custom",
        description = "User-configured libvidstab detection, camera path, zoom & sharpening settings",
        configFactory = { VideoEisConfig(presetProfile = CUSTOM) }
    );

    fun createConfig(): VideoEisConfig = configFactory()
}

/**
 * Full configuration for two-pass FFmpeg `libvidstab` Electronic Image Stabilization (`vidstabdetect` + `vidstabtransform`).
 */
data class VideoEisConfig(
    val presetProfile: EisPresetProfile = EisPresetProfile.HANDHELD_WALK,
    val shakiness: Int = 5,
    val accuracy: Int = 15,
    val stepSize: Int = 6,
    val smoothing: Int = 20,
    val zoomPercent: Int = 0,
    val cropBlackBorders: Boolean = false,
    val cameraPathAlgorithm: EisCameraPathAlgorithm = EisCameraPathAlgorithm.GAUSS,
    val optZoomMode: EisOptZoomMode = EisOptZoomMode.OPTIMAL_STATIC,
    val zoomSpeed: Float = 0.25f,
    val interpolation: EisInterpolationMode = EisInterpolationMode.BILINEAR,
    val tripodMode: Boolean = false,
    val sharpenStrength: Float = 0.8f,
    val downscaleTo540p: Boolean = false
) {
    /**
     * Returns a bounds-clamped copy guaranteed to satisfy `libvidstab` parameter constraints.
     */
    fun clamped(): VideoEisConfig {
        return copy(
            shakiness = shakiness.coerceIn(1, 10),
            accuracy = accuracy.coerceIn(1, 15),
            stepSize = stepSize.coerceIn(1, 32),
            smoothing = smoothing.coerceIn(0, 30),
            zoomPercent = zoomPercent.coerceIn(-5, 30),
            zoomSpeed = zoomSpeed.coerceIn(0.05f, 2.0f),
            sharpenStrength = sharpenStrength.coerceIn(0f, 1.5f)
        )
    }

    fun shortSummary(): String {
        val mode = if (tripodMode) "Tripod Lock" else "${presetProfile.label} • Smooth $smoothing"
        val zoomDesc = if (zoomPercent != 0) " • ${if (zoomPercent > 0) "+" else ""}$zoomPercent% Zoom" else ""
        val scaleDesc = if (downscaleTo540p) " • 540p" else ""
        return "$mode$zoomDesc$scaleDesc"
    }

    companion object {
        val SHAKINESS_PRESETS = listOf(1, 5, 10)
        val ACCURACY_PRESETS = listOf(1, 8, 15)
        val STEP_SIZE_PRESETS = listOf(1, 6, 12, 24, 32)
        val SMOOTHING_PRESETS = listOf(0, 5, 10, 15, 20, 25, 30)
        val ZOOM_PRESETS = listOf(0, 5, 10, 15, 20, 25, 30)
    }
}

