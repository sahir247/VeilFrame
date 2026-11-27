package com.veilframe.app.cv.motion

import kotlin.math.abs

/**
 * Fail-closed source/output compressed-audio stream integrity check.
 *
 * MP4 stream-copy audio is intentionally not resampled. Verify that a source
 * track survives and its first/last packet PTS stay aligned to video after
 * FFmpeg remuxing. Exact packet counts may differ at MP4 edit-list boundaries.
 * This does not measure perceptual acoustic sync or codec quality.
 */
object MotionLabAudioTimeline {
    const val MAX_AUDIO_SAMPLES = 500_000
    const val MAX_AUDIO_TRACKS = 16
    const val SYNC_TOLERANCE_US = 300_000L

    data class AudioSpan(
        val firstPtsUs: Long,
        val lastPtsUs: Long,
        val packets: Int
    ) {
        init {
            require(firstPtsUs >= 0L && lastPtsUs >= firstPtsUs && packets > 0) {
                "Audio samples must have valid ordered timestamps"
            }
        }
    }

    data class Track(val mime: String, val span: AudioSpan)

    fun verifyTracks(
        source: List<Track>, output: List<Track>, sourceVideoFirstUs: Long,
        outputVideoFirstUs: Long, durationMs: Long
    ) {
        require(source.size <= MAX_AUDIO_TRACKS && output.size <= MAX_AUDIO_TRACKS) {
            "Audio track count exceeds bounded verification budget"
        }
        check(source.size == output.size) { "Motion Lab audio track count changed" }
        for ((before, after) in source.zip(output)) {
            check(before.mime == after.mime) { "Motion Lab stream-copy audio codec changed" }
            verify(before.span, after.span, sourceVideoFirstUs, outputVideoFirstUs, durationMs)
        }
    }

    fun verify(
        source: AudioSpan?,
        output: AudioSpan?,
        sourceVideoFirstUs: Long,
        outputVideoFirstUs: Long,
        durationMs: Long
    ) {
        require(durationMs > 0L) { "Source duration unavailable" }
        if (source == null) {
            check(output == null) { "Motion Lab unexpectedly introduced an audio stream" }
            return
        }
        check(output != null) { "Motion Lab output is missing the source audio track" }
        val sourceRelativeStart = source.firstPtsUs - sourceVideoFirstUs
        val outputRelativeStart = output.firstPtsUs - outputVideoFirstUs
        check(abs(sourceRelativeStart - outputRelativeStart) <= SYNC_TOLERANCE_US) {
            "Motion Lab audio start shifted relative to video"
        }

        val requestedDurationUs = durationMs * 1_000L
        val expectedAudioTail = minOf(
            source.lastPtsUs - sourceVideoFirstUs,
            requestedDurationUs
        )
        val actualAudioTail = output.lastPtsUs - outputVideoFirstUs
        check(abs(expectedAudioTail - actualAudioTail) <= SYNC_TOLERANCE_US) {
            "Motion Lab audio tail was truncated, extended, or shifted"
        }
    }
}
