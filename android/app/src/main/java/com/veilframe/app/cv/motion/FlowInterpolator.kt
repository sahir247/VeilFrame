package com.veilframe.app.cv.motion

import org.opencv.core.Mat

/**
 * FlowInterpolator — sequence-level frame-rate synthesis.
 *
 * `30 FPS  A → B → C` becomes `60 FPS  A → A' → B → B' → C` by generating real
 * intermediate frames from dense optical flow ([FrameSynthesizer]), never by
 * duplicating frames.
 *
 * Every output frame carries EXPLICIT metadata ([OutputFrame]) — whether it is
 * a source frame or a synthetic one, which source pair it sits between, its t
 * and its fallback ratio. Relationships are never reconstructed by positional
 * guessing across the interleaved output list.
 */
object FlowInterpolator {

    /**
     * One entry of the output sequence.
     *
     * @param sourceIndex for source frames: index into the input list.
     *                    for synthetic frames: index of the LEFT source frame.
     * @param interpolationT 0..1 position between sources; null for source frames.
     * @param fallbackRatio artifact/fallback ratio from synthesis; null for
     *                      source frames.
     */
    data class OutputFrame(
        val frame: Mat,
        val isSynthetic: Boolean,
        val sourceIndex: Int,
        val interpolationT: Double? = null,
        val fallbackRatio: Double? = null,
    )

    data class InterpolationReport(
        val outputs: List<OutputFrame>,
    ) {
        /** Convenience view; owners must release every Mat in it. */
        val frames: List<Mat> get() = outputs.map { it.frame }
    }

    /**
     * Inserts [intermediatesPerGap] synthetic frames between each input pair.
     * Input frames are CLONED into the output sequence — callers must release.
     */
    fun interpolateSequence(
        frames: List<Mat>,
        intermediatesPerGap: Int = 1,
        options: FrameSynthesizer.Options = FrameSynthesizer.Options(),
    ): InterpolationReport {
        require(frames.isNotEmpty()) { "no frames to interpolate" }
        require(intermediatesPerGap >= 0) { "intermediatesPerGap must be >= 0" }
        if (frames.size == 1 || intermediatesPerGap == 0) {
            return InterpolationReport(frames.mapIndexed { index, mat ->
                OutputFrame(mat.clone(), isSynthetic = false, sourceIndex = index)
            })
        }

        val outputs = mutableListOf<OutputFrame>()
        for (i in 0 until frames.size - 1) {
            val a = frames[i]
            val b = frames[i + 1]
            outputs += OutputFrame(a.clone(), isSynthetic = false, sourceIndex = i)
            for (step in 1..intermediatesPerGap) {
                val t = step.toDouble() / (intermediatesPerGap + 1)
                val result = FrameSynthesizer.synthesize(a, b, t, options)
                outputs += OutputFrame(
                    frame = result.frame,
                    isSynthetic = true,
                    sourceIndex = i,
                    interpolationT = t,
                    fallbackRatio = result.fallbackRatio,
                )
                result.confidence.release()
                result.fallbackMask.release()
            }
        }
        outputs += OutputFrame(
            frame = frames.last().clone(),
            isSynthetic = false,
            sourceIndex = frames.size - 1,
        )
        return InterpolationReport(outputs)
    }

    /** Doubles frame rate: A, A', B, B', C … */
    fun doubleFrameRate(
        frames: List<Mat>,
        options: FrameSynthesizer.Options = FrameSynthesizer.Options(),
    ): InterpolationReport = interpolateSequence(frames, intermediatesPerGap = 1, options = options)

    /**
     * Quality protection: synthetic frames with fallbackRatio above
     * [maxFallbackRatio] are replaced by the TEMPORALLY NEAREST source frame
     * (explicitly derived from sourceIndex + interpolationT — never by
     * positional guessing across the interleaved list).
     */
    fun rejectPoorFrames(
        report: InterpolationReport,
        sourceFrames: List<Mat>,
        maxFallbackRatio: Double = 0.35,
    ): InterpolationReport {
        if (sourceFrames.isEmpty()) return report
        val outputs = report.outputs.map { output ->
            val fallback = output.fallbackRatio
            if (output.isSynthetic && fallback != null && fallback > maxFallbackRatio) {
                val nearestIndex = if ((output.interpolationT ?: 0.5) < 0.5) {
                    output.sourceIndex
                } else {
                    output.sourceIndex + 1
                }.coerceIn(0, sourceFrames.size - 1)
                output.frame.release()
                output.copy(
                    frame = sourceFrames[nearestIndex].clone(),
                    isSynthetic = false,
                    interpolationT = null,
                    fallbackRatio = fallback,
                )
            } else {
                output
            }
        }
        return InterpolationReport(outputs)
    }
}
