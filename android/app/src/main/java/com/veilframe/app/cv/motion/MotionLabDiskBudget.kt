package com.veilframe.app.cv.motion

import java.io.File

/**
 * Bounded cache-workspace guard. The legacy JPEG bridge can consume large
 * temporary storage (source copy, input JPEGs, synthesized JPEGs, MP4).
 * Refuse low storage explicitly instead of completing a partial gallery file
 * or exhausting space while an encoder owns the output.
 *
 * This reserve is a failure guard, not an estimate that the entire job fits;
 * source-frame and duration caps remain independent.
 */
object MotionLabDiskBudget {
    const val RESERVED_FREE_BYTES: Long = 256L * 1024L * 1024L

    fun requireHeadroom(workDirectory: File) {
        require(workDirectory.isDirectory) { "Motion Lab cache directory is unavailable" }
        val usable = workDirectory.usableSpace
        check(hasHeadroom(usable)) {
            "Motion Lab needs at least 256 MiB free working storage; " +
                "available: ${usable / (1024L * 1024L)} MiB. Free space or shorten the video."
        }
    }

    fun hasHeadroom(usableBytes: Long): Boolean = usableBytes >= RESERVED_FREE_BYTES
}
