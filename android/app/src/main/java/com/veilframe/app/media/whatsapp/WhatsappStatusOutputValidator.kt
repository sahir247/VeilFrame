package com.veilframe.app.media.whatsapp

import android.util.Log
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import java.util.Locale

/**
 * Validation report describing actual measured properties of the generated WhatsApp Status MP4.
 */
data class WhatsappStatusValidationReport(
    val isValid: Boolean,
    val videoCodec: String,
    val pixelFormat: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationSec: Double,
    val sizeBytes: Long,
    val videoBitrateKbps: Int?,
    val hasAudio: Boolean,
    val audioCodec: String?,
    val audioSampleRate: Int?,
    val audioBitrateKbps: Int?,
    val warnings: List<String>,
    val displayAspectRatio: String = "1:1"
) {
    val error: String? get() = warnings.firstOrNull()

    fun toDiagnosticString(): String {
        val sb = StringBuilder()
        sb.append("WhatsApp Status Output Inspection:\n")
        sb.append("  Dimensions:   ${width}×${height} (DAR: $displayAspectRatio)\n")
        sb.append("  Video Codec:  $videoCodec (Target: H.264 / libx264)\n")
        sb.append("  Pixel Format: $pixelFormat (Target: yuv420p)\n")
        sb.append("  Frame Rate:   ${String.format(Locale.US, "%.2f", fps)} fps (Target: 29.97)\n")
        sb.append("  Duration:     ${String.format(Locale.US, "%.2f", durationSec)}s\n")
        sb.append("  Size:         ${String.format(Locale.US, "%.2f", sizeBytes / (1024.0 * 1024.0))} MB\n")
        if (videoBitrateKbps != null) {
            sb.append("  Video Rate:   ${videoBitrateKbps} kbps\n")
        }
        if (hasAudio) {
            sb.append("  Audio:        $audioCodec • ${audioSampleRate ?: 44100} Hz • ${audioBitrateKbps ?: 128} kbps\n")
        } else {
            sb.append("  Audio:        Muted / None\n")
        }
        if (warnings.isNotEmpty()) {
            sb.append("  Warnings:     ${warnings.joinToString("; ")}\n")
        }
        return sb.toString()
    }
}

/**
 * Validates the generated Status MP4 with FFprobeKit to verify actual output rather than assumed parameters.
 */
object WhatsappStatusOutputValidator {

    private const val TAG = "VeilFrame.StatusValidator"

    fun validate(outFile: File, expectedResolution: WhatsappStatusResolution): WhatsappStatusValidationReport {
        return validate(outFile, WhatsappStatusValidationSpec(resolution = expectedResolution))
    }

