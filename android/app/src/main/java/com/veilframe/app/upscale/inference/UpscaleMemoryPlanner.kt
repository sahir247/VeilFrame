package com.veilframe.app.upscale.inference

/**
 * Calculates memory budgets and plans optimal tile sizes to avoid Android OutOfMemory errors.
 */
object UpscaleMemoryPlanner {

    // 50 MP input limit (e.g. ~8192×6144) to prevent excessive downscaling or out-of-bounds inputs
    const val MAX_SOURCE_PIXELS = 50_000_000L

    // Constrain output to safe working budget: 36 MP (~144 MB ARGB_8888) as maximum absolute ceiling on mobile
    const val MAX_OUTPUT_PIXELS_ABSOLUTE = 36_000_000L

    data class MemoryPlan(
        val tileSize: Int,
        val overlap: Int = 24,
        val estimatedWorkingSetBytes: Long = 0L,
        val maxOutputBitmapBytes: Long = 0L,
        val isSafe: Boolean,
        val warningMessage: String? = null
    )

    fun plan(sourceWidth: Int, sourceHeight: Int, scale: Int, isAiModel: Boolean = false): MemoryPlan {
        val srcPixels = sourceWidth.toLong() * sourceHeight.toLong()
        val targetWidth = sourceWidth.toLong() * scale
        val targetHeight = sourceHeight.toLong() * scale
        val outPixels = targetWidth * targetHeight
        val outputBitmapBytes = outPixels * 4L // ARGB_8888 is 4 bytes per pixel

        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemoryInHeap = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemoryInHeap
        val freeMemory = (maxMemory - usedMemory).coerceAtLeast(64 * 1024 * 1024L)
        val freeMb = freeMemory / (1024 * 1024)

        // Dynamically compute safe budget: max 60% of available memory, capped at 192 MB for the output bitmap
        val safeBitmapMemoryLimit = minOf((freeMemory * 0.60).toLong(), 192 * 1024 * 1024L)

        if (srcPixels > MAX_SOURCE_PIXELS) {
            return MemoryPlan(
                tileSize = 256,
                overlap = 16,
                estimatedWorkingSetBytes = outputBitmapBytes,
                maxOutputBitmapBytes = safeBitmapMemoryLimit,
                isSafe = false,
                warningMessage = "Source resolution (${sourceWidth}×${sourceHeight} = ${srcPixels / 1_000_000} MP) exceeds safe limit of 50 MP. Please crop or downscale before processing."
            )
        }

        if (outPixels > MAX_OUTPUT_PIXELS_ABSOLUTE || outputBitmapBytes > safeBitmapMemoryLimit) {
            val outMp = String.format(java.util.Locale.US, "%.1f", outPixels / 1_000_000.0)
            val outMb = outputBitmapBytes / (1024 * 1024)
            val limitMb = safeBitmapMemoryLimit / (1024 * 1024)
            return MemoryPlan(
                tileSize = 256,
                overlap = 16,
                estimatedWorkingSetBytes = outputBitmapBytes,
                maxOutputBitmapBytes = safeBitmapMemoryLimit,
                isSafe = false,
                warningMessage = "Target resolution ($scale× = ${targetWidth}×${targetHeight}, ${outMp} MP, ~$outMb MB) exceeds safe working budget (~$limitMb MB). Please select a lower scaling factor or crop."
            )
        }

        // Standard tile sizes: 256 (conservative), 384 (balanced), 512 (performance / high memory).
        // 768 and 1024 are removed to prevent native tensor spikes and CPU starvation.
        val (tileSize, overlap) = if (isAiModel) {
            when {
                freeMb >= 384 -> 384 to 24
                else -> 256 to 16
            }
        } else {
            when {
                freeMb >= 512 -> 512 to 32
                freeMb >= 256 -> 384 to 24
                else -> 256 to 16
            }
        }

        // Calculate estimated working set = output bitmap + tile input buffer + tile output tensor & intermediate activation estimate
        val tileInBytes = tileSize.toLong() * tileSize.toLong() * 3L * 4L
        val tileOutPixels = (tileSize.toLong() * scale) * (tileSize.toLong() * scale)
        val tileOutBytes = tileOutPixels * 4L // ARGB
        val onnxActivationEstimateBytes = if (isAiModel) tileOutBytes * 3L else 0L
        val estimatedWorkingSet = outputBitmapBytes + tileInBytes + tileOutBytes + onnxActivationEstimateBytes

        return MemoryPlan(
            tileSize = tileSize,
            overlap = overlap,
            estimatedWorkingSetBytes = estimatedWorkingSet,
            maxOutputBitmapBytes = safeBitmapMemoryLimit,
            isSafe = true
        )
    }
}
