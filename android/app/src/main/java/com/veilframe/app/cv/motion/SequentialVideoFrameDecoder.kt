package com.veilframe.app.cv.motion

import android.content.Context
import android.graphics.Bitmap
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.media.VideoThumbnailPolicy
import com.veilframe.app.media.awaitFfmpegTermination
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Backpressured sequential raw-frame transport; no per-video raster spool. */
internal object SequentialVideoFrameDecoder {
    private const val MAX_FRAMES = 216_000
    private const val STALL_MS = 60_000L

    private class PipeReader(
        val fd: FileDescriptor, val done: CountDownLatch, val cancelled: () -> Boolean
    ) {
        private val poll = StructPollfd().apply {
            this.fd = this@PipeReader.fd
            events = OsConstants.POLLIN.toShort()
        }
        private val pollSet = arrayOf(poll)

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                if (cancelled()) throw CancellationException("Video scan cancelled")
                // Check completion BEFORE the read to avoid racing a final producer write.
                val finished = done.count == 0L
                val n = try { Os.read(fd, buffer, offset, length) }
                catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EWOULDBLOCK) throw IOException("Frame pipe read failed", e)
                    0
                }
                if (n > 0) return n
                if (finished) return -1
                if (android.os.SystemClock.elapsedRealtime() - start > STALL_MS)
                    throw IOException("Sequential video transport stalled")
                try {
                    // Wake as soon as a producer makes bytes available rather than
                    // sleeping between small writes on a narrow kernel pipe.
                    Os.poll(pollSet, 100)
                    val errors = OsConstants.POLLERR or OsConstants.POLLNVAL
                    if (poll.revents.toInt() and errors != 0)
                        throw IOException("Frame pipe reported an error")
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EINTR) throw IOException("Frame pipe poll failed", e)
                }
            }
        }

        fun frame(bytes: ByteArray): Boolean {
            var offset = 0
            while (offset < bytes.size) {
                val n = read(bytes, offset, bytes.size - offset)
                if (n < 0) {
                    if (offset == 0) return false
                    throw IOException("Truncated raw video frame")
                }
                offset += n
            }
            return true
        }
    }

    private class MetadataReader(private val input: PipeReader) {
        private val bytes = ByteArray(4096)
        private var offset = 0
        private var count = 0
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                if (offset == count) {
                    count = input.read(bytes, 0, bytes.size)
                    offset = 0
                    if (count < 0) {
                        count = 0
                        if (out.isEmpty()) return null
                        throw IOException("Truncated frame metadata")
                    }
                }
                val ch = bytes[offset++].toInt() and 255
                if (ch == 10) return out.toString()
                if (ch !in 32..126 || out.length >= 1024) throw IOException("Invalid frame metadata line")
                out.append(ch.toChar())
            }
        }
    }

    internal fun arguments(source: File, rawPipe: String, metadataPipe: String,
                           frameWidth: Int, frameHeight: Int, width: Int, height: Int): Array<String> {
        require(listOf(rawPipe, metadataPipe).all { it.startsWith("/") && it.none { ch -> ch in "|[]':\\" } })
        // A geometry change must fail, not silently scale into the old coordinate system.
        val filter = "settb=1/1000000,scale=w=if(eq(iw\\,$frameWidth)*eq(ih\\,$frameHeight)\\,$width\\,0/0):h=$height:eval=frame:flags=area,format=rgb24"
        return arrayOf("-y", "-nostdin", "-hide_banner", "-loglevel", "error", "-nostats", "-xerror",
            "-threads", "2", "-err_detect", "explode", "-i", source.absolutePath, "-map", "0:v:0",
            "-an", "-sn", "-dn", "-filter_threads", "1", "-vf", filter, "-vsync", "0",
            "-enc_time_base", "1:1000000", "-frames:v", (MAX_FRAMES + 1).toString(),
            "-c:v", "rawvideo", "-threads:v", "1", "-pix_fmt", "rgb24", "-f", "tee",
            "[f=rawvideo:flush_packets=1]$rawPipe|[f=framecrc:flush_packets=1]$metadataPipe")
    }

    fun decode(context: Context, source: File, frameWidth: Int, frameHeight: Int,
               isCancelled: () -> Boolean,
               onFrame: (bitmap: Bitmap, timestampUs: Long, nextTimestampUs: Long?, index: Int, count: Int) -> Unit): Int {
        require(source.isFile && frameWidth >= 8 && frameHeight >= 8)
        val (width, height) = VideoThumbnailPolicy.fitWithin(frameWidth, frameHeight, 1280)
        val sourceLength = source.length()
        val sourceModified = source.lastModified()
        fun verifySource() {
            if (source.length() != sourceLength || source.lastModified() != sourceModified)
                throw IOException("Video changed during sequential scan")
        }
        var rawPath: String? = null
        var metaPath: String? = null
        var rawFd: FileDescriptor? = null
        var metaFd: FileDescriptor? = null
        var session: FFmpegSession? = null
        val done = CountDownLatch(1)
        var completed: FFmpegSession? = null
        try {
            if (isCancelled()) throw CancellationException("Video scan cancelled")
            val raw = FFmpegKitConfig.registerNewFFmpegPipe(context) ?: throw IOException("Cannot allocate raw frame pipe")
            rawPath = raw
            val meta = FFmpegKitConfig.registerNewFFmpegPipe(context) ?: throw IOException("Cannot allocate metadata pipe")
            metaPath = meta
            // RDWR avoids blocking during producer open and NONBLOCK lets cancellation
            // interrupt an otherwise empty pipe. Producer completion defines EOF.
            val rawHandle = Os.open(raw, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            rawFd = rawHandle
            val metaHandle = Os.open(meta, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            metaFd = metaHandle
            session = FFmpegKit.executeWithArgumentsAsync(arguments(source, raw, meta, frameWidth, frameHeight, width, height), { result ->
                completed = result
                done.countDown()
            }) ?: throw IOException("Cannot start sequential FFmpeg decoder")
            val rawReader = PipeReader(rawHandle, done, isCancelled)
            val metaReader = MetadataReader(PipeReader(metaHandle, done, isCancelled))
            val contract = FramePacketContract(width, height)
            val bytes = ByteArray(width * height * 3)
            val pixels = IntArray(width * height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                var count = 0
                var previous: Long? = null
                while (rawReader.frame(bytes)) {
                    if (count == MAX_FRAMES) throw IOException("Every-frame scan exceeds 216,000 frames; split the source")
                    var line = metaReader.line() ?: throw IOException("Missing frame metadata")
                    while (line.startsWith("#")) {
                        contract.header(line)
                        line = metaReader.line() ?: throw IOException("Missing frame packet")
                    }
                    val pts = contract.packet(line, bytes)
                    previous?.let { onFrame(bitmap, it, pts, count, 0) }
                    var offset = 0
                    for (i in pixels.indices) {
                        val red = bytes[offset++].toInt() and 255
                        val green = bytes[offset++].toInt() and 255
                        val blue = bytes[offset++].toInt() and 255
                        pixels[i] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    previous = pts
                    count++
                }
                if (metaReader.line() != null) throw IOException("Metadata without corresponding raw frame")
                val result = completed ?: throw IOException("Sequential decoder did not finish")
                if (!ReturnCode.isSuccess(result.returnCode)) throw IOException("Sequential FFmpeg decode failed; no masks applied")
                val last = previous ?: throw IOException("No video frames decoded")
                verifySource()
                if (isCancelled()) throw CancellationException("Video scan cancelled")
                onFrame(bitmap, last, null, count, count)
                verifySource()
                if (isCancelled()) throw CancellationException("Video scan cancelled")
                return count
            } finally { bitmap.recycle() }
        } finally {
            // Close our RDWR handles first so a blocked producer cannot remain
            // waiting for a consumer after cancellation or detector failure.
            rawFd?.let { runCatching { Os.close(it) } }
            metaFd?.let { runCatching { Os.close(it) } }
            try {
                session?.let {
                    if (done.count != 0L) {
                        try { FFmpegKit.cancel(it.sessionId) }
                        finally { awaitFfmpegTermination(done) }
                    }
                }
            } finally {
                rawPath?.let { FFmpegKitConfig.closeFFmpegPipe(it) }
                metaPath?.let { FFmpegKitConfig.closeFFmpegPipe(it) }
            }
        }
    }
}
