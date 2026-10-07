package com.veilframe.app.cv.core

import org.opencv.core.Mat

/**
 * MatPool — reusable native buffer pool.
 *
 * A 6000x4000 RGBA image is ~96 MB for ONE uncompressed buffer. Allocating
 * several intermediate Mats per operation destroys a 6 GB device.
  * Honest scope (see docs/cv/VEILFRAME_CV_ENGINE.md): pooling is an OPT-IN
 * native buffer leasing facility for heavy operations and high-frequency routines.
 * Feature primitives in com.veilframe.app.cv.* that do not lease from this pool
 * allocate Mat() directly and release within structured try/finally scopes.
 *
 * Contract:
 *   - [acquire] returns a [MatLease]; the lease MUST be closed (use `lease.use { }`).
 *   - Closing a lease returns retainable buffers to their size-class bucket.
 *   - A pooled buffer is never handed out twice concurrently.
 *   - Buffers larger than [SizeClass.MAX_BYTE_CLASS] (512 MiB) or exceeding
 *     [maxRetainedBytes] are released immediately instead of retained.
 *   - Actual allocation bytes are tracked separately from size-class capacity buckets.
 *
 * Thread-safety: all public methods are synchronized on the pool instance.
 */
/**
 * Abstraction for native [Mat] allocation, release, and emptiness inspection.
 * Decouples [MatPool] from direct native calls and groups test seams into a clean contract.
 */
internal interface MatAllocator {
    fun allocate(rows: Int, cols: Int, type: Int): Mat
    fun release(mat: Mat)
    fun isEmpty(mat: Mat): Boolean

    companion object {
        val Default: MatAllocator = object : MatAllocator {
            override fun allocate(rows: Int, cols: Int, type: Int): Mat = Mat(rows, cols, type)
            override fun release(mat: Mat) { mat.release() }
            override fun isEmpty(mat: Mat): Boolean = mat.empty()
        }
    }
}

/**
 * Factory abstraction for creating [MatLease] instances over pooled or newly-allocated Mats.
 */
internal fun interface MatLeaseFactory {
    fun create(
        mat: Mat,
        pool: MatPool,
        accounting: MatLeaseAccounting,
        key: SizeClass?,
        rows: Int,
        cols: Int,
        type: Int,
    ): MatLease

    companion object {
        val Default: MatLeaseFactory = MatLeaseFactory { mat, pool, accounting, key, rows, cols, type ->
            MatLease(mat, pool, accounting, key, rows, cols, type)
        }
    }
}

internal data class PooledBuffer(
    val mat: Mat,
    val actualBytes: Long,
    val rows: Int,
    val cols: Int,
    val type: Int,
)

