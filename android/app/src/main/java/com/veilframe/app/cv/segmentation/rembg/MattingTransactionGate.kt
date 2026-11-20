package com.veilframe.app.cv.segmentation.rembg

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative

/**
 * The native mutex protects one JNI call, not an init + multiple inference calls.
 * This gate protects the entire transaction. A release request never waits on
 * the caller (typically Main), and each request is consumed exactly once before
 * the next transaction or by the retirement worker, whichever wins.
 */
internal class MattingTransactionGate(private val executor: Executor, private val release: () -> Unit) {
    private val lock = ReentrantLock(true)
    private val requested = AtomicLong()
    private var applied = 0L

    private fun drain() {
        val target = requested.get()
        if (target != applied) {
            release()
            applied = target
        }
    }

    fun <T> run(checkpoint: () -> Unit = {}, block: () -> T): T {
        // Poll instead of indefinitely blocking a cancelled queued worker.
        while (!lock.tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) checkpoint()
        try {
            checkpoint()
            drain()
            checkpoint()
            return block()
        } finally { lock.unlock() }
    }

    fun requestRelease() {
        requested.incrementAndGet()
        executor.execute { lock.withLock { drain() } }
    }
}

internal object NcnnMattingTransactions {
    private val retirement = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vf-matting-retirement").apply { isDaemon = true }
    }
    private val gate = MattingTransactionGate(retirement) { NcnnMattingNative.nativeRelease() }

    fun <T> run(checkpoint: () -> Unit = {}, block: () -> T): T =
        gate.run(checkpoint, block)

    fun requestRelease() = gate.requestRelease()
}
