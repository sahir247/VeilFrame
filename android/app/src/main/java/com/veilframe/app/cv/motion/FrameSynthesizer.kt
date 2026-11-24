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
        val estimator: FlowEstimator? = null,
    ) {
        init {
            com.veilframe.app.cv.core.CvContracts.requirePositive(artifactFactor, "artifactFactor")
            com.veilframe.app.cv.core.CvContracts.requireInRange(minConfidence, 0.0, 1.0, "minConfidence")
        }
    }

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
        return withMotionMats {
            val flowFull = own(scaleFlowTo(flow, src.size()))
            val channels = split(flowFull)
            poolInstance.acquire(src.rows(), src.cols(), CvType.CV_32F).use { mapXLease ->
            poolInstance.acquire(src.rows(), src.cols(), CvType.CV_32F).use { mapYLease ->
            val mapX = mapXLease.mat
            val mapY = mapYLease.mat
            Core.multiply(channels[0], Scalar(s), mapX)
            Core.multiply(channels[1], Scalar(s), mapY)
            CoordinateGridCache.withGrid(src.rows(), src.cols()) { gridX, gridY ->
                Core.add(mapX, gridX, mapX)
                Core.add(mapY, gridY, mapY)
            }
            val out = own(Mat())
            Imgproc.remap(src, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            transfer(out)
            }
            }
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
    ): SynthesisResult = synthesizeInternal(frameA, frameB, t, options, null)

    /**
     * Synthesize a frame using a previously computed bidirectional flow.
     * The caller retains ownership of [flows]; we never release its native
     * Mats. This is crucial for 3×/4× Motion Lab and partial RIFE fallbacks.
     */
    fun synthesizeWithFlows(
        frameA: Mat,
        frameB: Mat,
        t: Double,
        flows: FlowEstimator.BidirectionalFlow,
        options: Options = Options(),
    ): SynthesisResult = synthesizeInternal(frameA, frameB, t, options, flows)

    private fun synthesizeInternal(
        frameA: Mat,
        frameB: Mat,
        t: Double,
        options: Options,
        preparedFlows: FlowEstimator.BidirectionalFlow?,
    ): SynthesisResult {
        require(t in 0.0..1.0) { "t must be in [0, 1]" }
        require(frameA.size() == frameB.size()) { "frames must have identical size" }

        if (t == 0.0) return passthrough(frameA)
        if (t == 1.0) return passthrough(frameB)

        val flows = preparedFlows ?: (options.estimator ?: FlowEstimator()).estimate(frameA, frameB)
        try {
            val forward = if (options.refineFlow) refineField(flows.forward) else flows.forward
            try {
                val backward = if (options.refineFlow) refineField(flows.backward) else flows.backward
                try {
                    val consistency = FlowConsistency.check(forward, backward)
                    try {
                        // Track every temporary Mat: the earlier success-only
                        // release list leaked native allocations if any OpenCV
                        // operation threw partway through synthesis.
                        val temporaryMats = ArrayList<Mat>()
                        fun own(mat: Mat): Mat = mat.also { temporaryMats.add(it) }
                        try {
                val warpedA = own(warpToward(frameA, forward, -t, options.pool))
                val warpedB = own(warpToward(frameB, backward, (1 - t), options.pool))

                // Artifact mask: disagreeing warps in supposedly consistent areas.
                val residual = own(Mat())
                Core.absdiff(warpedA, warpedB, residual)
                val residualGray = own(toGray(residual))
                val artifactMask = own(Mat())
                Imgproc.threshold(
                    residualGray,
                    artifactMask,
                    adaptiveArtifactThreshold(residualGray, options.artifactFactor),
                    255.0,
                    Imgproc.THRESH_BINARY,
                )

                // Full-resolution confidence/occlusion.
                val confidence = own(Mat())
                Imgproc.resize(consistency.confidence, confidence, frameA.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val occlusion = own(Mat())
                Imgproc.resize(consistency.occlusionMask, occlusion, frameA.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)

                // Fallback mask: occluded OR artifact OR low confidence.
                val fallbackMask = own(Mat())
                Core.max(occlusion, artifactMask, fallbackMask)
                val lowConfidence = own(Mat())
                Core.compare(confidence, ScalarOf(options.minConfidence), lowConfidence, Core.CMP_LT)
                Core.max(fallbackMask, lowConfidence, fallbackMask)

                // Weights: temporal proximity * confidence, zeroed at fallback.
                val weightA = own(Mat())
                val weightB = own(Mat())
                val temporalA = ScalarOf((1 - t).coerceAtLeast(1e-3))
                val temporalB = ScalarOf(t.coerceAtLeast(1e-3))
                Core.multiply(confidence, temporalA, weightA)
                Core.multiply(confidence, temporalB, weightB)

                val validA = own(Mat())
                val validB = own(Mat())
                val zero = own(Mat.zeros(fallbackMask.size(), fallbackMask.type()))
                Core.compare(fallbackMask, zero, validA, Core.CMP_EQ) // valid where mask == 0
                validA.copyTo(validB)

                val weightA32 = own(Mat())
                val weightB32 = own(Mat())
                validA.convertTo(weightA32, CvType.CV_32F, 1.0 / 255.0)
                validB.convertTo(weightB32, CvType.CV_32F, 1.0 / 255.0)
                Core.multiply(weightA, weightA32, weightA)
                Core.multiply(weightB, weightB32, weightB)

                val blended = own(blendWeighted(warpedA, warpedB, weightA, weightB))

                // Fallback pixels take the temporally nearest source frame.
                val nearest = if (t < 0.5) frameA else frameB
                val out = own(Mat())
                blended.copyTo(out)
                nearest.copyTo(out, fallbackMask)

                val fallbackRatio = Core.countNonZero(fallbackMask).toDouble() /
                    (fallbackMask.rows().toDouble() * fallbackMask.cols())

                // Transfer only these three matrices to the result owner.
                // Every other intermediate (including the two warped images)
                // is reclaimed even if an earlier native step fails.
                temporaryMats.removeAll { it === out || it === confidence || it === fallbackMask }
                return SynthesisResult(out, confidence, fallbackMask, fallbackRatio)
                        } finally {
                            temporaryMats.forEach { it.release() }
                        }
                    } finally {
                        consistency.release()
                    }
                } finally {
                    if (backward !== flows.backward) backward.release()
                }
            } finally {
                if (forward !== flows.forward) forward.release()
            }
        } finally {
            // Borrowed flow matrices remain owned by Motion Lab's pair scope.
            if (preparedFlows == null) flows.release()
        }
    }

    private fun passthrough(frame: Mat): SynthesisResult = withMotionMats {
        val confidence = own(Mat.ones(frame.rows(),frame.cols(),CvType.CV_32F))
        val fallbackMask = own(Mat.zeros(frame.rows(),frame.cols(),CvType.CV_8U))
        val result = own(frame.clone())
        SynthesisResult(result,confidence,fallbackMask,0.0).also {
            transfer(result); transfer(confidence); transfer(fallbackMask)
        }
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
    private fun blendWeighted(a: Mat, b: Mat, wA: Mat, wB: Mat): Mat = withMotionMats {
        val a32 = own(Mat())
        val b32 = own(Mat())
        a.convertTo(a32, CvType.CV_32F)
        b.convertTo(b32, CvType.CV_32F)
        val wa3 = own(expandToChannels(wA, a.channels()))
        val wb3 = own(expandToChannels(wB, a.channels()))
        val sum = own(Mat())
        Core.add(wa3, wb3, sum)
        Core.add(sum, EPS, sum)
        val termA = own(Mat())
        val termB = own(Mat())
        Core.multiply(a32, wa3, termA)
        Core.multiply(b32, wb3, termB)
        val num = own(Mat())
        Core.add(termA, termB, num)
        val out32 = own(Mat())
        Core.divide(num, sum, out32)
        val out = own(Mat())
        out32.convertTo(out, a.type())
        transfer(out)
    }

    /** Replicates a single-channel CV_32F weight map across [channels] channels. */
    private fun expandToChannels(single: Mat, channels: Int): Mat {
        if (channels == 1) return single.clone()
        val list = ArrayList<Mat>(channels)
        repeat(channels) { list += single }
        return withMotionMats {
            val merged = own(Mat())
            Core.merge(list, merged)
            transfer(merged)
        }
    }

    private fun scaleFlowTo(flow: FlowEstimator.FlowField, size: org.opencv.core.Size): Mat {
        if (flow.flow.size() == size) return flow.flow.clone()
        return withMotionMats {
        val out = own(Mat())
        Imgproc.resize(flow.flow, out, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        // Flow vectors are displacements: scale them to the target resolution independently along X and Y.
        if (flow.scale.isUniform) {
            Core.multiply(out, ScalarOf(1.0 / flow.scale.x), out)
        } else {
            val channels = split(out)
            Core.multiply(channels[0], ScalarOf(1.0 / flow.scale.x), channels[0])
            Core.multiply(channels[1], ScalarOf(1.0 / flow.scale.y), channels[1])
            Core.merge(channels, out)
        }
        transfer(out)
        }
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