class MatPool internal constructor(
    private val maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,
    private val maxPerClass: Int = DEFAULT_MAX_PER_CLASS,
    private val allocator: MatAllocator = MatAllocator.Default,
    private val leaseFactory: MatLeaseFactory = MatLeaseFactory.Default,
) {
    /**
     * Backward-compatible test constructor for callers specifying individual functional seams.
     */
    internal constructor(
        maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,
        maxPerClass: Int = DEFAULT_MAX_PER_CLASS,
        matFactory: (rows: Int, cols: Int, type: Int) -> Mat,
        matReleaser: (Mat) -> Unit = { it.release() },
        matEmptyPredicate: (Mat) -> Boolean = { it.empty() },
        leaseFactory: (mat: Mat, pool: MatPool, accounting: MatLeaseAccounting, key: SizeClass?, rows: Int, cols: Int, type: Int) -> MatLease = { m, p, a, k, r, c, t -> MatLease(m, p, a, k, r, c, t) },
    ) : this(
        maxRetainedBytes = maxRetainedBytes,
        maxPerClass = maxPerClass,
        allocator = object : MatAllocator {
            override fun allocate(rows: Int, cols: Int, type: Int): Mat = matFactory(rows, cols, type)
            override fun release(mat: Mat) = matReleaser(mat)
            override fun isEmpty(mat: Mat): Boolean = matEmptyPredicate(mat)
        },
        leaseFactory = MatLeaseFactory { m, p, a, k, r, c, t -> leaseFactory(m, p, a, k, r, c, t) },
    )

    private val free = HashMap<SizeClass, ArrayDeque<PooledBuffer>>()
    private var retainedBytes = 0L
    private var liveBytes = 0L

    // Observability
    var allocations: Int = 0
        private set
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set
    var liveLeases: Int = 0
        private set

    /** High-water mark of concurrently outstanding pooled actual bytes. */
    var peakLiveBytes: Long = 0L
        private set

    /** Acquires a zero-or-undefined content buffer of [rows] x [cols] x [type]. */
    @Synchronized
    fun acquire(rows: Int, cols: Int, type: Int): MatLease {
        val accounting = SizeClass.accountingFor(rows, cols, type)
        val key = if (accounting.retainable) {
            SizeClass(
                channels = org.opencv.core.CvType.channels(type),
                depth = org.opencv.core.CvType.depth(type),
                byteClass = accounting.classBytes.toInt(),
            )
        } else {
            null
        }

        val bucket = key?.let { free[it] }
        val recycled = bucket?.removeFirstOrNull()
        val (mat, isHit) = if (recycled != null) {
            retainedBytes = (retainedBytes - recycled.actualBytes).coerceAtLeast(0L)
            if (recycled.rows == rows && recycled.cols == cols && recycled.type == type) {
                recycled.mat to true
            } else {
                allocator.release(recycled.mat)
                allocator.allocate(rows, cols, type) to false
            }
        } else {
            allocator.allocate(rows, cols, type) to false
        }

        val lease = try {
            leaseFactory.create(mat, this, accounting, key, rows, cols, type)
        } catch (t: Throwable) {
            allocator.release(mat)
            throw t
        }

        if (isHit) {
            hits++
        } else {
            misses++
            allocations++
        }

        liveLeases++
        liveBytes += accounting.actualBytes
        if (liveBytes > peakLiveBytes) peakLiveBytes = liveBytes
        return lease
    }

    /** Convenience: acquire a buffer with the same shape/type as [template]. */
    @Synchronized
    fun acquireLike(template: Mat): MatLease = acquire(template.rows(), template.cols(), template.type())

    /** Copies [template]'s content into a fresh lease. */
    @Synchronized
    fun acquireCopyOf(template: Mat): MatLease = acquireLike(template).also { template.copyTo(it.mat) }

    @Synchronized
    internal fun release(mat: Mat, lease: MatLease) {
        liveLeases--
        liveBytes = (liveBytes - lease.accounting.actualBytes).coerceAtLeast(0L)
        val key = lease.key
        if (allocator.isEmpty(mat) || !lease.accounting.retainable || key == null) {
            allocator.release(mat)
            return
        }
        val actual = lease.accounting.actualBytes
        val bucket = free.getOrPut(key) { ArrayDeque() }
        if (bucket.size >= maxPerClass || retainedBytes + actual > maxRetainedBytes) {
            allocator.release(mat)
        } else {
            bucket.addLast(PooledBuffer(mat, actual, lease.rows, lease.cols, lease.type))
            retainedBytes += actual
        }
    }

    /** Releases every retained buffer. Live leases stay valid until closed. */
    @Synchronized
    fun trim() {
        for (bucket in free.values) {
            while (bucket.isNotEmpty()) {
                val entry = bucket.removeFirst()
                retainedBytes = (retainedBytes - entry.actualBytes).coerceAtLeast(0L)
                allocator.release(entry.mat)
            }
        }
        free.clear()
        retainedBytes = 0L
    }

    @Synchronized
    fun stats(): PoolStats = PoolStats(
        retainedBytes = retainedBytes,
        retainedBuffers = free.values.sumOf { it.size },
        liveLeases = liveLeases,
        allocations = allocations,
        hits = hits,
        misses = misses,
        liveBytes = liveBytes,
        peakLiveBytes = peakLiveBytes,
    )

    companion object {
        const val DEFAULT_MAX_RETAINED_BYTES: Long = 96L * 1024 * 1024
        const val DEFAULT_MAX_PER_CLASS: Int = 3

        val default: MatPool by lazy { MatPool() }
    }
}

data class PoolStats(
    val retainedBytes: Long,
    val retainedBuffers: Int,
    val liveLeases: Int,
    val allocations: Int,
    val hits: Int,
    val misses: Int,
    val liveBytes: Long = 0L,
    /** High-water mark of concurrently outstanding pooled actual bytes. */
    val peakLiveBytes: Long = 0L,
) {
    val hitRate: Double
        get() {
            val total = hits + misses
            return if (total == 0) 0.0 else hits.toDouble() / total
        }
}

/**
 * Exclusive lease over a pooled [Mat]. Closing returns the buffer to the pool.
 * Always use `use { }`; double close is a no-op.
 */
class MatLease internal constructor(
    val mat: Mat,
    private val pool: MatPool,
    val accounting: MatLeaseAccounting,
    internal val key: SizeClass?,
    val rows: Int = runCatching { mat.rows() }.getOrDefault(0),
    val cols: Int = runCatching { mat.cols() }.getOrDefault(0),
    val type: Int = runCatching { mat.type() }.getOrDefault(0),
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            pool.release(mat, this)
        }
    }
}
