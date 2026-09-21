package com.veilframe.app.media.whatsapp

import com.veilframe.app.media.CropSpec
import java.util.Locale

/**
 * Builds modular FFmpeg filter_complex graphs for WhatsApp Status exports.
 *
 * Supports:
 * - Verified HDR to SDR Hable tone mapping into BT.709
 * - 9:16 Status crop-to-fill canvas targeting selected resolution (720x1280 for HD, 1080x1920 for FHD)
 * - Custom normalized CropSpec
 * - Orientation, flip, rotation, and speed transforms
 * - Normalized yuv420p pixel format terminating at [vout]
 */
object WhatsappStatusFilterGraphBuilder {

    /**
     * Builds the complete filter_complex string for WhatsApp Status video encoding.
     * Preserves Display Aspect Ratio (DAR) without crop in Original mode.
     * Applies target-aspect crop then bounded scaling in explicit aspect modes.
     * Strictly enforces setsar=1 and even dimension rounding.
     */
    fun buildVideoFilterGraph(
        resolution: WhatsappStatusResolution,
        srcWidth: Int = 0,
        srcHeight: Int = 0,
        aspect: String = "Original",
        cropSpec: CropSpec? = null,
        isHdr: Boolean = false,
        flipH: Boolean = false,
        flipV: Boolean = false,
        rotate: Int = 0,
        speed: Float = 1.0f,
        colorProfile: String = "Original",
        sar: Double = 1.0
    ): String {
        val displayW = if (sar > 0.0 && sar != 1.0) (srcWidth * sar).toInt() else srcWidth
        val effW = if (rotate == 90 || rotate == 270) srcHeight else displayW
        val effH = if (rotate == 90 || rotate == 270) displayW else srcHeight
        val (targetW, targetH) = resolution.calculateBoundedDimensions(effW, effH, aspect)

        val filterChains = mutableListOf<String>()
        var currentInput = "0:v"

        // 1. HDR to SDR tone mapping via Hable & BT.709 conversion
        if (isHdr) {
            val hdrToneMap = "[$currentInput]zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,setsar=1[v_sdr]"
            filterChains.add(hdrToneMap)
            currentInput = "v_sdr"
        }

        // 2. Spatial, geometric, and aspect transformations
        val transforms = mutableListOf<String>()

        if (flipH) transforms.add("hflip")
        if (flipV) transforms.add("vflip")

        when (rotate) {
            90 -> transforms.add("transpose=1")
            180 -> transforms.add("hflip,vflip")
            270 -> transforms.add("transpose=2")
        }

        // Custom crop if specified
        if (cropSpec != null && !cropSpec.isIdentity()) {
            val cropW = String.format(Locale.US, "trunc(iw*%.4f/2)*2", cropSpec.widthFraction)
            val cropH = String.format(Locale.US, "trunc(ih*%.4f/2)*2", cropSpec.heightFraction)
            val cropX = String.format(Locale.US, "trunc(iw*%.4f/2)*2", cropSpec.left)
            val cropY = String.format(Locale.US, "trunc(ih*%.4f/2)*2", cropSpec.top)
            transforms.add("crop=$cropW:$cropH:$cropX:$cropY")
        }

        // Normalize non-square SAR to 1:1 square pixels before scaling
        transforms.add("setsar=1")

        val isOriginalAspect = aspect.equals("Original", ignoreCase = true) || aspect.isBlank()
        if (isOriginalAspect) {
            // Original aspect: Proportional resize strictly fitting inside bounds without cropping or stretching
            transforms.add("scale=$targetW:$targetH")
        } else {
            // Explicit aspect ratio target: crop to composition, then scale to bounded canvas
            transforms.add("scale=$targetW:$targetH:force_original_aspect_ratio=increase")
            transforms.add("crop=$targetW:$targetH")
        }

        // Playback speed timing
        if (Math.abs(speed - 1.0f) > 0.01f && speed > 0.1f) {
            val ptsMultiplier = 1.0 / speed
            transforms.add(String.format(Locale.US, "setpts=%.4f*PTS", ptsMultiplier))
        }

        // Color look adjustments if requested
        val colorFilter = com.veilframe.app.media.preview.VideoColorFilterHelper.getFFmpegFilter(colorProfile)
        if (!colorFilter.isNullOrEmpty()) {
            transforms.add(colorFilter)
        }

        // Final normalization to yuv420p
        transforms.add("format=${WhatsappStatusConstants.PIXEL_FORMAT}")

        val transformChain = "[$currentInput]" + transforms.joinToString(",") + "[vout]"
        filterChains.add(transformChain)

        return filterChains.joinToString(";")
    }

    /**
     * Builds the filter graph for Photo-to-Status (5-second 29.97 fps video).
     */
    fun buildPhotoStatusFilterGraph(resolution: WhatsappStatusResolution): String {
        val targetW = resolution.width
        val targetH = resolution.height
        return "scale=$targetW:$targetH:force_original_aspect_ratio=increase,crop=$targetW:$targetH,format=${WhatsappStatusConstants.PIXEL_FORMAT}"
    }
}
