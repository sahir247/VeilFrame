package com.veilframe.app.cv.document

import com.veilframe.app.cv.preprocess.Preprocessor
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * DocumentScanner — detection + perspective correction + enhancement.
 *
 * Pipeline (detection runs at working resolution, warp at full resolution):
 *
 *   Camera/Image → downscale → grayscale → Gaussian blur → Canny → contours →
 *   polygon approximation → quadrilateral → perspective transform →
 *   full-resolution refinement → document enhancement
 */
object DocumentScanner {

    /** Output flavour applied after the warp. */
    enum class DocumentMode {
        /** Warped pixels, untouched. */
        ORIGINAL,

        /** Adaptive-threshold binary — receipts, printed text. */
        BLACK_AND_WHITE,

        /** Single channel. */
        GRAYSCALE,

        /** CLAHE local contrast + mild denoise — scans with uneven lighting. */
        ENHANCED,

        /** Warm-ish, sharpened, contrast-boosted — thermal receipts. */
        RECEIPT,

        /** ID / document mode: strong contrast, sharp text, minimal colour shift. */
        ID_DOCUMENT,
    }

    data class ScanResult(
        val warped: Mat,
        /** TL, TR, BR, BL in source coordinates. */
        val corners: List<org.opencv.core.Point>,
        val confidence: Double,
        val mode: DocumentMode,
    )

    data class Options(
        val mode: DocumentMode = DocumentMode.ENHANCED,
        val workingMaxEdge: Int = 1280,
        val minCoverage: Double = 0.08,
    )

    /**
     * Scans a document out of [source].
     * Returns null when no plausible document quadrilateral was found — callers
     * can then fall back to a full-frame enhancement.
     */
    fun scan(source: Mat, options: Options = Options()): ScanResult? {
        require(!source.empty()) { "source is empty" }
        val detection = com.veilframe.app.cv.geometry.QuadDetector.detect(
            source,
            workingMaxEdge = options.workingMaxEdge,
            minCoverage = options.minCoverage,
        ) ?: return null

        val warped = try {
            // Detection corners are already in SOURCE coordinates (QuadDetector
            // scales them back); warp directly — no second rescale.
            com.veilframe.app.cv.geometry.PerspectiveCorrector.correct(
                source,
                detection.corners,
            )
        } catch (e: org.opencv.core.CvException) {
            // Native warp failure (e.g. degenerate quad from noise): no scan result.
            return null
        } catch (e: IllegalArgumentException) {
            // Degenerate corner geometry rejected by PerspectiveCorrector.
            return null
        }
        var warpedToRelease: Mat? = warped
        try {
            val enhanced = enhance(warped, options.mode)
            if (enhanced === warped) {
                warpedToRelease = null
            }
            return ScanResult(
                warped = enhanced,
                corners = detection.corners,
                confidence = detection.confidence,
                mode = options.mode,
            )
        } finally {
            warpedToRelease?.release()
        }
    }

    /**
     * Full-frame fallback when no quad was detected: applies the mode
     * enhancement to the whole image (still useful for flatbed-like photos).
     */
    fun enhanceFullFrame(source: Mat, mode: DocumentMode): Mat = enhance(source.clone(), mode)

