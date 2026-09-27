package com.veilframe.app.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.qr.model.QrFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Robust extractor for animated media (GIFs, WebP animations, MP4/MOV videos)
 * to support multi-frame animated QR code generation.
 */
object AnimatedMediaHelper {

    suspend fun extractFrames(
        context: Context,
        uri: Uri,
        maxFrames: Int = 120
    ): List<QrFrame> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: ""
        val uriStr = uri.toString().lowercase(Locale.ROOT)
        val isGif = mimeType.equals("image/gif", ignoreCase = true) || uriStr.endsWith(".gif")
        val isWebp = mimeType.equals("image/webp", ignoreCase = true) || uriStr.endsWith(".webp")
        val isVideo = mimeType.startsWith("video/", ignoreCase = true) ||
            uriStr.endsWith(".mp4") || uriStr.endsWith(".mov") || uriStr.endsWith(".webm") || uriStr.endsWith(".mkv")

        if (!isGif && !isWebp && !isVideo) return@withContext emptyList()

        val tempDir = File(context.cacheDir, "anim_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        val ext = when {
            isGif -> "gif"
            isWebp -> "webp"
            else -> "mp4"
        }
        val inputFile = File(tempDir, "input.$ext")

        try {
            // 1. Copy stream to cache file
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext emptyList()

            val framesDir = File(tempDir, "frames").apply { mkdirs() }
            val framePattern = File(framesDir, "frame_%04d.png").absolutePath

            val videoFps = 15
            val videoDelayMs = (1000.0 / videoFps).toInt() // ~67ms per frame at 15 fps
            val ffmpegCmd = if (isGif || isWebp) {
                "-y -i \"${inputFile.absolutePath}\" -vf \"scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=black@0\" -vframes $maxFrames \"$framePattern\""
            } else {
                "-y -i \"${inputFile.absolutePath}\" -vf \"scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2\" -r $videoFps -vframes $maxFrames \"$framePattern\""
            }

            var extractedViaFFmpeg = false
            try {
                val session = FFmpegKit.execute(ffmpegCmd)
                if (ReturnCode.isSuccess(session.returnCode)) {
                    extractedViaFFmpeg = true
                }
            } catch (_: Throwable) {
                extractedViaFFmpeg = false
            }

            val resultFrames = mutableListOf<QrFrame>()

            if (extractedViaFFmpeg) {
                val frameFiles = framesDir.listFiles()?.filter { it.extension.equals("png", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()
                val delays = when {
                    isGif -> parseGifDelays(inputFile)
                    isWebp -> parseWebpDelays(inputFile)
                    else -> emptyList()
                }
                for ((idx, file) in frameFiles.withIndex()) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        val frameDelay = if ((isGif || isWebp) && delays.isNotEmpty()) {
                            delays.getOrElse(idx) { delays.lastOrNull() ?: 100 }
                        } else if (isVideo) {
                            videoDelayMs
                        } else {
                            100
                        }
                        resultFrames.add(QrFrame(bmp, frameDelay))
                    }
                }
            }

            // Fallback for video if FFmpeg didn't produce frames
            if (resultFrames.isEmpty() && isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(inputFile.absolutePath)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
                    val count = 24.coerceAtMost(maxFrames)
                    val stepMs = (durationMs / count).coerceIn(40L, 500L)
                    for (i in 0 until count) {
                        val timeUs = i * stepMs * 1000L
                        val frameBmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (frameBmp != null) {
                            val scaled = Bitmap.createScaledBitmap(frameBmp, 512, 512, true)
                            resultFrames.add(QrFrame(scaled, stepMs.toInt()))
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { retriever.release() } catch (_: Throwable) {}
                }
            }

            resultFrames
        } catch (_: Throwable) {
            emptyList()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Parses per-frame delay timings from GIF Graphic Control Extension blocks (0x21 0xF9 0x04).
     * Format: Byte 4 (delay low), Byte 5 (delay high) in hundredths of a second (10ms units).
     */
    fun parseGifDelays(file: File): List<Int> {
        val delays = mutableListOf<Int>()
        try {
            val bytes = file.readBytes()
            var i = 0
            while (i < bytes.size - 7) {
                if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 0x04.toByte()) {
                    val delayLow = bytes[i + 4].toInt() and 0xFF
                    val delayHigh = bytes[i + 5].toInt() and 0xFF
                    val delayHundredths = delayLow or (delayHigh shl 8)
                    val delayMs = if (delayHundredths > 0) delayHundredths * 10 else 100
                    delays.add(delayMs.coerceIn(20, 10000))
                    i += 7
                } else {
                    i++
                }
            }
        } catch (_: Throwable) {
        }
        return delays
    }

    /**
     * Parses per-frame delay timings from WebP ANMF (Animation Frame) chunks.
     * WebP container format: RIFF....WEBP chunks.
     * ANMF chunk payload offset 12..14 is uint24 frame duration in milliseconds.
     */
    fun parseWebpDelays(file: File): List<Int> {
        return try {
            parseWebpDelays(file.readBytes())
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseWebpDelays(bytes: ByteArray): List<Int> {
        val delays = mutableListOf<Int>()
        if (bytes.size < 12) return delays
        // Validate RIFF and WEBP signatures
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[8] != 'W'.code.toByte() || bytes[9] != 'E'.code.toByte() ||
            bytes[10] != 'B'.code.toByte() || bytes[11] != 'P'.code.toByte()
        ) {
            return delays
        }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkFourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val payloadOffset = offset + 8
            if (chunkFourCC == "ANMF" && chunkSize >= 16 && payloadOffset + 16 <= bytes.size) {
                // ANMF Chunk duration is at offset 12..14 of payload (uint24 little-endian in ms)
                val durLow = bytes[payloadOffset + 12].toInt() and 0xFF
                val durMid = bytes[payloadOffset + 13].toInt() and 0xFF
                val durHigh = bytes[payloadOffset + 14].toInt() and 0xFF
                val durationMs = durLow or (durMid shl 8) or (durHigh shl 16)
                delays.add(if (durationMs > 0) durationMs.coerceIn(10, 10000) else 100)
            }
            // Chunks in RIFF are padded to 2-byte boundary if size is odd
            val paddedChunkSize = chunkSize + (chunkSize and 1)
            if (paddedChunkSize <= 0) break
            offset = payloadOffset + paddedChunkSize
        }
        return delays
    }
}
