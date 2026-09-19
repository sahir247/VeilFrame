package com.veilframe.app.media.whatsapp

import java.io.File
import java.util.Locale

/**
 * Builds direct argument arrays for FFmpeg execution.
 * Eliminates shell concatenation, shell escaping, and argument injection vulnerabilities.
 */
object WhatsappStatusCommandBuilder {

    /**
     * Builds Stage 1 (Prepare Clip: trim/remux stream-copy) argument array.
     *
     * Properties:
     * - Fast-seek and slice using stream-copy (-c copy)
     * - Video and audio copied losslessly without re-encoding
     * - Produces clean temporary MP4 for Stage 2
     */
    fun buildStage1Arguments(
        srcFile: File,
        tempIntermediateFile: File,
        trimStartSec: Double = 0.0,
        trimDurationSec: Double = 0.0
    ): Array<String> {
        val args = mutableListOf<String>()
        args.add("-y")

        if (trimStartSec > 0.05) {
            args.add("-ss")
            args.add(String.format(Locale.US, "%.3f", trimStartSec))
        }

        if (trimDurationSec > 0.05) {
            args.add("-t")
            args.add(String.format(Locale.US, "%.3f", trimDurationSec))
        }

        args.add("-i")
        args.add(srcFile.absolutePath)

        args.add("-map")
        args.add("0:v:0")

        args.add("-map")
        args.add("0:a?")

        args.add("-c")
        args.add("copy")

        args.add("-movflags")
        args.add("+faststart")

        args.add(tempIntermediateFile.absolutePath)
        return args.toTypedArray()
    }

    /**
     * Builds Stage 2 (Controlled WhatsApp Status Encoding) argument array.
     *
     * Enforces:
     * - Fixed base maxrate (1900k for HD, 3800k for FHD)
     * - Duration-derived bufsize
     * - CRF 23
     * - 29.97 fps
     * - yuv420p
     * - Audio normalized to 44.1 kHz, 128 kbps (or muted with -an)
     * - +faststart layout
     */
    fun buildStage2Arguments(
        tempIntermediateFile: File,
        outFile: File,
        filterGraph: String,
        baseMaxRateKbps: Int,
        bufSizeKbps: Int,
        isMuted: Boolean = false,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    ): Array<String> {
        val args = mutableListOf<String>()
        args.add("-y")

        args.add("-i")
        args.add(tempIntermediateFile.absolutePath)

        args.add("-filter_complex")
        args.add(filterGraph)

        args.add("-map")
        args.add("[vout]")

        if (isMuted) {
            args.add("-an")
        } else {
            args.add("-map")
            args.add("0:a?")
            args.add("-c:a")
            args.add("aac")
            args.add("-ar")
            args.add(WhatsappStatusConstants.AUDIO_SAMPLE_RATE.toString())
            args.add("-b:a")
            args.add(WhatsappStatusConstants.AUDIO_BITRATE_STR)
        }

        args.add("-c:v")
        args.add(WhatsappStatusConstants.CODEC)

        args.add("-crf")
        args.add(WhatsappStatusConstants.CRF.toString())

        args.add("-maxrate")
        args.add("${baseMaxRateKbps}k")

        args.add("-bufsize")
        args.add("${bufSizeKbps}k")

        args.add("-r")
        args.add(String.format(Locale.US, "%.2f", WhatsappStatusConstants.FPS))

        // Explicit BT.709 color metadata tags for WhatsApp player consistency
        args.add("-color_primaries")
        args.add("bt709")
        args.add("-color_trc")
        args.add("bt709")
        args.add("-colorspace")
        args.add("bt709")

        // Privacy metadata scrubbing
        args.add("-map_metadata")
        args.add("-1")
        args.add("-map_chapters")
        args.add("-1")

        args.add("-movflags")
        args.add("+faststart")

        args.add("-threads")
        args.add(threads.toString())

        args.add(outFile.absolutePath)
        return args.toTypedArray()
    }

    /**
     * Builds argument array for Photo-to-Status (5-second 29.97 fps video).
     */
    fun buildPhotoStatusArguments(
        inputImageFile: File,
        outFile: File,
        filterGraph: String,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    ): Array<String> {
        val args = mutableListOf<String>()
        args.add("-y")

        args.add("-loop")
        args.add("1")

        args.add("-t")
        args.add(String.format(Locale.US, "%.1f", WhatsappStatusConstants.PHOTO_STATUS_DURATION_SEC))

        args.add("-i")
        args.add(inputImageFile.absolutePath)

        args.add("-vf")
        args.add(filterGraph)

        args.add("-c:v")
        args.add(WhatsappStatusConstants.CODEC)

        args.add("-crf")
        args.add(WhatsappStatusConstants.CRF.toString())

        args.add("-maxrate")
        args.add("${WhatsappStatusConstants.PHOTO_STATUS_MAXRATE_KBPS}k")

        args.add("-bufsize")
        args.add("${WhatsappStatusConstants.PHOTO_STATUS_BUFSIZE_KBPS}k")

        args.add("-r")
        args.add(String.format(Locale.US, "%.2f", WhatsappStatusConstants.FPS))

        // Explicit BT.709 color metadata tags
        args.add("-color_primaries")
        args.add("bt709")
        args.add("-color_trc")
        args.add("bt709")
        args.add("-colorspace")
        args.add("bt709")

        args.add("-map_metadata")
        args.add("-1")

        args.add("-movflags")
        args.add("+faststart")

        args.add("-threads")
        args.add(threads.toString())

        args.add(outFile.absolutePath)
        return args.toTypedArray()
    }
}
