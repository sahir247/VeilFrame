package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MattingTransactionGateTest {
    @Test fun delayedReleaseIsConsumedBeforeNextTransactionAndCannotCloseItAgain() {
        val queued = ArrayList<Runnable>()
        val releases = AtomicInteger()
        val gate = MattingTransactionGate(Executor { queued.add(it) }) { releases.incrementAndGet() }
        gate.run {
            gate.requestRelease()
            assertEquals(0, releases.get())
        }
        gate.run {
            assertEquals(1, releases.get())
        }
        queued.single().run()
        assertEquals(1, releases.get())
    }

    @Test fun retirementCannotInterruptAnActiveTransaction() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val gate = MattingTransactionGate(worker) { retired.countDown() }
        val inference = Thread {
            gate.run {
                entered.countDown()
                check(leave.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            inference.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            gate.requestRelease()
            assertFalse(retired.await(100, TimeUnit.MILLISECONDS))
            leave.countDown()
            assertTrue(retired.await(5, TimeUnit.SECONDS))
        } finally {
            leave.countDown()
            inference.join(5000)
            worker.shutdownNow()
        }
    }
}
