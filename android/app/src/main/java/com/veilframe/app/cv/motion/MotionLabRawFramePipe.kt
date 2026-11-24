package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.media.executeOwnedFfmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.IOException
import kotlin.coroutines.coroutineContext
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-process FFmpeg 8.1 descriptor transport. Unlike named FIFOs, creating both
 * ends cannot block waiting for another process to open a path. FFmpeg's pipe
 * protocol duplicates the read descriptor; keep the original alive until its
 * owned session has actually terminated. Only the writer is nonblocking.
 *
 * One sequential producer owns the writer; no concurrent frame writes or
 * retention of input bitmaps/Mats. An exception invalidates the entire stream.
 * The caller owns any staged output file and must verify before publication.
 */
internal object MotionLabRawFramePipe {
    internal data class Geometry(val width: Int, val height: Int) {
        init {
            require(width in 1..8192 && height in 1..8192 &&
                width.toLong() * height <= 16_777_216L) { "Unsupported raw frame geometry" }
        }
        val rowBytes: Int get() = width * 3
        val frameBytes: Long get() = rowBytes.toLong() * height
    }

    /**
     * Additional arguments are owned by the caller (output mapping, codecs etc.).
     * No frame limit that makes FFmpeg stop reading before EOF may be supplied.
     */
    suspend fun encode(
        geometry: Geometry,
        fps: Double,
        expectedFrames: Int,
        outputArguments: Array<String>,
        stallTimeoutMs: Long = 30_000L,
        produce: suspend Writer.() -> Unit
    ): FFmpegSession {
        require(fps.isFinite() && fps in 1.0..120.0)
        require(expectedFrames in 1..MotionLabTimingContract.MAX_SOURCE_FRAMES * 4)
        require(stallTimeoutMs in 100L..120_000L)
        coroutineContext.ensureActive()
        val ends = ParcelFileDescriptor.createPipe()
        // Outer ownership outlives coroutineScope, including cancellation of
        // the async session, which awaits native retirement.
        ends[0].use { reader ->
            ends[1].use { writer ->
                val fd = writer.fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL,
                    Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                val args = arrayOf(
                    "-nostdin", "-y", "-f", "rawvideo", "-pixel_format", "bgr24",
                    "-video_size", "${geometry.width}x${geometry.height}",
                    "-framerate", String.format(Locale.ROOT, "%.8f", fps),
                    "-i", "pipe:${reader.fd}"
                ) + outputArguments
                return coroutineScope {
                    val session = async(Dispatchers.IO) {
                        val completed = executePipeSession(args)
                        if (!ReturnCode.isSuccess(completed.returnCode)) {
                            throw IOException("Raw frame encoder failed (rc=${completed.returnCode})")
                        }
                        completed
                    }
                    val frames = Writer(geometry, expectedFrames, stallTimeoutMs, fd) { session.isCompleted }
                    try {
                        frames.produce()
                        frames.requireComplete()
                    } finally {
                        // Crucial: deliver EOF before waiting for a native read
                        // to retire, even when producer fails or is cancelled.
                        writer.close()
                    }
                    session.await()
                }
            }
        }
    }

    // Each end has its own native execution thread. A saturated shared
    // FFmpegKit executor cannot serialize the two mutually dependent sessions.
    private suspend fun executePipeSession(args: Array<String>): FFmpegSession {
        val executor = Executors.newSingleThreadExecutor()
        return try { executeOwnedFfmpeg(args, executor) }
        finally { executor.shutdown() }
    }

