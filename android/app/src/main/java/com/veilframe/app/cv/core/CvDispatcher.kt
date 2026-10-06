package com.veilframe.app.cv.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.cancellation.CancellationException

/**
 * CvDispatcher — the only place CV work runs.
 *
 * Three lanes, as required by the CV plan:
 *   INTERACTIVE  user-visible, lowest latency, always has free slots
 *   BACKGROUND   batch/warm-up work, starved before INTERACTIVE ever is
 *   VIDEO        sustained frame work in its own lane so it can never freeze UI
 *
 * Lanes hold dedicated permits instead of a single shared pool: background and
 * video work physically cannot occupy interactive capacity. Within a lane,
 * kotlinx.coroutines [Semaphore] is fair (FIFO), so ordering is deterministic.
 *
 * Every job supports cancel(), progress and a memory estimate via [CvJob].
 */
class CvDispatcher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val config: DispatcherConfig = DispatcherConfig(),
) {
    private val laneSemaphores: Map<CvPriority, Semaphore> = mapOf(
        CvPriority.INTERACTIVE to Semaphore(config.interactiveSlots),
        CvPriority.BACKGROUND to Semaphore(config.backgroundSlots),
        CvPriority.VIDEO to Semaphore(config.videoSlots),
    )

    /** Submit a block for execution on [priority]'s lane. */
    fun <T> submit(
        name: String,
        priority: CvPriority,
        memoryEstimate: Long = 0L,
        block: (CvContext) -> T,
    ): CvJob<T> {
        // A lane configured with 0 slots is DISABLED: submitting to a
        // Semaphore(0) lane would suspend forever, so reject up front.
        if (config.slotsFor(priority) == 0) {
            return CvJob.finished(
                name,
                priority,
                memoryEstimate,
                CvResult.unsupported("CV lane $priority is disabled (0 slots)"),
            )
        }
        val cancellation = CvCancellation()
        val result = CompletableDeferred<CvResult<T>>()
        val stateRef = AtomicReference(CvJobState.PENDING)
        val context = CvContext(
            name = name,
            priority = priority,
            memoryEstimate = memoryEstimate,
            cancellation = cancellation,
            state = stateRef,
        )

        val deferred: Deferred<CvResult<T>> = scope.asyncResult(priority, context, block)

        // Keep the engine's result and the coroutine result in sync.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            result.complete(runCatching { deferred.await() }.getOrElse { CvResult.errFrom(it) })
        }

        return CvJob(name, priority, memoryEstimate, cancellation, result, stateRef, deferred)
    }

    private fun <T> CoroutineScope.asyncResult(
        priority: CvPriority,
        context: CvContext,
        block: (CvContext) -> T,
    ): Deferred<CvResult<T>> {
        val lane = laneSemaphores.getValue(priority)
        return async {
            lane.acquire()
            try {
                if (context.cancellation.isCancelled) {
                    CvResult.Err(CvErrorCode.CANCELLED, context.cancellation.reason() ?: "cancelled")
                } else {
                    context.markRunning()
                    CvResult.Ok(block(context), context.warningsSnapshot(), context.timings())
                }
            } catch (c: CvCancelled) {
                CvResult.Err(CvErrorCode.CANCELLED, c.detail)
            } catch (oom: OutOfMemoryError) {
                CvResult.Err(CvErrorCode.OUT_OF_MEMORY, "native/heap OOM: ${oom.message}", oom)
            } catch (t: Throwable) {
                CvResult.errFrom(t)
            } finally {
                context.markFinished()
                lane.release()
            }
        }
    }

    /** Cancels the dispatcher scope (and thus every queued/running job). */
    fun shutdown() {
        scope.cancel("CvDispatcher.shutdown()")
    }
}

data class DispatcherConfig(
    val interactiveSlots: Int = 2,
    val backgroundSlots: Int = 1,
    val videoSlots: Int = 1,
) {
    init {
        require(interactiveSlots >= 1) { "interactive lane must never be empty" }
        // 0 = lane DISABLED: submissions are rejected instead of queueing forever.
        require(backgroundSlots >= 0 && videoSlots >= 0) { "slots must be >= 0 (0 disables the lane)" }
    }

    /** Permits configured for [priority]; 0 means the lane is disabled. */
    fun slotsFor(priority: CvPriority): Int = when (priority) {
        CvPriority.INTERACTIVE -> interactiveSlots
        CvPriority.BACKGROUND -> backgroundSlots
        CvPriority.VIDEO -> videoSlots
    }
}

