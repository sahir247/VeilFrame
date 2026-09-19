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
    val warnings: List<String>
) {
    fun toDiagnosticString(): String {
        val sb = StringBuilder()
        sb.append("WhatsApp Status Output Inspection:\n")
        sb.append("  Dimensions:   ${width}×${height} (9:16 Canvas)\n")
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
        val warnings = mutableListOf<String>()
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
                warnings = warnings
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

        // Validate dimensions match requested Status canvas
        if (width != expectedResolution.width || height != expectedResolution.height) {
            warnings.add("Canvas dimension mismatch: got ${width}×${height}, expected ${expectedResolution.width}×${expectedResolution.height}")
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
            warnings = warnings
        )
    }
}
