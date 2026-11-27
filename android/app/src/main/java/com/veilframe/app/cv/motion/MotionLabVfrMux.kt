package com.veilframe.app.cv.motion

import kotlin.math.abs

/** Assign encoded packets to the planned source interval subdivisions.
 * libx264 without B frames produces packets in presentation order. MP4
 * derives nonterminal sample durations from consecutive DTS; specify the
 * last packet duration explicitly. Use a balanced expression tree per
 * source interval rather than one nested branch per output frame.
 */
internal object MotionLabVfrMux {
    fun arguments(plan: MotionLabTimingContract.Plan): Array<String> {
        val duration = plan.preservedDurationUs ?: return emptyArray()
        val pts = plan.preservedPresentationUs
        val factor = plan.multiplier
        require(factor in listOf(2,4) && pts.size == plan.sourceFrames*factor &&
            pts.size == plan.expectedOutputFrames && pts.first() == 0L)
        val starts = pts.filterIndexed { index,_ -> index%factor == 0 } + duration
        require(starts.zipWithNext().all { (a,b) -> b-a >= factor })
        fun expression(lo: Int, hi: Int): String {
            if (hi-lo == 1) {
                val delta = starts[lo+1]-starts[lo]
                return "${starts[lo]}+floor(mod(N,$factor)*$delta/$factor)"
            }
            val mid = (lo+hi)/2
            return "if(lt(N,${mid*factor}),${expression(lo,mid)},${expression(mid,hi)})"
        }
        val formula = expression(0,plan.sourceFrames)
        // In-process FFmpegKit passes argv directly. These single quotes
        // protect commas from the bitstream-filter chain parser, not a shell.
        val lastDuration = duration-pts.last()
        require(lastDuration > 0)
        return arrayOf("-bf","0","-enc_time_base","1:1000000",
            "-bsf:v","setts=ts='$formula':duration=$lastDuration:time_base=1/1000000",
            "-video_track_timescale","1000000")
    }

    fun verify(presentationUs: List<Long>, durationUs: Long?, plan: MotionLabTimingContract.Plan) {
        val duration = plan.preservedDurationUs
        if (duration == null) {
            MotionLabSequenceVerifier.verifyPresentation(presentationUs,plan.expectedOutputFrames,plan.outputFps)
            return
        }
        val expected = plan.preservedPresentationUs
        check(presentationUs.size == expected.size) { "VFR output frame count changed" }
        // Exact microsecond MP4 track timebase; tolerate only extractor rounding.
        check(presentationUs.zip(expected).all { (actual,wanted) -> abs(actual-wanted) <= 1L } &&
            presentationUs.zipWithNext().all { (a,b) -> b>a }) { "VFR output presentation timeline changed" }
        check(durationUs != null && abs(durationUs-duration) <= 1L) { "VFR output track duration changed" }
    }

    fun durationSeconds(plan: MotionLabTimingContract.Plan): Double =
        plan.preservedDurationUs?.let { it/1_000_000.0 } ?: plan.expectedOutputFrames/plan.outputFps
}
