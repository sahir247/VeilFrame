package com.veilframe.app.upscale.model

/**
 * Metadata definition for image upscaling models.
 * Strictly adheres to Point 7 specification (3 algorithmic models, 3 AI ONNX models).
 */
data class UpscaleModel(
    val id: String,
    val name: String,
    val description: String,
    val type: ModelType,
    val nativeScale: Int,
    val sizeBytes: Long,
    val downloadUrl: String = "",
    val sha256: String = "",
    val supportedScales: List<Int> = listOf(nativeScale),
    val recommendedPresetIds: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val supportsAlpha: Boolean = true
)

enum class ModelType {
    ALGORITHMIC,
    AI_ONNX
}
