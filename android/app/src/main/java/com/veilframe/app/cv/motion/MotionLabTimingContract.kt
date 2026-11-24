package com.veilframe.app.cv.motion

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fail-closed timing admission for Motion Lab's constant-rate raw frame stream.
 *
 * Use the actual encoded video sample presentation timestamps (not JPEG count
 * divided by container duration). VFR is currently unsupported by the raw CFR
 * bridge; require a well-defined CFR contract until PTS-native streaming exists.
 */
object MotionLabTimingContract {
    const val MAX_SOURCE_FRAMES = 1800
    const val MAX_DURATION_MS = 60_000L

    data class Plan(
        val sourceFrames: Int,
        val sourceFps: Double,
        val multiplier: Int,
        val outputFps: Double,
        val expectedOutputFrames: Int
    )

    fun plan(
        presentationUs: List<Long>,
        durationMs: Long,
        target60Fps: Boolean,
        requestedMultiplier: Int
    ): Plan {
        require(durationMs in 1..MAX_DURATION_MS) {
            "Motion Lab supports complete clips up to 60 seconds; trim the source first"
        }
        require(presentationUs.size in 2..MAX_SOURCE_FRAMES) {
            "Motion Lab supports 2..$MAX_SOURCE_FRAMES input frames"
        }
        val sorted = presentationUs.sorted()
        val deltas = sorted.zipWithNext { a, b -> b - a }
        require(deltas.all { it > 0L }) { "Input has duplicate/non-increasing presentation timestamps" }
        val nominalPeriodUs = deltas.sorted()[deltas.size / 2].toDouble()
        val jitterUs = maxOf(3_000.0, nominalPeriodUs * 0.10)
        require(deltas.all { abs(it - nominalPeriodUs) <= jitterUs }) {
            "Variable-frame-rate video is not supported in this Motion Lab mode; convert to CFR first"
        }
        val expectedDurationUs = nominalPeriodUs * sorted.size
        require(abs(expectedDurationUs - durationMs * 1_000.0) <= maxOf(150_000.0, nominalPeriodUs * 2.0)) {
            "Source timestamps disagree with container duration; refusing an A/V timing change"
        }
        val sourceFps = 1_000_000.0 / nominalPeriodUs
        require(sourceFps in 10.0..120.0) { "Source FPS outside 10..120 supported range" }
        val factor = if (target60Fps) {
            val nearest = (60.0 / sourceFps).roundToInt()
            require(nearest in 2..4 && abs(sourceFps * nearest - 60.0) <= 1.2) {
                "Exact 60fps mode requires compatible 2x, 3x or 4x source cadence; choose 2x/4x instead"
            }
            nearest
        } else {
            require(requestedMultiplier == 2 || requestedMultiplier == 4) {
                "Choose the supported 2x or 4x multiplier"
            }
            requestedMultiplier
        }
        val outputFps = if (target60Fps) 60.0 else sourceFps * factor
        require(outputFps <= 120.0) { "Output would exceed the 120fps admission cap" }
        return Plan(sorted.size, sourceFps, factor, outputFps, sorted.size * factor)
    }
}
