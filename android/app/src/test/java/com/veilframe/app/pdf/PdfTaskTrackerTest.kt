package com.veilframe.app.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTaskTrackerTest {
    @Test
    fun cancelAll_cancelsEveryTrackedWorkspaceTask() = runBlocking {
        val tracker = PdfTaskTracker(this)
        val started = CompletableDeferred<Unit>()

        val first = tracker.launch {
            started.complete(Unit)
            awaitCancellation()
        }
        val secondStarted = CompletableDeferred<Unit>()
        val second = tracker.launch {
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        started.await()
        secondStarted.await()
        tracker.cancelAll()

        assertTrue("First task must be cancelled on workspace discard", first.isCancelled)
        assertTrue("Second task must be cancelled on workspace discard", second.isCancelled)
    }
}
