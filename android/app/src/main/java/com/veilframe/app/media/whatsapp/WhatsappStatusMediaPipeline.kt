package com.veilframe.app.media.whatsapp

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.veilframe.app.media.AspectSpec
import com.veilframe.app.media.CompressionResult
import com.veilframe.app.media.CropSpec
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * High-performance, production-grade WhatsApp Status Media Pipeline.
 *
 * Implements the verified two-stage PureStatus architecture:
 * Stage 1: Fast-seek and slice using stream-copy (-c copy) to produce an intermediate MP4 without generational loss.
 *          Verifies duration accuracy within allowed error margin; falls back to single-pass transcode trim if inaccurate.
 * Stage 2: Controlled H.264 encoding with 16 MiB size policy ceiling and iterative retry mechanism.
 *          Fixed base maxrate (1900k HD / 3800k FHD), duration-dependent bufsize,
 *          29.97 fps, yuv420p, BT.709, and normalized audio (44.1 kHz, 128 kbps).
 * Output Validation: Measures actual output parameters with FFprobeKit to ensure compliance.
 * Lifecycle: Non-blocking cancellable coroutines and guaranteed temporary file deletion in a finally block.
 */
object WhatsappStatusMediaPipeline {

    private const val TAG = "VeilFrame.StatusPipeline"

