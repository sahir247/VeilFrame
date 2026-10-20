package com.veilframe.app.upscale.model

import com.veilframe.app.upscale.preset.UpscalePreset
import com.veilframe.app.upscale.preset.UpscalePresetRegistry

/**
 * Resolves the appropriate UpscaleModel based on preset, manual override, target scale, and installation.
 *
 * Resolution is fail-closed: a misspelled preset/model ID must never silently switch
 * the user's requested AI operation to Lanczos.
 */
object UpscaleModelResolver {

    fun resolveModel(
        presetId: String,
        overrideModelId: String? = null
    ): UpscaleModel {
        if (!overrideModelId.isNullOrEmpty()) {
            return requireNotNull(UpscaleModelRegistry.getModelById(overrideModelId)) {
                "Unknown upscale model override '$overrideModelId'. No fallback was applied."
            }
        }

        val preset = requireNotNull(UpscalePresetRegistry.getPresetById(presetId)) {
            "Unknown upscale preset '$presetId'. No fallback was applied."
        }
        return requireNotNull(UpscaleModelRegistry.getModelById(preset.recommendedModelId)) {
            "Preset '${preset.id}' refers to unregistered model '${preset.recommendedModelId}'. No fallback was applied."
        }
    }

    fun resolve(
        preset: UpscalePreset,
        targetScale: Int,
        repository: UpscaleModelRepository,
        explicitModel: UpscaleModel? = null
    ): UpscaleModel {
        if (explicitModel != null) return explicitModel

        val recommended = requireNotNull(UpscaleModelRegistry.getModelById(preset.recommendedModelId)) {
            "Preset refers to an unregistered model. No fallback was applied."
        }

        return recommended
    }
}