    /**
     * The decoder owns the write descriptor until its native duplicate has
     * retired. The consumer owns the nonblocking read end and closes it
     * before cancellation joins a potentially blocked native writer.
     */
    suspend fun <T> decode(
        geometry: Geometry,
        expectedFrames: Int,
        inputArguments: Array<String>,
        stallTimeoutMs: Long = 30_000L,
        consume: suspend Reader.() -> T
    ): T {
        require(expectedFrames in 1..MotionLabTimingContract.MAX_SOURCE_FRAMES)
        require(stallTimeoutMs in 100L..120_000L)
        coroutineContext.ensureActive()
        val ends = ParcelFileDescriptor.createPipe()
        return ends[0].use { reader ->
            ends[1].use { writer ->
                val fd = reader.fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL,
                    Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                val args = arrayOf("-nostdin", "-xerror") + inputArguments + arrayOf(
                    "-map", "0:v:0", "-an", "-sn", "-dn", "-fps_mode", "passthrough",
                    "-c:v", "rawvideo", "-threads:v", "1", "-pix_fmt", "bgr24",
                    "-f", "rawvideo", "pipe:${writer.fd}"
                )
                coroutineScope {
                    val session = async(Dispatchers.IO) {
                        try {
                            val result = executePipeSession(args)
                            if (!ReturnCode.isSuccess(result.returnCode)) {
                                throw IOException("Raw frame decoder failed (rc=${result.returnCode})")
                            }
                        } finally {
                            // EOF means the native writer is no longer alive.
                            writer.close()
                        }
                    }
                    val frames = Reader(geometry, expectedFrames, stallTimeoutMs, fd)
                    val result = try {
                        frames.consume().also { frames.requireComplete() }
                    } finally {
                        reader.close()
                    }
                    session.await()
                    result
                }
            }
        }
    }

