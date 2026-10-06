package com.veilframe.app.cv.core

import org.opencv.core.CvType

/**
 * Size-class bookkeeping for [MatPool].
 *
 * Buffers are bucketed by channel count and a byte class (power-of-two below
 * [FINE_GRAINED_FROM], +25% steps above it) so that near-identical working
 * resolutions (1080p landscape vs portrait, for example) reuse the same
 * physical allocation instead of fragmenting native heap — while large
 * buffers don't jump e.g. 256 MiB → 512 MiB and pin twice the needed memory.
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
        const val MAX_BYTE_CLASS = 512 * 1024 * 1024 // 512 MiB ceiling per buffer

        /** Growth switches from doubling to +25% steps at this size. */
        const val FINE_GRAINED_FROM = 32 * 1024 * 1024

        /** Smallest byte class that fits [bytes], clamped to sane bounds. */
        fun byteClassFor(bytes: Long): Int {
            require(bytes >= 0) { "bytes must be >= 0, got $bytes" }
            if (bytes <= MIN_BYTE_CLASS) return MIN_BYTE_CLASS
            var size = MIN_BYTE_CLASS.toLong()
            while (size < bytes) {
                // Doubling keeps class counts small; above FINE_GRAINED_FROM the
                // +25% steps avoid oversized class jumps (256 → 512 MiB) that
                // would retain far more native memory than the buffer needs.
                size = if (size < FINE_GRAINED_FROM) size shl 1 else size + (size shr 2)
                if (size >= MAX_BYTE_CLASS) return MAX_BYTE_CLASS
            }
            return size.toInt()
        }

        fun forMat(rows: Int, cols: Int, type: Int): SizeClass {
            require(rows > 0 && cols > 0) { "invalid Mat shape ${rows}x$cols" }
            val bytes = rows.toLong() * cols.toLong() * CvType.ELEM_SIZE(type)
            return SizeClass(
                channels = CvType.channels(type),
                depth = CvType.depth(type),
                byteClass = byteClassFor(bytes),
            )
        }
    }
}