    /** Applies a document enhancement mode in place and returns [image]. */
    fun enhance(
        image: Mat,
        mode: DocumentMode,
        context: com.veilframe.app.cv.core.CvContext? = null,
    ): Mat = when (mode) {
        DocumentMode.ORIGINAL -> image

        DocumentMode.GRAYSCALE -> {
            context?.ensureActive()
            val shadowFree = Preprocessor.removeShadows(image, kernelSize = 25)
            context?.ensureActive()
            val gray = Preprocessor.grayscale(shadowFree)
            val normalized = Preprocessor.normalize(gray, clipLimit = 2.0)
            val sharpened = Preprocessor.sharpen(normalized, amount = 0.4, radius = 1.0)
            sharpened.copyTo(image)
            shadowFree.release()
            gray.release()
            normalized.release()
            sharpened.release()
            image
        }

        DocumentMode.BLACK_AND_WHITE -> {
            context?.ensureActive()
            val shadowFree = Preprocessor.removeShadows(image, kernelSize = 25)
            context?.ensureActive()
            val gray = Preprocessor.grayscale(shadowFree)
            val binary = Preprocessor.adaptiveThreshold(gray, blockSize = 31, c = 10.0)
            binary.copyTo(image)
            shadowFree.release()
            gray.release()
            binary.release()
            image
        }

        DocumentMode.ENHANCED -> {
            context?.ensureActive()
            // 1. Remove uneven shadows & background illumination gradient
            val shadowFree = Preprocessor.removeShadows(image, kernelSize = 25)
            context?.ensureActive()
            // 2. Normalize contrast via CLAHE on L-channel
            val normalized = Preprocessor.normalize(shadowFree, clipLimit = 2.2)
            context?.ensureActive()
            // 3. Bilateral filter to smooth paper grain while retaining crisp character edges
            val denoised = Preprocessor.denoise(normalized, strength = 3, method = com.veilframe.app.cv.preprocess.DenoiseMethod.BILATERAL)
            context?.ensureActive()
            // 4. Controlled unsharp masking to make printed & handwritten text pop
            val sharpened = com.veilframe.app.cv.sharpen.SmartSharpener.sharpen(
                denoised,
                com.veilframe.app.cv.sharpen.SmartSharpener.Params(amount = 0.5, radius = 1.0, threshold = 2.0),
            )
            sharpened.copyTo(image)
            shadowFree.release()
            normalized.release()
            denoised.release()
            sharpened.release()
            image
        }

        DocumentMode.RECEIPT -> {
            context?.ensureActive()
            val shadowFree = Preprocessor.removeShadows(image, kernelSize = 25)
            context?.ensureActive()
            val normalized = Preprocessor.normalize(shadowFree, clipLimit = 2.8)
            context?.ensureActive()
            val sharpened = Preprocessor.sharpen(normalized, amount = 0.7, radius = 1.0)
            val warmed = Mat()
            sharpened.convertTo(warmed, -1, 1.05, 4.0)
            warmed.copyTo(image)
            shadowFree.release()
            normalized.release()
            sharpened.release()
            warmed.release()
            image
        }

        DocumentMode.ID_DOCUMENT -> {
            context?.ensureActive()
            val shadowFree = Preprocessor.removeShadows(image, kernelSize = 25)
            context?.ensureActive()
            val normalized = Preprocessor.normalize(shadowFree, clipLimit = 1.8)
            context?.ensureActive()
            val sharpened = com.veilframe.app.cv.sharpen.SmartSharpener.sharpen(
                normalized,
                com.veilframe.app.cv.sharpen.SmartSharpener.Params(amount = 0.7, radius = 1.0, threshold = 2.5),
            )
            sharpened.copyTo(image)
            shadowFree.release()
            normalized.release()
            sharpened.release()
            image
        }
    }

    /**
     * Detects 4 document corners in [source] image coordinates.
     * When [blurredMat] and [edgesMat] are provided from a pool, zero intermediate allocations occur.
     * Returns empty list if no valid quadrilateral was detected.
     */
    fun findCorners(
        source: Mat,
        blurredMat: Mat? = null,
        edgesMat: Mat? = null,
        context: com.veilframe.app.cv.core.CvContext? = null,
    ): List<org.opencv.core.Point> {
        val detection = com.veilframe.app.cv.geometry.QuadDetector.detectWithBuffers(
            source = source,
            blurredMat = blurredMat,
            edgesMat = edgesMat,
            context = context,
        )
        return detection?.corners ?: emptyList()
    }

    /**
     * Warps [source] by perspective transformation based on [corners].
     */
    fun warpPerspective(source: Mat, corners: List<org.opencv.core.Point>): Mat {
        return com.veilframe.app.cv.geometry.PerspectiveCorrector.correct(source, corners)
    }

    /**
     * Enhances [source] according to [mode] and returns a new Mat.
     */
    fun process(
        source: Mat,
        mode: DocumentMode,
        context: com.veilframe.app.cv.core.CvContext? = null,
    ): Mat {
        context?.ensureActive() // B6
        val cloned = source.clone()
        return enhance(cloned, mode, context)
    }
}
