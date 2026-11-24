package com.veilframe.app.cv.motion

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Source-only validation helpers: no Android runtime required for test fixtures.
 * Fail before publication if a sequence hole, empty file, or timed CFR output
 * disagrees with the admitted input track.
 */
object MotionLabSequenceVerifier {
    fun verify(directory: File, sourceFrames: Int, factor: Int, outputFrames: Int) {
        require(sourceFrames in 2..MotionLabTimingContract.MAX_SOURCE_FRAMES)
        require(factor in 2..4)
        val expected = sourceFrames * factor
        check(outputFrames == expected) {
            "Motion Lab generated $outputFrames frames; expected $expected"
        }
        val frames = directory.listFiles { file ->
            file.name.matches(Regex("o_[0-9]{5}\\.jpg"))
        } ?: throw IllegalStateException("Motion sequence cannot be read")
        check(frames.size == expected) { "Motion sequence has extra or missing files" }
        for (i in 0 until expected) {
            val frame = File(directory, String.format(Locale.ROOT, "o_%05d.jpg", i))
            check(frame.isFile && frame.length() > 0L) {
                "Motion sequence has a missing/empty frame at index $i"
            }
        }
    }

    fun verifyPresentation(presentationUs: List<Long>, expectedFrames: Int, expectedFps: Double) {
        require(expectedFrames in 2..(MotionLabTimingContract.MAX_SOURCE_FRAMES * 4))
        require(expectedFps > 0.0 && expectedFps.isFinite())
        check(presentationUs.size == expectedFrames) {
            "Encoded video has ${presentationUs.size} frames; expected $expectedFrames"
        }
        val sorted = presentationUs.sorted()
        val periodUs = 1_000_000.0 / expectedFps
        // The MP4 encoder is configured for a CFR grid, not merely a similar
        // average duration. Allow timestamp/muxer rounding without allowing
        // accumulated drift or alternating short/long presentation intervals.
        val toleranceUs = 2_000.0
        val first = sorted.first()
        check(first >= 0L && sorted.zipWithNext().all { (a, b) ->
            b > a && abs((b - a) - periodUs) <= toleranceUs
        }) {
            "Encoded video presentation timestamps contain gaps, duplicates or timing drift"
        }
        check(sorted.withIndex().all { (index, timestamp) ->
            abs((timestamp - first) - index * periodUs) <= toleranceUs
        }) {
            "Encoded Motion Lab timeline differs from the requested CFR grid"
        }
    }
}