    /**
     * Executes the complete two-stage WhatsApp Status video pipeline (blocking wrapper).
     */
    fun processVideo(
        srcFile: File,
        outFile: File,
        resolution: WhatsappStatusResolution = WhatsappStatusResolution.HD_720P,
        aspect: String = "Original",
        cropSpec: CropSpec? = null,
        trimStartSec: Double = 0.0,
        trimDurationSec: Double = 0.0,
        flipH: Boolean = false,
        flipV: Boolean = false,
        rotate: Int = 0,
        speed: Float = 1.0f,
        colorProfile: String = "Original",
        isMuted: Boolean = false,
        onStatistics: ((encodedMs: Long) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null
    ): CompressionResult = runBlocking {
        processVideoSuspend(
            srcFile = srcFile,
            outFile = outFile,
            resolution = resolution,
            aspect = aspect,
            cropSpec = cropSpec,
            trimStartSec = trimStartSec,
            trimDurationSec = trimDurationSec,
            flipH = flipH,
            flipV = flipV,
            rotate = rotate,
            speed = speed,
            colorProfile = colorProfile,
            isMuted = isMuted,
            onStatistics = onStatistics,
            onSessionId = onSessionId
        )
    }

    /**
     * Executes the complete two-stage WhatsApp Status video pipeline as a suspend function.
     */
    suspend fun processVideoSuspend(
        srcFile: File,
        outFile: File,
        resolution: WhatsappStatusResolution = WhatsappStatusResolution.HD_720P,
        aspect: String = "Original",
        cropSpec: CropSpec? = null,
        trimStartSec: Double = 0.0,
        trimDurationSec: Double = 0.0,
        flipH: Boolean = false,
        flipV: Boolean = false,
        rotate: Int = 0,
        speed: Float = 1.0f,
        colorProfile: String = "Original",
        isMuted: Boolean = false,
        onStatistics: ((encodedMs: Long) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null
    ): CompressionResult {
        val tempDir = outFile.parentFile ?: srcFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val tempIntermediate = File(tempDir, "vf_wa_stage1_${System.currentTimeMillis()}_${(1000..9999).random()}.mp4")

        return try {
            if (outFile.exists()) outFile.delete()

            // Step 1: Input Analysis
            val analysis = WhatsappStatusMediaAnalyzer.analyze(srcFile)
            val effectiveDuration = when {
                trimDurationSec > 0.05 -> trimDurationSec / (if (speed > 0.1f) speed else 1.0f)
                analysis.durationSec > 0.05 -> analysis.durationSec / (if (speed > 0.1f) speed else 1.0f)
                else -> 10.0
            }

            // Step 2: Rate Control Calculation with 16 MiB Ceiling
            val effectiveMute = isMuted || !analysis.hasAudio
            val audioKbps = if (effectiveMute) 0 else WhatsappStatusConstants.AUDIO_BITRATE_KBPS
            val initialEffectiveRate = WhatsappStatusRateControl.effectiveMaxRate(resolution, effectiveDuration, audioKbps)
            var currentRate = initialEffectiveRate
            var currentBufSize = WhatsappStatusRateControl.calculateBufSize(currentRate, effectiveDuration)

            Log.i(TAG, "WhatsApp Status Rate Control: Mode=${resolution.displayName}, Duration=${String.format(Locale.US, "%.2f", effectiveDuration)}s, EffectiveRate=${currentRate}k, BufSize=${currentBufSize}k")

            // Step 3: Build Filter Graph (handling HDR to SDR, crop, rotation, SAR, etc.)
            val parsedSar = try {
                val sarStr = analysis.sampleAspectRatio
                if (sarStr.contains(":")) {
                    val parts = sarStr.split(":")
                    val num = parts[0].toDoubleOrNull() ?: 1.0
                    val den = parts[1].toDoubleOrNull() ?: 1.0
                    if (den != 0.0) num / den else 1.0
                } else {
                    sarStr.toDoubleOrNull() ?: 1.0
                }
            } catch (_: Throwable) {
                1.0
            }

            val filterGraph = WhatsappStatusFilterGraphBuilder.buildVideoFilterGraph(
                resolution = resolution,
                srcWidth = analysis.width,
                srcHeight = analysis.height,
                aspect = aspect,
                cropSpec = cropSpec,
                isHdr = analysis.isHdr,
                flipH = flipH,
                flipV = flipV,
                rotate = rotate,
                speed = speed,
                colorProfile = colorProfile,
                sar = parsedSar
            )

            // Step 4: Stage 1 — Prepare Clip (Trim / Remux with -c copy)
            val hasTrimming = trimStartSec > 0.05 || trimDurationSec > 0.05
            var stage2InputFile = srcFile
            var stage2TrimStart = 0.0
            var stage2TrimDuration = 0.0

            if (hasTrimming) {
                val stage1Args = WhatsappStatusCommandBuilder.buildStage1Arguments(
                    srcFile = srcFile,
                    tempIntermediateFile = tempIntermediate,
                    trimStartSec = trimStartSec,
                    trimDurationSec = trimDurationSec
                )
                Log.d(TAG, "Executing Stage 1 (trim/remux): ${stage1Args.joinToString(" ")}")

                val s1Session = executeAsyncCancellable(stage1Args)
                var stage1Verified = false

                if (ReturnCode.isSuccess(s1Session.returnCode) && tempIntermediate.exists() && tempIntermediate.length() > 0L) {
                    val intermediateInfo = FFprobeKit.getMediaInformation(tempIntermediate.absolutePath)?.mediaInformation
                    val actualDur = intermediateInfo?.duration?.toDoubleOrNull() ?: 0.0
                    val targetTrim = if (trimDurationSec > 0.05) trimDurationSec else (analysis.durationSec - trimStartSec).coerceAtLeast(0.1)
                    val allowedError = maxOf(0.5, targetTrim * 0.05)

                    if (Math.abs(actualDur - targetTrim) <= allowedError) {
                        stage1Verified = true
                        stage2InputFile = tempIntermediate
                        stage2TrimStart = 0.0
                        stage2TrimDuration = 0.0
                        Log.d(TAG, "Stage 1 remux verified successfully: ${actualDur}s (requested ~${targetTrim}s)")
                    } else {
                        Log.w(TAG, "Stage 1 remux duration mismatch: actual=${actualDur}s vs requested=${targetTrim}s. Falling back to single-pass transcode trim.")
                    }
                } else {
                    Log.w(TAG, "Stage 1 remux failed with code ${s1Session.returnCode}. Falling back to single-pass transcode trim.")
                }

                if (!stage1Verified) {
                    try { if (tempIntermediate.exists()) tempIntermediate.delete() } catch (_: Throwable) {}
                    stage2InputFile = srcFile
                    stage2TrimStart = trimStartSec
                    stage2TrimDuration = trimDurationSec
                }
            }

            // Step 5: Stage 2 — Controlled H.264 Status Encoding with 16 MiB Retry Loop
            var retryCount = 0
            var s2Session: FFmpegSession? = null

            while (retryCount <= WhatsappStatusRateControl.MAX_SIZE_RETRIES) {
                if (outFile.exists()) outFile.delete()

                val stage2Args = WhatsappStatusCommandBuilder.buildStage2Arguments(
                    tempIntermediateFile = stage2InputFile,
                    outFile = outFile,
                    filterGraph = filterGraph,
                    baseMaxRateKbps = currentRate,
                    bufSizeKbps = currentBufSize,
                    isMuted = effectiveMute,
                    trimStartSec = stage2TrimStart,
                    trimDurationSec = stage2TrimDuration
                )
                Log.d(TAG, "Executing Stage 2 (attempt ${retryCount + 1}): ${stage2Args.joinToString(" ")}")

                s2Session = executeAsyncCancellable(
                    args = stage2Args,
                    onStatistics = { stats ->
                        val encMs = stats.time.toLong().coerceAtLeast(0L)
                        onStatistics?.invoke(encMs)
                    },
                    onSessionId = onSessionId
                )

                if (!ReturnCode.isSuccess(s2Session.returnCode) || !outFile.exists() || outFile.length() == 0L) {
                    break
                }

                val outSize = outFile.length()
                if (!WhatsappStatusRateControl.exceedsSizeCeiling(outSize)) {
                    // Successfully under 16 MiB ceiling
                    break
                }

                if (retryCount < WhatsappStatusRateControl.MAX_SIZE_RETRIES) {
                    val nextRate = WhatsappStatusRateControl.retryRate(currentRate, outSize, 0.92)
                    Log.w(TAG, "Output size ${outSize} bytes exceeded 16 MiB ceiling. Retrying (${retryCount + 1}/${WhatsappStatusRateControl.MAX_SIZE_RETRIES}) with rate ${nextRate}k (was ${currentRate}k)...")
                    currentRate = nextRate
                    currentBufSize = WhatsappStatusRateControl.calculateBufSize(currentRate, effectiveDuration)
                }
                retryCount++
            }

            val returnCode = s2Session?.returnCode

            if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 0L) {
                val finalSize = outFile.length()
                if (WhatsappStatusRateControl.exceedsSizeCeiling(finalSize)) {
                    val errMsg = "SIZE_LIMIT_UNACHIEVABLE: Encoded output ($finalSize bytes) exceeds WhatsApp Status 16 MiB ceiling after ${WhatsappStatusRateControl.MAX_SIZE_RETRIES} retries."
                    Log.e(TAG, errMsg)
                    return CompressionResult(
                        success = false,
                        outputPath = outFile.absolutePath,
                        sizeBytes = finalSize,
                        duration = effectiveDuration,
                        error = errMsg
                    )
                }

                // Step 6: Output Validation
                val validationSpec = WhatsappStatusValidationSpec(
                    resolution = resolution,
                    aspectSpec = AspectSpec.fromString(aspect),
                    sourceGeometry = ResolvedGeometry(
                        width = analysis.width,
                        height = analysis.height,
                        rotation = analysis.rotation,
                        sampleAspectRatio = analysis.sampleAspectRatio
                    )
                )
                val report = WhatsappStatusOutputValidator.validate(outFile, validationSpec)
                val diagnostic = report.toDiagnosticString()
                Log.i(TAG, diagnostic)

                val origSize = srcFile.length()
                val savings = if (origSize > 0) ((origSize - finalSize).toDouble() / origSize.toDouble() * 100.0) else 0.0

                CompressionResult(
                    success = report.isValid,
                    outputPath = outFile.absolutePath,
                    sizeBytes = finalSize,
                    savingsPercent = savings,
                    duration = effectiveDuration,
                    error = if (report.warnings.isNotEmpty()) report.warnings.joinToString("; ") else null
                )
            } else {
                val tailLogs = s2Session?.allLogsAsString?.lines()?.takeLast(15)?.joinToString("\n") ?: "No logs"
                val errorMsg = "WhatsApp Status encode failed (code $returnCode):\n$tailLogs"
                Log.e(TAG, errorMsg)
                CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = 0L,
                    error = errorMsg
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "WhatsApp Status pipeline exception: ${e.message}", e)
            CompressionResult(
                success = false,
                outputPath = outFile.absolutePath,
                sizeBytes = 0L,
                error = e.message
            )
        } finally {
            try {
                if (tempIntermediate.exists()) {
                    tempIntermediate.delete()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Converts a static photo into a 5-second 29.97 fps WhatsApp Status video.
     */
    fun processPhotoToStatus(
        srcImageFile: File,
        outFile: File,
        resolution: WhatsappStatusResolution = WhatsappStatusResolution.HD_720P,
        onSessionId: ((Long) -> Unit)? = null
    ): CompressionResult = runBlocking {
        try {
            if (outFile.exists()) outFile.delete()

            val filterGraph = WhatsappStatusFilterGraphBuilder.buildPhotoStatusFilterGraph(resolution)
            val args = WhatsappStatusCommandBuilder.buildPhotoStatusArguments(
                inputImageFile = srcImageFile,
                outFile = outFile,
                filterGraph = filterGraph
            )

            Log.d(TAG, "Executing Photo-to-Status: ${args.joinToString(" ")}")
            val session = executeAsyncCancellable(args, onSessionId = onSessionId)
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 0L) {
                val report = WhatsappStatusOutputValidator.validate(outFile, resolution)
                CompressionResult(
                    success = report.isValid,
                    outputPath = outFile.absolutePath,
                    sizeBytes = outFile.length(),
                    savingsPercent = 0.0,
                    duration = WhatsappStatusConstants.PHOTO_STATUS_DURATION_SEC,
                    error = if (report.warnings.isNotEmpty()) report.warnings.joinToString("; ") else null
                )
            } else {
                val tailLogs = session.allLogsAsString?.lines()?.takeLast(15)?.joinToString("\n") ?: "No logs"
                CompressionResult(
                    success = false,
                    outputPath = outFile.absolutePath,
                    sizeBytes = 0L,
                    error = "Photo-to-Status failed (code $returnCode):\n$tailLogs"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Photo-to-Status exception: ${e.message}", e)
            CompressionResult(
                success = false,
                outputPath = outFile.absolutePath,
                sizeBytes = 0L,
                error = e.message
            )
        }
    }

    /**
     * Non-blocking cancellable coroutine execution of FFmpegKit commands.
     * Guarantees FFmpeg cancellation if the caller coroutine job is cancelled.
     */
    private suspend fun executeAsyncCancellable(
        args: Array<String>,
        onStatistics: ((Statistics) -> Unit)? = null,
        onSessionId: ((Long) -> Unit)? = null
    ): FFmpegSession = suspendCancellableCoroutine { cont ->
        val asyncSession = FFmpegKit.executeWithArgumentsAsync(
            args,
            { session ->
                if (cont.isActive) {
                    cont.resume(session)
                }
            },
            null,
            { stats ->
                onStatistics?.invoke(stats)
            }
        )
        val sessionId = asyncSession?.sessionId ?: -1L
        if (sessionId != -1L) {
            onSessionId?.invoke(sessionId)
        }
        cont.invokeOnCancellation {
            if (sessionId != -1L) {
                FFmpegKit.cancel(sessionId)
            }
        }
    }
}
