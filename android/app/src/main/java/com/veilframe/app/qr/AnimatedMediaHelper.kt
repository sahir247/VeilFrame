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
        maxFrames: Int = 36
    ): List<QrFrame> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: ""
        val uriStr = uri.toString().lowercase(Locale.ROOT)
        val isGif = mimeType.equals("image/gif", ignoreCase = true) || uriStr.endsWith(".gif")
        val isVideo = mimeType.startsWith("video/", ignoreCase = true) ||
            uriStr.endsWith(".mp4") || uriStr.endsWith(".mov") || uriStr.endsWith(".webm") || uriStr.endsWith(".mkv")

        if (!isGif && !isVideo) return@withContext emptyList()

        val tempDir = File(context.cacheDir, "anim_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        val inputFile = File(tempDir, if (isGif) "input.gif" else "input.mp4")

        try {
            // 1. Copy stream to cache file
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext emptyList()

            val framesDir = File(tempDir, "frames").apply { mkdirs() }
            val framePattern = File(framesDir, "frame_%04d.png").absolutePath

            // 2. Extract frames via FFmpegKit
            val ffmpegCmd = if (isGif) {
                "-y -i \"${inputFile.absolutePath}\" -vf \"scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=black@0\" -vframes $maxFrames \"$framePattern\""
            } else {
                "-y -i \"${inputFile.absolutePath}\" -vf \"scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2\" -r 10 -vframes $maxFrames \"$framePattern\""
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
                val delayMs = if (isGif) 100 else 100 // 10 fps default
                for (file in frameFiles) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        resultFrames.add(QrFrame(bmp, delayMs))
                    }
                }
            }

            // Fallback for video if FFmpeg didn't produce frames
            if (resultFrames.isEmpty() && isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(inputFile.absolutePath)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
                    val count = 16.coerceAtMost(maxFrames)
                    val stepMs = (durationMs / count).coerceAtLeast(40L)
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
}
