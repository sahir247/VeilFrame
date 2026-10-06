package com.veilframe.app.cv.motion

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Motion warping + intermediate-frame synthesis.
 *
 * For 30 FPS input `A → B → C` the synthesiser generates REAL intermediate
 * frames (`A → A' → B → B' → C`) — never duplicated frames.
 *
 * Required protections (all present):
 *   bidirectional flow          [FlowEstimator]
 *   forward/backward consistency[FlowConsistency.check]
 *   occlusion map               [FlowConsistency.check]
 *   multi-resolution flow       [FlowEstimator] working-resolution estimation +
 *                              DIS internal scales (not a custom interpolation
 *                              pyramid — see FlowEstimator docs)
 *   motion-boundary handling    [FlowConsistency.refine]
 *   edge-aware warping          [warpToward] bilinear + boundary-weighted blend
 *   artifact detection          [synthesize] residual masking
 *   fallback to source frame    where confidence is poor
 */
object FrameSynthesizer {

    data class SynthesisResult(
        val frame: Mat,
        /** Blend confidence 0..1 (CV_32F, single channel). */
        val confidence: Mat,
        /** 255 where sources were occluded or artifacts forced a fallback. */
        val fallbackMask: Mat,
        /** Fraction of pixels that fell back to a source frame. */
        val fallbackRatio: Double,
    ) {
        fun release() {
            frame.release()
            confidence.release()
            fallbackMask.release()
        }
    }

    data class Options(
        /**
         * Artifact threshold = mean + factor·σ of the residual (|A' − B'|),
         * floored at [ARTIFACT_FLOOR]. Adaptive on purpose: a fixed global cut
         * misfires on textured/compression-heavy frames.
         */
        val artifactFactor: Double = 2.0,
        /** Confidence below this falls back to the temporally nearest frame. */
        val minConfidence: Double = 0.25,
        val refineFlow: Boolean = true,
        val pool: com.veilframe.app.cv.core.MatPool? = null,
    )