/**
 * Per-job execution context handed to CV blocks.
 *
 * Gives access to cooperative cancellation and progress reporting without
 * leaking engine internals. (Buffer pooling is currently enforced at the
 * CvEngine job level; feature primitives allocate and release Mats directly —
 * see docs/cv/VEILFRAME_CV_ENGINE.md.)
 */
class CvContext internal constructor(
    val name: String,
    val priority: CvPriority,
    val memoryEstimate: Long,
    val cancellation: CvCancellation,
    private val state: AtomicReference<CvJobState> = AtomicReference(CvJobState.PENDING),
) {
    private val warnings = java.util.concurrent.CopyOnWriteArrayList<CvWarning>()
    private val timings = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 0f..1f, monotonic best-effort. */
    @Volatile
    var progress: Float = 0f
        private set

    val isActive: Boolean get() = !cancellation.isCancelled

    fun ensureActive() = cancellation.ensureActive()

    fun reportProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
    }

    fun warn(code: String, message: String) {
        warnings += CvWarning(code, message)
    }

    /** Records a named stage duration in milliseconds. */
    fun <T> timed(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            timings.merge(stage, (System.nanoTime() - start) / 1_000_000, Long::plus)
        }
    }

    internal fun markRunning() {
        state.compareAndSet(CvJobState.PENDING, CvJobState.RUNNING)
    }

    internal fun markFinished() {
        state.compareAndSet(CvJobState.RUNNING, CvJobState.COMPLETED)
    }

    internal fun warningsSnapshot(): List<CvWarning> = warnings.toList()
    internal fun timings(): Map<String, Long> = timings.toMap()
}

/** Handle over submitted CV work. */
class CvJob<T> internal constructor(
    val name: String,
    val priority: CvPriority,
    val memoryEstimate: Long,
    private val cancellation: CvCancellation,
    private val result: CompletableDeferred<CvResult<T>>,
    private val stateRef: AtomicReference<CvJobState>,
    private val coroutine: Job?,
) {
    val isActive: Boolean get() = !result.isCompleted && !cancellation.isCancelled

    val state: CvJobState
        get() = when {
            result.isCompleted -> when (result.getCompleted()) {
                is CvResult.Ok -> CvJobState.COMPLETED
                else -> if (cancellation.isCancelled) CvJobState.CANCELLED else CvJobState.FAILED
            }
            cancellation.isCancelled -> CvJobState.CANCELLED
            // PENDING until the block actually enters its lane — a queued job is
            // NOT running (the old fallback claimed RUNNING for every queued job).
            else -> stateRef.get()
        }

    fun cancel(reason: String = "cancelled by caller") {
        cancellation.cancel(reason)
        // Also cancel the coroutine: a job stuck in lane.acquire() must abort
        // immediately instead of waiting for a free permit. In-flight blocks are
        // non-suspending, so their cooperative checks (ensureActive) still apply.
        coroutine?.cancel(CancellationException(reason))
    }

    suspend fun await(): CvResult<T> = result.await()

    /** Non-blocking peek; null while the job is still pending/running. */
    fun tryGetNow(): CvResult<T>? = if (result.isCompleted) result.getCompleted() else null

    companion object {
        /** Already-finished handle (used for admission rejections and inline failures). */
        internal fun <T> finished(
            name: String,
            priority: CvPriority,
            memoryEstimate: Long,
            outcome: CvResult<T>,
        ): CvJob<T> = CvJob(
            name,
            priority,
            memoryEstimate,
            CvCancellation(),
            CompletableDeferred(outcome),
            AtomicReference(CvJobState.COMPLETED),
            null,
        )
    }
}

private fun <T> CvResult.Companion.errFrom(t: Throwable): CvResult<T> = when (t) {
    is CvCancelled -> CvResult.Err(CvErrorCode.CANCELLED, t.detail, t)
    // A cancelled coroutine surfaces its CancellationException through await();
    // it must map to CANCELLED, not INTERNAL.
    is CancellationException -> CvResult.Err(CvErrorCode.CANCELLED, t.message ?: "cancelled", t)
    is OutOfMemoryError -> CvResult.Err(CvErrorCode.OUT_OF_MEMORY, t.message ?: "OOM", t)
    is IllegalArgumentException -> CvResult.Err(CvErrorCode.INVALID_INPUT, t.message ?: "invalid input", t)
    is UnsupportedOperationException -> CvResult.Err(CvErrorCode.UNSUPPORTED, t.message ?: "unsupported", t)
    else -> CvResult.Err(CvErrorCode.INTERNAL, t.message ?: t::class.java.simpleName, t)
}
