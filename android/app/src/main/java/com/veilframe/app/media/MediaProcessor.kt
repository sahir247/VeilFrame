package com.veilframe.app.media

import android.graphics.Bitmap
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.veilframe.app.media.compression.ImageCompressionEngine
import java.io.File
import java.util.Locale

/**
 * Central MediaProcessor coordinating ImageProcessor and VideoProcessor.
 * Fully native Kotlin engine powered by FFmpegKit and AndroidX ExifInterface.
 */
object MediaProcessor {

    private const val TAG = "VeilFrame.MediaProcessor"

    val video = VideoProcessor
    val image = ImageProcessor
}

/**
 * Authoritative Video Processor for Android.
 * Never assumes ffmpeg exists in Android system PATH.
 * Uses Android FFmpeg execution layer (FFmpegKit) directly via argument array execution.
 * Shell string concatenation and shell escaping are completely eliminated.
 */
object VideoProcessor {

    private const val TAG = "VeilFrame.VideoProcessor"

    /**
     * Builds a chained FFmpeg atempo filter string for arbitrary speed multipliers (0.25x to 4.0x).
     * FFmpeg atempo filter is strictly bounded to [0.5, 2.0] per filter instance.
     */
    fun buildAtempoChain(speed: Float): String {
        if (Math.abs(speed - 1.0f) < 0.01f || speed <= 0f) return ""
        val factors = mutableListOf<Float>()
        var current = speed
        while (current > 2.0f) {
            factors.add(2.0f)
            current /= 2.0f
        }
        while (current < 0.5f) {
            factors.add(0.5f)
            current /= 0.5f
        }
        if (Math.abs(current - 1.0f) >= 0.01f) {
            factors.add(current)
        }
        return factors.joinToString(",") { String.format(Locale.US, "atempo=%.3f", it).trimEnd('0').trimEnd('.') }
    }

