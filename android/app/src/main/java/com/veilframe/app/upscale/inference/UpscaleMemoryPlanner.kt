package com.veilframe.app.upscale.inference

/**
 * Output storage strategy planned to bound RAM consumption.
 */
enum class OutputSinkMode {
    MEMORY_BUFFER,
    STREAMING_STRIP,
    TILED_SINK
}

/**
 * Calculates memory budgets and plans optimal tile sizes to avoid Android OutOfMemory errors.
 * Uses genuine stage-aware memory planning rather than whole-output monolithic budgeting,
 * protected by an emergency hard ceiling against catastrophic inputs.
 */
object UpscaleMemoryPlanner {

    // Emergency absolute upper guard against pathological inputs
    @Volatile
    var emergencyMaxOutputPixels: Long = 100_000_000L

    const val DEFAULT_EMERGENCY_MAX_OUTPUT_PIXELS = 100_000_000L
    const val EMERGENCY_MAX_OUTPUT_PIXELS = DEFAULT_EMERGENCY_MAX_OUTPUT_PIXELS

    data class MemoryBudgetBreakdown(
        val availableHeadroomBytes: Long,
        val javaBudgetBytes: Long,
        val nativeBudgetBytes: Long,
        val sourceBitmapBytes: Long,
        val outputBitmapBytes: Long,
        val runtimeSessionBytes: Long,
        val safetyReserveBytes: Long,
        val workerBudgetBytes: Long,
        val perWorkerEstimatedBytes: Long,
        val javaPerWorkerBytes: Long,
        val nativePerWorkerBytes: Long,
        val maxMemorySafeWorkers: Int,
        val observedPeakMemoryBytes: Long? = null
    )

