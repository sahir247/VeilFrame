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
     */
    fun <T> submit(
        name: String,
        priority: CvPriority = CvPriority.INTERACTIVE,
        memoryEstimate: Long = 0L,
        block: (CvContext) -> T,
    ): CvJob<T> {
        if (memoryEstimate > 0L) {
            when (val decision = memory.admit(memoryEstimate)) {
                is AdmissionDecision.Rejected -> return CvJob.finished(
                    name,
                    priority,
                    memoryEstimate,
                    CvResult.Err(
                        CvErrorCode.OUT_OF_MEMORY,
                        "estimated $memoryEstimate bytes exceeds budget ${decision.budgetBytes}; " +
                            "retry at ${decision.suggestedTier}",
                    ),
                )
                is AdmissionDecision.Admitted -> Unit
            }
        }
        return dispatcher.submit(name, priority, memoryEstimate, block)
    }

    /** Convenience: run [block] now on the calling thread with a full [CvContext]. */
    fun <T> runInline(name: String, block: (CvContext) -> T): CvResult<T> {
        val context = CvContext(name, CvPriority.INTERACTIVE, 0L, CvCancellation())
        return try {
            CvResult.Ok(block(context), context.warningsSnapshot(), context.timings())
        } catch (c: CvCancelled) {
            CvResult.Err(CvErrorCode.CANCELLED, c.detail)
        } catch (t: Throwable) {
            CvResult.Err(
                when (t) {
                    is IllegalArgumentException -> CvErrorCode.INVALID_INPUT
                    is UnsupportedOperationException -> CvErrorCode.UNSUPPORTED
                    is OutOfMemoryError -> CvErrorCode.OUT_OF_MEMORY
                    else -> CvErrorCode.INTERNAL
                },
                t.message ?: t::class.java.simpleName,
                t,
            )
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
