package com.veilframe.app.upscale.inference

/**
 * Extensible tile-size policy for AI inference.
 * Replaces hardcoded tile sizes with dynamic candidate generation based on
 * model constraints, memory budgets, and device capabilities.
 */
object TileSizePolicy {

    val BASELINE_CANDIDATES = listOf(256, 384, 512)
    val EXTENDED_FLAGSHIP_CANDIDATES = listOf(256, 384, 512, 640, 768)

    /**
     * Resolves the list of eligible candidate tile sizes to benchmark.
     */
    fun candidates(
        modelCaps: ModelExecutionCapabilities,
        deviceProfile: DeviceCapabilityProfile,
        javaBudgetBytes: Long,
        nativeBudgetBytes: Long
    ): List<Int> {
        // 1. Fixed spatial dimension models must adhere strictly to model geometry
        if (modelCaps.fixedWidth != null && modelCaps.fixedWidth > 0) {
            return listOf(modelCaps.fixedWidth)
        }

        // 2. Determine base pool according to memory headroom
        val candidatePool = if (nativeBudgetBytes >= 1536L * 1024 * 1024 && javaBudgetBytes >= 512L * 1024 * 1024) {
            EXTENDED_FLAGSHIP_CANDIDATES
        } else {
            BASELINE_CANDIDATES
        }

        // 3. Filter candidates that exceed single-tile memory safety limits
        val validCandidates = candidatePool.filter { tileSize ->
            if (tileSize < modelCaps.minSpatialSize) return@filter false

            // Verify single tile memory requirement fits within both budgets with safety margin
            val scale = modelCaps.nativeScale
            val tileOutBytes = (tileSize.toLong() * scale) * (tileSize.toLong() * scale) * 4L
            val tileInBytes = tileSize.toLong() * tileSize.toLong() * 3L * 4L
            val onnxActivationEstimate = tileOutBytes * 3L

            val fitsJava = tileOutBytes < (javaBudgetBytes * 0.75)
            val fitsNative = (tileInBytes + onnxActivationEstimate) < (nativeBudgetBytes * 0.75)

            fitsJava && fitsNative
        }

        return if (validCandidates.isNotEmpty()) validCandidates else listOf(256)
    }
}