    /**
     * Validates output metrics directly without invoking FFprobeKit (suitable for pure unit testing and fast checks).
     */
    fun validate(
        actualWidth: Int,
        actualHeight: Int,
        fileSizeBytes: Long,
        spec: WhatsappStatusValidationSpec,
        videoCodec: String = "h264",
        pixelFormat: String = WhatsappStatusConstants.PIXEL_FORMAT
    ): WhatsappStatusValidationReport {
        val warnings = mutableListOf<String>()
        val expectedResolution = spec.resolution

        // Validate codec
        if (!videoCodec.equals("h264", ignoreCase = true) && !videoCodec.equals("avc1", ignoreCase = true)) {
            warnings.add("Unexpected video codec: $videoCodec (expected h264)")
        }

        // Validate pixel format
        if (!pixelFormat.equals(WhatsappStatusConstants.PIXEL_FORMAT, ignoreCase = true)) {
            warnings.add("Unexpected pixel format: $pixelFormat (expected ${WhatsappStatusConstants.PIXEL_FORMAT})")
        }

        // Bounded-DAR dimension validation
        val maxDim = maxOf(actualWidth, actualHeight)
        val minDim = minOf(actualWidth, actualHeight)
        if (maxDim > expectedResolution.maxLongSide || minDim > expectedResolution.maxShortSide) {
            warnings.add("Bounded dimension exceeded: ${actualWidth}×${actualHeight} outside ${expectedResolution.maxShortSide}×${expectedResolution.maxLongSide} bounds")
        }

        // Even dimension check
        if (actualWidth % 2 != 0 || actualHeight % 2 != 0) {
            warnings.add("Odd dimensions: ${actualWidth}×${actualHeight} (H.264 requires even dimensions)")
        }

        // Target DAR tolerance check
        val expectedDar = spec.expectedTargetDar()
        val actualDar = if (actualHeight > 0) actualWidth.toDouble() / actualHeight.toDouble() else 1.0
        val actualDarStr = AspectRatioResolver.formatDar(actualWidth, actualHeight)

        if (expectedDar != null && expectedDar > 0.0 && actualHeight > 0) {
            val darDiffPercent = Math.abs(actualDar - expectedDar) / expectedDar
            if (darDiffPercent > 0.03) {
                warnings.add(String.format(Locale.US, "DAR mismatch: got %.3f (%s), expected %.3f (tolerance 3%%)", actualDar, actualDarStr, expectedDar))
            }
        }

        // 16 MiB file size ceiling check
        if (WhatsappStatusRateControl.exceedsSizeCeiling(fileSizeBytes)) {
            warnings.add("Output exceeds 16 MiB ceiling: ${String.format(Locale.US, "%.2f", fileSizeBytes / (1024.0 * 1024.0))} MB")
        }

        return WhatsappStatusValidationReport(
            isValid = warnings.isEmpty(),
            videoCodec = videoCodec,
            pixelFormat = pixelFormat,
            width = actualWidth,
            height = actualHeight,
            fps = 29.97,
            durationSec = 30.0,
            sizeBytes = fileSizeBytes,
            videoBitrateKbps = null,
            hasAudio = false,
            audioCodec = null,
            audioSampleRate = null,
            audioBitrateKbps = null,
            warnings = warnings,
            displayAspectRatio = actualDarStr
        )
    }

