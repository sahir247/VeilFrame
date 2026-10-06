package com.veilframe.app.cv.core

import org.opencv.core.Mat

/**
 * MatPool — reusable native buffer pool.
 *
 * A 6000x4000 RGBA image is ~96 MB for ONE uncompressed buffer. Allocating
 * several intermediate Mats per operation destroys a 6 GB device.
 *
 * Honest scope (see docs/cv/VEILFRAME_CV_ENGINE.md): pooling is currently
 * ENFORCED at the CvEngine job level — the feature primitives in
 * com.veilframe.app.cv.* still allocate Mat() directly and release in finally.
 * Migrating hot paths onto this pool is tracked phase work; do not describe the
 * engine as pool-backed until that lands.
 *
 * Contract:
 *   - [acquire] returns a [MatLease]; the lease MUST be closed (use `lease.use { }`).
 *   - Closing a lease returns the buffer to its size-class bucket.
 *   - A pooled buffer is never handed out twice concurrently.
 *   - Buffers larger than [maxRetainedBytes] per class are released instead of
 *     retained, so the pool cannot pin an unbounded amount of native memory.
 *
 * Thread-safety: all public methods are synchronized on the pool instance.
 */
class MatPool(
    private val maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,
    private val maxPerClass: Int = DEFAULT_MAX_PER_CLASS,
) {
    private val free = HashMap<SizeClass, ArrayDeque<Mat>>()
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

    /** High-water mark of concurrently outstanding pooled bytes (peak, not retained). */
    var peakLiveBytes: Long = 0L
        private set

    /** Acquires a zero-or-undefined content buffer of [rows] x [cols] x [type]. */
    @Synchronized
    fun acquire(rows: Int, cols: Int, type: Int): MatLease {
        val key = SizeClass.forMat(rows, cols, type)
        val bucket = free[key]
        val recycled = bucket?.removeFirstOrNull()
        val lease = if (recycled != null) {
            retainedBytes -= key.approximateBytes
            hits++
            // Shape may differ inside the class; allocate fresh when it does.
            val mat = if (recycled.rows() == rows && recycled.cols() == cols && recycled.type() == type) {
                recycled
            } else {
                recycled.release()
                Mat(rows, cols, type).also { allocations++ }
            }
            MatLease(mat, this, key)
        } else {
            misses++
            allocations++
            MatLease(Mat(rows, cols, type), this, key)
        }
        liveLeases++
        liveBytes += key.approximateBytes
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
    internal fun release(mat: Mat, key: SizeClass) {
        liveLeases--
        liveBytes = (liveBytes - key.approximateBytes).coerceAtLeast(0L)
        if (mat.empty()) return
        val bucket = free.getOrPut(key) { ArrayDeque() }
        if (bucket.size >= maxPerClass || retainedBytes + key.approximateBytes > maxRetainedBytes) {
            mat.release()
        } else {
            bucket.addLast(mat)
            retainedBytes += key.approximateBytes
        }
    }

    /** Releases every retained buffer. Live leases stay valid until closed. */
    @Synchronized
    fun trim() {
        for (bucket in free.values) {
            while (bucket.isNotEmpty()) {
                val mat = bucket.removeFirst()
                retainedBytes -= SizeClass.forMat(
                    maxOf(mat.rows(), 1), maxOf(mat.cols(), 1), mat.type()
                ).approximateBytes
                mat.release()
            }
        }
        free.clear()
        retainedBytes = 0
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
    /** High-water mark of concurrently outstanding pooled bytes. */
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
    private val key: SizeClass,
) : AutoCloseable {
    val rows: Int get() = mat.rows()
    val cols: Int get() = mat.cols()
    val type: Int get() = mat.type()

    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            pool.release(mat, key)
        }
    }
}

