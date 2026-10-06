package com.veilframe.app.cv.segmentation

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * MaskOps — deterministic mask post-processing around a segmentation model.
 *
 * OpenCV is NOT the segmentation model. The model layer (ONNX Runtime, e.g.
 * MODNet) produces a raw foreground mask; these primitives clean it up:
 *
 *   mask cleanup → morphology → hole filling → edge refinement → feathering →
 *   alpha compositing
 */
object MaskOps {

    /** Morphological cleanup: removes speckles and closes pinholes. */
    fun cleanup(mask: Mat, kernelSize: Int = 5): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(kernelSize.toDouble(), kernelSize.toDouble()),
        )
        val out = Mat()
        try {
            Imgproc.morphologyEx(mask, out, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, kernel)
            return out
        } finally {
            kernel.release()
        }
    }

    /**
     * Fills interior holes via border flood-fill on the INVERTED mask:
     * background reachable from the image border is painted; anything the flood
     * cannot reach is an enclosed hole and is restored to foreground.
     *
     * Precondition: [mask] must be a BINARY single-channel 8-bit mask
     * (0 = background, 255 = foreground). Soft alpha masks are not supported —
     * run this BEFORE [refineEdges]/[feather].
     */
    fun fillHoles(mask: Mat): Mat {
        require(mask.type() == CvType.CV_8UC1) { "fillHoles expects a single-channel 8-bit mask, got type ${mask.type()}" }
        val inverted = Mat()
        val padded = Mat()
        val floodMask = Mat.zeros(mask.rows() + 4, mask.cols() + 4, CvType.CV_8U)
        try {
            Core.bitwise_not(mask, inverted)
            // 1px border filled with 255: guarantees a background-valued seed at
            // (0,0) even when foreground touches the frame.
            Core.copyMakeBorder(
                inverted, padded, 1, 1, 1, 1,
                Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0),
            )
            Imgproc.floodFill(
                padded,
                floodMask,
                org.opencv.core.Point(0.0, 0.0),
                org.opencv.core.Scalar(128.0),
            )
            // Now: reachable background = 128, holes = 255, foreground = 0.
            val holesPadded = Mat()
            Imgproc.threshold(padded, holesPadded, 254.0, 255.0, Imgproc.THRESH_BINARY)
            val holes = Mat(
                holesPadded,
                org.opencv.core.Rect(1, 1, mask.cols(), mask.rows()),
            ).clone()
            holesPadded.release()
            val out = mask.clone()
            Core.bitwise_or(out, holes, out) // foreground ∪ enclosed holes
            holes.release()
            return out
        } finally {
            inverted.release()
            padded.release()
            floodMask.release()
        }
    }

    /**
     * Edge refinement via signed distance — softens the boundary band WITHOUT
     * eroding the subject (the previous distance-threshold version removed the
     * boundary band and shrank hair/fingers/thin objects).
     *
     * alpha = clamp(0.5 + signedDistance / (2 * band), 0, 1)
     * The zero-crossing stays exactly on the original boundary.
     *
     * Precondition: [mask] must be BINARY single-channel 8-bit (the distance
     * transform measures distance to the boundary of a binary region; feeding
     * soft alpha here silently produces wrong distances).
     */
    fun refineEdges(mask: Mat, bandPx: Int = 3): Mat {
        require(mask.type() == CvType.CV_8UC1) { "refineEdges expects a single-channel 8-bit mask, got type ${mask.type()}" }
        val inside = Mat()
        val inverted = Mat()
        val outside = Mat()
        try {
            Imgproc.distanceTransform(mask, inside, Imgproc.DIST_L2, 3)
            Core.bitwise_not(mask, inverted)
            Imgproc.distanceTransform(inverted, outside, Imgproc.DIST_L2, 3)
            // signed distance: positive inside foreground, negative outside.
            val signed = Mat()
            Core.subtract(inside, outside, signed)
            val scaled = Mat()
            signed.convertTo(scaled, CvType.CV_32F, 1.0 / (2.0 * bandPx.coerceAtLeast(1)), 0.5)
            val clamped = Mat()
            Core.max(scaled, org.opencv.core.Scalar(0.0), clamped)
            Core.min(clamped, org.opencv.core.Scalar(1.0), clamped)
            val out = Mat()
            clamped.convertTo(out, CvType.CV_8U, 255.0)
            signed.release()
            scaled.release()
            clamped.release()
            return out
        } finally {
            inside.release()
            inverted.release()
            outside.release()
        }
    }

    /** Feathering: soft alpha edge of [radius] pixels. */
    fun feather(mask: Mat, radius: Double = 2.0): Mat {
        val out = Mat()
        Imgproc.GaussianBlur(mask, out, Size(0.0, 0.0), radius)
        return out
    }

    /**
     * Alpha compositing: [foreground] over [background] using [mask] (255 =
     * foreground). Both inputs must share size; output is BGRA or BGR matching
     * [foreground].
     */
    fun composite(foreground: Mat, background: Mat, mask: Mat): Mat {
        require(foreground.size() == background.size()) { "foreground/background size mismatch" }
        val alpha = Mat()
        val fg = Mat()
        val bg = Mat()
        try {
            mask.convertTo(alpha, CvType.CV_32F, 1.0 / 255.0)
            val alphaCh = if (foreground.channels() == 1) alpha else {
                val list = ArrayList<Mat>(foreground.channels())
                repeat(foreground.channels()) { list += alpha }
                val merged = Mat()
                Core.merge(list, merged)
                merged
            }
            foreground.convertTo(fg, CvType.CV_32F)
            background.convertTo(bg, CvType.CV_32F)
            Core.multiply(fg, alphaCh, fg)
            val invAlpha = Mat()
            val ones = Mat.ones(alpha.size(), alpha.type())
            Core.subtract(ones, alpha, invAlpha)
            ones.release()
            val invCh = if (foreground.channels() == 1) invAlpha else {
                val list = ArrayList<Mat>(foreground.channels())
                repeat(foreground.channels()) { list += invAlpha }
                val merged = Mat()
                Core.merge(list, merged)
                merged
            }
            Core.multiply(bg, invCh, bg)
            Core.add(fg, bg, fg)
            val out = Mat()
            fg.convertTo(out, foreground.type())
            if (alphaCh !== alpha) alphaCh.release()
            if (invCh !== invAlpha) invAlpha.release()
            invAlpha.release()
            return out
        } finally {
            alpha.release()
            fg.release()
            bg.release()
        }
    }

    /** Transparent PNG-style output: BGRA with [mask] as alpha channel. */
    fun toTransparent(foreground: Mat, mask: Mat): Mat {
        val channels = ArrayList<Mat>()
        val fgBgr = if (foreground.channels() == 4) {
            val bgr = Mat()
            Imgproc.cvtColor(foreground, bgr, Imgproc.COLOR_BGRA2BGR)
            bgr
        } else if (foreground.channels() == 3) {
            foreground
        } else {
            val bgr = Mat()
            Imgproc.cvtColor(foreground, bgr, Imgproc.COLOR_GRAY2BGR)
            bgr
        }
        Core.split(fgBgr, channels)
        channels += mask
        val out = Mat()
        Core.merge(channels, out)
        if (fgBgr !== foreground) fgBgr.release()
        channels.take(3).forEach { it.release() }
        return out
    }
}