    /**
     * Direct Android video processing using the native FFmpegKit engine.
     * Does not call desktop Python subprocess, eliminating Android PATH FileNotFoundError.
     */
    fun process(
        srcFile: File,
        outFile: File,
        editState: VideoEditState,
        outputConfig: VideoOutputConfig,
        onStatistics: ((encodedMs: Long) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null
    ): CompressionResult {
        val trimStartSec = editState.trimStartSeconds
        val trimDurationSec = editState.trimmedDurationSeconds

        val targetSizeMb: Double? = outputConfig.targetMb?.toDouble() ?: when (outputConfig.targetPreset) {
            "WhatsApp (16 MB)" -> 16.0
            "Discord (25 MB)" -> 25.0
            "Discord Nitro (50 MB)" -> 50.0
            "Email Attachment (8 MB)" -> 8.0
            "Web Stream (10 MB)" -> 10.0
            else -> null
        }
        val resolutionStr = when (editState.scalePreset) {
            "1080p (Full HD)" -> "1080p"
            "720p (HD)" -> "720p"
            "480p (SD Compact)" -> "480p"
            "360p (Ultra Small)" -> "360p"
            else -> null
        }
        val aspectStr = when (editState.aspect) {
            "9:16 (Reel / Shorts / TikTok)", "9:16" -> "9:16"
            "1:1 (Square Feed)", "1:1" -> "1:1"
            "16:9 (Landscape YouTube)", "16:9" -> "16:9"
            "4:3 (Classic)", "4:3" -> "4:3"
            else -> null
        }
        val audioActionStr = when (editState.audioMode) {
            AudioMode.MUTE -> "mute"
            AudioMode.COMPRESS_AAC_128K -> "aac_128k"
            AudioMode.VOICE_64K -> "aac_64k"
            AudioMode.HIGH_FIDELITY_256K -> "aac_256k"
            AudioMode.KEEP -> "keep"
        }
        val codecParam = when {
            outputConfig.codec.contains("265", ignoreCase = true) || outputConfig.codec.contains("hevc", ignoreCase = true) -> "libx265"
            outputConfig.codec.contains("vp9", ignoreCase = true) -> "libvpx-vp9"
            outputConfig.codec.contains("av1", ignoreCase = true) -> "libsvtav1"
            outputConfig.codec.contains("copy", ignoreCase = true) -> "copy"
            else -> "libx264"
        }

        val isGifMode = outputConfig.outputMode == VideoOutputMode.GIF ||
                outputConfig.format.equals("gif", ignoreCase = true) ||
                outFile.name.endsWith(".gif", ignoreCase = true)

        // Authoritative Android FFmpegKit direct argument-array execution layer
        return executeNativeFFmpeg(
            srcFile = srcFile,
            outFile = outFile,
            trimStartSec = trimStartSec,
            trimDurationSec = trimDurationSec,
            resolutionStr = resolutionStr,
            aspectStr = aspectStr,
            speed = editState.speed,
            audioAction = audioActionStr,
            audioVolume = editState.audioVolume,
            audioChannels = editState.audioChannels,
            audioCodec = outputConfig.audioCodec,
            codec = codecParam,
            crf = outputConfig.crf,
            flipH = editState.flipH,
            flipV = editState.flipV,
            rotate = editState.rotationAngle,
            fps = editState.fps,
            targetSizeMb = targetSizeMb,
            isGifMode = isGifMode,
            onStatistics = onStatistics,
            onSessionId = onSessionId
        )
    }

    /**
     * Backward-compatible overload accepting single VideoEditState.
     */
    fun process(
        srcFile: File,
        outFile: File,
        state: VideoEditState
    ): CompressionResult {
        val outputConfig = VideoOutputConfig(
            format = "MP4",
            codec = "H.264",
            crf = 28,
            targetPreset = "Auto (Balanced CRF 28)",
            outputFileName = outFile.name
        )
        return process(srcFile, outFile, state, outputConfig)
    }

    private fun executeNativeFFmpeg(
        srcFile: File,
        outFile: File,
        trimStartSec: Double,
        trimDurationSec: Double,
        resolutionStr: String?,
        aspectStr: String?,
        speed: Float,
        audioAction: String,
        audioVolume: Float,
        audioChannels: String,
        audioCodec: String,
        codec: String,
        crf: Int,
        flipH: Boolean,
        flipV: Boolean,
        rotate: Int,
        fps: Int?,
        targetSizeMb: Double?,
        isGifMode: Boolean = false,
        onStatistics: ((encodedMs: Long) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null
    ): CompressionResult {
        return try {
            // Delete any stale output from a previous failed encode
            if (outFile.exists()) outFile.delete()

            val cmd = mutableListOf<String>()
            cmd.add("-y")

            if (trimStartSec > 0.05) {
                cmd.add("-ss")
                cmd.add(String.format(Locale.US, "%.3f", trimStartSec))
            }
            // Explicit duration trimming (-t) rather than -to
            if (trimDurationSec > 0.05) {
                cmd.add("-t")
                cmd.add(String.format(Locale.US, "%.3f", trimDurationSec))
            }

            cmd.add("-i")
            cmd.add(srcFile.absolutePath)

            // Strip metadata tags for privacy
            cmd.add("-map_metadata")
            cmd.add("-1")
            cmd.add("-map_chapters")
            cmd.add("-1")

            // Video filters
            val vfFilters = mutableListOf<String>()

            if (aspectStr != null) {
                when (aspectStr) {
                    "9:16" -> vfFilters.add("crop=trunc(min(iw\\,ih*9/16)/2)*2:trunc(min(ih\\,iw*16/9)/2)*2")
                    "1:1" -> vfFilters.add("crop=trunc(min(iw\\,ih)/2)*2:trunc(min(iw\\,ih)/2)*2")
                    "16:9" -> vfFilters.add("crop=trunc(min(iw\\,ih*16/9)/2)*2:trunc(min(ih\\,iw*9/16)/2)*2")
                    "4:3" -> vfFilters.add("crop=trunc(min(iw\\,ih*4/3)/2)*2:trunc(min(ih\\,iw*3/4)/2)*2")
                }
            }

            if (flipH) vfFilters.add("hflip")
            if (flipV) vfFilters.add("vflip")

            when (rotate) {
                90 -> vfFilters.add("transpose=1")
                180 -> vfFilters.add("hflip,vflip")
                270 -> vfFilters.add("transpose=2")
            }

            if (resolutionStr != null) {
                when (resolutionStr) {
                    "1080p" -> vfFilters.add("scale=-2:1080")
                    "720p" -> vfFilters.add("scale=-2:720")
                    "480p" -> vfFilters.add("scale=-2:480")
                    "360p" -> vfFilters.add("scale=-2:360")
                }
            }

            if (speed != 1.0f && speed > 0.1f) {
                val ptsMultiplier = 1.0 / speed
                vfFilters.add(String.format(Locale.US, "setpts=%.4f*PTS", ptsMultiplier))
            }

            // Audio configuration & filters
            val afFilters = mutableListOf<String>()
            val isMuted = isGifMode || audioAction == "mute" || audioCodec.equals("mute", ignoreCase = true)

            if (!isMuted) {
                if (Math.abs(audioVolume - 1.0f) > 0.01f && audioVolume >= 0f) {
                    afFilters.add(String.format(Locale.US, "volume=%.2f", audioVolume))
                }
                if (audioChannels.equals("mono", ignoreCase = true)) {
                    afFilters.add("pan=mono|c0=0.5*c0+0.5*c1")
                }
                if (speed != 1.0f && speed > 0.1f) {
                    val tempoChain = buildAtempoChain(speed)
                    if (tempoChain.isNotEmpty()) {
                        afFilters.add(tempoChain)
                    }
                }
            }

            if (isMuted) {
                cmd.add("-an")
            } else {
                var resolvedACodec = when {
                    audioCodec.contains("mp3", ignoreCase = true) -> "libmp3lame"
                    audioCodec.contains("opus", ignoreCase = true) -> "libopus"
                    audioCodec.contains("flac", ignoreCase = true) -> "flac"
                    audioCodec.contains("copy", ignoreCase = true) -> if (afFilters.isNotEmpty()) "aac" else "copy"
                    else -> "aac"
                }

                // Container compatibility validation
                if (outFile.name.endsWith(".webm", ignoreCase = true) && resolvedACodec != "libopus") {
                    resolvedACodec = "libopus"
                } else if ((outFile.name.endsWith(".mp4", ignoreCase = true) || outFile.name.endsWith(".mov", ignoreCase = true)) && resolvedACodec == "libopus") {
                    resolvedACodec = "aac"
                }

                if (afFilters.isNotEmpty() && resolvedACodec != "copy") {
                    cmd.add("-af")
                    cmd.add(afFilters.joinToString(","))
                }

                cmd.add("-c:a")
                cmd.add(resolvedACodec)
                if (resolvedACodec != "copy" && resolvedACodec != "flac") {
                    val bitrateStr = when (audioAction) {
                        "aac_64k" -> "64k"
                        "aac_256k" -> "256k"
                        else -> "128k"
                    }
                    cmd.add("-b:a")
                    cmd.add(bitrateStr)
                }
            }

            val isWebm = outFile.name.endsWith(".webm", ignoreCase = true)
            val isMp4 = outFile.name.endsWith(".mp4", ignoreCase = true) || outFile.name.endsWith(".mov", ignoreCase = true)

            // Video codec, GIF Animation mode, & CRF / Target Bitrate
            if (isGifMode) {
                val gifFps = if (fps != null && fps > 0) fps else 15
                val baseVf = listOf("fps=$gifFps") + vfFilters.filterNot { it.startsWith("setpts") }
                val baseChain = baseVf.joinToString(",")
                val gifVf = if (baseChain.isNotEmpty()) {
                    "$baseChain,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3"
                } else {
                    "split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3"
                }
                cmd.add("-vf")
                cmd.add(gifVf)
                cmd.add("-c:v")
                cmd.add("gif")
                cmd.add("-loop")
                cmd.add("0")
            } else {
                // Enforce copy codec incompatibility rule:
                // Stream copy cannot coexist with spatial or temporal video filter graphs
                val hasVideoTransforms = vfFilters.isNotEmpty() || (fps != null && fps > 0)
                val resolvedCodec = when {
                    isWebm -> "libvpx-vp9"
                    codec.contains("copy", ignoreCase = true) && hasVideoTransforms -> {
                        Log.w(TAG, "Stream copy ('copy') is incompatible with video filtering; auto-promoting to libx264 re-encode.")
                        "libx264"
                    }
                    codec.contains("copy", ignoreCase = true) -> "copy"
                    codec.contains("265", ignoreCase = true) || codec.contains("hevc", ignoreCase = true) -> "libx265"
                    codec.contains("vp9", ignoreCase = true) -> "libvpx-vp9"
                    else -> "libx264" // Normalized encoder for H.264 / default
                }

                if (vfFilters.isNotEmpty()) {
                    cmd.add("-vf")
                    cmd.add(vfFilters.joinToString(","))
                }

                if (fps != null && fps > 0) {
                    cmd.add("-r")
                    cmd.add(fps.toString())
                }

                cmd.add("-c:v")
                cmd.add(resolvedCodec)

                if (resolvedCodec != "copy") {
                    val isVp9 = resolvedCodec == "libvpx-vp9"
                    if (targetSizeMb != null && targetSizeMb > 0 && trimDurationSec > 0.2) {
                        // Total target budget in bits
                        val totalTargetBits = targetSizeMb * 8.0 * 1024.0 * 1024.0
                        // Reserve 6% for container/mux overhead
                        val containerReserveBits = totalTargetBits * 0.06
                        val audioBitrate = if (isMuted) 0.0 else when (audioAction) {
                            "aac_64k" -> 64.0
                            "aac_256k" -> 256.0
                            else -> 128.0
                        }
                        val audioReserveBits = audioBitrate * 1024.0 * trimDurationSec
                        val availableVideoBits = totalTargetBits - containerReserveBits - audioReserveBits

                        // Minimum viable video bitrate is 120 kbps to avoid severe encoder crash or black frames
                        val targetBitrateKbps = ((availableVideoBits / (trimDurationSec * 1024.0)).toInt()).coerceAtLeast(120)

                        cmd.add("-b:v")
                        cmd.add("${targetBitrateKbps}k")
                        if (isVp9) {
                            cmd.add("-deadline")
                            cmd.add("realtime")
                            cmd.add("-cpu-used")
                            cmd.add("4")
                            cmd.add("-row-mt")
                            cmd.add("1")
                        } else {
                            cmd.add("-maxrate")
                            cmd.add("${(targetBitrateKbps * 1.35).toInt()}k")
                            cmd.add("-bufsize")
                            cmd.add("${targetBitrateKbps * 2}k")
                        }
                    } else {
                        cmd.add("-crf")
                        cmd.add(crf.toString())
                        if (isVp9) {
                            cmd.add("-b:v")
                            cmd.add("0")
                            cmd.add("-deadline")
                            cmd.add("realtime")
                            cmd.add("-cpu-used")
                            cmd.add("4")
                            cmd.add("-row-mt")
                            cmd.add("1")
                        } else {
                            cmd.add("-preset")
                            cmd.add("ultrafast")
                        }
                    }
                }
            }

            // Container-specific flags
            if (outFile.name.endsWith(".mp4", ignoreCase = true) || outFile.name.endsWith(".mov", ignoreCase = true)) {
                cmd.add("-movflags")
                cmd.add("+faststart")
            }

            cmd.add(outFile.absolutePath)

            val cmdArray = cmd.toTypedArray()
            Log.d(TAG, "Executing native FFmpegKit with ${cmdArray.size} arguments: ${cmdArray.joinToString(" ")}")

            var activeSessionId = -1L
            val resultHolder = arrayOfNulls<com.arthenica.ffmpegkit.FFmpegSession>(1)
            val latch = java.util.concurrent.CountDownLatch(1)

            // Direct argument array execution — zero shell string concatenation or shell escaping
            val asyncSession = FFmpegKit.executeWithArgumentsAsync(
                cmdArray,
                { session ->
                    resultHolder[0] = session
                    latch.countDown()
                },
                null,
                { stats: Statistics ->
                    val encMs = stats.time.toLong().coerceAtLeast(0L)
                    onStatistics?.invoke(encMs)
                }
            )
            activeSessionId = asyncSession?.sessionId ?: -1L
            onSessionId?.invoke(activeSessionId)
            latch.await()

            val session = resultHolder[0]
            val returnCode = session?.returnCode

            if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 0L) {
                val outSize = outFile.length()
                val origSize = srcFile.length()
                val savings = if (origSize > 0) ((origSize - outSize).toDouble() / origSize.toDouble() * 100.0) else 0.0

                // Inspect actual output size against requested target
                val warning: String? = if (targetSizeMb != null && targetSizeMb > 0) {
                    val targetBytes = (targetSizeMb * 1024.0 * 1024.0).toLong()
                    if (outSize > targetBytes * 1.05) {
                        "Target ceiling not strictly achieved: Output is ${formatBytes(outSize)}, requested was <= ${formatBytes(targetBytes)}"
                    } else null
                } else null

                CompressionResult(
                    success = true,
                    outputPath = outFile.absolutePath,
                    sizeBytes = outSize,
                    savingsPercent = savings,
                    duration = trimDurationSec,
                    error = warning
                )
            } else {
                val allLogs = session?.allLogsAsString ?: "No session logs available"
                val failTrace = session?.failStackTrace
                val tailLogs = allLogs.lines().takeLast(15).joinToString("\n")

                val diagnostic = StringBuilder()
                    .append("FFmpeg execution failed (code $returnCode)\n")
                    .append("Details: Codec=$codec, Container=${outFile.extension.uppercase(Locale.US)}, Duration=${String.format(Locale.US, "%.2f", trimDurationSec)}s")
                if (targetSizeMb != null) {
                    diagnostic.append(", Target=${String.format(Locale.US, "%.1f", targetSizeMb)}MB")
                }
                diagnostic.append("\n\nFFmpeg Log:\n").append(tailLogs)
                if (!failTrace.isNullOrBlank()) {
                    diagnostic.append("\nStacktrace: ").append(failTrace)
                }

                val diagStr = diagnostic.toString()
                Log.e(TAG, diagStr)
                CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = 0L,
                    error = diagStr
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native FFmpeg execution exception: ${e.message}", e)
            CompressionResult(
                success = false,
                outputPath = outFile.absolutePath,
                sizeBytes = 0L,
                error = e.message
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024.0 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }
}

/**
 * Authoritative Image Processor for Android.
 * Delegates cleanly to ImageCompressionEngine.
 */
object ImageProcessor {

    fun process(
        srcFile: File,
        outFile: File,
        editState: ImageEditState,
        outputConfig: ImageOutputConfig,
        previewBitmap: Bitmap?
    ): CompressionResult {
        val bmp = previewBitmap ?: return CompressionResult(
            success = false,
            outputPath = outFile.absolutePath,
            sizeBytes = 0L,
            error = "No valid preview bitmap to encode"
        )
        return ImageCompressionEngine.compress(
            srcFile = srcFile,
            outFile = outFile,
            editState = editState,
            outputConfig = outputConfig,
            processedBitmap = bmp
        )
    }

    fun process(
        srcFile: File,
        outFile: File,
        state: ImageEditState,
        previewBitmap: Bitmap?
    ): CompressionResult {
        val outputConfig = ImageOutputConfig(
            format = "JPG",
            quality = 85,
            outputFileName = outFile.name
        )
        return process(srcFile, outFile, state, outputConfig, previewBitmap)
    }
}
