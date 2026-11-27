package com.veilframe.app.cv.motion

import java.io.File
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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

    fun requireHeadroom(workDirectory: File, upcomingBytes: Long = 0) {
        require(workDirectory.isDirectory) { "Motion Lab cache directory is unavailable" }
        val usable = workDirectory.usableSpace
        check(hasHeadroom(usable, upcomingBytes)) {
            "Motion Lab needs at least 256 MiB free working storage; " +
                "available: ${usable / (1024L * 1024L)} MiB. Free space or shorten the video."
        }
    }

    fun hasHeadroom(usableBytes: Long, upcomingBytes: Long = 0): Boolean =
        upcomingBytes >= 0 && usableBytes >= RESERVED_FREE_BYTES &&
            upcomingBytes <= usableBytes - RESERVED_FREE_BYTES

    /** Copy directly into an owned file, maintaining reserve before each write. */
    suspend fun stageInput(input: InputStream, output: File, directory: File): Long {
        val buffer = ByteArray(1024 * 1024)
        var copied = 0L
        output.outputStream().use { sink ->
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) throw IOException("Video source made no read progress")
                requireHeadroom(directory, n.toLong())
                sink.write(buffer, 0, n)
                copied = Math.addExact(copied, n.toLong())
            }
        }
        check(copied > 0 && output.length() == copied) { "Empty or incomplete video source" }
        return copied
    }

    /** A sampled safety guard, not a hard disk reservation. */
    internal suspend fun <T> monitor(
        check: () -> Unit, intervalMs: Long = 250, block: suspend () -> T
    ): T = coroutineScope {
        require(intervalMs > 0)
        check()
        val watcher = launch {
            while (isActive) {
                delay(intervalMs)
                check()
            }
        }
        try {
            block().also { check() }
        } finally {
            watcher.cancel()
        }
    }
}
