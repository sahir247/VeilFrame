package com.veilframe.app.privacy

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

/**
 * Native Video Privacy and Metadata Sanitization Engine.
 * Enforces container metadata scrubbing, chapter removal, audio scrubbing,
 * and temporal noise dithering via FFmpegKit (mitigating camera sensor fingerprinting
 * via high-frequency noise injection, though not full physical PRNU subtraction).
 *
 * Uses direct argument arrays exclusively — zero shell string concatenation or escaping.
 */
enum class VideoSanitizationStatus { TRANSCODED, METADATA_ONLY_FALLBACK, FAILED }

data class VideoSanitizationResult(
    val status: VideoSanitizationStatus,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = status != VideoSanitizationStatus.FAILED
}

object VideoMetadataSanitizer {

    private const val TAG = "VeilFrame.VideoSanitizer"

    fun buildArguments(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false,
        enableAsymmetricScale: Boolean = false
    ): List<String> {
        val isWebm = outputPath.endsWith(".webm", ignoreCase = true)
        val isInputWebm = inputPath.endsWith(".webm", ignoreCase = true)

        val cmd = mutableListOf<String>()
        cmd.add("-y")
        cmd.add("-i")
        cmd.add(inputPath)
        if (stripExif) {
            cmd.add("-map_metadata")
            cmd.add("-1")
            cmd.add("-map_chapters")
            cmd.add("-1")
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

        val vCodec = if (isWebm) "libvpx-vp9" else "libx264"

        when (noiseLevel.lowercase()) {
            "high", "stealth", "aggressive" -> {
                cmd.add("-vf")
                if (enableAsymmetricScale) {
                    cmd.add("scale=iw*0.963:ih*0.957:flags=bicubic,pad=ceil(iw/2)*2:ceil(ih/2)*2:(ow-iw)/2:(oh-ih)/2,noise=alls=8:allf=t+u")
                } else {
                    cmd.add("noise=alls=8:allf=t+u")
                }
                cmd.add("-c:v")
                cmd.add(vCodec)
                if (isWebm) {
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add((crf ?: 32).toString())
                } else {
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add((crf ?: 23).toString())
                }
            }
            "medium" -> {
                cmd.add("-vf")
                if (enableAsymmetricScale) {
                    cmd.add("scale=iw*0.971:ih*0.967:flags=bicubic,pad=ceil(iw/2)*2:ceil(ih/2)*2:(ow-iw)/2:(oh-ih)/2,noise=alls=4:allf=t")
                } else {
                    cmd.add("noise=alls=4:allf=t")
                }
                cmd.add("-c:v")
                cmd.add(vCodec)
                if (isWebm) {
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add((crf ?: 30).toString())
                } else {
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add((crf ?: 22).toString())
                }
            }
            "low" -> {
                cmd.add("-vf")
                if (enableAsymmetricScale) {
                    cmd.add("scale=iw*0.982:ih*0.979:flags=bicubic,pad=ceil(iw/2)*2:ceil(ih/2)*2:(ow-iw)/2:(oh-ih)/2,noise=alls=2:allf=t")
                } else {
                    cmd.add("noise=alls=2:allf=t")
                }
                cmd.add("-c:v")
                cmd.add(vCodec)
                if (isWebm) {
                    cmd.add("-b:v")
                    cmd.add("0")
                    cmd.add("-crf")
                    cmd.add((crf ?: 28).toString())
                } else {
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add((crf ?: 20).toString())
                }
            }
            else -> {
                if (reencodeBitstream || (isWebm && !isInputWebm)) {
                    cmd.add("-c:v")
                    cmd.add(vCodec)
                    if (isWebm) {
                        cmd.add("-b:v")
                        cmd.add("0")
                        cmd.add("-crf")
                        cmd.add((crf ?: 30).toString())
                    } else {
                        cmd.add("-preset")
                        cmd.add("veryfast")
                        cmd.add("-crf")
                        cmd.add((crf ?: 24).toString())
                    }
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
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false
    ): Boolean = cleanVideoDetailed(
        inputPath, outputPath, noiseLevel, scrubAudio, crf, stripExif, reencodeBitstream
    ).isSuccess

    fun cleanVideoDetailed(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false
    ): VideoSanitizationResult {
        return try {
            val primaryArgs = buildArguments(
                inputPath = inputPath,
                outputPath = outputPath,
                noiseLevel = noiseLevel,
                scrubAudio = scrubAudio,
                crf = crf,
                stripExif = stripExif,
                reencodeBitstream = reencodeBitstream,
                enableAsymmetricScale = reencodeBitstream
            )
            Log.d(TAG, "Executing video sanitization with arguments: $primaryArgs")
            val session = FFmpegKit.executeWithArguments(primaryArgs.toTypedArray())
            if (ReturnCode.isSuccess(session.returnCode)) {
                Log.i(TAG, "Video transcode completed: $outputPath")
                VideoSanitizationResult(VideoSanitizationStatus.TRANSCODED)
            } else {
                Log.w(TAG, "Primary transcode failed with code ${session.returnCode}; trying metadata-only fallback.")
                val fallbackArgs = buildFallbackArguments(inputPath, outputPath, scrubAudio)
                val fallbackSession = FFmpegKit.executeWithArguments(fallbackArgs.toTypedArray())
                if (ReturnCode.isSuccess(fallbackSession.returnCode)) {
                    Log.w(TAG, "Metadata-only fallback completed; requested temporal noise processing was not applied.")
                    VideoSanitizationResult(VideoSanitizationStatus.METADATA_ONLY_FALLBACK)
                } else {
                    VideoSanitizationResult(VideoSanitizationStatus.FAILED, "Primary and fallback FFmpeg passes failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video processing failed: ${e.message}", e)
            VideoSanitizationResult(VideoSanitizationStatus.FAILED, e.message)
        }
    }

    suspend fun cleanVideoCancellable(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false
    ): Boolean = cleanVideoCancellableDetailed(
        inputPath, outputPath, noiseLevel, scrubAudio, crf, stripExif, reencodeBitstream
    ).isSuccess

    suspend fun cleanVideoCancellableDetailed(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false
    ): VideoSanitizationResult = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        try {
            val primaryArgs = buildArguments(
                inputPath = inputPath,
                outputPath = outputPath,
                noiseLevel = noiseLevel,
                scrubAudio = scrubAudio,
                crf = crf,
                stripExif = stripExif,
                reencodeBitstream = reencodeBitstream,
                enableAsymmetricScale = reencodeBitstream
            )
            Log.d(TAG, "Executing video sanitization async with arguments: $primaryArgs")
            val session = FFmpegKit.executeWithArgumentsAsync(primaryArgs.toTypedArray()) { completedSession ->
                if (cont.isActive) {
                    if (ReturnCode.isSuccess(completedSession.returnCode)) {
                        cont.resumeWith(Result.success(VideoSanitizationResult(VideoSanitizationStatus.TRANSCODED)))
                    } else {
                        Log.w(TAG, "Primary transcode failed with code ${completedSession.returnCode}; trying metadata-only fallback.")
                        val fallbackArgs = buildFallbackArguments(inputPath, outputPath, scrubAudio)
                        val fallbackSession = FFmpegKit.executeWithArgumentsAsync(fallbackArgs.toTypedArray()) { fallbackCompleted ->
                            if (cont.isActive) {
                                val result = if (ReturnCode.isSuccess(fallbackCompleted.returnCode)) {
                                    Log.w(TAG, "Metadata-only fallback completed; temporal noise filter was not applied.")
                                    VideoSanitizationResult(VideoSanitizationStatus.METADATA_ONLY_FALLBACK)
                                } else {
                                    VideoSanitizationResult(VideoSanitizationStatus.FAILED, "Primary and fallback FFmpeg passes failed")
                                }
                                cont.resumeWith(Result.success(result))
                            }
                        }
                        cont.invokeOnCancellation {
                            try { FFmpegKit.cancel(fallbackSession.sessionId) } catch (_: Throwable) {}
                        }
                    }
                }
            }
            cont.invokeOnCancellation {
                try { FFmpegKit.cancel(session.sessionId) } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video processing failed: ${e.message}", e)
            if (cont.isActive) {
                cont.resumeWith(Result.success(VideoSanitizationResult(VideoSanitizationStatus.FAILED, e.message)))
            }
        }
    }
}