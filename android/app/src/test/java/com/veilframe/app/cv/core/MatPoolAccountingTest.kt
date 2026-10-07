package com.veilframe.app.cv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType

class MatPoolAccountingTest {

    @Test
    fun accountingForNormalAllocationComputesExactBytes() {
        // 1000 x 1000 x CV_8UC1 = 1,000,000 bytes (~976 KiB)
        val accounting = SizeClass.accountingFor(1000, 1000, CvType.CV_8UC1)
        assertEquals(1_000_000L, accounting.actualBytes)
        assertTrue(accounting.retainable)
        assertTrue(accounting.classBytes >= accounting.actualBytes)

        val sizeClass = SizeClass.forMat(1000, 1000, CvType.CV_8UC1)
        org.junit.Assert.assertNotNull(sizeClass)
        assertEquals(1, sizeClass!!.channels)
        assertEquals(CvType.CV_8U, sizeClass.depth)
        assertEquals(accounting.classBytes.toInt(), sizeClass.byteClass)
    }

    @Test
    fun accountingForMultiChannelFloatComputesCorrectElemSize() {
        // 1920 x 1080 x CV_32FC2 = 1920 * 1080 * 8 = 16,588,800 bytes (~15.8 MiB)
        val accounting = SizeClass.accountingFor(1080, 1920, CvType.CV_32FC2)
        val expected = 1920L * 1080L * 8L
        assertEquals(expected, accounting.actualBytes)
        assertTrue(accounting.retainable)
        assertTrue(accounting.classBytes >= accounting.actualBytes)
    }

    @Test
    fun oversizeAllocationMarkedNonRetainable() {
        // 800 MiB Mat: 10,000 x 10,000 x CV_64FC1 = 100,000,000 * 8 = 800,000,000 bytes (~762.9 MiB)
        // > 512 MiB MAX_BYTE_CLASS
        val accounting = SizeClass.accountingFor(10000, 10000, CvType.CV_64FC1)
        val expectedBytes = 800_000_000L
        assertEquals(expectedBytes, accounting.actualBytes)
        assertFalse("Buffers exceeding 512 MiB must NOT be retainable in pool", accounting.retainable)
        assertEquals(expectedBytes, accounting.classBytes)

        // forMat must return null for non-retainable oversize buffers
        assertNull(SizeClass.forMat(10000, 10000, CvType.CV_64FC1))
    }

    @Test
    fun byteClassForReturnsMinusOneForOversize() {
        val maxClass = SizeClass.MAX_BYTE_CLASS.toLong()
        assertEquals(SizeClass.MAX_BYTE_CLASS, SizeClass.byteClassFor(maxClass))
        assertEquals(-1, SizeClass.byteClassFor(maxClass + 1))
        assertEquals(-1, SizeClass.byteClassFor(800L * 1024 * 1024))
    }

