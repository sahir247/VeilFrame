package com.veilframe.app.cv.motion

import kotlin.math.abs
import kotlin.math.roundToInt

/** Timestamp-aware interpolation: explicit 60 fps CFR or cadence-preserving VFR multipliers. */
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
        val variableCadence: Boolean,
        val preservedPresentationUs: List<Long> = emptyList(),
        val preservedDurationUs: Long? = null
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
        val variable = deltas.any { abs(it-nominalPeriod) > 2.0 }
        val containerUs = durationMs*1000L
        val durationUs = videoDurationUs ?: containerUs
        require(durationUs in 1..MAX_DURATION_MS*1000L &&
            abs(durationUs-containerUs) <= 200_000L) { "Video and container duration disagree" }
        require(relative.last() < durationUs && durationUs-relative.last() <= MAX_GAP_US) {
            "Source timestamps disagree with video duration"
        }
        // MediaExtractor exposes integer microseconds. A real 30 fps track can
        // therefore have a 33333 us median and appear slightly faster than 30.
        // Snap only a CFR boundary within one microsecond of quantization.
        fun atPeriodBoundary(fps: Double): Boolean =
            !variable && abs(nominalPeriod - 1_000_000.0/fps) <= 1.0
        val measuredFps = if (variable) sorted.size*1_000_000.0/durationUs else 1_000_000.0/nominalPeriod
        val sourceFps = when {
            atPeriodBoundary(10.0) -> 10.0
            atPeriodBoundary(120.0) -> 120.0
            else -> measuredFps
        }
        require(sourceFps in 10.0..120.0) { "Source average FPS outside supported range" }
        val factor = if (target60Fps) (60.0/sourceFps).roundToInt().coerceAtLeast(1) else {
            require(requestedMultiplier == 2 || requestedMultiplier == 4) { "Choose 2x or 4x" }
            requestedMultiplier
        }
        val outputFps = when {
            target60Fps -> 60.0
            atPeriodBoundary(120.0/factor) -> 120.0
            else -> sourceFps*factor
        }
        require(outputFps in 10.0..120.0) { "Output would exceed supported FPS range" }
        if (variable && !target60Fps) {
            val boundaries = relative + durationUs
            val count = relative.size * factor
            require(count in 2..MAX_OUTPUT_FRAMES) { "Output frame count exceeds timing budget" }
            val outputPts = ArrayList<Long>(count)
            for (i in relative.indices) {
                val delta = boundaries[i+1]-boundaries[i]
                require(delta >= factor) { "Source interval is too short for distinct interpolated timestamps" }
                repeat(factor) { sub -> outputPts.add(boundaries[i]+delta*sub/factor) }
            }
            val fractions = List(relative.size-1) { i ->
                List(factor) { sub ->
                    (outputPts[i*factor+sub]-relative[i]).toDouble()/(relative[i+1]-relative[i])
                }
            }
            return Plan(sorted.size,sourceFps,factor,outputFps,count,fractions,factor,true,outputPts,durationUs)
        }
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
