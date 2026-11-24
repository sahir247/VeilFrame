package com.veilframe.app.cv.motion

import org.junit.Assert.*
import org.junit.Test

class MotionLabDecodedTimelineTest {
    @Test fun acceptsNormalizedVfrPresentationAndRejectsMismatch() {
        val log = """[Parsed_showinfo_1] n: 0 pts: 0 pts_time:0
[Parsed_showinfo_1] n: 1 pts: 33 pts_time:0.033
[Parsed_showinfo_1] n: 2 pts: 110 pts_time:0.11
[Parsed_showinfo_1] n: 3 pts: 145 pts_time:0.145"""
        val expected = listOf(1_000_000L,1_033_000L,1_145_000L,1_110_000L)
        MotionLabDecodedTimeline.verify(log,expected)
        for (bad in listOf(log.replace("0.11","0.09"),log.substringBeforeLast("\n"),
            log.replace("n: 2","n: 0"),log+"\nn: 4 pts: 200 pts_time:0.2")) {
            assertThrows(java.io.IOException::class.java) { MotionLabDecodedTimeline.verify(bad,expected) }
        }
    }
}
