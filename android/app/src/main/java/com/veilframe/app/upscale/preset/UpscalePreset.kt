package com.veilframe.app.upscale.preset

/**
 * Definition of user presets for Image Upscaler.
 * Presets recommend models automatically so users don't need to understand AI architectures.
 */
data class UpscalePreset(
    val id: String,
    val name: String,
    val description: String,
    val recommendedModelId: String,
    val defaultScale: Int = 2
)
