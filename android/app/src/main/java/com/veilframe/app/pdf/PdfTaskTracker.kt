package com.veilframe.app.pdf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Tracks every coroutine owned by a PDF workspace so leaving/discarding that
 * workspace cancels previews, document inspection, imports, exports and shares.
 */
internal class PdfTaskTracker(private val scope: CoroutineScope) {
    private val lock = Any()
    private val jobs = linkedSetOf<Job>()

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val job = scope.launch(context, start, block)
        synchronized(lock) { jobs.add(job) }
        job.invokeOnCompletion {
            synchronized(lock) { jobs.remove(job) }
        }
        return job
    }

    fun cancelAll() {
        val pending = synchronized(lock) {
            jobs.toList().also { jobs.clear() }
        }
        pending.forEach { it.cancel() }
    }
}
