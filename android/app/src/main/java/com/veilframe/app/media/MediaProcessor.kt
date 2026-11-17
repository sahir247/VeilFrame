package com.veilframe.app.media

import android.graphics.Bitmap
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.motion.VideoStabilizerEngine
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
        onSessionId: ((Long) -> Unit)? = null,
        onProgressPhase: ((phaseLabel: String, encodedMs: Long, percent: Int) -> Unit)? = null
    ): CompressionResult {
        val trimStartSec = editState.trimStartSeconds
        val trimDurationSec = editState.trimmedDurationSeconds

        val targetSizeMb: Double? = outputConfig.targetMb?.toDouble() ?: when (outputConfig.targetPreset) {
            "WhatsApp (16 MB)" -> 16.0
            "Instagram Story" -> 50.0
            "TikTok (72 MB)" -> 72.0
            "YouTube Shorts" -> 100.0
            "Twitter / X" -> 120.0
            "Telegram HD" -> 200.0
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
        val aspectSpec = AspectSpec.fromString(editState.aspect)
        val aspectStr = when (aspectSpec) {
            is AspectSpec.Ratio -> "${aspectSpec.widthRatio.toInt()}:${aspectSpec.heightRatio.toInt()}"
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
            outputConfig.codec.contains("copy", ignoreCase = true) -> "copy"
            else -> "libx264"
        }

        val isGifMode = outputConfig.outputMode == VideoOutputMode.GIF ||
                outputConfig.format.equals("gif", ignoreCase = true) ||
                outFile.name.endsWith(".gif", ignoreCase = true)

        val isMuted = isGifMode || audioActionStr == "mute" || outputConfig.audioCodec.equals("mute", ignoreCase = true)
        val shouldStabilizeInPass = editState.isStabilized && !editState.isStabilizationBaked

        // Route WhatsApp Status destination directly to dedicated WhatsappStatusMediaPipeline.
        // Distinct from generic "WhatsApp (16 MB)" chat attachment preset.
        val isStatusPreset = outputConfig.targetPreset.equals("WhatsApp Status", ignoreCase = true) ||
                (outputConfig.targetPreset.contains("Status", ignoreCase = true) && !outputConfig.targetPreset.contains("16", ignoreCase = true))
        if (isStatusPreset) {
            val statusResolution = outputConfig.whatsappStatusResolution
            val cropSpec = if (editState.customCropPercent > 0) {
                CropSpec.fromCustomCropPercent(editState.customCropPercent)
            } else null

            val needsPreFilterPass = shouldStabilizeInPass ||
                !editState.dynamicCropFilter.isNullOrBlank() ||
                !editState.piiRedactionFilter.isNullOrBlank()

            if (!needsPreFilterPass) {
                return com.veilframe.app.media.whatsapp.WhatsappStatusMediaPipeline.processVideo(
                    srcFile = srcFile,
                    outFile = outFile,
                    resolution = statusResolution,
                    aspect = editState.aspect,
                    cropSpec = cropSpec,
                    trimStartSec = trimStartSec,
                    trimDurationSec = trimDurationSec,
                    flipH = editState.flipH,
                    flipV = editState.flipV,
                    rotate = editState.rotationAngle,
                    speed = editState.speed,
                    colorProfile = editState.colorProfile,
                    isMuted = isMuted,
                    onStatistics = onStatistics,
                    onSessionId = onSessionId
                )
            }

            // Apply AI PII redaction / 2-pass EIS / dynamic reframe in a clean high-quality pre-pass
            // before handing off to WhatsappStatusMediaPipeline so HDR/bitrate/29.97fps invariants stay intact.
            val tmpParent = outFile.parentFile ?: srcFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            val prePassFile = File.createTempFile("vf_status_pre_", ".mp4", tmpParent)
            return try {
                val preRes = executeNativeFFmpeg(
                    srcFile = srcFile,
                    outFile = prePassFile,
                    trimStartSec = trimStartSec,
                    trimDurationSec = trimDurationSec,
                    resolutionStr = null,
                    aspectStr = null,
                    dynamicCropFilter = editState.dynamicCropFilter,
                    isStabilized = shouldStabilizeInPass,
                    stabilizationMarginPercent = editState.stabilizationMarginPercent,
                    eisConfig = editState.eisConfig,
                    piiRedactionFilter = editState.piiRedactionFilter,
                    speed = 1.0f,
                    colorProfile = "Original",
                    audioAction = if (isMuted) "mute" else "keep",
                    audioVolume = 1.0f,
                    audioChannels = "auto",
                    audioCodec = if (isMuted) "mute" else "copy",
                    codec = "libx264",
                    crf = 16,
                    compressionPreset = "fast",
                    flipH = false,
                    flipV = false,
                    rotate = 0,
                    customCropPercent = 0,
                    fps = null,
                    targetSizeMb = null,
                    pixelFormat = "yuv420p",
                    isGifMode = false,
                    bitrateReductionFactor = 1.0,
                    targetPreset = null,
                    onStatistics = null,
                    onSessionId = onSessionId,
                    onProgressPhase = onProgressPhase
                )
                if (!preRes.success || !prePassFile.exists() || prePassFile.length() == 0L) {
                    preRes.copy(outputPath = outFile.absolutePath)
                } else {
                    com.veilframe.app.media.whatsapp.WhatsappStatusMediaPipeline.processVideo(
                        srcFile = prePassFile,
                        outFile = outFile,
                        resolution = statusResolution,
                        aspect = editState.aspect,
                        cropSpec = cropSpec,
                        trimStartSec = 0.0,
                        trimDurationSec = 0.0,
                        flipH = editState.flipH,
                        flipV = editState.flipV,
                        rotate = editState.rotationAngle,
                        speed = editState.speed,
                        colorProfile = editState.colorProfile,
                        isMuted = isMuted,
                        onStatistics = onStatistics,
                        onSessionId = onSessionId
                    )
                }
            } finally {
                if (prePassFile.exists()) prePassFile.delete()
            }
        }

        if (targetSizeMb != null && targetSizeMb > 0) {
            val targetBytes = (targetSizeMb * 1024.0 * 1024.0).toLong()
            var sharedTrfFile: File? = null
            var resolvedSharedEis: VideoEisConfig? = null
            try {
                if (shouldStabilizeInPass) {
                    val cfg = resolveEisConfig(editState)
                    resolvedSharedEis = cfg
                    val trfParent = outFile.parentFile ?: srcFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
                    val pass1 = runVidstabPass1(
                        srcFile = srcFile,
                        outFile = outFile,
                        trfParent = trfParent,
                        trimStartSec = trimStartSec,
                        trimDurationSec = trimDurationSec,
                        eisConfig = cfg,
                        onStatistics = onStatistics,
                        onSessionId = onSessionId,
                        onProgressPhase = onProgressPhase
                    )
                    if (pass1.errorResult != null) {
                        return pass1.errorResult
                    }
                    sharedTrfFile = pass1.trfFile
                }

                var attempt = 0
                var reduction = 1.0
                var lastResult: CompressionResult? = null
                while (attempt < 3) {
                    val res = executeNativeFFmpeg(
                        srcFile = srcFile,
                        outFile = outFile,
                        trimStartSec = trimStartSec,
                        trimDurationSec = trimDurationSec,
                        resolutionStr = resolutionStr,
                        aspectStr = aspectStr,
                        dynamicCropFilter = editState.dynamicCropFilter,
                        isStabilized = shouldStabilizeInPass,
                        stabilizationMarginPercent = editState.stabilizationMarginPercent,
                        eisConfig = resolvedSharedEis ?: editState.eisConfig,
                        piiRedactionFilter = editState.piiRedactionFilter,
                        speed = editState.speed,
                        colorProfile = editState.colorProfile,
                        audioAction = audioActionStr,
                        audioVolume = editState.audioVolume,
                        audioChannels = editState.audioChannels,
                        audioCodec = outputConfig.audioCodec,
                        codec = codecParam,
                        crf = outputConfig.crf,
                        compressionPreset = outputConfig.compressionPreset,
                        flipH = editState.flipH,
                        flipV = editState.flipV,
                        rotate = editState.rotationAngle,
                        customCropPercent = editState.customCropPercent,
                        fps = editState.fps,
                        targetSizeMb = targetSizeMb,
                        pixelFormat = outputConfig.pixelFormat,
                        isGifMode = isGifMode,
                        bitrateReductionFactor = reduction,
                        targetPreset = outputConfig.targetPreset,
                        precomputedTrfFile = sharedTrfFile,
                        onStatistics = onStatistics,
                        onSessionId = onSessionId,
                        onProgressPhase = onProgressPhase
                    )
                    lastResult = res
                    if (!res.success) {
                        return res
                    }
                    if (res.sizeBytes <= targetBytes) {
                        return res
                    }
                    // Overshot target ceiling: reduce bitrate by 15% and retry (max 2 retries)
                    attempt++
                    reduction *= 0.85
                    Log.w(TAG, "Output size ${formatBytes(res.sizeBytes)} exceeded target ceiling ${formatBytes(targetBytes)}. Retry attempt $attempt/2 with 15% bitrate reduction (factor $reduction)...")
                }
                val finalSize = lastResult?.sizeBytes ?: outFile.length()
                val errMsg = "SIZE_LIMIT_UNACHIEVABLE: Encoded output ($finalSize bytes) exceeds target ceiling ($targetBytes bytes) after 2 retries."
                Log.e(TAG, errMsg)
                return CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = finalSize,
                    savingsPercent = lastResult?.savingsPercent ?: 0.0,
                    duration = trimDurationSec,
                    error = errMsg
                )
            } finally {
                if (sharedTrfFile != null && sharedTrfFile.exists()) {
                    sharedTrfFile.delete()
                }
            }
        }

        // Authoritative Android FFmpegKit direct argument-array execution layer
        return executeNativeFFmpeg(
            srcFile = srcFile,
            outFile = outFile,
            trimStartSec = trimStartSec,
            trimDurationSec = trimDurationSec,
            resolutionStr = resolutionStr,
            aspectStr = aspectStr,
            dynamicCropFilter = editState.dynamicCropFilter,
            isStabilized = shouldStabilizeInPass,
            stabilizationMarginPercent = editState.stabilizationMarginPercent,
            eisConfig = editState.eisConfig,
            piiRedactionFilter = editState.piiRedactionFilter,
            speed = editState.speed,
            colorProfile = editState.colorProfile,
            audioAction = audioActionStr,
            audioVolume = editState.audioVolume,
            audioChannels = editState.audioChannels,
            audioCodec = outputConfig.audioCodec,
            codec = codecParam,
            crf = outputConfig.crf,
            compressionPreset = outputConfig.compressionPreset,
            flipH = editState.flipH,
            flipV = editState.flipV,
            rotate = editState.rotationAngle,
            customCropPercent = editState.customCropPercent,
            fps = editState.fps,
            targetSizeMb = targetSizeMb,
            pixelFormat = outputConfig.pixelFormat,
            isGifMode = isGifMode,
            bitrateReductionFactor = 1.0,
            targetPreset = outputConfig.targetPreset,
            onStatistics = onStatistics,
            onSessionId = onSessionId,
            onProgressPhase = onProgressPhase
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

    internal fun shouldSkipResolutionAfter540pEis(
        resolutionStr: String?,
        isStabilized: Boolean,
        eisConfig: VideoEisConfig
    ): Boolean {
        return isStabilized &&
            eisConfig.downscaleTo540p &&
            (resolutionStr == "1080p" || resolutionStr == "720p")
    }

    private fun resolveEisConfig(
        eisConfig: VideoEisConfig,
        stabilizationMarginPercent: Int
    ): VideoEisConfig {
        return if (eisConfig.zoomPercent == 0 && stabilizationMarginPercent != 5 && eisConfig.presetProfile == EisPresetProfile.HANDHELD_WALK) {
            eisConfig.copy(zoomPercent = stabilizationMarginPercent.coerceIn(0, 30)).clamped()
        } else {
            eisConfig.clamped()
        }
    }

    private fun resolveEisConfig(editState: VideoEditState): VideoEisConfig {
        return resolveEisConfig(editState.eisConfig, editState.stabilizationMarginPercent)
    }

    private data class VidstabPass1Outcome(
        val trfFile: File? = null,
        val errorResult: CompressionResult? = null
    )

    private fun runVidstabPass1(
        srcFile: File,
        outFile: File,
        trfParent: File,
        trimStartSec: Double,
        trimDurationSec: Double,
        eisConfig: VideoEisConfig,
        onStatistics: ((encodedMs: Long) -> Unit)?,
        onSessionId: ((Long) -> Unit)?,
        onProgressPhase: ((phaseLabel: String, encodedMs: Long, percent: Int) -> Unit)?
    ): VidstabPass1Outcome {
        val trfFile = File.createTempFile("vf_vidstab_", ".trf", trfParent)
        val effectiveDurMs = (trimDurationSec * 1000.0).coerceAtLeast(1000.0)
        val pass1Cmd = mutableListOf<String>().apply {
            add("-y")
            add("-nostdin")
            add("-filter_threads"); add("1")
            add("-filter_complex_threads"); add("1")
            if (trimStartSec > 0.05) {
                add("-ss")
                add(String.format(Locale.US, "%.3f", trimStartSec))
            }
            if (trimDurationSec > 0.05) {
                add("-t")
                add(String.format(Locale.US, "%.3f", trimDurationSec))
            }
            add("-threads"); add("2")
            add("-i"); add(srcFile.absolutePath)
            add("-map"); add("0:v:0")
            add("-an"); add("-sn"); add("-dn")
            add("-vf"); add(VideoStabilizerEngine.buildVidstabDetectFilter(trfFile.absolutePath, eisConfig))
            add("-f"); add("null")
            add("-")
        }
        Log.d(TAG, "Executing EIS Pass 1 (vidstabdetect): -vf ${VideoStabilizerEngine.buildVidstabDetectFilter("[TRF_PATH]", eisConfig)}")
        val pass1Session = runFFmpegSession(
            cmdArray = pass1Cmd.toTypedArray(),
            onStatistics = { encMs ->
                val rawRatio = (encMs / effectiveDurMs).coerceIn(0.0, 1.0)
                val weightedPct = (rawRatio * 45.0).toInt().coerceIn(0, 45)
                onProgressPhase?.invoke("Step 1/2: Analyzing motion", encMs, weightedPct)
                onStatistics?.invoke((encMs * 0.45).toLong())
            },
            onSessionId = onSessionId
        )
        val p1Rc = pass1Session?.returnCode
        if (!ReturnCode.isSuccess(p1Rc) || !trfFile.exists() || trfFile.length() == 0L) {
            if (trfFile.exists()) trfFile.delete()
            val tailLogs = (pass1Session?.allLogsAsString ?: "No Pass 1 logs").lines().takeLast(12).joinToString("\n")
            val errMsg = if (ReturnCode.isCancel(p1Rc)) {
                "Video stabilization cancelled during motion analysis"
            } else {
                "EIS Pass 1 (vidstabdetect) failed (code $p1Rc)\n\nFFmpeg Log:\n$tailLogs"
            }
            Log.e(TAG, errMsg)
            return VidstabPass1Outcome(
                errorResult = CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = 0L,
                    error = errMsg
                )
            )
        }
        return VidstabPass1Outcome(trfFile = trfFile)
    }

    /**
     * Dedicated standalone two-pass Electronic Image Stabilization (EIS) pipeline.
     * Executes Pass 1 (vidstabdetect motion estimation) followed immediately by Pass 2
     * (vidstabtransform frame warping & edge sharpening) without monolithic filter graph concatenation.
     * Preserves original audio losslessly (-c:a copy) and produces a clean stabilized video file.
     */
    fun stabilizeVideoStandalone(
        srcFile: File,
        outFile: File,
        eisConfig: VideoEisConfig,
        onSessionId: ((Long) -> Unit)? = null,
        onProgressPhase: ((phaseLabel: String, encodedMs: Long, percent: Int) -> Unit)? = null
    ): CompressionResult {
        val safeConfig = eisConfig.clamped()
        val trfParent = outFile.parentFile ?: srcFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")

        // Pass 1: Motion vector extraction
        val pass1Outcome = runVidstabPass1(
            srcFile = srcFile,
            outFile = outFile,
            trfParent = trfParent,
            trimStartSec = 0.0,
            trimDurationSec = 0.0,
            eisConfig = safeConfig,
            onStatistics = null,
            onSessionId = onSessionId,
            onProgressPhase = onProgressPhase
        )
        if (pass1Outcome.errorResult != null) {
            return pass1Outcome.errorResult
        }
        val trfFile = pass1Outcome.trfFile ?: return CompressionResult(
            success = false,
            outputPath = outFile.absolutePath,
            sizeBytes = 0L,
            error = "MOTION_ANALYSIS_NO_RESULTS: Motion vector calculation failed."
        )

        try {
            // Pass 2: Frame warping & stabilization
            val transformVf = VideoStabilizerEngine.buildVidstabTransformFilter(trfFile.absolutePath, safeConfig)
            val cmd = mutableListOf<String>().apply {
                add("-y")
                add("-nostdin")
                add("-threads"); add("2")
                add("-i"); add(srcFile.absolutePath)
                add("-map_metadata"); add("-1")
                add("-map_chapters"); add("-1")
                add("-vf"); add(transformVf)
                add("-c:v"); add("libx264")
                add("-preset"); add("fast")
                add("-crf"); add("20")
                add("-pix_fmt"); add("yuv420p")
                add("-c:a"); add("copy")
                add("-movflags"); add("+faststart")
                add(outFile.absolutePath)
            }

            Log.d(TAG, "Executing Standalone EIS Pass 2 (vidstabtransform): -vf $transformVf")
            val durMs = try {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(srcFile.absolutePath)
                    (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L).coerceAtLeast(1L)
                } finally {
                    mmr.release()
                }
            } catch (_: Throwable) { 1000L }

            val pass2Session = runFFmpegSession(
                cmdArray = cmd.toTypedArray(),
                onStatistics = { encMs ->
                    val ratio = (encMs.toDouble() / durMs.toDouble()).coerceIn(0.0, 1.0)
                    val pct = (45 + (ratio * 55.0)).toInt().coerceIn(45, 100)
                    onProgressPhase?.invoke("Step 2/2: Stabilizing frames", encMs, pct)
                },
                onSessionId = onSessionId
            )

            val p2Rc = pass2Session?.returnCode
            if (!ReturnCode.isSuccess(p2Rc) || !outFile.exists() || outFile.length() == 0L) {
                val tailLogs = (pass2Session?.allLogsAsString ?: "No Pass 2 logs").lines().takeLast(16).joinToString("\n")
                val errMsg = if (ReturnCode.isCancel(p2Rc)) {
                    "Video stabilization cancelled by user"
                } else {
                    "STABILIZATION_FAILED: EIS Pass 2 failed (code $p2Rc)\n\nFFmpeg Log:\n$tailLogs"
                }
                Log.e(TAG, errMsg)
                return CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = 0L,
                    error = errMsg
                )
            }

            val originalSize = srcFile.length()
            val finalSize = outFile.length()
            val savings = if (originalSize > 0) ((originalSize - finalSize).toDouble() / originalSize.toDouble()) * 100.0 else 0.0

            return CompressionResult(
                success = true,
                outputPath = outFile.absolutePath,
                sizeBytes = finalSize,
                savingsPercent = savings,
                error = null
            )
        } finally {
            if (trfFile.exists()) {
                trfFile.delete()
            }
        }
    }

    /**
     * Dedicated standalone Video PII Redaction execution pipeline.
     * Bakes the temporal redaction filter graph directly into the video file,
     * preserving high visual fidelity (crf 18, fast preset) and original audio losslessly (-c:a copy).
     */
    fun redactVideoStandalone(
        srcFile: File,
        outFile: File,
        filterChain: String,
        onSessionId: ((Long) -> Unit)? = null,
        onProgress: ((phaseLabel: String, encodedMs: Long, percent: Int) -> Unit)? = null
    ): CompressionResult {
        val durMs = try {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(srcFile.absolutePath)
                (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L).coerceAtLeast(1L)
            } finally {
                mmr.release()
            }
        } catch (_: Throwable) { 1000L }

        val isComplex = filterChain.contains("[") && filterChain.contains("]")
        val cmd = mutableListOf<String>().apply {
            add("-y")
            add("-nostdin")
            add("-threads"); add("2")
            add("-i"); add(srcFile.absolutePath)
            add("-map_metadata"); add("-1")
            add("-map_chapters"); add("-1")
            if (isComplex) {
                add("-filter_complex"); add(filterChain)
                add("-map"); add("[v_out]")
                add("-map"); add("0:a?")
            } else {
                add("-vf"); add(filterChain)
            }
            add("-c:v"); add("libx264")
            add("-preset"); add("fast")
            add("-crf"); add("18")
            add("-pix_fmt"); add("yuv420p")
            add("-c:a"); add("copy")
            add("-movflags"); add("+faststart")
            add(outFile.absolutePath)
        }

        Log.d(TAG, "Executing Standalone Video PII Redaction: -vf $filterChain")
        val session = runFFmpegSession(
            cmdArray = cmd.toTypedArray(),
            onStatistics = { encMs ->
                val ratio = (encMs.toDouble() / durMs.toDouble()).coerceIn(0.0, 1.0)
                val pct = (ratio * 100.0).toInt().coerceIn(0, 100)
                onProgress?.invoke("Redacting video frames", encMs, pct)
            },
            onSessionId = onSessionId
        )

        val rc = session?.returnCode
        if (!ReturnCode.isSuccess(rc) || !outFile.exists() || outFile.length() == 0L) {
            val tailLogs = (session?.allLogsAsString ?: "No FFmpeg logs").lines().takeLast(16).joinToString("\n")
            val errMsg = if (ReturnCode.isCancel(rc)) {
                "Video PII redaction cancelled by user"
            } else {
                "REDACTION_FAILED: Video PII redaction failed (code $rc)\n\nFFmpeg Log:\n$tailLogs"
            }
            Log.e(TAG, errMsg)
            return CompressionResult(
                success = false,
                outputPath = outFile.absolutePath,
                sizeBytes = 0L,
                error = errMsg
            )
        }

        val originalSize = srcFile.length()
        val finalSize = outFile.length()
        val savings = if (originalSize > 0) ((originalSize - finalSize).toDouble() / originalSize.toDouble()) * 100.0 else 0.0

        return CompressionResult(
            success = true,
            outputPath = outFile.absolutePath,
            sizeBytes = finalSize,
            savingsPercent = savings,
            error = null
        )
    }

    private fun executeNativeFFmpeg(
        srcFile: File,
        outFile: File,
        trimStartSec: Double,
        trimDurationSec: Double,
        resolutionStr: String?,
        aspectStr: String?,
        dynamicCropFilter: String? = null,
        isStabilized: Boolean = false,
        stabilizationMarginPercent: Int = 5,
        eisConfig: VideoEisConfig = VideoEisConfig(),
        piiRedactionFilter: String? = null,
        speed: Float,
        colorProfile: String = "Original",
        audioAction: String,
        audioVolume: Float,
        audioChannels: String,
        audioCodec: String,
        codec: String,
        crf: Int,
        compressionPreset: String = "slow",
        flipH: Boolean,
        flipV: Boolean,
        rotate: Int,
        customCropPercent: Int = 0,
        fps: Int?,
        targetSizeMb: Double?,
        pixelFormat: String = "yuv420p",
        isGifMode: Boolean = false,
        bitrateReductionFactor: Double = 1.0,
        targetPreset: String? = null,
        precomputedTrfFile: File? = null,
        onStatistics: ((encodedMs: Long) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null,
        onProgressPhase: ((phaseLabel: String, encodedMs: Long, percent: Int) -> Unit)? = null
    ): CompressionResult {
        var trfFile: File? = precomputedTrfFile
        val ownsTrfFile = precomputedTrfFile == null
        return try {
            // Delete any stale output from a previous failed encode
            if (outFile.exists()) outFile.delete()

            val effectiveDurMs = (trimDurationSec * 1000.0).coerceAtLeast(1000.0)
            val resolvedEisConfig = if (isStabilized) {
                resolveEisConfig(eisConfig, stabilizationMarginPercent)
            } else {
                eisConfig
            }

            if (isStabilized && trfFile == null) {
                val trfParent = outFile.parentFile ?: srcFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
                val pass1 = runVidstabPass1(
                    srcFile = srcFile,
                    outFile = outFile,
                    trfParent = trfParent,
                    trimStartSec = trimStartSec,
                    trimDurationSec = trimDurationSec,
                    eisConfig = resolvedEisConfig,
                    onStatistics = onStatistics,
                    onSessionId = onSessionId,
                    onProgressPhase = onProgressPhase
                )
                if (pass1.errorResult != null) {
                    return pass1.errorResult
                }
                trfFile = pass1.trfFile
            }

            val cmd = mutableListOf<String>()
            cmd.add("-y")
            cmd.add("-nostdin")

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

            // Apply AI PII redaction masks first so frame-space coordinates align prior to warping/scaling/cropping
            if (!piiRedactionFilter.isNullOrBlank()) {
                vfFilters.add(piiRedactionFilter)
            }

            if (isStabilized && trfFile != null) {
                vfFilters.add(VideoStabilizerEngine.buildVidstabTransformFilter(trfFile!!.absolutePath, resolvedEisConfig))
            }

            if (!dynamicCropFilter.isNullOrBlank()) {
                vfFilters.add(dynamicCropFilter)
            } else if (aspectStr != null) {
                when (aspectStr) {
                    "9:16" -> vfFilters.add("crop=trunc(min(iw\\,ih*9/16)/2)*2:trunc(min(ih\\,iw*16/9)/2)*2")
                    "4:5" -> vfFilters.add("crop=trunc(min(iw\\,ih*4/5)/2)*2:trunc(min(ih\\,iw*5/4)/2)*2")
                    "1:1" -> vfFilters.add("crop=trunc(min(iw\\,ih)/2)*2:trunc(min(iw\\,ih)/2)*2")
                    "16:9" -> vfFilters.add("crop=trunc(min(iw\\,ih*16/9)/2)*2:trunc(min(ih\\,iw*9/16)/2)*2")
                    "4:3" -> vfFilters.add("crop=trunc(min(iw\\,ih*4/3)/2)*2:trunc(min(ih\\,iw*3/4)/2)*2")
                    "3:4" -> vfFilters.add("crop=trunc(min(iw\\,ih*3/4)/2)*2:trunc(min(ih\\,iw*4/3)/2)*2")
                }
            }

            if (customCropPercent > 0) {
                val factor = (1.0 - (customCropPercent / 100.0).coerceIn(0.0, 0.8))
                vfFilters.add(String.format(Locale.US, "crop=trunc(iw*%.3f/2)*2:trunc(ih*%.3f/2)*2", factor, factor))
            }

            if (flipH) vfFilters.add("hflip")
            if (flipV) vfFilters.add("vflip")

            when (rotate) {
                90 -> vfFilters.add("transpose=1")
                180 -> vfFilters.add("hflip,vflip")
                270 -> vfFilters.add("transpose=2")
            }

            val skipUpscaleOver540p = shouldSkipResolutionAfter540pEis(
                resolutionStr = resolutionStr,
                isStabilized = isStabilized,
                eisConfig = resolvedEisConfig
            )
            if (resolutionStr != null && !skipUpscaleOver540p) {
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

            val colorFilter = com.veilframe.app.media.preview.VideoColorFilterHelper.getFFmpegFilter(colorProfile)
            if (!colorFilter.isNullOrEmpty()) {
                vfFilters.add(colorFilter)
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
                    val speedPresetNormalized = compressionPreset.lowercase(Locale.US)

                    if (targetSizeMb != null && targetSizeMb > 0 && trimDurationSec > 0.2) {
                        // Total target budget in bits
                        val totalTargetBits = targetSizeMb * 8.0 * 1024.0 * 1024.0
                        // Reserve 8% for container/mux overhead
                        val containerReserveBits = totalTargetBits * 0.08
                        val audioBitrate = if (isMuted) 0.0 else when (audioAction) {
                            "aac_64k" -> 64.0
                            "aac_256k" -> 256.0
                            else -> 128.0
                        }
                        val audioReserveBits = audioBitrate * 1024.0 * trimDurationSec
                        val availableVideoBits = totalTargetBits - containerReserveBits - audioReserveBits

                        // Minimum viable video bitrate is 120 kbps to avoid severe encoder crash or black frames
                        val baseBitrate = (availableVideoBits / (trimDurationSec * 1024.0)) * bitrateReductionFactor
                        val targetBitrateKbps = (baseBitrate.toInt()).coerceAtLeast(120)

                        cmd.add("-b:v")
                        cmd.add("${targetBitrateKbps}k")
                        if (isVp9) {
                            when (speedPresetNormalized) {
                                "slow" -> {
                                    cmd.add("-deadline")
                                    cmd.add("good")
                                    cmd.add("-cpu-used")
                                    cmd.add("1")
                                }
                                "fast" -> {
                                    cmd.add("-deadline")
                                    cmd.add("realtime")
                                    cmd.add("-cpu-used")
                                    cmd.add("4")
                                }
                                else -> {
                                    cmd.add("-deadline")
                                    cmd.add("good")
                                    cmd.add("-cpu-used")
                                    cmd.add("3")
                                }
                            }
                            cmd.add("-row-mt")
                            cmd.add("1")
                        } else {
                            cmd.add("-preset")
                            val presetArg = when (speedPresetNormalized) {
                                "slow" -> "slow"
                                "fast" -> "fast"
                                else -> "medium"
                            }
                            cmd.add(presetArg)
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
                            when (speedPresetNormalized) {
                                "slow" -> {
                                    cmd.add("-deadline")
                                    cmd.add("good")
                                    cmd.add("-cpu-used")
                                    cmd.add("1")
                                }
                                "fast" -> {
                                    cmd.add("-deadline")
                                    cmd.add("realtime")
                                    cmd.add("-cpu-used")
                                    cmd.add("4")
                                }
                                else -> {
                                    cmd.add("-deadline")
                                    cmd.add("good")
                                    cmd.add("-cpu-used")
                                    cmd.add("3")
                                }
                            }
                            cmd.add("-row-mt")
                            cmd.add("1")
                        } else {
                            cmd.add("-preset")
                            val presetArg = when (speedPresetNormalized) {
                                "slow" -> "slow"
                                "fast" -> "fast"
                                else -> "medium"
                            }
                            cmd.add(presetArg)
                        }
                    }

                    if (!pixelFormat.equals("auto", ignoreCase = true)) {
                        cmd.add("-pix_fmt")
                        cmd.add("yuv420p")
                    }

                    if (resolvedCodec == "libx264") {
                        val isSocialPreset = targetPreset != null && (
                            targetPreset.contains("WhatsApp", ignoreCase = true) ||
                            targetPreset.contains("Instagram", ignoreCase = true) ||
                            targetPreset.contains("TikTok", ignoreCase = true) ||
                            targetPreset.contains("Shorts", ignoreCase = true) ||
                            targetPreset.contains("Twitter", ignoreCase = true) ||
                            targetPreset.contains("Telegram", ignoreCase = true)
                        )
                        if (isSocialPreset) {
                            cmd.add("-x264-params")
                            cmd.add("no-mbtree=1")
                        }
                    }
                }
            }

            // Container-specific flags
            if (!isGifMode && (outFile.name.endsWith(".mp4", ignoreCase = true) || outFile.name.endsWith(".mov", ignoreCase = true))) {
                cmd.add("-avoid_negative_ts")
                cmd.add("make_zero")
                cmd.add("-movflags")
                cmd.add("+faststart")
            }

            cmd.add(outFile.absolutePath)

            val cmdArray = cmd.toTypedArray()
            val sanitizedCmd = cmdArray.map { arg ->
                if (arg == srcFile.absolutePath) "[INPUT_PATH]"
                else if (arg == outFile.absolutePath) "[OUTPUT_PATH]"
                else if (trfFile != null && arg.contains(trfFile!!.absolutePath)) arg.replace(trfFile!!.absolutePath, "[TRF_PATH]")
                else arg
            }.joinToString(" ")
            Log.d(TAG, "Executing native FFmpegKit with ${cmdArray.size} arguments: $sanitizedCmd")

            val pass2DurMs = ((trimDurationSec / speed.coerceAtLeast(0.1f)) * 1000.0).coerceAtLeast(1000.0)
            val session = runFFmpegSession(
                cmdArray = cmdArray,
                onStatistics = { encMs ->
                    if (isStabilized) {
                        val rawRatio = (encMs / pass2DurMs).coerceIn(0.0, 1.0)
                        val weightedPct = (45 + (rawRatio * 54.0).toInt()).coerceIn(45, 99)
                        onProgressPhase?.invoke("Step 2/2: Stabilizing & encoding", encMs, weightedPct)
                        onStatistics?.invoke((effectiveDurMs * (weightedPct / 100.0)).toLong())
                    } else {
                        val pct = ((encMs / pass2DurMs) * 100.0).toInt().coerceIn(0, 99)
                        onProgressPhase?.invoke("Encoding", encMs, pct)
                        onStatistics?.invoke(encMs)
                    }
                },
                onSessionId = onSessionId
            )
            val returnCode = session?.returnCode

            if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 0L) {
                val outSize = outFile.length()
                val origSize = srcFile.length()
                val savings = if (origSize > 0) ((origSize - outSize).toDouble() / origSize.toDouble() * 100.0) else 0.0

                // Inspect actual output size against requested target
                if (targetSizeMb != null && targetSizeMb > 0) {
                    val targetBytes = (targetSizeMb * 1024.0 * 1024.0).toLong()
                    if (outSize > targetBytes) {
                        val errMsg = "Target ceiling not achieved: Output is ${formatBytes(outSize)}, requested limit was ${formatBytes(targetBytes)} (SIZE_LIMIT_UNACHIEVABLE)"
                        Log.e(TAG, errMsg)
                        return CompressionResult(
                            success = false,
                            outputPath = outFile.absolutePath,
                            sizeBytes = outSize,
                            savingsPercent = savings,
                            duration = trimDurationSec,
                            error = errMsg
                        )
                    }
                }

                CompressionResult(
                    success = true,
                    outputPath = outFile.absolutePath,
                    sizeBytes = outSize,
                    savingsPercent = savings,
                    duration = trimDurationSec,
                    error = null
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
        } finally {
            if (ownsTrfFile && trfFile != null && trfFile.exists()) {
                trfFile.delete()
            }
        }
    }

    private fun runFFmpegSession(
        cmdArray: Array<String>,
        onStatistics: ((encodedMs: Long) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null
    ): FFmpegSession? {
        val resultHolder = arrayOfNulls<FFmpegSession>(1)
        val latch = java.util.concurrent.CountDownLatch(1)
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
        val activeSessionId = asyncSession?.sessionId
            ?: throw IllegalStateException("FFmpegKit failed to create a session")
        try {
            onSessionId?.invoke(activeSessionId)
            latch.await()
        } catch (failure: Throwable) {
            // Stop only the session owned by this worker, including when the
            // registration callback throws. Never cancel all sessions globally.
            try {
                FFmpegKit.cancel(activeSessionId)
            } catch (cancelError: Throwable) {
                failure.addSuppressed(cancelError)
            }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        }
        return resultHolder[0]
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
