package com.veilframe.app.upscale.inference

/**
 * Memory-aware and hardware-aware parallelism policy for tiled AI inference.
 * Inspired by the bounded worker architecture in ImageToolbox, but strictly
 * budgeted against mobile memory limits and backend characteristics.
 */
object WorkerPolicy {

    /**
     * Resolves safe concurrent tile worker count based on backend, device cores,
     * available memory budget, and per-worker working set estimation.
     *
     * @param backend Active inference backend (NNAPI or CPU)
     * @param memoryBudgetBytes Available memory headroom for concurrent tile inference
     * @param perWorkerBytes Estimated memory required per concurrent worker (tensor + activations + tile bitmaps)
     * @param tileCount Total tiles in the current image job
     */
    fun resolve(
        backend: Backend,
        memoryBudgetBytes: Long,
        perWorkerBytes: Long,
        tileCount: Int
    ): Int {
        if (tileCount <= 1) return 1

        val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val memoryCappedWorkers = if (perWorkerBytes > 0L) {
            (memoryBudgetBytes / perWorkerBytes).toInt().coerceAtLeast(1)
        } else {
            1
        }

        val maxAllowed = when (backend) {
            Backend.NNAPI -> {
                // NNAPI mobile drivers generally perform best with 1 worker to prevent accelerator
                // queue serialization and thermal throttling. Allow 2 only on flagship multi-core devices with larger batches.
                if (availableCores >= 8 && memoryCappedWorkers >= 2 && tileCount > 4) 2 else 1
            }
            Backend.CPU -> {
                // ORT CPU execution benefits from 2 concurrent tile workers when cores and memory allow
                val coreLimit = when {
                    availableCores >= 8 -> 3
                    availableCores >= 6 -> 2
                    else -> 1
                }
                minOf(coreLimit, memoryCappedWorkers)
            }
        }

        return maxAllowed.coerceIn(1, minOf(tileCount, 4))
    }
}