    fun validate(outFile: File, spec: WhatsappStatusValidationSpec): WhatsappStatusValidationReport {
        val warnings = mutableListOf<String>()
        val expectedResolution = spec.resolution
        var videoCodec = "unknown"
        var pixelFormat = "unknown"
        var width = 0
        var height = 0
        var fps = 0.0
        var durSec = 0.0
        var videoBitrateKbps: Int? = null
        var hasAudio = false
        var audioCodec: String? = null
        var audioSampleRate: Int? = null
        var audioBitrateKbps: Int? = null

        if (!outFile.exists() || outFile.length() == 0L) {
            warnings.add("Output file does not exist or is empty")
            return WhatsappStatusValidationReport(
                isValid = false,
                videoCodec = videoCodec,
                pixelFormat = pixelFormat,
                width = width,
                height = height,
                fps = fps,
                durationSec = durSec,
                sizeBytes = 0L,
                videoBitrateKbps = null,
                hasAudio = false,
                audioCodec = null,
                audioSampleRate = null,
                audioBitrateKbps = null,
                warnings = warnings,
                displayAspectRatio = "0:0"
            )
        }

        try {
            val session = FFprobeKit.getMediaInformation(outFile.absolutePath)
            val info = session?.mediaInformation
            if (info != null) {
                durSec = info.duration?.toDoubleOrNull() ?: 0.0

                for (stream in info.streams) {
                    if (stream.type == "video") {
                        width = stream.width?.toInt() ?: width
                        height = stream.height?.toInt() ?: height
                        videoCodec = stream.codec ?: videoCodec
                        pixelFormat = stream.getStringProperty("pix_fmt") ?: stream.format ?: pixelFormat

                        val rFrameRate = stream.realFrameRate
                        if (!rFrameRate.isNullOrBlank()) {
                            val parts = rFrameRate.split("/")
                            if (parts.size == 2) {
                                val num = parts[0].toDoubleOrNull() ?: 0.0
                                val den = parts[1].toDoubleOrNull() ?: 1.0
                                if (den > 0) fps = num / den
                            } else {
                                fps = rFrameRate.toDoubleOrNull() ?: fps
                            }
                        }

                        val br = stream.bitrate?.toLongOrNull()
                        if (br != null && br > 0) {
                            videoBitrateKbps = (br / 1000).toInt()
                        }
                    } else if (stream.type == "audio") {
                        hasAudio = true
                        audioCodec = stream.codec
                        audioSampleRate = stream.sampleRate?.toIntOrNull()
                        val br = stream.bitrate?.toLongOrNull()
                        if (br != null && br > 0) {
                            audioBitrateKbps = (br / 1000).toInt()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "FFprobe validation error: ${e.message}")
            warnings.add("FFprobe error: ${e.message}")
        }

        // Validate codec
        if (!videoCodec.equals("h264", ignoreCase = true) && !videoCodec.equals("avc1", ignoreCase = true)) {
            warnings.add("Unexpected video codec: $videoCodec (expected h264)")
        }

        // Validate pixel format
        if (!pixelFormat.equals(WhatsappStatusConstants.PIXEL_FORMAT, ignoreCase = true)) {
            warnings.add("Unexpected pixel format: $pixelFormat (expected ${WhatsappStatusConstants.PIXEL_FORMAT})")
        }

        // Bounded-DAR dimension validation
        // HD: maxOf(w,h) <= 1280 && minOf(w,h) <= 720; FHD: maxOf(w,h) <= 1920 && minOf(w,h) <= 1080
        // Square (e.g. 720×720) is 100% valid
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        if (maxDim > expectedResolution.maxLongSide || minDim > expectedResolution.maxShortSide) {
            warnings.add("Bounded dimension exceeded: ${width}×${height} outside ${expectedResolution.maxShortSide}×${expectedResolution.maxLongSide} bounds")
        }

        // Even dimension check (required for H.264 / yuv420p)
        if (width % 2 != 0 || height % 2 != 0) {
            warnings.add("Odd dimensions: ${width}×${height} (H.264 requires even dimensions)")
        }

        // Target DAR tolerance check (within 3% tolerance)
        val expectedDar = spec.expectedTargetDar()
        val actualDar = if (height > 0) width.toDouble() / height.toDouble() else 1.0
        val actualDarStr = AspectRatioResolver.formatDar(width, height)

        if (expectedDar != null && expectedDar > 0.0 && height > 0) {
            val darDiffPercent = Math.abs(actualDar - expectedDar) / expectedDar
            if (darDiffPercent > 0.03) {
                warnings.add(String.format(Locale.US, "DAR mismatch: got %.3f (%s), expected %.3f (tolerance 3%%)", actualDar, actualDarStr, expectedDar))
            }
        }

        // 16 MiB file size ceiling check
        val fileSizeBytes = outFile.length()
        if (WhatsappStatusRateControl.exceedsSizeCeiling(fileSizeBytes)) {
            warnings.add("Output exceeds 16 MiB ceiling: ${String.format(Locale.US, "%.2f", fileSizeBytes / (1024.0 * 1024.0))} MB")
        }

        return WhatsappStatusValidationReport(
            isValid = warnings.isEmpty(),
            videoCodec = videoCodec,
            pixelFormat = pixelFormat,
            width = width,
            height = height,
            fps = fps,
            durationSec = durSec,
            sizeBytes = outFile.length(),
            videoBitrateKbps = videoBitrateKbps,
            hasAudio = hasAudio,
            audioCodec = audioCodec,
            audioSampleRate = audioSampleRate,
            audioBitrateKbps = audioBitrateKbps,
            warnings = warnings,
            displayAspectRatio = actualDarStr
        )
    }
}
