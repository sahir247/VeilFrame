package com.veilframe.app.upscale.model

import com.veilframe.app.upscale.preset.UpscalePreset
import com.veilframe.app.upscale.preset.UpscalePresetRegistry

/**
 * Resolves the appropriate UpscaleModel based on preset, manual override, target scale, and installation.
 */
object UpscaleModelResolver {

    fun resolveModel(
        presetId: String,
        overrideModelId: String? = null
    ): UpscaleModel {
        if (!overrideModelId.isNullOrEmpty()) {
            val overridden = UpscaleModelRegistry.getModelById(overrideModelId)
            if (overridden != null) return overridden
        }

        val preset = UpscalePresetRegistry.getPresetById(presetId) ?: UpscalePresetRegistry.PHOTO
        val recommended = UpscaleModelRegistry.getModelById(preset.recommendedModelId)
        return recommended ?: UpscaleModelRegistry.LANCZOS
    }

    fun resolve(
        preset: UpscalePreset,
        targetScale: Int,
        repository: UpscaleModelRepository,
        explicitModel: UpscaleModel? = null
    ): UpscaleModel {
        if (explicitModel != null) return explicitModel

        val recommended = UpscaleModelRegistry.getModelById(preset.recommendedModelId)
        if (recommended != null) {
            // If scale is 2x but recommended model is 4x native and not built-in, prefer 2x model if available
            if (targetScale == 2 && recommended.nativeScale == 4 && !recommended.isBuiltIn) {
                if (repository.isModelInstalled(UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X)) {
                    return UpscaleModelRegistry.REAL_ESRGAN_GENERAL_2X
                }
            }
            return recommended
        }
        return UpscaleModelRegistry.LANCZOS
    }
}
