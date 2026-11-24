package com.veilframe.app.cv.motion

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Forward/backward consistency checking, occlusion detection and flow
 * refinement — the protections that separate a serious classical interpolation
 * from naive pixel warping.
 */
object FlowConsistency {

    data class ConsistencyResult(
        /** Endpoint error of the forward-backward check (CV_32F). */
        val errorMap: Mat,
        /** 255 where motion is occluded / flow is unreliable (CV_8U). */
        val occlusionMask: Mat,
        /** 0..1 blend confidence (CV_32F). */
        val confidence: Mat,
    ) {
        fun release() {
            errorMap.release()
            occlusionMask.release()
            confidence.release()
        }
    }

    /**
     * Checks `F_ab(q) + F_ba(q + F_ab(q)) ≈ 0`.
     * Large residual → flow is inconsistent (typically occlusion).
     */
    fun check(forward: FlowEstimator.FlowField, backward: FlowEstimator.FlowField): ConsistencyResult = withMotionMats {
        val warpedBackward = own(warpFlow(backward.flow, forward.flow, scale = 1.0))
        val sum = own(Mat())
        val err = own(Mat())
        val occ = own(Mat())
        val conf = own(Mat())
        Core.add(forward.flow, warpedBackward, sum)
        release(warpedBackward)
        val channels = split(sum)
        Core.magnitude(channels[0], channels[1], err)
        channels.forEach { release(it) }
        release(sum)

        val dispChannels = split(forward.flow)
        val dispMagnitude = own(Mat())
        Core.magnitude(dispChannels[0], dispChannels[1], dispMagnitude)
        dispChannels.forEach { release(it) }
        val thresholdMap = own(Mat())
        Core.add(dispMagnitude, Scalar1(), thresholdMap)
        release(dispMagnitude)
        Core.multiply(thresholdMap, Scalar05(), thresholdMap)
        Core.compare(err, thresholdMap, occ, Core.CMP_GT)
        Core.divide(err, thresholdMap, conf)
        release(thresholdMap)
        val ones = own(Mat.ones(conf.size(), conf.type()))
        Core.subtract(ones, conf, conf)
        Core.max(conf, Scalar0(), conf)
        Core.min(conf, Scalar1(), conf)
        ConsistencyResult(err, occ, conf).also {
            transfer(err); transfer(occ); transfer(conf)
        }
    }

    /**
     * Edge-aware flow refinement: median smoothing on flat regions, preserved
     * motion boundaries (flow-gradient mask), so warps stay sharp at edges.
     */
    fun refine(flow: Mat, boundaryStrength: Double = 0.7): Mat {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(flow, "flow")
        com.veilframe.app.cv.core.CvContracts.requireFlow32FC2(flow, "flow")
        com.veilframe.app.cv.core.CvContracts.requireInRange(boundaryStrength, 0.0, 1.0, "boundaryStrength")
        return withMotionMats {
            val smoothed = own(Mat())
            val boundaryMask = own(Mat())
            val refined = own(Mat())
            val channels = split(flow)
            val smoothedChannels = channels.map { channel ->
                val out = own(Mat())
                Imgproc.medianBlur(channel, out, 5)
                out
            }
            Core.merge(smoothedChannels, smoothed)
            smoothedChannels.forEach { release(it) }

            // Motion-boundary mask from the gradients of Fx, Fy AND |F|.
            // |F| alone misses directional discontinuities (|A| ≈ |B| but
            // direction A ≠ direction B) — those motion boundaries would be
            // smoothed away by the median pass. Combining all three gradients
            // keeps them.
            val boundaryGrad = own(Mat())
            for (channelIndex in 0..2) {
                val src = if (channelIndex < 2) {
                    channels[channelIndex]
                } else {
                    val magnitude = own(Mat())
                    Core.magnitude(channels[0], channels[1], magnitude)
                    magnitude
                }
                val gradX = own(Mat())
                val gradY = own(Mat())
                Imgproc.Sobel(src, gradX, CvType.CV_32F, 1, 0, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                Imgproc.Sobel(src, gradY, CvType.CV_32F, 0, 1, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                val gradMag = own(Mat())
                Core.magnitude(gradX, gradY, gradMag)
                if (channelIndex == 0) {
                    gradMag.copyTo(boundaryGrad)
                } else {
                    Core.max(boundaryGrad, gradMag, boundaryGrad)
                }
                release(gradX)
                release(gradY)
                release(gradMag)
                if (channelIndex == 2) release(src) // the |F| magnitude Mat
            }
            Core.normalize(boundaryGrad, boundaryMask, 0.0, 1.0, Core.NORM_MINMAX)
            channels.forEach { release(it) }
            release(boundaryGrad)

            // refined = smoothed * (1 - strength*mask) + flow * strength*mask
            val maskWeighted = own(Mat())
            boundaryMask.convertTo(maskWeighted, CvType.CV_32F, boundaryStrength)
            val invMask = own(Mat())
            val ones = own(Mat.ones(boundaryMask.size(), CvType.CV_32F))
            Core.subtract(ones, maskWeighted, invMask)
            release(ones)

            val flowChannels = split(flow)
            val smoothChannels = split(smoothed)
            for (i in flowChannels.indices) {
                val keepOriginal = own(Mat())
                val keepSmoothed = own(Mat())
                Core.multiply(flowChannels[i], maskWeighted, keepOriginal)
                Core.multiply(smoothChannels[i], invMask, keepSmoothed)
                Core.add(keepOriginal, keepSmoothed, flowChannels[i])
                release(keepOriginal)
                release(keepSmoothed)
                release(smoothChannels[i])
            }
            Core.merge(flowChannels, refined)
            flowChannels.forEach { release(it) }
            release(maskWeighted)
            release(invMask)
            transfer(refined)
        }
    }

    /** Warps [flow] by [byFlow] * [scale] using bilinear remap. */
    private fun warpFlow(
        flow: Mat,
        byFlow: Mat,
        scale: Double,
        pool: com.veilframe.app.cv.core.MatPool = com.veilframe.app.cv.core.MatPool.default,
    ): Mat {
        return withMotionMats {
            val channels = split(byFlow)
            pool.acquire(byFlow.rows(), byFlow.cols(), CvType.CV_32F).use { mapXLease ->
            pool.acquire(byFlow.rows(), byFlow.cols(), CvType.CV_32F).use { mapYLease ->
            val mapX = mapXLease.mat
            val mapY = mapYLease.mat
            Core.multiply(channels[0], ScalarOf(scale), mapX)
            Core.multiply(channels[1], ScalarOf(scale), mapY)
            CoordinateGridCache.withGrid(byFlow.rows(), byFlow.cols()) { gridX, gridY ->
                Core.add(mapX, gridX, mapX)
                Core.add(mapY, gridY, mapY)
            }
            val out = own(Mat())
            Imgproc.remap(flow, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            transfer(out)
            }
            }
        }
    }

    // Scalar helpers (avoid repeated allocation of Scalar objects).
    private fun Scalar1() = org.opencv.core.Scalar(1.0)
    private fun Scalar0() = org.opencv.core.Scalar(0.0)
    private fun ScalarOf(v: Double) = org.opencv.core.Scalar(v)
    private fun Scalar05() = org.opencv.core.Scalar(0.5)
}
