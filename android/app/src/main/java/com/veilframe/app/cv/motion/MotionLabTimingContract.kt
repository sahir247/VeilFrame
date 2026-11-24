package com.veilframe.app.cv.motion

import kotlin.math.abs
import kotlin.math.roundToInt

/** Timestamp-aware resampling of decoded presentation-order frames to a CFR output grid. */
object MotionLabTimingContract {
    const val MAX_SOURCE_FRAMES = 1800
    const val MAX_DURATION_MS = 60_000L
    const val MAX_OUTPUT_FRAMES = 7200
    private const val MAX_GAP_US = 1_000_000L

    data class Plan(
        val sourceFrames: Int,
        val sourceFps: Double,
        val multiplier: Int,
        val outputFps: Double,
        val expectedOutputFrames: Int,
        val pairFractions: List<List<Double>>,
        val tailFrames: Int,
        val variableCadence: Boolean
    )

    fun plan(
        presentationUs: List<Long>,
        durationMs: Long,
        target60Fps: Boolean,
        requestedMultiplier: Int,
        videoDurationUs: Long? = null
    ): Plan {
        require(durationMs in 1..MAX_DURATION_MS) {
            "Motion Lab supports complete clips up to 60 seconds; trim the source first"
        }
        require(presentationUs.size in 2..MAX_SOURCE_FRAMES) {
            "Motion Lab supports 2..$MAX_SOURCE_FRAMES input frames"
        }
        require(presentationUs.all { it >= 0L }) { "Invalid source presentation timestamps" }
        val sorted = presentationUs.sorted()
        val first = sorted.first()
        val relative = sorted.map { it - first }
        val deltas = relative.zipWithNext { a,b -> b-a }
        require(deltas.all { it in 1..MAX_GAP_US }) {
            "Input has duplicate timestamps or an unsupported presentation gap"
        }
        val nominalPeriod = deltas.sorted()[deltas.size/2].toDouble()
        val variable = deltas.any { abs(it-nominalPeriod) > maxOf(3_000.0,nominalPeriod*0.10) }
        val containerUs = durationMs*1000L
        val durationUs = videoDurationUs ?: containerUs
        require(durationUs in 1..MAX_DURATION_MS*1000L &&
            abs(durationUs-containerUs) <= 200_000L) { "Video and container duration disagree" }
        require(relative.last() < durationUs && durationUs-relative.last() <= MAX_GAP_US) {
            "Source timestamps disagree with video duration"
        }
        val sourceFps = if (variable) sorted.size*1_000_000.0/durationUs else 1_000_000.0/nominalPeriod
        require(sourceFps in 10.0..120.01) { "Source average FPS outside supported range" }
        val factor = if (target60Fps) (60.0/sourceFps).roundToInt().coerceAtLeast(1) else {
            require(requestedMultiplier == 2 || requestedMultiplier == 4) { "Choose 2x or 4x" }
            requestedMultiplier
        }
        val outputFps = if (target60Fps) 60.0 else sourceFps*factor
        require(outputFps in 10.0..120.0) { "Output would exceed supported FPS range" }
        val count = (durationUs*outputFps/1_000_000.0).roundToInt()
        require(count in 2..MAX_OUTPUT_FRAMES) { "Output frame count exceeds timing budget" }
        val pairs = ArrayList<List<Double>>(relative.size-1)
        var next = 0
        for (i in 0 until relative.lastIndex) {
            val left = relative[i]
            val right = relative[i+1]
            val fractions = ArrayList<Double>()
            while (next < count) {
                val instant = next*1_000_000.0/outputFps
                if (instant >= right) break
                fractions.add(((instant-left)/(right-left)).coerceIn(0.0,1.0))
                next++
            }
            pairs.add(fractions)
        }
        return Plan(sorted.size,sourceFps,factor,outputFps,count,pairs,count-next,variable)
    }
}
