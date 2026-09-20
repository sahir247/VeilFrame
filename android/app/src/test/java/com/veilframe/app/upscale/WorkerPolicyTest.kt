package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.Backend
import com.veilframe.app.upscale.inference.WorkerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerPolicyTest {

    @Test
    fun testNnapiSingleTileAlwaysResolvesToOneWorker() {
        val workers = WorkerPolicy.resolve(
            backend = Backend.NNAPI,
            memoryBudgetBytes = 500 * 1024 * 1024L,
            perWorkerBytes = 10 * 1024 * 1024L,
            tileCount = 1
        )
        assertEquals(1, workers)
    }

    @Test
    fun testNnapiSmallTileCountPrefersSingleWorker() {
        // <= 4 tiles should use 1 worker to prevent accelerator contention
        val workers = WorkerPolicy.resolve(
            backend = Backend.NNAPI,
            memoryBudgetBytes = 500 * 1024 * 1024L,
            perWorkerBytes = 10 * 1024 * 1024L,
            tileCount = 4
        )
        assertEquals(1, workers)
    }

    @Test
    fun testCpuSingleTileAlwaysResolvesToOneWorker() {
        val workers = WorkerPolicy.resolve(
            backend = Backend.CPU,
            memoryBudgetBytes = 500 * 1024 * 1024L,
            perWorkerBytes = 10 * 1024 * 1024L,
            tileCount = 1
        )
        assertEquals(1, workers)
    }

    @Test
    fun testMemoryConstraintLimitsWorkerCount() {
        // Only enough memory for 2 workers
        val workers = WorkerPolicy.resolve(
            backend = Backend.CPU,
            memoryBudgetBytes = 25 * 1024 * 1024L,
            perWorkerBytes = 10 * 1024 * 1024L,
            tileCount = 20
        )
        assertEquals(2, workers)
    }

    @Test
    fun testMinimumWorkerIsAlwaysAtLeastOne() {
        val workers = WorkerPolicy.resolve(
            backend = Backend.CPU,
            memoryBudgetBytes = 1024L, // Very small memory
            perWorkerBytes = 10 * 1024 * 1024L,
            tileCount = 10
        )
        assertEquals(1, workers)
    }
}
