package com.veilframe.app.cv.motion

import java.io.File
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
            val frame = File(directory, "o_%05d.jpg".format(i))
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
        val toleranceUs = maxOf(5_000.0, periodUs * 0.65)
        check(sorted.zipWithNext().all { (a, b) ->
            b > a && abs((b - a) - periodUs) <= toleranceUs
        }) {
            "Encoded video presentation timestamps contain gaps, duplicates or timing drift"
        }
        val expectedSpanUs = (expectedFrames - 1) * periodUs
        check(abs((sorted.last() - sorted.first()) - expectedSpanUs) <= maxOf(20_000.0, periodUs)) {
            "Encoded Motion Lab timeline is not the requested full duration"
        }
    }
}
