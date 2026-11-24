package com.veilframe.app.cv.motion

import org.junit.Assert.assertThrows
import org.junit.Test

class MotionLabAudioTimelineTest {
    private fun span(first: Long, last: Long) =
        MotionLabAudioTimeline.AudioSpan(first, last, 120)

    @Test fun everyStreamCopyTrackMustSurviveWithTheSameCodecAndTimeline() {
        val aac = MotionLabAudioTimeline.Track("audio/mp4a-latm", span(0, 1_900_000))
        val opus = MotionLabAudioTimeline.Track("audio/opus", span(10_000, 1_850_000))
        val source = listOf(aac, opus)
        MotionLabAudioTimeline.verifyTracks(source, source, 0, 0, 2_000)
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verifyTracks(source, listOf(aac), 0, 0, 2_000)
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verifyTracks(source, listOf(aac, opus.copy(mime = "audio/mpeg")), 0, 0, 2_000)
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verifyTracks(source, listOf(aac, opus.copy(span = span(0, 800_000))), 0, 0, 2_000)
        }
    }

    @Test fun sourceWithoutAudioRequiresAudioFreeOutput() {
        MotionLabAudioTimeline.verify(null, null, 0, 0, 2_000)
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verify(null, span(0, 1_900_000), 0, 0, 2_000)
        }
    }

    @Test fun sourceAudioMustRemainPresentAndSynchronized() {
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verify(span(0, 1_900_000), null, 0, 0, 2_000)
        }
        MotionLabAudioTimeline.verify(span(10_000, 1_950_000),
            span(20_000, 1_945_000), 0, 0, 2_000)
    }

    @Test fun relativeVideoClockIsPreservedAcrossContainerStartOffsets() {
        MotionLabAudioTimeline.verify(
            span(600_000, 2_400_000),
            span(100_000, 1_900_000),
            sourceVideoFirstUs = 500_000,
            outputVideoFirstUs = 0L,
            durationMs = 2_000
        )
    }

    @Test fun missingTailAndLargeAudioStartShiftFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verify(
                span(0, 2_000_000), span(0, 1_400_000), 0, 0, 2_000)
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabAudioTimeline.verify(
                span(0, 2_000_000), span(400_000, 2_000_000), 0, 0, 2_000)
        }
    }

    @Test fun endOfClipAudioIsAllowedToBeTrimmedByTargetDuration() {
        MotionLabAudioTimeline.verify(
            span(0, 2_100_000), span(0, 1_990_000), 0, 0, 2_000)
    }
}