    data class MemoryPlan(
        val tileSize: Int,
        val overlap: Int = 24,
        val estimatedWorkingSetBytes: Long = 0L,
        val maxOutputBitmapBytes: Long = 0L,
        val budgetBreakdown: MemoryBudgetBreakdown? = null,
        val isSafe: Boolean,
        val warningMessage: String? = null,
        val outputSinkMode: OutputSinkMode = OutputSinkMode.MEMORY_BUFFER
    )

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        scale: Int,
        isAiModel: Boolean = false,
        aiScale: Int = scale,
        deviceProfile: DeviceCapabilityProfile? = null
    ): MemoryPlan {
        val srcPixels = sourceWidth.toLong() * sourceHeight.toLong()
        val targetWidth = sourceWidth.toLong() * scale
        val targetHeight = sourceHeight.toLong() * scale
        val outPixels = targetWidth * targetHeight
        val fullOutputBitmapBytes = outPixels * 4L // ARGB_8888 is 4 bytes per pixel

        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemoryInHeap = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemoryInHeap
        val freeJavaMemory = (maxMemory - usedMemory).coerceAtLeast(64L * 1024 * 1024L)
        val systemAvailableMemory = deviceProfile?.systemAvailableMemory ?: deviceProfile?.nativeProcessBudget ?: freeJavaMemory
        val freeMb = freeJavaMemory / (1024 * 1024)

        // Dynamically compute safe budget: max 70% of available memory for the output bitmap
        val safeBitmapMemoryLimit = (freeJavaMemory * 0.70).toLong().coerceAtLeast(128L * 1024 * 1024L)

        // Adaptive candidate tile size based on available memory headroom
        val (tileSize, overlap) = if (isAiModel) {
            when {
                freeMb >= 512 -> 384 to 24
                else -> 256 to 16
            }
        } else {
            when {
                freeMb >= 512 -> 512 to 32
                freeMb >= 256 -> 384 to 24
                else -> 256 to 16
            }
        }

        // Determine output sink mode
        val stripHeight = minOf(tileSize * scale, targetHeight.toInt())
        val stripBytes = targetWidth * stripHeight * 4L
        val sinkMode = when {
            fullOutputBitmapBytes <= safeBitmapMemoryLimit -> OutputSinkMode.MEMORY_BUFFER
            stripBytes <= safeBitmapMemoryLimit -> OutputSinkMode.STREAMING_STRIP
            else -> OutputSinkMode.TILED_SINK
        }

        if (outPixels > emergencyMaxOutputPixels) {
            val outMp = String.format(java.util.Locale.US, "%.1f", outPixels / 1_000_000.0)
            return MemoryPlan(
                tileSize = tileSize,
                overlap = overlap,
                estimatedWorkingSetBytes = fullOutputBitmapBytes,
                maxOutputBitmapBytes = safeBitmapMemoryLimit,
                isSafe = false,
                warningMessage = "Target resolution ($scale× = ${targetWidth}×${targetHeight}, ${outMp} MP) exceeds emergency safety ceiling. Please crop or downscale.",
                outputSinkMode = sinkMode
            )
        }

        val breakdown = calculateWorkerBudget(
            availableHeadroomBytes = freeJavaMemory,
            nativeHeadroomBytes = systemAvailableMemory,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scale = scale,
            tileSize = tileSize,
            isAiModel = isAiModel,
            aiScale = aiScale,
            outputSinkMode = sinkMode
        )

        val estimatedWorkingSet = breakdown.sourceBitmapBytes + breakdown.outputBitmapBytes +
                breakdown.runtimeSessionBytes + (breakdown.perWorkerEstimatedBytes * breakdown.maxMemorySafeWorkers)

        if (breakdown.maxMemorySafeWorkers == 0) {
            return MemoryPlan(
                tileSize = tileSize,
                overlap = overlap,
                estimatedWorkingSetBytes = estimatedWorkingSet,
                maxOutputBitmapBytes = safeBitmapMemoryLimit,
                budgetBreakdown = breakdown,
                isSafe = false,
                warningMessage = "Insufficient memory headroom to safely accommodate even a single tile worker. Please reduce tile size or resolution.",
                outputSinkMode = sinkMode
            )
        }

        return MemoryPlan(
            tileSize = tileSize,
            overlap = overlap,
            estimatedWorkingSetBytes = estimatedWorkingSet,
            maxOutputBitmapBytes = safeBitmapMemoryLimit,
            budgetBreakdown = breakdown,
            isSafe = true,
            outputSinkMode = sinkMode
        )
    }

    /**
     * Calculates the dedicated memory budget available for concurrent tile workers,
     * maintaining decoupled Java heap and native process memory accounts.
     *
     * In hybrid pipelines (e.g. 4× AI + 2× Lanczos -> 8× final), neural workers process
     * at [aiScale] (4×), keeping worker memory decoupled from the final 8× output spike.
     */
    fun calculateWorkerBudget(
        availableHeadroomBytes: Long,
        nativeHeadroomBytes: Long = availableHeadroomBytes,
        sourceWidth: Int,
        sourceHeight: Int,
        scale: Int,
        tileSize: Int,
        isAiModel: Boolean,
        aiScale: Int = scale,
        outputSinkMode: OutputSinkMode = OutputSinkMode.MEMORY_BUFFER,
        sessionStrategy: SessionStrategy = SessionStrategy.SHARED_SESSION,
        candidateWorkerLimit: Int = 16
    ): MemoryBudgetBreakdown {
        val srcBitmapBytes = sourceWidth.toLong() * sourceHeight.toLong() * 4L
        val persistentSinkBytes = when (outputSinkMode) {
            OutputSinkMode.MEMORY_BUFFER -> (sourceWidth.toLong() * scale) * (sourceHeight.toLong() * scale) * 4L
            OutputSinkMode.STREAMING_STRIP -> {
                val stripH = minOf(tileSize * scale, sourceHeight * scale)
                (sourceWidth.toLong() * scale) * stripH.toLong() * 4L
            }
            OutputSinkMode.TILED_SINK -> 0L
        }

        val baseRuntimeBytes = if (isAiModel) 48L * 1024 * 1024L else 8L * 1024 * 1024L
        val runtimeSessionBytes = if (sessionStrategy == SessionStrategy.SESSION_POOL) baseRuntimeBytes * 2 else baseRuntimeBytes
        val safetyReserveBytes = (availableHeadroomBytes * 0.15).toLong().coerceAtLeast(32L * 1024 * 1024L)

        // Decoupled Java and Native budgets
        val javaBudget = (availableHeadroomBytes - srcBitmapBytes - persistentSinkBytes - safetyReserveBytes).coerceAtLeast(0L)
        val nativeBudget = (nativeHeadroomBytes - runtimeSessionBytes - safetyReserveBytes).coerceAtLeast(0L)

        // Worker transient peak modeling around neural AI stage (aiScale)
        val t = tileSize.toLong()
        val srcTilePixels = t * t
        val aiTilePixels = (t * aiScale) * (t * aiScale)
        val aiTileOutputBytes = aiTilePixels * 4L

        // Java transient per AI worker: srcPixels + alpha + dstPixels + output bitmap
        val javaPerWorker = (srcTilePixels * 8L) + (aiTileOutputBytes * 2L)

        // Native transient per AI worker: input direct buffer + ONNX activations estimate
        val nativePerWorker = (srcTilePixels * 12L) + (if (isAiModel) aiTileOutputBytes * 3L else 0L)
        val perWorkerTotal = javaPerWorker + nativePerWorker

        // Iterative worker solver accounting for reorder buffer byte ceiling
        var safeWorkers = 0
        for (w in 1..candidateWorkerLimit) {
            val reorderQueueBytes = minOf(w * 2, 8) * aiTileOutputBytes
            val totalJavaNeeded = (w * javaPerWorker) + reorderQueueBytes
            val totalNativeNeeded = w * nativePerWorker
            if (totalJavaNeeded <= javaBudget && totalNativeNeeded <= nativeBudget) {
                safeWorkers = w
            } else {
                break
            }
        }

        if (safeWorkers == 0 && javaBudget >= javaPerWorker && nativeBudget >= nativePerWorker) {
            safeWorkers = 1
        }

        return MemoryBudgetBreakdown(
            availableHeadroomBytes = availableHeadroomBytes,
            javaBudgetBytes = javaBudget,
            nativeBudgetBytes = nativeBudget,
            sourceBitmapBytes = srcBitmapBytes,
            outputBitmapBytes = persistentSinkBytes,
            runtimeSessionBytes = runtimeSessionBytes,
            safetyReserveBytes = safetyReserveBytes,
            workerBudgetBytes = minOf(javaBudget, nativeBudget),
            perWorkerEstimatedBytes = perWorkerTotal,
            javaPerWorkerBytes = javaPerWorker,
            nativePerWorkerBytes = nativePerWorker,
            maxMemorySafeWorkers = safeWorkers
        )
    }
}