    internal class Reader(
        val geometry: Geometry,
        private val expectedFrames: Int,
        private val stallTimeoutMs: Long,
        private val descriptor: java.io.FileDescriptor
    ) {
        private val row = ByteArray(geometry.rowBytes)
        private val pixels = IntArray(geometry.width)
        private val rowsPerChunk = (64 * 1024 / geometry.rowBytes).coerceAtLeast(1)
        private val chunk = ByteArray(rowsPerChunk * geometry.rowBytes)
        var framesRead = 0
            private set

        suspend fun readBitmap(): Bitmap {
            check(framesRead < expectedFrames) { "Unexpected additional source frame request" }
            val bitmap = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
            try {
                readRows { y ->
                    for (x in pixels.indices) {
                        val p = x * 3
                        pixels[x] = (0xff shl 24) or ((row[p+2].toInt() and 255) shl 16) or
                            ((row[p+1].toInt() and 255) shl 8) or (row[p].toInt() and 255)
                    }
                    bitmap.setPixels(pixels, 0, geometry.width, 0, y, geometry.width, 1)
                }
                return bitmap
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
        }

        suspend fun readMat(): Mat {
            check(framesRead < expectedFrames) { "Unexpected additional source frame request" }
            val mat = Mat(geometry.height, geometry.width, CvType.CV_8UC3)
            try {
                readRows { y -> check(mat.put(y, 0, row) == row.size) { "Incomplete raw Mat row" } }
                return mat
            } catch (failure: Throwable) {
                mat.release()
                throw failure
            }
        }

        private suspend fun readRows(accept: (Int) -> Unit) {
            var y = 0
            while (y < geometry.height) {
                val rows = minOf(rowsPerChunk, geometry.height-y)
                val bytes = rows * row.size
                var offset = 0
                while (offset < bytes) {
                    val n = readProgress(chunk, offset, bytes-offset)
                    if (n == 0) throw IOException("Raw source ended in frame ${framesRead+1}")
                    offset += n
                }
                for (i in 0 until rows) {
                    coroutineContext.ensureActive()
                    chunk.copyInto(row, 0, i*row.size, (i+1)*row.size)
                    accept(y++)
                }
            }
            framesRead++
        }

        private suspend fun readProgress(bytes: ByteArray, offset: Int, length: Int): Int {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                coroutineContext.ensureActive()
                try {
                    return Os.read(descriptor, bytes, offset, length)
                } catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.EAGAIN && failure.errno != OsConstants.EINTR)
                        throw IOException("Raw source pipe read failed", failure)
                }
                if (SystemClock.elapsedRealtime()-start >= stallTimeoutMs)
                    throw IOException("Raw source decoder stopped producing bytes")
                delay(2)
            }
        }

        internal suspend fun requireComplete() {
            check(framesRead == expectedFrames) { "Raw source delivered $framesRead of $expectedFrames expected frames" }
            check(readProgress(row, 0, 1) == 0) { "Raw source contains unexpected extra bytes or frames" }
        }
    }

    internal class Writer(
        val geometry: Geometry,
        private val expectedFrames: Int,
        private val stallTimeoutMs: Long,
        private val descriptor: java.io.FileDescriptor,
        private val encoderFinished: () -> Boolean
    ) {
        private val pixels = IntArray(geometry.width)
        private val row = ByteArray(geometry.rowBytes)
        // Row-aligned batch amortizes system calls; never allocates a full frame.
        private val rowsPerChunk = (64 * 1024 / geometry.rowBytes).coerceAtLeast(1)
        private val chunk = ByteArray(rowsPerChunk * geometry.rowBytes)
        var framesWritten: Int = 0
            private set

        private fun requireGeometry(width: Int, height: Int) {
            require(width == geometry.width && height == geometry.height) { "Raw frame geometry changed" }
            check(framesWritten < expectedFrames) { "Too many raw frames" }
        }

        suspend fun writeBitmap(bitmap: Bitmap) {
            require(!bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888) {
                "Raw transport requires a live ARGB_8888 bitmap"
            }
            requireGeometry(bitmap.width, bitmap.height)
            writeRows { y ->
                bitmap.getPixels(pixels, 0, geometry.width, 0, y, geometry.width, 1)
                packArgbAsBgr(pixels, row)
            }
        }

        suspend fun writeMat(mat: Mat) {
            require(!mat.empty() && mat.type() == CvType.CV_8UC3) { "Raw transport requires a BGR CV_8UC3 Mat" }
            requireGeometry(mat.cols(), mat.rows())
            writeRows { y ->
                check(mat.get(y, 0, row) == row.size) { "Incomplete OpenCV raw frame row" }
            }
        }

        private suspend fun writeRows(fill: (Int) -> Unit) {
            var used = 0
            for (y in 0 until geometry.height) {
                coroutineContext.ensureActive()
                fill(y)
                row.copyInto(chunk, used)
                used += row.size
                if (used == chunk.size) {
                    writeFully(chunk, used)
                    used = 0
                }
            }
            if (used > 0) writeFully(chunk, used)
            framesWritten++
        }

        private suspend fun writeFully(bytes: ByteArray, length: Int) {
            var offset = 0
            var lastProgress = SystemClock.elapsedRealtime()
            while (offset < length) {
                coroutineContext.ensureActive()
                if (encoderFinished()) throw IOException("Raw frame encoder exited before the producer finished")
                val count = try {
                    Os.write(descriptor, bytes, offset, length - offset)
                } catch (e: ErrnoException) {
                    when (e.errno) {
                        OsConstants.EAGAIN, OsConstants.EINTR -> 0
                        else -> throw IOException("Raw frame pipe write failed", e)
                    }
                }
                if (count > 0) {
                    offset += count
                    lastProgress = SystemClock.elapsedRealtime()
                } else {
                    if (SystemClock.elapsedRealtime() - lastProgress >= stallTimeoutMs) {
                        throw IOException("Raw frame encoder stopped consuming bytes")
                    }
                    delay(2)
                }
            }
        }

        internal fun requireComplete() {
            check(framesWritten == expectedFrames) {
                "Raw stream contains $framesWritten of $expectedFrames expected frames"
            }
        }
    }

    internal fun packArgbAsBgr(pixels: IntArray, destination: ByteArray) {
        require(destination.size == pixels.size * 3)
        var offset = 0
        for (pixel in pixels) {
            destination[offset++] = pixel.toByte()
            destination[offset++] = (pixel ushr 8).toByte()
            destination[offset++] = (pixel ushr 16).toByte()
        }
    }
}
