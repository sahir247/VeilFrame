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
    fun check(forward: FlowEstimator.FlowField, backward: FlowEstimator.FlowField): ConsistencyResult {
        val warpedBackward = warpFlow(backward.flow, forward.flow, scale = 1.0)
        val sum = Mat()
        val errorMap = Mat()
        val occlusionMask = Mat()
        val confidence = Mat()
        try {
            Core.add(forward.flow, warpedBackward, sum)
            // split + magnitude
            val channels = ArrayList<Mat>()
            Core.split(sum, channels)
            Core.magnitude(channels[0], channels[1], errorMap)
            channels.forEach { it.release() }

            // Threshold: relative to displacement + 1px tolerance.
            val dispChannels = ArrayList<Mat>()
            Core.split(forward.flow, dispChannels)
            val dispMagnitude = Mat()
            Core.magnitude(dispChannels[0], dispChannels[1], dispMagnitude)
            dispChannels.forEach { it.release() }
            val thresholdMap = Mat()
            Core.add(dispMagnitude, Scalar1(), thresholdMap)
            Core.multiply(thresholdMap, Scalar05(), thresholdMap)

            Core.compare(errorMap, thresholdMap, occlusionMask, Core.CMP_GT)

            // confidence = 1 - normalized error, clipped to 0..1
            Core.divide(errorMap, thresholdMap, confidence)
            val ones = Mat.ones(confidence.size(), confidence.type())
            Core.subtract(ones, confidence, confidence)
            Core.max(confidence, Scalar0(), confidence)
            Core.min(confidence, Scalar1(), confidence)
            ones.release()
            dispMagnitude.release()
            thresholdMap.release()

            return ConsistencyResult(errorMap, occlusionMask, confidence)
        } catch (t: Throwable) {
            errorMap.release()
            occlusionMask.release()
            confidence.release()
            throw t
        } finally {
            warpedBackward.release()
            sum.release()
        }
    }

    /**
     * Edge-aware flow refinement: median smoothing on flat regions, preserved
     * motion boundaries (flow-gradient mask), so warps stay sharp at edges.
     */
    fun refine(flow: Mat, boundaryStrength: Double = 0.7): Mat {
        val smoothed = Mat()
        val boundaryMask = Mat()
        val refined = Mat()
        try {
            val channels = ArrayList<Mat>()
            Core.split(flow, channels)
            val smoothedChannels = ArrayList<Mat>()
            for (channel in channels) {
                val out = Mat()
                Imgproc.medianBlur(channel, out, 5)
                smoothedChannels += out
            }
            Core.merge(smoothedChannels, smoothed)
            smoothedChannels.forEach { it.release() }

            // Motion-boundary mask from the gradients of Fx, Fy AND |F|.
            // |F| alone misses directional discontinuities (|A| ≈ |B| but
            // direction A ≠ direction B) — those motion boundaries would be
            // smoothed away by the median pass. Combining all three gradients
            // keeps them.
            val boundaryGrad = Mat()
            for (channelIndex in 0..2) {
                val src = if (channelIndex < 2) {
                    channels[channelIndex]
                } else {
                    val magnitude = Mat()
                    Core.magnitude(channels[0], channels[1], magnitude)
                    magnitude
                }
                val gradX = Mat()
                val gradY = Mat()
                Imgproc.Sobel(src, gradX, CvType.CV_32F, 1, 0, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                Imgproc.Sobel(src, gradY, CvType.CV_32F, 0, 1, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                val gradMag = Mat()
                Core.magnitude(gradX, gradY, gradMag)
                if (channelIndex == 0) {
                    gradMag.copyTo(boundaryGrad)
                } else {
                    Core.max(boundaryGrad, gradMag, boundaryGrad)
                }
                gradX.release()
                gradY.release()
                gradMag.release()
                if (channelIndex == 2) src.release() // the |F| magnitude Mat
            }
            Core.normalize(boundaryGrad, boundaryMask, 0.0, 1.0, Core.NORM_MINMAX)
            channels.forEach { it.release() }
            boundaryGrad.release()

            // refined = smoothed * (1 - strength*mask) + flow * strength*mask
            val maskWeighted = Mat()
            boundaryMask.convertTo(maskWeighted, CvType.CV_32F, boundaryStrength)
            val invMask = Mat()
            val ones = Mat.ones(boundaryMask.size(), CvType.CV_32F)
            Core.subtract(ones, maskWeighted, invMask)
            ones.release()

            val flowChannels = ArrayList<Mat>()
            val smoothChannels = ArrayList<Mat>()
            Core.split(flow, flowChannels)
            Core.split(smoothed, smoothChannels)
            for (i in flowChannels.indices) {
                val keepOriginal = Mat()
                val keepSmoothed = Mat()
                Core.multiply(flowChannels[i], maskWeighted, keepOriginal)
                Core.multiply(smoothChannels[i], invMask, keepSmoothed)
                Core.add(keepOriginal, keepSmoothed, flowChannels[i])
                keepOriginal.release()
                keepSmoothed.release()
                smoothChannels[i].release()
            }
            Core.merge(flowChannels, refined)
            flowChannels.forEach { it.release() }
            maskWeighted.release()
            invMask.release()
            return refined
        } finally {
            smoothed.release()
            boundaryMask.release()
        }
    }

    /** Warps [flow] by [byFlow] * [scale] using bilinear remap. */
    private fun warpFlow(
        flow: Mat,
        byFlow: Mat,
        scale: Double,
        pool: com.veilframe.app.cv.core.MatPool = com.veilframe.app.cv.core.MatPool.default,
    ): Mat {
        val channels = ArrayList<Mat>()
        Core.split(byFlow, channels)
        val mapXLease = pool.acquire(byFlow.rows(), byFlow.cols(), CvType.CV_32F)
        val mapYLease = pool.acquire(byFlow.rows(), byFlow.cols(), CvType.CV_32F)
        val gridXLease = pool.acquire(byFlow.rows(), byFlow.cols(), CvType.CV_32F)
        val gridYLease = pool.acquire(byFlow.rows(), byFlow.cols(), CvType.CV_32F)
        val mapX = mapXLease.mat
        val mapY = mapYLease.mat
        val gridX = gridXLease.mat
        val gridY = gridYLease.mat
        try {
            buildGrid(gridX, gridY)
            Core.multiply(channels[0], ScalarOf(scale), mapX)
            Core.multiply(channels[1], ScalarOf(scale), mapY)
            Core.add(mapX, gridX, mapX)
            Core.add(mapY, gridY, mapY)
            val out = Mat()
            Imgproc.remap(flow, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            return out
        } finally {
            channels.forEach { it.release() }
            mapXLease.close()
            mapYLease.close()
            gridXLease.close()
            gridYLease.close()
        }
    }

    private fun buildGrid(gridX: Mat, gridY: Mat) {
        val rows = gridX.rows()
        val cols = gridX.cols()
        val rowX = FloatArray(cols)
        val rowY = FloatArray(cols)
        for (x in 0 until cols) {
            rowX[x] = x.toFloat()
        }
        for (y in 0 until rows) {
            rowY.fill(y.toFloat())
            gridX.put(y, 0, rowX)
            gridY.put(y, 0, rowY)
        }
    }

    // Scalar helpers (avoid repeated allocation of Scalar objects).
    private fun Scalar1() = org.opencv.core.Scalar(1.0)
    private fun Scalar0() = org.opencv.core.Scalar(0.0)
    private fun ScalarOf(v: Double) = org.opencv.core.Scalar(v)
    private fun Scalar05() = org.opencv.core.Scalar(0.5)
}
