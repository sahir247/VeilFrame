package com.veilframe.app.media.whatsapp

import com.veilframe.app.media.AspectSpec

/**
 * Typed validation specification for WhatsApp Status outputs.
 * Provides expected resolution bounds, target aspect ratio, and source geometry context.
 */
data class WhatsappStatusValidationSpec(
    val resolution: WhatsappStatusResolution = WhatsappStatusResolution.HD_720P,
    val aspectSpec: AspectSpec = AspectSpec.Original,
    val sourceGeometry: ResolvedGeometry? = null,
    val maxSizeBytes: Long = WhatsappStatusRateControl.WHATSAPP_STATUS_SIZE_CEILING_BYTES
) {
    constructor(
        expectedWidth: Int,
        expectedHeight: Int,
        targetAspect: String = "Original",
        sourceWidth: Int = expectedWidth,
        sourceHeight: Int = expectedHeight,
        maxSizeBytes: Long = WhatsappStatusRateControl.WHATSAPP_STATUS_SIZE_CEILING_BYTES
    ) : this(
        resolution = if (maxOf(expectedWidth, expectedHeight) > 1280) WhatsappStatusResolution.FHD_1080P else WhatsappStatusResolution.HD_720P,
        aspectSpec = AspectSpec.fromString(targetAspect),
        sourceGeometry = ResolvedGeometry(sourceWidth, sourceHeight),
        maxSizeBytes = maxSizeBytes
    )

    /**
     * Calculates the expected Display Aspect Ratio (DAR) target.
     * In "Original" mode, uses the effective DAR from sourceGeometry if available.
     * Returns null if unconstrained.
     */
    fun expectedTargetDar(): Double? {
        return when (aspectSpec) {
            is AspectSpec.Ratio -> aspectSpec.aspect.toDouble()
            is AspectSpec.Original -> sourceGeometry?.effectiveDar
            is AspectSpec.Free -> null
        }
    }
}
