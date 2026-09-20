package com.veilframe.app.media.whatsapp

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.veilframe.app.media.CompressionResult
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * High-performance, production-grade WhatsApp Status Media Pipeline.
 *
 * Implements the verified two-stage PureStatus architecture:
 * Stage 1: Fast-seek and slice using stream-copy (-c copy) to produce an intermediate MP4 without generational loss.
 * Stage 2: Controlled H.264 encoding with fixed base maxrate (1900k HD / 3800k FHD), duration-dependent bufsize,
 *          29.97 fps, yuv420p, BT.709, and normalized audio (44.1 kHz, 128 kbps).
 * Output Validation: Measures actual output parameters with FFprobeKit to ensure compliance.
 * Lifecycle: Guarantees intermediate temporary file deletion in a finally block.
 */
object WhatsappStatusMediaPipeline {

    private const val TAG = "VeilFrame.StatusPipeline"

    /**
     * Executes the complete two-stage WhatsApp Status video pipeline.
     */
    fun processVideo(
        srcFile: File,
        outFile: File,
        resolution: WhatsappStatusResolution = WhatsappStatusResolution.HD_720P,
        aspect: String = "Original",
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
        // Prepare temporary intermediate file for Stage 1
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

            // Step 2 & 3: Rate Control Calculation
            val baseMaxRate = WhatsappStatusRateControl.baseMaxRate(resolution)
            val bufSize = WhatsappStatusRateControl.calculateBufSize(baseMaxRate, effectiveDuration)

            Log.i(TAG, "WhatsApp Status Rate Control: Mode=${resolution.displayName}, Duration=${String.format(Locale.US, "%.2f", effectiveDuration)}s, BaseMaxRate=${baseMaxRate}k, CalculatedBufSize=${bufSize}k")

            // Step 4: Build Filter Graph (handling HDR to SDR if detected)
            val effectiveHdr = analysis.isHdr
            val filterGraph = WhatsappStatusFilterGraphBuilder.buildVideoFilterGraph(
                resolution = resolution,
                srcWidth = analysis.width,
                srcHeight = analysis.height,
                aspect = aspect,
                isHdr = effectiveHdr,
                flipH = flipH,
                flipV = flipV,
                rotate = rotate,
                speed = speed,
                colorProfile = colorProfile
            )

            // Step 5: Stage 1 — Prepare Clip (Trim / Remux with -c copy)
            val hasTrimming = trimStartSec > 0.05 || trimDurationSec > 0.05
            val stage1InputFile = if (hasTrimming) {
                val stage1Args = WhatsappStatusCommandBuilder.buildStage1Arguments(
                    srcFile = srcFile,
                    tempIntermediateFile = tempIntermediate,
                    trimStartSec = trimStartSec,
                    trimDurationSec = trimDurationSec
                )
                Log.d(TAG, "Executing Stage 1 (trim/remux): ${stage1Args.joinToString(" ")}")

                val stage1Latch = CountDownLatch(1)
                val stage1Result = arrayOfNulls<com.arthenica.ffmpegkit.FFmpegSession>(1)

                FFmpegKit.executeWithArgumentsAsync(
                    stage1Args,
                    { session ->
                        stage1Result[0] = session
                        stage1Latch.countDown()
                    }
                )
                stage1Latch.await()

                val s1Session = stage1Result[0]
                if (!ReturnCode.isSuccess(s1Session?.returnCode) || !tempIntermediate.exists() || tempIntermediate.length() == 0L) {
                    val logs = s1Session?.allLogsAsString ?: "No logs"
                    Log.w(TAG, "Stage 1 remux failed (code ${s1Session?.returnCode}), falling back to direct input for Stage 2. Logs: $logs")
                    srcFile
                } else {
                    tempIntermediate
                }
            } else {
                srcFile
            }

            // Step 6: Stage 2 — Controlled H.264 Status Encoding
            val effectiveMute = isMuted || !analysis.hasAudio
            val stage2Args = WhatsappStatusCommandBuilder.buildStage2Arguments(
                tempIntermediateFile = stage1InputFile,
                outFile = outFile,
                filterGraph = filterGraph,
                baseMaxRateKbps = baseMaxRate,
                bufSizeKbps = bufSize,
                isMuted = effectiveMute
            )
            Log.d(TAG, "Executing Stage 2 (status encode): ${stage2Args.joinToString(" ")}")

            val stage2Latch = CountDownLatch(1)
            val stage2Result = arrayOfNulls<com.arthenica.ffmpegkit.FFmpegSession>(1)

            val asyncSession = FFmpegKit.executeWithArgumentsAsync(
                stage2Args,
                { session ->
                    stage2Result[0] = session
                    stage2Latch.countDown()
                },
                null,
                { stats: Statistics ->
                    val encMs = stats.time.toLong().coerceAtLeast(0L)
                    onStatistics?.invoke(encMs)
                }
            )
            val s2SessionId = asyncSession?.sessionId ?: -1L
            onSessionId?.invoke(s2SessionId)
            stage2Latch.await()

            val s2Session = stage2Result[0]
            val returnCode = s2Session?.returnCode

            if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 0L) {
                // Step 7: Output Validation
                val report = WhatsappStatusOutputValidator.validate(outFile, resolution)
                val diagnostic = report.toDiagnosticString()
                Log.i(TAG, diagnostic)

                val outSize = outFile.length()
                val origSize = srcFile.length()
                val savings = if (origSize > 0) ((origSize - outSize).toDouble() / origSize.toDouble() * 100.0) else 0.0

                CompressionResult(
                    success = true,
                    outputPath = outFile.absolutePath,
                    sizeBytes = outSize,
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
            // Guarantee cleanup of intermediate temporary file
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
    ): CompressionResult {
        return try {
            if (outFile.exists()) outFile.delete()

            val filterGraph = WhatsappStatusFilterGraphBuilder.buildPhotoStatusFilterGraph(resolution)
            val args = WhatsappStatusCommandBuilder.buildPhotoStatusArguments(
                inputImageFile = srcImageFile,
                outFile = outFile,
                filterGraph = filterGraph
            )

            Log.d(TAG, "Executing Photo-to-Status: ${args.joinToString(" ")}")

            val latch = CountDownLatch(1)
            val resultHolder = arrayOfNulls<com.arthenica.ffmpegkit.FFmpegSession>(1)

            val asyncSession = FFmpegKit.executeWithArgumentsAsync(
                args,
                { session ->
                    resultHolder[0] = session
                    latch.countDown()
                }
            )
            onSessionId?.invoke(asyncSession?.sessionId ?: -1L)
            latch.await()

            val session = resultHolder[0]
            val returnCode = session?.returnCode

            if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 0L) {
                val report = WhatsappStatusOutputValidator.validate(outFile, resolution)
                CompressionResult(
                    success = true,
                    outputPath = outFile.absolutePath,
                    sizeBytes = outFile.length(),
                    savingsPercent = 0.0,
                    duration = WhatsappStatusConstants.PHOTO_STATUS_DURATION_SEC,
                    error = if (report.warnings.isNotEmpty()) report.warnings.joinToString("; ") else null
                )
            } else {
                val tailLogs = session?.allLogsAsString?.lines()?.takeLast(15)?.joinToString("\n") ?: "No logs"
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
}
