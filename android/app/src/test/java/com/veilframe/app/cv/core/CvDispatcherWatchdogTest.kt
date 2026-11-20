package com.veilframe.app.cv.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CvDispatcherWatchdogTest {

    @Test
    fun joinWorkerWaitsForNonCooperativeWorkAfterCancellation() = runBlocking {
        val dispatcher = CvDispatcher()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = dispatcher.submit(name = "blocking_native_fixture", priority = CvPriority.INTERACTIVE) {
            started.countDown()
            try {
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                7
            } finally { exited.set(true) }
        }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel("owner left")
            // A cancelled handle is not evidence that a noncooperative native worker exited.
            assertTrue(!exited.get())
            release.countDown()
            kotlinx.coroutines.withTimeout(5000) { job.joinWorker() }
            assertTrue(exited.get())
        } finally {
            release.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun testJobCompletesNormallyBeforeTimeoutCancelsWatchdog() = runBlocking {
        val dispatcher = CvDispatcher()
        val job = dispatcher.submit(
            name = "fast_job",
            priority = CvPriority.INTERACTIVE,
            timeoutMs = 5000L
        ) { ctx ->
            42
        }

        val result = job.await()
        assertTrue("Job must succeed", result is CvResult.Ok)
        assertEquals(42, (result as CvResult.Ok).value)
        assertEquals(CvJobState.COMPLETED, job.state)
    }

    @Test
    fun testWatchdogCancelsJobWhenTimeoutExceeded() = runBlocking {
        val dispatcher = CvDispatcher()
        val job = dispatcher.submit(
            name = "slow_job",
            priority = CvPriority.INTERACTIVE,
            timeoutMs = 50L
        ) { ctx ->
            delay(500L)
            ctx.ensureActive()
            99
        }

        val result = job.await()
        assertTrue("Job must fail with CANCELLED on timeout", result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.CANCELLED, err.code)
        assertTrue("Error message must indicate watchdog timeout", err.message?.contains("watchdog timeout") == true)
    }
}
