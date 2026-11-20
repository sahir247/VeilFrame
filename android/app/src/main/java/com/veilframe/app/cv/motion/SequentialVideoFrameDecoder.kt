package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.media.VideoThumbnailPolicy
import com.veilframe.app.media.awaitFfmpegTermination
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Sequential decode with actual FFmpeg presentation timestamps and bounded private storage. */
internal object SequentialVideoFrameDecoder {
    private const val MAX_FRAMES = 1800
    private const val MAX_SPOOL_BYTES = 1024L * 1024 * 1024
    private const val FREE_SPACE_RESERVE = 128L * 1024 * 1024
    private val frameLine = Regex("""\bn:\s*(\d+)\s+pts:\s*(-?\d+)(?:\s|$).*?\bs:\s*(\d+)x(\d+)(?:\s|$)""")

    internal fun parseFrameTimestamps(logs: String, expectedFrames: Int,
                                      frameWidth: Int, frameHeight: Int): List<Long> {
        require(expectedFrames in 1..MAX_FRAMES)
        val timestamps = ArrayList<Long>(expectedFrames)
        for (line in logs.lineSequence()) {
            if (!line.contains("showinfo") || !line.contains("pts:")) continue
            val match = frameLine.find(line) ?: throw IOException("Unrecognized sequential frame metadata")
            val timestamp = match.groupValues[2].toLongOrNull()
            if (match.groupValues[1].toIntOrNull() != timestamps.size || timestamp == null || timestamp < 0L ||
                (timestamps.isNotEmpty() && timestamp <= timestamps.last()) ||
                match.groupValues[3].toIntOrNull() != frameWidth ||
                match.groupValues[4].toIntOrNull() != frameHeight) {
                throw IOException("Frame timestamp order or source display geometry changed during decode")
            }
            timestamps.add(timestamp)
            if (timestamps.size > expectedFrames) throw IOException("Frame metadata count does not match decoded output")
        }
        if (timestamps.size != expectedFrames) throw IOException("Missing frame timestamps; every-frame scan cannot be verified")
        return timestamps
    }

    internal fun frameLimit(bytesPerFrame: Long, usableSpace: Long): Int {
        require(bytesPerFrame > 0)
        val bytes = minOf(MAX_SPOOL_BYTES, (usableSpace - FREE_SPACE_RESERVE).coerceAtLeast(0L))
        // Reserve one sentinel frame to detect an over-budget source.
        return (bytes / bytesPerFrame - 1L).coerceIn(0L, MAX_FRAMES.toLong()).toInt()
    }

    fun decode(
        source: File, cacheDirectory: File, frameWidth: Int, frameHeight: Int,
        isCancelled: () -> Boolean,
        onFrame: (bitmap: Bitmap, timestampUs: Long, nextTimestampUs: Long?, index: Int, count: Int) -> Unit
    ): Int {
        require(source.isFile && frameWidth >= 8 && frameHeight >= 8)
        val (width, height) = VideoThumbnailPolicy.fitWithin(frameWidth, frameHeight, 1280)
        val bytesPerFrame = width.toLong() * height * 3
        val maxFrames = frameLimit(bytesPerFrame, cacheDirectory.usableSpace)
        if (maxFrames < 1) throw IOException("Insufficient private storage for every-frame scan")
        val sourceLength = source.length()
        val sourceModified = source.lastModified()
        val spool = File.createTempFile("pii_sequential_", ".rgb", cacheDirectory)
        try {
            if (isCancelled()) throw CancellationException("Video decode cancelled")
            // Report actual input PTS on a microsecond timebase before scaling.
            val filter = "settb=1/1000000,showinfo,scale=$width:$height:flags=area,format=rgb24"
            val args = arrayOf("-y", "-nostdin", "-hide_banner", "-loglevel", "info",
                "-nostats", "-xerror", "-threads", "2", "-err_detect", "explode",
                "-i", source.absolutePath, "-map", "0:v:0", "-an", "-sn", "-dn",
                "-filter_threads", "1", "-vf", filter, "-vsync", "0",
                "-frames:v", (maxFrames + 1).toString(), "-c:v", "rawvideo",
                "-threads:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", spool.absolutePath)
            val latch = CountDownLatch(1)
            var completed: FFmpegSession? = null
            val session = FFmpegKit.executeWithArgumentsAsync(args, { result ->
                completed = result
                latch.countDown()
            }) ?: throw IOException("Cannot create sequential FFmpeg session")
            try {
                while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                    if (isCancelled()) throw CancellationException("Video decode cancelled")
                }
            } catch (failure: Throwable) {
                try { FFmpegKit.cancel(session.sessionId) }
                catch (cancelError: Throwable) { failure.addSuppressed(cancelError) }
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                awaitFfmpegTermination(latch)
                throw failure
            }
            if (isCancelled()) throw CancellationException("Video decode cancelled")
            val result = completed ?: throw IOException("Sequential decode did not finish")
            if (!ReturnCode.isSuccess(result.returnCode)) throw IOException("Sequential FFmpeg decode failed; no masks applied")
            val length = spool.length()
            if (length <= 0 || length % bytesPerFrame != 0L) throw IOException("Incomplete raw frame output")
            val count = length / bytesPerFrame
            if (count > maxFrames) throw IOException("Every-frame scan exceeds the frame/storage budget. Split the video into shorter clips; no partial scan was applied.")
            val timestamps = parseFrameTimestamps(result.allLogsAsString, count.toInt(), frameWidth, frameHeight)
            fun verifySource() {
                if (source.length() != sourceLength || source.lastModified() != sourceModified)
                    throw IOException("Video changed during sequential scan")
            }
            verifySource()
            val bytes = ByteArray(bytesPerFrame.toInt())
            val pixels = IntArray(width * height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                DataInputStream(spool.inputStream().buffered()).use { input ->
                    for (index in timestamps.indices) {
                        if (isCancelled()) throw CancellationException("Video scan cancelled")
                        input.readFully(bytes)
                        var offset = 0
                        for (i in pixels.indices) {
                            val red = bytes[offset++].toInt() and 255
                            val green = bytes[offset++].toInt() and 255
                            val blue = bytes[offset++].toInt() and 255
                            pixels[i] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                        onFrame(bitmap, timestamps[index], timestamps.getOrNull(index + 1), index + 1, timestamps.size)
                    }
                    if (input.read() != -1) throw IOException("Unexpected trailing frame data")
                }
            } finally {
                bitmap.recycle()
            }
            if (isCancelled()) throw CancellationException("Video scan cancelled")
            verifySource()
            return timestamps.size
        } finally {
            spool.delete()
        }
    }
}
