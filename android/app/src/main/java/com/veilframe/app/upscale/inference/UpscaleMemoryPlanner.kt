package com.veilframe.app.upscale.inference

/**
 * Calculates memory budgets and plans optimal tile sizes to avoid Android OutOfMemory errors.
 * Uses genuine memory-derived budgets rather than hardcoded RAM-class tiers, protected
 * by an emergency hard ceiling against catastrophic inputs.
 */
object UpscaleMemoryPlanner {

    // Emergency absolute upper guard against pathological inputs (production value configurable pending stress testing)
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
        val warningMessage: String? = null
    )

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        scale: Int,
        isAiModel: Boolean = false,
        deviceProfile: DeviceCapabilityProfile? = null
    ): MemoryPlan {
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
        val freeJavaMemory = (maxMemory - usedMemory).coerceAtLeast(64L * 1024 * 1024L)
        // System available memory signal (not an exact per-process native budget)
        val systemAvailableMemory = deviceProfile?.systemAvailableMemory ?: deviceProfile?.nativeProcessBudget ?: freeJavaMemory
        val freeMb = freeJavaMemory / (1024 * 1024)

        // Dynamically compute safe budget: max 70% of available memory for the output bitmap
        val safeBitmapMemoryLimit = (freeJavaMemory * 0.70).toLong().coerceAtLeast(128L * 1024 * 1024L)

        if (outPixels > emergencyMaxOutputPixels) {
            val outMp = String.format(java.util.Locale.US, "%.1f", outPixels / 1_000_000.0)
            return MemoryPlan(
                tileSize = 256,
                overlap = 16,
                estimatedWorkingSetBytes = outputBitmapBytes,
                maxOutputBitmapBytes = safeBitmapMemoryLimit,
                isSafe = false,
                warningMessage = "Target resolution ($scale× = ${targetWidth}×${targetHeight}, ${outMp} MP) exceeds emergency safety ceiling. Please crop or downscale."
            )
        }

        if (outputBitmapBytes > safeBitmapMemoryLimit) {
            val outMp = String.format(java.util.Locale.US, "%.1f", outPixels / 1_000_000.0)
            val outMb = outputBitmapBytes / (1024 * 1024)
            val limitMb = safeBitmapMemoryLimit / (1024 * 1024)
            return MemoryPlan(
                tileSize = 256,
                overlap = 16,
                estimatedWorkingSetBytes = outputBitmapBytes,
                maxOutputBitmapBytes = safeBitmapMemoryLimit,
                isSafe = false,
                warningMessage = "Target resolution ($scale× = ${targetWidth}×${targetHeight}, ${outMp} MP, ~$outMb MB) exceeds available memory budget (~$limitMb MB). Please select a lower scale or crop."
            )
        }

        // Adaptive default candidate tile size based on available memory headroom
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

        val breakdown = calculateWorkerBudget(
            availableHeadroomBytes = freeJavaMemory,
            nativeHeadroomBytes = systemAvailableMemory,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scale = scale,
            tileSize = tileSize,
            isAiModel = isAiModel
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
                warningMessage = "Insufficient memory headroom to safely accommodate even a single tile worker. Please reduce tile size or resolution."
            )
        }

        return MemoryPlan(
            tileSize = tileSize,
            overlap = overlap,
            estimatedWorkingSetBytes = estimatedWorkingSet,
            maxOutputBitmapBytes = safeBitmapMemoryLimit,
            budgetBreakdown = breakdown,
            isSafe = true
        )
    }

    /**
     * Calculates the dedicated memory budget available for concurrent tile workers,
     * maintaining decoupled Java heap and native process memory accounts.
     *
     * Java budget = Java headroom - Java persistent allocations (Source + Output Bitmaps)
     * Native budget = Native headroom - ORT/session/provider allocations
     * memorySafeWorkers = min(floor(JavaBudget / JavaPerWorker), floor(NativeBudget / NativePerWorker))
     */
    fun calculateWorkerBudget(
        availableHeadroomBytes: Long,
        nativeHeadroomBytes: Long = availableHeadroomBytes,
        sourceWidth: Int,
        sourceHeight: Int,
        scale: Int,
        tileSize: Int,
        isAiModel: Boolean,
        sessionStrategy: SessionStrategy = SessionStrategy.SHARED_SESSION
    ): MemoryBudgetBreakdown {
        val srcBitmapBytes = sourceWidth.toLong() * sourceHeight.toLong() * 4L
        val outBitmapBytes = (sourceWidth.toLong() * scale) * (sourceHeight.toLong() * scale) * 4L
        val baseRuntimeBytes = if (isAiModel) 48L * 1024 * 1024L else 8L * 1024 * 1024L
        val runtimeSessionBytes = if (sessionStrategy == SessionStrategy.SESSION_POOL) baseRuntimeBytes * 2 else baseRuntimeBytes
        val safetyReserveBytes = (availableHeadroomBytes * 0.15).toLong().coerceAtLeast(32L * 1024 * 1024L)

        // Decoupled Java budget
        val javaBudget = (availableHeadroomBytes - srcBitmapBytes - outBitmapBytes - safetyReserveBytes).coerceAtLeast(0L)
        // Decoupled Native budget
        val nativeBudget = (nativeHeadroomBytes - runtimeSessionBytes - safetyReserveBytes).coerceAtLeast(0L)

        // Estimated memory consumption per concurrent tile worker
        val tileInBytes = tileSize.toLong() * tileSize.toLong() * 3L * 4L // Float32 input tensor buffer (Native)
        val tileOutPixels = (tileSize.toLong() * scale) * (tileSize.toLong() * scale)
        val tileOutBytes = tileOutPixels * 4L // ARGB tile bitmap (Java Heap)
        val onnxActivationEstimateBytes = if (isAiModel) tileOutBytes * 3L else 0L // Native workspace

        val javaPerWorker = tileOutBytes.coerceAtLeast(1L)
        val nativePerWorker = (tileInBytes + onnxActivationEstimateBytes).coerceAtLeast(1L)
        val perWorkerTotal = (javaPerWorker + nativePerWorker)

        val javaSafeWorkers = (javaBudget / javaPerWorker).toInt().coerceAtLeast(0)
        val nativeSafeWorkers = (nativeBudget / nativePerWorker).toInt().coerceAtLeast(0)

        // Minimum of Java and Native safe workers without arbitrary 1..4 cap
        val maxSafeWorkers = minOf(javaSafeWorkers, nativeSafeWorkers)

        return MemoryBudgetBreakdown(
            availableHeadroomBytes = availableHeadroomBytes,
            javaBudgetBytes = javaBudget,
            nativeBudgetBytes = nativeBudget,
            sourceBitmapBytes = srcBitmapBytes,
            outputBitmapBytes = outBitmapBytes,
            runtimeSessionBytes = runtimeSessionBytes,
            safetyReserveBytes = safetyReserveBytes,
            workerBudgetBytes = minOf(javaBudget, nativeBudget),
            perWorkerEstimatedBytes = perWorkerTotal,
            javaPerWorkerBytes = javaPerWorker,
            nativePerWorkerBytes = nativePerWorker,
            maxMemorySafeWorkers = maxSafeWorkers
        )
    }
}

