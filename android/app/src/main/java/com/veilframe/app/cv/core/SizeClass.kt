package com.veilframe.app.cv.core

import org.opencv.core.CvType

/**
 * Accounting metadata for a [MatLease], separating actual allocation bytes
 * from size-class capacity buckets and retainability decisions.
 */
data class MatLeaseAccounting(
    /** Exact physical memory footprint (rows * cols * elemSize). */
    val actualBytes: Long,
    /** Size-class bucket capacity (or actualBytes if non-retainable). */
    val classBytes: Long,
    /** True if this buffer is eligible for retention in [MatPool]. */
    val retainable: Boolean,
)

/**
 * Size-class bookkeeping for [MatPool].
 *
 * Buffers are bucketed by channel count and a byte class (power-of-two below
 * [FINE_GRAINED_FROM], +25% steps above it) so that near-identical working
 * resolutions reuse the same physical allocation instead of fragmenting native heap.
 *
 * Buffers larger than [MAX_BYTE_CLASS] (512 MiB) are classified as non-retainable
 * to prevent pinning excessive native memory.
 *
 * Pure math — no OpenCV allocation happens here.
 */
data class SizeClass(
    val channels: Int,
    val depth: Int,
    val byteClass: Int,
) {
    val approximateBytes: Long get() = byteClass.toLong()

    companion object {
        const val MIN_BYTE_CLASS = 64 * 1024 // 64 KiB floor
        const val MAX_BYTE_CLASS = 512 * 1024 * 1024 // 512 MiB ceiling per retainable buffer

        /** Growth switches from doubling to +25% steps at this size. */
        const val FINE_GRAINED_FROM = 32 * 1024 * 1024

        /** Smallest byte class that fits [bytes], or -1 if [bytes] exceeds [MAX_BYTE_CLASS]. */
        fun byteClassFor(bytes: Long): Int {
            require(bytes >= 0) { "bytes must be >= 0, got $bytes" }
            if (bytes <= MIN_BYTE_CLASS) return MIN_BYTE_CLASS
            if (bytes > MAX_BYTE_CLASS) return -1
            var size = MIN_BYTE_CLASS.toLong()
            while (size < bytes) {
                size = if (size < FINE_GRAINED_FROM) size shl 1 else size + (size shr 2)
                if (size >= MAX_BYTE_CLASS) return MAX_BYTE_CLASS
            }
            return size.toInt()
        }

        /** Produces exact [MatLeaseAccounting] for the given Mat dimensions and type. */
        fun accountingFor(rows: Int, cols: Int, type: Int): MatLeaseAccounting {
            require(rows > 0 && cols > 0) { "invalid Mat shape ${rows}x$cols" }
            val actual = rows.toLong() * cols.toLong() * CvType.ELEM_SIZE(type).toLong()
            val bClass = byteClassFor(actual)
            val retainable = bClass > 0 && actual <= MAX_BYTE_CLASS
            return MatLeaseAccounting(
                actualBytes = actual,
                classBytes = if (retainable) bClass.toLong() else actual,
                retainable = retainable,
            )
        }

        /** Returns the [SizeClass] key if retainable, or null for oversize buffers. */
        fun forMat(rows: Int, cols: Int, type: Int): SizeClass? {
            val accounting = accountingFor(rows, cols, type)
            if (!accounting.retainable) return null
            return SizeClass(
                channels = CvType.channels(type),
                depth = CvType.depth(type),
                byteClass = accounting.classBytes.toInt(),
            )
        }
    }
}
