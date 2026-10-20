package com.veilframe.app.media.dialogs

import android.view.View
import com.veilframe.app.databinding.DialogVideoPresetBinding

/**
 * Controller helper for DialogVideoPresetBinding to enforce parameter compatibility.
 * Prevents mutually exclusive encoding combinations:
 * 1. Target Size limits override CRF; disables CRF slider when non-Auto preset is active.
 * 2. "Copy Stream" skips encoding; disables CRF slider, target size limits, and incompatible containers.
 * 3. WebM container requires VP9 codec; disables H.264 / H.265.
 */
class VideoPresetDialogHelper(
    private val binding: DialogVideoPresetBinding
) {
    fun setup() {
        binding.sliderCrf.addOnChangeListener { _, value, _ ->
            binding.tvCrfValue.text = value.toInt().toString()
        }

        binding.chipGroupTargetSize.setOnCheckedStateChangeListener { _, _ ->
            updateCompatibilityState()
        }

        binding.chipGroupVideoCodecDialog.setOnCheckedStateChangeListener { _, _ ->
            updateCompatibilityState()
        }

        binding.chipGroupVideoContainerDialog.setOnCheckedStateChangeListener { _, _ ->
            updateCompatibilityState()
        }

        updateCompatibilityState()
    }

    fun updateCompatibilityState() {
        val isCopyStream = binding.chipCodecCopy.isChecked
        val isWebm = binding.chipContainerWebm.isChecked
        val isAutoPreset = binding.chipPresetAuto.isChecked

        // 1. Container & Codec restrictions
        if (isWebm) {
            binding.chipCodecH264.isEnabled = false
            binding.chipCodecH265.isEnabled = false
            binding.chipCodecCopy.isEnabled = false
            binding.chipCodecVp9.isEnabled = true
            if (!binding.chipCodecVp9.isChecked) {
                binding.chipCodecVp9.isChecked = true
            }
        } else {
            binding.chipCodecH264.isEnabled = true
            binding.chipCodecH265.isEnabled = true
            binding.chipCodecVp9.isEnabled = true
            binding.chipCodecCopy.isEnabled = true
        }

        if (binding.chipCodecH264.isChecked || binding.chipCodecH265.isChecked) {
            binding.chipContainerWebm.isEnabled = false
            if (binding.chipContainerWebm.isChecked) {
                binding.chipContainerMp4.isChecked = true
            }
        } else {
            binding.chipContainerWebm.isEnabled = true
        }

        // 2. Copy Stream restrictions
        if (isCopyStream) {
            binding.chipPresetWhatsapp.isEnabled = false
            binding.chipPresetInstagram.isEnabled = false
            binding.chipPresetTikTok.isEnabled = false
            binding.chipPresetYouTubeShorts.isEnabled = false
            binding.chipPresetTwitter.isEnabled = false
            binding.chipPresetTelegram.isEnabled = false
            binding.chipPresetDiscord.isEnabled = false
            binding.chipPresetNitro.isEnabled = false
            binding.chipPresetEmail.isEnabled = false
            binding.chipPresetWeb.isEnabled = false
            if (!binding.chipPresetAuto.isChecked) {
                binding.chipPresetAuto.isChecked = true
            }
            binding.chipContainerWebm.isEnabled = false
            binding.chipContainerGif.isEnabled = false
        } else {
            binding.chipPresetWhatsapp.isEnabled = true
            binding.chipPresetInstagram.isEnabled = true
            binding.chipPresetTikTok.isEnabled = true
            binding.chipPresetYouTubeShorts.isEnabled = true
            binding.chipPresetTwitter.isEnabled = true
            binding.chipPresetTelegram.isEnabled = true
            binding.chipPresetDiscord.isEnabled = true
            binding.chipPresetNitro.isEnabled = true
            binding.chipPresetEmail.isEnabled = true
            binding.chipPresetWeb.isEnabled = true
            binding.chipContainerGif.isEnabled = true
        }

        // 3. CRF slider enable/disable based on Target Size / Copy Stream
        val crfActive = binding.chipPresetAuto.isChecked && !isCopyStream
        binding.sliderCrf.isEnabled = crfActive
        binding.containerCrfSection.alpha = if (crfActive) 1.0f else 0.4f
    }
}
