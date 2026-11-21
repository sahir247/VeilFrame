package com.veilframe.app.cv.motion

import java.io.IOException
import java.util.zip.Adler32

/** Strict packet metadata from FFmpeg's framecrc muxer, paired with raw RGB. */
internal class FramePacketContract(private val width: Int, private val height: Int) {
    private var timebase = false
    private var geometry = false
    private var codec = false
    private var video = false
    private var previous = -1L
    private val bytes = Math.multiplyExact(Math.multiplyExact(width, height), 3)

    fun header(line: String) {
        if (previous >= 0) throw IOException("Unexpected header inside frame sequence")
        when {
            line.startsWith("#tb ") -> {
                if (timebase || line != "#tb 0: 1/1000000") throw IOException("Unexpected video timebase")
                timebase = true
            }
            line.startsWith("#dimensions ") -> {
                if (geometry || line != "#dimensions 0: ${width}x${height}")
                    throw IOException("Unexpected packet dimensions")
                geometry = true
            }
            line.startsWith("#codec_id ") -> {
                if (codec || line != "#codec_id 0: rawvideo") throw IOException("Unexpected packet codec")
                codec = true
            }
            line.startsWith("#media_type ") -> {
                if (video || line != "#media_type 0: video") throw IOException("Unexpected packet type")
                video = true
            }
            line.startsWith("#software:") || line.startsWith("#sar 0:") -> Unit
            else -> throw IOException("Unrecognized frame metadata header")
        }
    }

    fun packet(line: String, rgb: ByteArray): Long {
        if (!timebase || !geometry || !codec || !video || rgb.size != bytes)
            throw IOException("Missing frame metadata or unexpected payload size")
        val fields = line.split(',').map { it.trim() }
        if (fields.size != 6 || fields[0] != "0") throw IOException("Unrecognized frame packet")
        val dts = fields[1].toLongOrNull()
        val pts = fields[2].toLongOrNull()
        val duration = fields[3].toLongOrNull()
        if (pts == null || dts != pts || pts < 0 || pts <= previous ||
            duration == null || duration < 0 || fields[4].toIntOrNull() != bytes ||
            !fields[5].matches(Regex("0x[0-9a-fA-F]{8}"))) {
            throw IOException("Invalid frame packet timing or size")
        }
        if (fields[5].substring(2).toLong(16) != checksum(rgb)) throw IOException("Frame payload checksum mismatch")
        previous = pts
        return pts
    }

    companion object {
        /** FFmpeg framecrc initializes Adler-32 to zero; java.util.zip starts at one. */
        fun checksum(rgb: ByteArray): Long {
            val java = Adler32().apply { update(rgb) }.value
            val low = ((java and 65535L) + 65520L) % 65521L
            val high = ((java ushr 16) + 65521L - rgb.size % 65521L) % 65521L
            return (high shl 16) or low
        }
    }
}
