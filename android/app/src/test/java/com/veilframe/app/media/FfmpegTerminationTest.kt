package com.veilframe.app.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class FfmpegTerminationTest {
    @Test fun interruptedWorkerDoesNotReleaseScratchBeforeNativeCompletion() {
        val nativeDone = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val interruptRestored = AtomicBoolean(false)
        val worker = Thread {
            Thread.currentThread().interrupt()
            entered.countDown()
            awaitFfmpegTermination(nativeDone)
            interruptRestored.set(Thread.currentThread().isInterrupted)
            returned.countDown()
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            nativeDone.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(interruptRestored.get())
        } finally {
            nativeDone.countDown()
            worker.join(2000)
        }
    }
}
