package com.veilframe.app.cv.core

/**
 * CvEngine — the single controlled executor for every CV operation.
 *
 *   UI
 *    │
 *    ▼
 *   CvEngine.submit()
 *    ├── memory check        (CvMemoryManager.admit)
 *    ├── resolution selection(CvMemoryManager.workingResolution)
 *    ├── operation           (caller-supplied block on a CvContext)
 *    ├── cancellation checks (context.ensureActive() between stages)
 *    └── release Mats        (MatPool leases closed via use { })
 *
 * The engine never touches Android UI classes and never allocates raw Mats on
 * behalf of callers — buffers come from [CvMemoryManager.matPool].
 */
class CvEngine(
    val memory: CvMemoryManager,
    val dispatcher: CvDispatcher = CvDispatcher(),
) {
    val pool: MatPool get() = memory.matPool

    /**
     * Submits work. When [memoryEstimate] exceeds the admission budget the job
     * fails fast with [CvErrorCode.OUT_OF_MEMORY] instead of killing the app.
     *
     * Concurrency-safe: atomically reserves [memoryEstimate] against the unreserved
     * budget envelope before enqueuing to prevent parallel over-budget spikes.
     */
    fun <T> submit(
        name: String,
        priority: CvPriority = CvPriority.INTERACTIVE,
        memoryEstimate: Long = 0L,
        block: (CvContext) -> T,
    ): CvJob<T> {
        val reservation = if (memoryEstimate > 0L) {
            when (val decision = memory.reserve(memoryEstimate)) {
                is ReservationResult.Rejected -> return CvJob.finished(
                    name,
                    priority,
                    memoryEstimate,
                    CvResult.Err(
                        CvErrorCode.OUT_OF_MEMORY,
                        "estimated $memoryEstimate bytes exceeds unreserved budget ${decision.budgetBytes}; " +
                            "retry at ${decision.suggestedTier}",
                    ),
                )
                is ReservationResult.Granted -> decision.reservation
            }
        } else {
            null
        }

        return try {
            dispatcher.submit(
                name = name,
                priority = priority,
                memoryEstimate = memoryEstimate,
                reservation = reservation,
                pool = pool,
                block = block,
            )
        } catch (e: Exception) {
            reservation?.close()
            throw e
        } catch (oom: OutOfMemoryError) {
            reservation?.close()
            throw oom
        }
    }

    /** Convenience: run [block] now on the calling thread with a full [CvContext]. */
    fun <T> runInline(name: String, block: (CvContext) -> T): CvResult<T> {
        val context = CvContext(name, CvPriority.INTERACTIVE, 0L, CvCancellation(), pool = pool)
        return try {
            CvResult.Ok(block(context), context.warningsSnapshot(), context.timings())
        } catch (t: Throwable) {
            CvFailureMapper.toResult(t)
        }
    }

    /** Pool + memory statistics for diagnostics screens and tests. */
    fun stats(): EngineStats = EngineStats(
        pool = pool.stats(),
        memoryTier = memory.tier(),
    )

    fun shutdown() = dispatcher.shutdown()
}

data class EngineStats(
    val pool: PoolStats,
    val memoryTier: MemoryTier,
)
