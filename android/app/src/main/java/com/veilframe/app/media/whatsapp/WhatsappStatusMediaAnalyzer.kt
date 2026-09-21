package com.veilframe.app.media.whatsapp

import android.media.MediaMetadataRetriever
import android.util.Log
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File

/**
 * Detailed media analysis data model for WhatsApp Status preprocessing.
 */
data class WhatsappMediaAnalysis(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val videoCodec: String,
    val pixelFormat: String,
    val isHdr: Boolean,
    val hasAudio: Boolean,
    val audioCodec: String?,
    val audioSampleRate: Int?,
    val audioBitrateKbps: Int?,
    val rotation: Int,
    val sampleAspectRatio: String = "1:1"
)

/**
 * Inspects source media to determine appropriate processing pathways:
 * HDR detection, orientation, audio streams, and durations.
 */
object WhatsappStatusMediaAnalyzer {

    private const val TAG = "VeilFrame.StatusAnalyzer"

    fun analyze(file: File): WhatsappMediaAnalysis {
        var durSec = 0.0
        var width = 0
        var height = 0
        var fps = 29.97
        var videoCodec = "unknown"
        var pixelFormat = "unknown"
        var isHdr = false
        var hasAudio = false
        var audioCodec: String? = null
        var audioSampleRate: Int? = null
        var audioBitrateKbps: Int? = null
        var rotation = 0
        var sampleAspectRatio = "1:1"

        // 1. Attempt FFprobeKit inspection for deep color space and codec details
        try {
            val session = FFprobeKit.getMediaInformation(file.absolutePath)
            val info = session?.mediaInformation
            if (info != null) {
                durSec = info.duration?.toDoubleOrNull() ?: 0.0

                for (stream in info.streams) {
                    if (stream.type == "video") {
                        width = stream.width?.toInt() ?: width
                        height = stream.height?.toInt() ?: height
                        videoCodec = stream.codec ?: videoCodec
                        pixelFormat = stream.getStringProperty("pix_fmt") ?: stream.format ?: pixelFormat

                        // Check FPS
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

                        // HDR detection: check color transfer and color space properties
                        val transfer = (stream.getStringProperty("color_transfer") ?: "").lowercase()
                        val primaries = (stream.getStringProperty("color_primaries") ?: "").lowercase()
                        val space = (stream.getStringProperty("color_space") ?: "").lowercase()

                        if (transfer.contains("smpte2084") || transfer.contains("arib-std-b67") ||
                            transfer.contains("hlg") || transfer.contains("pq") ||
                            primaries.contains("bt2020") || space.contains("bt2020")
                        ) {
                            isHdr = true
                        }

                        // Capture SAR (sample_aspect_ratio)
                        val sar = stream.getStringProperty("sample_aspect_ratio")
                        if (!sar.isNullOrBlank() && sar != "0:1" && sar != "N/A") {
                            sampleAspectRatio = sar
                        }
                    } else if (stream.type == "audio") {
                        hasAudio = true
                        audioCodec = stream.codec
                        audioSampleRate = stream.sampleRate?.toIntOrNull()
                        val bitRate = stream.bitrate?.toLongOrNull()
                        if (bitRate != null && bitRate > 0) {
                            audioBitrateKbps = (bitRate / 1000).toInt()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "FFprobeKit analysis warning: ${e.message}")
        }

        // 2. Native MediaMetadataRetriever fallback for baseline dimensions & duration
        if (width <= 0 || height <= 0 || durSec <= 0.0) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)

                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durMs > 0 && durSec <= 0.0) durSec = durMs / 1000.0

                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (w > 0) width = w
                if (h > 0) height = h

                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                rotation = rot

                val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                if (hasAudioStr != null && hasAudioStr.equals("yes", ignoreCase = true)) {
                    hasAudio = true
                }
                retriever.release()
            } catch (e: Throwable) {
                Log.w(TAG, "MediaMetadataRetriever fallback error: ${e.message}")
            }
        }

        return WhatsappMediaAnalysis(
            durationSec = durSec,
            width = width,
            height = height,
            fps = fps,
            videoCodec = videoCodec,
            pixelFormat = pixelFormat,
            isHdr = isHdr,
            hasAudio = hasAudio,
            audioCodec = audioCodec,
            audioSampleRate = audioSampleRate,
            audioBitrateKbps = audioBitrateKbps,
            rotation = rotation,
            sampleAspectRatio = sampleAspectRatio
        )
    }
}