    /**
     * Backward-map warp: `out(q) = src(q + s * flow(q))`.
     * `s = -t` warps A toward time t; `s = (1 - t)` warps B toward time t.
     */
    fun warpToward(
        src: Mat,
        flow: FlowEstimator.FlowField,
        s: Double,
        pool: com.veilframe.app.cv.core.MatPool? = null,
    ): Mat {
        val poolInstance = pool ?: com.veilframe.app.cv.core.MatPool.default
        val flowFull = scaleFlowTo(flow, src.size())
        val channels = ArrayList<Mat>()
        Core.split(flowFull, channels)
        val mapXLease = poolInstance.acquire(src.rows(), src.cols(), CvType.CV_32F)
        val mapYLease = poolInstance.acquire(src.rows(), src.cols(), CvType.CV_32F)
        val gridXLease = poolInstance.acquire(src.rows(), src.cols(), CvType.CV_32F)
        val gridYLease = poolInstance.acquire(src.rows(), src.cols(), CvType.CV_32F)
        val mapX = mapXLease.mat
        val mapY = mapYLease.mat
        val gridX = gridXLease.mat
        val gridY = gridYLease.mat
        try {
            buildGrid(gridX, gridY)
            Core.multiply(channels[0], Scalar(s), mapX)
            Core.multiply(channels[1], Scalar(s), mapY)
            Core.add(mapX, gridX, mapX)
            Core.add(mapY, gridY, mapY)
            val out = Mat()
            Imgproc.remap(src, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            return out
        } finally {
            flowFull.release()
            channels.forEach { it.release() }
            mapXLease.close()
            mapYLease.close()
            gridXLease.close()
            gridYLease.close()
        }
    }

    /**
     * Synthesises the intermediate frame between [frameA] and [frameB] at
     * fraction [t] (0 = A, 1 = B).
     */
    fun synthesize(
        frameA: Mat,
        frameB: Mat,
        t: Double,
        options: Options = Options(),
    ): SynthesisResult {
        require(t in 0.0..1.0) { "t must be in [0, 1]" }
        require(frameA.size() == frameB.size()) { "frames must have identical size" }

        if (t == 0.0) return passthrough(frameA)
        if (t == 1.0) return passthrough(frameB)

        val estimator = FlowEstimator()
        val flows = estimator.estimate(frameA, frameB)
        try {
            val forward = if (options.refineFlow) refineField(flows.forward) else flows.forward
            val backward = if (options.refineFlow) refineField(flows.backward) else flows.backward

            val consistency = FlowConsistency.check(forward, backward)
            try {
                val warpedA = warpToward(frameA, forward, -t, options.pool)
                val warpedB = warpToward(frameB, backward, (1 - t), options.pool)

                // Artifact mask: disagreeing warps in supposedly consistent areas.
                val residual = Mat()
                Core.absdiff(warpedA, warpedB, residual)
                val residualGray = toGray(residual)
                val artifactMask = Mat()
                Imgproc.threshold(
                    residualGray,
                    artifactMask,
                    adaptiveArtifactThreshold(residualGray, options.artifactFactor),
                    255.0,
                    Imgproc.THRESH_BINARY,
                )

                // Full-resolution confidence/occlusion.
                val confidence = Mat()
                Imgproc.resize(consistency.confidence, confidence, frameA.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val occlusion = Mat()
                Imgproc.resize(consistency.occlusionMask, occlusion, frameA.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)

                // Fallback mask: occluded OR artifact OR low confidence.
                val fallbackMask = Mat()
                Core.max(occlusion, artifactMask, fallbackMask)
                val lowConfidence = Mat()
                Core.compare(confidence, ScalarOf(options.minConfidence), lowConfidence, Core.CMP_LT)
                Core.max(fallbackMask, lowConfidence, fallbackMask)

                // Weights: temporal proximity * confidence, zeroed at fallback.
                val weightA = Mat()
                val weightB = Mat()
                val temporalA = ScalarOf((1 - t).coerceAtLeast(1e-3))
                val temporalB = ScalarOf(t.coerceAtLeast(1e-3))
                Core.multiply(confidence, temporalA, weightA)
                Core.multiply(confidence, temporalB, weightB)

                val validA = Mat()
                val validB = Mat()
                val zero = Mat.zeros(fallbackMask.size(), fallbackMask.type())
                Core.compare(fallbackMask, zero, validA, Core.CMP_EQ) // valid where mask == 0
                validA.copyTo(validB)
                zero.release()

                val weightA32 = Mat()
                val weightB32 = Mat()
                validA.convertTo(weightA32, CvType.CV_32F, 1.0 / 255.0)
                validB.convertTo(weightB32, CvType.CV_32F, 1.0 / 255.0)
                Core.multiply(weightA, weightA32, weightA)
                Core.multiply(weightB, weightB32, weightB)

                val blended = blendWeighted(warpedA, warpedB, weightA, weightB)

                // Fallback pixels take the temporally nearest source frame.
                val nearest = if (t < 0.5) frameA else frameB
                val out = Mat()
                blended.copyTo(out)
                nearest.copyTo(out, fallbackMask)

                val fallbackRatio = Core.countNonZero(fallbackMask).toDouble() /
                    (fallbackMask.rows().toDouble() * fallbackMask.cols())

                residual.release()
                residualGray.release()
                artifactMask.release()
                occlusion.release()
                lowConfidence.release()
                validA.release()
                validB.release()
                weightA.release()
                weightB.release()
                weightA32.release()
                weightB32.release()
                blended.release()
                warpedA.release()
                warpedB.release()
                if (forward !== flows.forward) forward.release()
                if (backward !== flows.backward) backward.release()

                return SynthesisResult(out, confidence, fallbackMask, fallbackRatio)
            } finally {
                consistency.release()
            }
        } finally {
            flows.release()
        }
    }

    private fun passthrough(frame: Mat): SynthesisResult {
        val confidence = Mat.ones(frame.rows(), frame.cols(), CvType.CV_32F)
        val fallbackMask = Mat.zeros(frame.rows(), frame.cols(), CvType.CV_8U)
        // No pixels fell back — the output IS the source frame. Reporting 1.0
        // here made consumers believe every pixel was a fallback.
        return SynthesisResult(frame.clone(), confidence, fallbackMask, 0.0)
    }

    /** mean + factor·σ of the residual, never below the noise floor. */
    private fun adaptiveArtifactThreshold(residualGray: Mat, factor: Double): Double {
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        return try {
            Core.meanStdDev(residualGray, mean, std)
            val meanValue = mean.get(0, 0)[0]
            val stdValue = std.get(0, 0)[0]
            maxOf(ARTIFACT_FLOOR, meanValue + factor * stdValue)
        } finally {
            mean.release()
            std.release()
        }
    }

    private fun refineField(flow: FlowEstimator.FlowField): FlowEstimator.FlowField =
        FlowEstimator.FlowField(FlowConsistency.refine(flow.flow), flow.scale)

    /**
     * out = (A*wA + B*wB) / (wA + wB) per pixel; zero-weight pixels → 0.
     *
     * ALL arithmetic runs in CV_32F: Core.multiply/add/divide reject mixed
     * depths (a CV_8U channel against a CV_32F weight throws), and 8-bit
     * accumulation would clamp the weighted sums regardless.
     */
    private fun blendWeighted(a: Mat, b: Mat, wA: Mat, wB: Mat): Mat {
        val a32 = Mat()
        val b32 = Mat()
        a.convertTo(a32, CvType.CV_32F)
        b.convertTo(b32, CvType.CV_32F)
        val wa3 = expandToChannels(wA, a.channels())
        val wb3 = expandToChannels(wB, a.channels())
        try {
            val sum = Mat()
            Core.add(wa3, wb3, sum)
            // Tiny epsilon ONLY — a full 1.0 here halved the brightness of every
            // blended pixel (denominator was (wA + wB + 1)).
            Core.add(sum, EPS, sum)
            val termA = Mat()
            val termB = Mat()
            Core.multiply(a32, wa3, termA)
            Core.multiply(b32, wb3, termB)
            val num = Mat()
            Core.add(termA, termB, num)
            val out32 = Mat()
            Core.divide(num, sum, out32)
            val out = Mat()
            out32.convertTo(out, a.type())
            sum.release()
            termA.release()
            termB.release()
            num.release()
            out32.release()
            return out
        } finally {
            a32.release()
            b32.release()
            wa3.release()
            wb3.release()
        }
    }

    /** Replicates a single-channel CV_32F weight map across [channels] channels. */
    private fun expandToChannels(single: Mat, channels: Int): Mat {
        if (channels == 1) return single.clone()
        val list = ArrayList<Mat>(channels)
        repeat(channels) { list += single }
        val merged = Mat()
        Core.merge(list, merged)
        return merged
    }

    private fun scaleFlowTo(flow: FlowEstimator.FlowField, size: org.opencv.core.Size): Mat {
        if (flow.flow.size() == size) return flow.flow.clone()
        val out = Mat()
        Imgproc.resize(flow.flow, out, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        // Flow vectors are displacements: scale them to the target resolution.
        Core.multiply(out, ScalarOf(1.0 / flow.scale), out)
        return out
    }

    private fun buildGrid(gridX: Mat, gridY: Mat) {
        val rows = gridX.rows()
        val cols = gridX.cols()
        val rowX = FloatArray(cols)
        val rowY = FloatArray(cols)
        for (x in 0 until cols) rowX[x] = x.toFloat()
        for (y in 0 until rows) {
            rowY.fill(y.toFloat())
            gridX.put(y, 0, rowX)
            gridY.put(y, 0, rowY)
        }
    }

    private fun toGray(src: Mat): Mat = when (src.channels()) {
        1 -> src.clone()
        else -> {
            val out = Mat()
            Imgproc.cvtColor(src, out, if (src.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY)
            out
        }
    }

    private fun Scalar(v: Double) = org.opencv.core.Scalar(v)
    private fun ScalarOf(v: Double) = org.opencv.core.Scalar(v)

    private val EPS = org.opencv.core.Scalar(1e-6)

    /** Residual floor (8-bit levels) below which no artifact is declared. */
    private const val ARTIFACT_FLOOR = 8.0
}
