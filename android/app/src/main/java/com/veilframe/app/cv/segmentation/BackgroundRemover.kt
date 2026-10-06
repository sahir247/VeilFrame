package com.veilframe.app.cv.segmentation

import org.opencv.core.Mat

/**
 * BackgroundRemover — production background removal.
 *
 * OpenCV does NOT pretend to be the segmentation model:
 *
 *   Image → [ForegroundSegmenter] (ONNX-side, e.g. MODNet) → foreground mask
 *         → MaskOps cleanup / morphology / hole filling / edge refinement /
 *           feathering / alpha compositing
 *         → transparent image
 *
 * The CV engine owns deterministic image manipulation around the model.
 */
object BackgroundRemover {

    /** Model-layer SPI (implemented in the app/ONNX layer, not here). */
    fun interface ForegroundSegmenter {
        /** 8-bit foreground mask (255 = foreground) for [image], or null on failure. */
        fun segment(image: Mat): Mat?
    }

    data class Options(
        val cleanupKernel: Int = 5,
        val fillHoles: Boolean = true,
        val refineEdges: Boolean = true,
        val featherRadius: Double = 2.0,
        /** Composite over a background colour instead of producing alpha. */
        val backgroundBgr: DoubleArray? = null,
    ) {
        init {
            com.veilframe.app.cv.core.CvContracts.requireOddPositive(cleanupKernel, "cleanupKernel")
            com.veilframe.app.cv.core.CvContracts.requireNonNegative(featherRadius, "featherRadius")
        }
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class RemovalResult(
        /** Final RGBA (transparent) image, or composited image when a background was given. */
        val output: Mat,
        /** The processed mask that produced [output]. */
        val mask: Mat,
        /** True when the segmenter produced nothing and the mask is all-foreground. */
        val degraded: Boolean,
    )

    fun removeBackground(
        image: Mat,
        segmenter: ForegroundSegmenter,
        options: Options = Options(),
    ): RemovalResult {
        com.veilframe.app.cv.core.CvContracts.requireNonEmpty(image, "image")
        val rawMask = resolveRawMask(image, segmenter)

        if (rawMask == null) {
            // Degraded path: identity mask — the image passes through untouched.
            val identity = Mat.ones(image.rows(), image.cols(), org.opencv.core.CvType.CV_8U)
            identity.setTo(org.opencv.core.Scalar(255.0))
            val output = image.clone()
            return RemovalResult(output, identity, degraded = true)
        }

        try {
            var mask = MaskOps.cleanup(rawMask, options.cleanupKernel)
            if (options.fillHoles) {
                val filled = MaskOps.fillHoles(mask)
                mask.release()
                mask = filled
            }
            if (options.refineEdges) {
                val refined = MaskOps.refineEdges(mask)
                mask.release()
                mask = refined
            }
            val feathered = MaskOps.feather(mask, options.featherRadius)
            mask.release()

            val output = options.backgroundBgr?.let { color ->
                val background = Mat.ones(image.rows(), image.cols(), image.type())
                background.setTo(org.opencv.core.Scalar(color.getOrElse(0) { 0.0 }, color.getOrElse(1) { 0.0 }, color.getOrElse(2) { 0.0 }))
                MaskOps.composite(image, background, feathered).also { background.release() }
            } ?: MaskOps.toTransparent(image, feathered)

            return RemovalResult(output, feathered, degraded = false)
        } finally {
            rawMask.release()
        }
    }

    internal fun resolveRawMask(
        image: Mat,
        segmenter: ForegroundSegmenter,
        shapeValidator: (mask: Mat, image: Mat) -> Boolean = { m, img ->
            !m.empty() && m.rows() == img.rows() && m.cols() == img.cols() && m.type() == org.opencv.core.CvType.CV_8UC1
        },
        maskReleaser: (Mat) -> Unit = { it.release() },
    ): Mat? = try {
        val m = segmenter.segment(image)
        if (m != null) {
            if (shapeValidator(m, image)) {
                m
            } else {
                maskReleaser(m)
                null
            }
        } else {
            null
        }
    } catch (e: Exception) {
        // Segmenter plugin failure: degrade to identity mask.
        // Fatal errors (OOM, LinkageError, etc.) propagate — they are not caught here.
    }
}
