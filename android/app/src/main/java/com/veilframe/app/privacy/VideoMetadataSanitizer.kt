package com.veilframe.app.privacy

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

/**
 * Native Video Privacy and Metadata Sanitization Engine.
 * Enforces container metadata scrubbing, chapter removal, audio scrubbing,
 * and optional PRNU camera sensor fingerprint noise masking via FFmpegKit.
 *
 * Uses direct argument arrays exclusively — zero shell string concatenation or escaping.
 */
object VideoMetadataSanitizer {

    private const val TAG = "VeilFrame.VideoSanitizer"

    fun buildArguments(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true
    ): List<String> {
        val isWebm = outputPath.endsWith(".webm", ignoreCase = true)
        val isInputWebm = inputPath.endsWith(".webm", ignoreCase = true)

        val cmd = mutableListOf<String>()
        cmd.add("-y")
        cmd.add("-i")
        cmd.add(inputPath)
        cmd.add("-map_metadata")
        cmd.add("-1")
        cmd.add("-map_chapters")
        cmd.add("-1")

        if (scrubAudio) {
            cmd.add("-an")
        } else {
            if (isWebm) {
                cmd.add("-c:a")
                cmd.add("libopus")
            } else {
                cmd.add("-c:a")
                cmd.add("copy")
            }
        }

        val vCodec = if (isWebm) "libvpx-vp9" else "libx264"

        when (noiseLevel.lowercase()) {
            "high", "stealth", "aggressive" -> {
                cmd.add("-vf")
                cmd.add("noise=alls=8:allf=t+u")
                cmd.add("-c:v")
                cmd.add(vCodec)
                if (isWebm) {
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add("32")
                } else {
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add("23")
                }
            }
            "medium" -> {
                cmd.add("-vf")
                cmd.add("noise=alls=4:allf=t")
                cmd.add("-c:v")
                cmd.add(vCodec)
                if (isWebm) {
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add("30")
                } else {
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add("22")
                }
            }
            "low" -> {
                cmd.add("-vf")
                cmd.add("noise=alls=2:allf=t")
                cmd.add("-c:v")
                cmd.add(vCodec)
                if (isWebm) {
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add("28")
                } else {
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add("20")
                }
            }
            else -> {
                if (isWebm && !isInputWebm) {
                    cmd.add("-c:v")
                    cmd.add("libvpx-vp9")
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add("30")
                } else {
                    cmd.add("-c:v")
                    cmd.add("copy")
                }
            }
        }

        cmd.add(outputPath)
        return cmd
    }

    fun buildFallbackArguments(
        inputPath: String,
        outputPath: String,
        scrubAudio: Boolean = true
    ): List<String> {
        val isWebm = outputPath.endsWith(".webm", ignoreCase = true)
        val cmd = mutableListOf<String>()
        cmd.add("-y")
        cmd.add("-i")
        cmd.add(inputPath)
        cmd.add("-map_metadata")
        cmd.add("-1")
        cmd.add("-map_chapters")
        cmd.add("-1")

        if (isWebm) {
            cmd.add("-c:v")
            cmd.add("libvpx-vp9")
            cmd.add("-b:v")
            cmd.add("0")
            cmd.add("-crf")
            cmd.add("32")
        } else {
            cmd.add("-c:v")
            cmd.add("copy")
        }

        if (scrubAudio) {
            cmd.add("-an")
        } else {
            if (isWebm) {
                cmd.add("-c:a")
                cmd.add("libopus")
            } else {
                cmd.add("-c:a")
                cmd.add("copy")
            }
        }

        cmd.add(outputPath)
        return cmd
    }

    fun cleanVideo(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true
    ): Boolean {
        return try {
            val primaryArgs = buildArguments(inputPath, outputPath, noiseLevel, scrubAudio)
            Log.d(TAG, "Executing video sanitization with arguments: $primaryArgs")

            val session = FFmpegKit.executeWithArguments(primaryArgs.toTypedArray())
            val returnCode = session.returnCode
            val success = ReturnCode.isSuccess(returnCode)

            if (success) {
                Log.i(TAG, "Video cleaned successfully with FFmpegKit: $outputPath")
                true
            } else {
                Log.w(TAG, "Transcode pass exited with code $returnCode. Retrying fallback pass...")
                val fallbackArgs = buildFallbackArguments(inputPath, outputPath, scrubAudio)
                Log.d(TAG, "Executing fallback sanitization with arguments: $fallbackArgs")
                val fallbackSession = FFmpegKit.executeWithArguments(fallbackArgs.toTypedArray())
                ReturnCode.isSuccess(fallbackSession.returnCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video processing failed: ${e.message}", e)
            false
        }
    }

    suspend fun cleanVideoCancellable(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        try {
            val primaryArgs = buildArguments(inputPath, outputPath, noiseLevel, scrubAudio)
            Log.d(TAG, "Executing video sanitization async with arguments: $primaryArgs")

            val session = FFmpegKit.executeWithArgumentsAsync(primaryArgs.toTypedArray()) { completedSession ->
                if (cont.isActive) {
                    val returnCode = completedSession.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    if (success) {
                        Log.i(TAG, "Video cleaned successfully with FFmpegKit: $outputPath")
                        cont.resumeWith(Result.success(true))
                    } else {
                        Log.w(TAG, "Transcode pass exited with code $returnCode. Retrying fallback pass...")
                        val fallbackArgs = buildFallbackArguments(inputPath, outputPath, scrubAudio)
                        val fallbackSession = FFmpegKit.executeWithArgumentsAsync(fallbackArgs.toTypedArray()) { fallbackCompleted ->
                            if (cont.isActive) {
                                cont.resumeWith(Result.success(ReturnCode.isSuccess(fallbackCompleted.returnCode)))
                            }
                        }
                        cont.invokeOnCancellation {
                            try { FFmpegKit.cancel(fallbackSession.sessionId) } catch (_: Throwable) {}
                        }
                    }
                }
            }

            cont.invokeOnCancellation {
                try {
                    FFmpegKit.cancel(session.sessionId)
                } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video processing failed: ${e.message}", e)
            if (cont.isActive) {
                cont.resumeWith(Result.success(false))
            }
        }
    }
}
