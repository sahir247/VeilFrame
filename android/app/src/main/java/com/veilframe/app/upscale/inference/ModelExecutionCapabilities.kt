package com.veilframe.app.upscale.inference

/**
 * Declares operational constraints of an AI super-resolution model extracted via graph introspection.
 * Used by the AdaptiveExecutionPlanner to evaluate candidate configurations for NNAPI and CPU.
 */
data class ModelExecutionCapabilities(
    val nativeScale: Int,
    val supportedOutputScales: List<Int> = listOf(nativeScale, nativeScale * 2),
    val inputType: String = "FLOAT",
    val outputType: String = "FLOAT",
    val fixedWidth: Int? = null,
    val fixedHeight: Int? = null,
    val minSpatialSize: Int = 32,
    val tileCompatible: Boolean = true,
    val supportsFp16: Boolean = true
) {
    val isDynamicSpatial: Boolean
        get() = fixedWidth == null && fixedHeight == null
}