    private fun allocateDummyMat(): org.opencv.core.Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, org.opencv.core.Mat::class.java) as org.opencv.core.Mat
    }

    @Test
    fun shapeMismatchInSameBucketRejectsReuseAndAdjustsRetainedBytesExactly() {
        val releasedMats = mutableListOf<org.opencv.core.Mat>()
        val pool = MatPool(
            maxRetainedBytes = 100L * 1024 * 1024,
            maxPerClass = 3,
            matFactory = { _, _, _ -> allocateDummyMat() },
            matReleaser = { releasedMats.add(it) },
            matEmptyPredicate = { false },
        )

        // Request A: 400 x 100 x CV_8UC1 -> 40,000 bytes (bucket = 64 KiB)
        val leaseA = pool.acquire(400, 100, CvType.CV_8UC1)
        assertEquals(40_000L, leaseA.accounting.actualBytes)
        assertEquals(40_000L, pool.stats().liveBytes)
        assertEquals(0L, pool.stats().retainedBytes)

        leaseA.close()
        assertEquals(0L, pool.stats().liveBytes)
        assertEquals(40_000L, pool.stats().retainedBytes)
        assertEquals(1, pool.stats().retainedBuffers)
        assertEquals(1, pool.stats().allocations)
        assertEquals(0, pool.stats().hits)
        assertEquals(1, pool.stats().misses)

        // Request B: 200 x 200 x CV_8UC1 -> 40,000 bytes (same bucket = 64 KiB, different shape)
        val leaseB = pool.acquire(200, 200, CvType.CV_8UC1)
        // Recycled was removed: retainedBytes must be decremented by 40,000L immediately
        assertEquals(0L, pool.stats().retainedBytes)
        // Mismatch rejected: liveBytes must match leaseB's actualBytes
        assertEquals(40_000L, pool.stats().liveBytes)
        // Incompatible Mat released immediately
        assertEquals(1, releasedMats.size)
        // Miss count incremented, hits remains 0, allocations incremented
        assertEquals(2, pool.stats().allocations)
        assertEquals(0, pool.stats().hits)
        assertEquals(2, pool.stats().misses)

        leaseB.close()
        assertEquals(0L, pool.stats().liveBytes)
        assertEquals(40_000L, pool.stats().retainedBytes)
        assertEquals(1, pool.stats().retainedBuffers)

        pool.trim()
        assertEquals(0L, pool.stats().retainedBytes)
        assertEquals(0, pool.stats().retainedBuffers)
        assertEquals(2, releasedMats.size)
    }

    @Test
    fun exactShapeMatchReusesBufferAndIncrementsHits() {
        val releasedMats = mutableListOf<org.opencv.core.Mat>()
        val pool = MatPool(
            maxRetainedBytes = 100L * 1024 * 1024,
            maxPerClass = 3,
            matFactory = { _, _, _ -> allocateDummyMat() },
            matReleaser = { releasedMats.add(it) },
            matEmptyPredicate = { false },
        )

        val leaseA = pool.acquire(100, 100, CvType.CV_8UC1)
        val matA = leaseA.mat
        leaseA.close()

        val leaseB = pool.acquire(100, 100, CvType.CV_8UC1)
        // Exact hit
        org.junit.Assert.assertSame(matA, leaseB.mat)
        assertEquals(1, pool.stats().hits)
        assertEquals(1, pool.stats().misses)
        assertEquals(1, pool.stats().allocations)
        assertEquals(10_000L, pool.stats().liveBytes)
        assertEquals(0L, pool.stats().retainedBytes)

        leaseB.close()
        assertEquals(0L, pool.stats().liveBytes)
        assertEquals(10_000L, pool.stats().retainedBytes)
    }

    @Test
    fun oversizeAllocationIsNeverRetainedAndReleasedImmediatelyOnClose() {
        val releasedMats = mutableListOf<org.opencv.core.Mat>()
        val pool = MatPool(
            maxRetainedBytes = 100L * 1024 * 1024,
            maxPerClass = 3,
            matFactory = { _, _, _ -> allocateDummyMat() },
            matReleaser = { releasedMats.add(it) },
            matEmptyPredicate = { false },
        )

        // 800 MiB allocation (>512 MiB MAX_BYTE_CLASS)
        val lease = pool.acquire(10000, 10000, CvType.CV_64FC1)
        assertFalse("Oversize lease must not be retainable", lease.accounting.retainable)
        assertNull("Oversize lease must have null size class key", lease.key)
        assertEquals(800_000_000L, pool.stats().liveBytes)
        assertEquals(0L, pool.stats().retainedBytes)

        lease.close()
        // Must be released immediately and never retained in free list
        assertEquals(1, releasedMats.size)
        assertEquals(0L, pool.stats().liveBytes)
        assertEquals(0L, pool.stats().retainedBytes)
        assertEquals(0, pool.stats().retainedBuffers)
    }

    @Test
    fun matPoolReleasesAllocatedMatIfLeaseConstructionThrows() {
        val releasedMats = mutableListOf<org.opencv.core.Mat>()
        val pool = MatPool(
            maxRetainedBytes = 100L * 1024 * 1024,
            maxPerClass = 3,
            matFactory = { _, _, _ -> allocateDummyMat() },
            matReleaser = { releasedMats.add(it) },
            matEmptyPredicate = { false },
            leaseFactory = { _, _, _, _, _, _, _ -> throw IllegalStateException("Simulated lease failure") },
        )

        var threw = false
        try {
            pool.acquire(100, 100, CvType.CV_8UC1)
        } catch (e: IllegalStateException) {
            threw = true
            assertEquals("Simulated lease failure", e.message)
        }

        assertTrue("Lease construction failure must propagate", threw)
        assertEquals("Allocated Mat must be released if lease construction throws", 1, releasedMats.size)
        assertEquals(0L, pool.stats().liveBytes)
        assertEquals(0, pool.stats().liveLeases)
    }

    @Test
    fun matPoolWithCustomAllocatorAndLeaseFactoryDirectlyGovernsOwnershipLifecycle() {
        val allocatedList = mutableListOf<org.opencv.core.Mat>()
        val releasedList = mutableListOf<org.opencv.core.Mat>()
        val customAllocator = object : MatAllocator {
            override fun allocate(rows: Int, cols: Int, type: Int): org.opencv.core.Mat {
                val mat = allocateDummyMat()
                allocatedList.add(mat)
                return mat
            }
            override fun release(mat: org.opencv.core.Mat) {
                releasedList.add(mat)
            }
            override fun isEmpty(mat: org.opencv.core.Mat): Boolean = false
        }

        var customLeaseCreated = false
        val customLeaseFactory = MatLeaseFactory { mat, pool, accounting, key, rows, cols, type ->
            customLeaseCreated = true
            MatLease(mat, pool, accounting, key, rows, cols, type)
        }

        val pool = MatPool(
            maxRetainedBytes = 100L * 1024 * 1024,
            maxPerClass = 3,
            allocator = customAllocator,
            leaseFactory = customLeaseFactory,
        )

        val lease = pool.acquire(100, 100, CvType.CV_8UC1)
        assertTrue("Custom lease factory must be invoked", customLeaseCreated)
        assertEquals(1, allocatedList.size)
        assertEquals(0, releasedList.size)

        lease.close()
        // Retained in pool
        assertEquals(1, pool.stats().retainedBuffers)

        pool.trim()
        assertEquals("Custom allocator release must be invoked on trim", 1, releasedList.size)
        org.junit.Assert.assertSame(allocatedList.first(), releasedList.first())
    }
}
