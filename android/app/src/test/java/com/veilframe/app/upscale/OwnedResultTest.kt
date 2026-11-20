package com.veilframe.app.upscale.inference

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OwnedResultTest {
    @Test fun successfulHandoffDoesNotDispose() = runBlocking {
        val disposed = AtomicInteger()
        val result = withOwnedResult(Dispatchers.Default, { _: String -> disposed.incrementAndGet() }) {
            "owned"
        }
        assertEquals("owned", result)
        assertEquals(0, disposed.get())
    }

    @Test fun promptCancellationDisposesUndeliveredResultExactlyOnce() = runBlocking {
        val disposed = AtomicInteger()
        val delivered = AtomicInteger()
        val job = launch {
            val caller = currentCoroutineContext().job
            withOwnedResult(Dispatchers.Default, { _: String -> disposed.incrementAndGet() }) {
                caller.cancel()
                "owned"
            }
            delivered.incrementAndGet()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, disposed.get())
        assertEquals(0, delivered.get())
    }
}
