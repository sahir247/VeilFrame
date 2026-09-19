package com.veilframe.app.upscale.inference

/**
 * Calculates memory budgets and plans optimal tile sizes to avoid Android OutOfMemory errors.
 */
object UpscaleMemoryPlanner {

    const val MAX_SOURCE_PIXELS = 100_000_000L // 100 MP limit
    const val MAX_OUTPUT_PIXELS = 400_000_000L // 400 MP limit

    data class MemoryPlan(
        val tileSize: Int,
        val overlap: Int = 32,
        val isSafe: Boolean,
        val warningMessage: String? = null
    )

    fun plan(sourceWidth: Int, sourceHeight: Int, scale: Int, isAiModel: Boolean = false): MemoryPlan {
        val srcPixels = sourceWidth.toLong() * sourceHeight.toLong()
        val outPixels = srcPixels * scale.toLong() * scale.toLong()

        if (srcPixels > MAX_SOURCE_PIXELS) {
            return MemoryPlan(
                tileSize = 512,
                isSafe = false,
                warningMessage = "Source resolution (${sourceWidth}x${sourceHeight} = ${srcPixels / 1_000_000} MP) exceeds safe limit of 100 MP. Please crop or downscale before processing."
            )
        }

        if (outPixels > MAX_OUTPUT_PIXELS) {
            return MemoryPlan(
                tileSize = 512,
                isSafe = false,
                warningMessage = "Target resolution ($scale× = ${outPixels / 1_000_000} MP) exceeds output limit of 400 MP. Choose a lower scaling factor."
            )
        }

        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val freeMb = freeMemory / (1024 * 1024)

        // Select tile size based on model type and available heap.
        // Neural models (Real-ESRGAN RRDBNet) require small tiles (256-384) to avoid multi-gigabyte
        // native activations, CPU starvation, and device freezing.
        val (tileSize, overlap) = if (isAiModel) {
            when {
                freeMb >= 512 -> 384 to 24
                else -> 256 to 16
            }
        } else {
            when {
                freeMb >= 384 -> 1024 to 32
                freeMb >= 192 -> 768 to 32
                else -> 512 to 32
            }
        }

        return MemoryPlan(
            tileSize = tileSize,
            overlap = overlap,
            isSafe = true
        )
    }
}
