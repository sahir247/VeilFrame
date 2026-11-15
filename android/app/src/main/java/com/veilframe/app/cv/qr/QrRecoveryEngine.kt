package com.veilframe.app.cv.qr

import com.veilframe.app.cv.preprocess.Preprocessor
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * QrRecoveryEngine — staged decode escalation for artistic VeilFrame QR.
 *
 * Camera frames (and degraded exports) are expensive to brute-force, so the
 * engine escalates ONE stage at a time and stops at the first success:
 *
 *   Stage 0  original frame                 → WeChat
 *   Stage 1  grayscale + upscale            → WeChat
 *   Stage 2  contrast / adaptive threshold  → WeChat
 *   Stage 3  perspective correction + upscale → WeChat
 *   Stage 4  alternative preprocessing      → WeChat
 *   Stage 5  ML Kit fallback (secondary)    → ML Kit
 *
 * Each stage is bounded work; nothing runs "every variant on every frame".
 *
 * Camera-cost policy: live scanning paths must throttle this ladder — run it
 * only on candidate frames (after a cheap presence/ROI check) and at most every
 * Nth processed frame; ML Kit stays the last resort. The scanner already gates
 * processed frames to ~8–10 FPS; do not run the full ladder per camera frame
 * on top of that.
 */
object QrRecoveryEngine {

    /** Cap for the stage-1 upscale (px on the long edge) — bounds per-attempt cost. */
    private const val MAX_UPSCALE_EDGE = 2048

    enum class Stage(val label: String) {
        ORIGINAL("original"),
        DOWNSCALE_SMOOTH("downscale smooth (512px)"),
        GRAY_UPSCALE("grayscale + quiet-zone pad + upscale"),
        THRESHOLD("contrast / adaptive threshold"),
        GAUSSIAN_BRIDGE("gaussian bridge + threshold"),
        CHROMA_BRIDGE("chroma min + hole-closing bridge"),
        PERSPECTIVE("perspective correction + upscale"),
        ALTERNATIVE("alternative preprocessing"),
        ML_KIT_FALLBACK("ML Kit fallback"),
    }

    data class RecoveryResult(
        val detection: QrCvDetection?,
        val stageReached: Stage,
        val stageLog: List<Stage>,
    ) {
        val recovered: Boolean get() = detection != null
    }

    /** Secondary fallback surface — plugged in from the app layer (ML Kit). */
    fun interface SecondaryDecoder {
        fun decode(bitmap: android.graphics.Bitmap): String?
    }

    /**
     * Runs the WeChatQRCode recovery ladder on a [android.graphics.Bitmap], stopping at the first match.
     * Transparent pixels are composited onto white so transparent margins don't turn black in grayscale.
     */
    fun recoverWithWeChat(
        bitmap: android.graphics.Bitmap,
        expected: String? = null,
        cancellation: com.veilframe.app.cv.core.CvCancellation? = null,
    ): RecoveryResult {
        val wechat = WeChatQrEngine.get()
        if (!wechat.isAvailable || bitmap.isRecycled) {
            return RecoveryResult(null, Stage.ORIGINAL, emptyList())
        }
        val opaqueBmp = if (bitmap.hasAlpha() || bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            try {
                val copy = android.graphics.Bitmap.createBitmap(
                    bitmap.width,
                    bitmap.height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(copy)
                canvas.drawColor(android.graphics.Color.WHITE)
                val srcSoft = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                    bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                } else bitmap
                try {
                    if (srcSoft != null) canvas.drawBitmap(srcSoft, 0f, 0f, null)
                } finally {
                    if (srcSoft != null && srcSoft !== bitmap && !srcSoft.isRecycled) srcSoft.recycle()
                }
                copy
            } catch (_: Throwable) {
                null
            }
        } else null

        val working = opaqueBmp ?: bitmap
        val mat = try {
            com.veilframe.app.cv.core.BitmapBridge.toMat(working)
        } catch (_: Throwable) {
            if (opaqueBmp != null && !opaqueBmp.isRecycled) opaqueBmp.recycle()
            return RecoveryResult(null, Stage.ORIGINAL, emptyList())
        }
        return try {
            recover(mat, expected = expected, secondary = null, cancellation = cancellation)
        } finally {
            mat.release()
            if (opaqueBmp != null && !opaqueBmp.isRecycled) opaqueBmp.recycle()
        }
    }

    /**
     * Runs the escalation ladder on [image].
     *
     * @param expected when non-null, a decode with a different payload keeps
     *                 escalating (protects against stale/foreign QR in frame).
     * @param cancellation optional per-invocation cancellation handle.
     */
    fun recover(
        image: Mat,
        expected: String? = null,
        secondary: SecondaryDecoder? = null,
        cancellation: com.veilframe.app.cv.core.CvCancellation? = null,
    ): RecoveryResult {
        cancelled = false
        val checkActive = {
            cancellation?.ensureActive()
            ensureActive()
        }
        val log = mutableListOf<Stage>()
        val wechat = WeChatQrEngine.get()

        // Stage 0 — original.
        log += Stage.ORIGINAL
        firstMatch(wechat.decode(image), expected)?.let { return RecoveryResult(it, Stage.ORIGINAL, log) }

        // Stage 1 — 512px downscale smoothing (coalesces 3x3 halftone dots & subpixel noise).
        if (maxOf(image.cols(), image.rows()) > 576) {
            checkActive()
            log += Stage.DOWNSCALE_SMOOTH
            val smooth = downscaleSmooth(image, 512)
            val smoothHit = firstMatch(wechat.decode(smooth), expected)
            smooth.release()
            smoothHit?.let { return RecoveryResult(it, Stage.DOWNSCALE_SMOOTH, log) }
        }

        // Stage 2 — grayscale + white quiet-zone pad + cubic upscale (helps tight borders, D25 corners, thin lines).
        checkActive()
        log += Stage.GRAY_UPSCALE
        val grayUp = grayPaddedUpscaled(image)
        firstMatch(wechat.decode(grayUp), expected)?.let {
            grayUp.release()
            return RecoveryResult(it, Stage.GRAY_UPSCALE, log)
        }

        // Stage 3 — contrast stretch + adaptive threshold.
        checkActive()
        log += Stage.THRESHOLD
        val binary = enhancedBinary(grayUp)
        firstMatch(wechat.decode(binary), expected)?.let {
            grayUp.release()
            binary.release()
            return RecoveryResult(it, Stage.THRESHOLD, log)
        }

        // Stage 4 — gaussian bridge (merges broken hairline runs, bubble islands, and halftone dots).
        checkActive()
        log += Stage.GAUSSIAN_BRIDGE
        val bridged = gaussianBridged(grayUp)
        val bridgeHit = firstMatch(wechat.decode(bridged), expected)
        bridged.release()
        if (bridgeHit != null) {
            grayUp.release()
            binary.release()
            return RecoveryResult(bridgeHit, Stage.GAUSSIAN_BRIDGE, log)
        }

        // Stage 4b — chroma min-channel threshold + mild bridge (recovers pastel & hollow Bubble / Line QRs
        // without filling in thin ambient bubble centers on light modules).
        if (image.channels() >= 3) {
            checkActive()
            log += Stage.CHROMA_BRIDGE
            val chromaMat = chromaThresholded(image, gaussianBridge = false)
            val chromaHit = try {
                firstMatch(wechat.decode(chromaMat), expected)
            } finally {
                chromaMat.release()
            }
            if (chromaHit != null) {
                grayUp.release()
                binary.release()
                return RecoveryResult(chromaHit, Stage.CHROMA_BRIDGE, log)
            }

            checkActive()
            val chromaBlurMat = chromaThresholded(image, gaussianBridge = true)
            val chromaBlurHit = try {
                firstMatch(wechat.decode(chromaBlurMat), expected)
            } finally {
                chromaBlurMat.release()
            }
            if (chromaBlurHit != null) {
                grayUp.release()
                binary.release()
                return RecoveryResult(chromaBlurHit, Stage.CHROMA_BRIDGE, log)
            }
        }

        // Stage 5 — perspective-corrected upscale (planar photos & tilted quads).
        checkActive()
        val perspective = perspectiveCorrected(image)
        if (perspective != null) {
            log += Stage.PERSPECTIVE
            val perspectiveHit = firstMatch(wechat.decode(perspective), expected)
            perspective.release()
            if (perspectiveHit != null) {
                grayUp.release()
                binary.release()
                return RecoveryResult(perspectiveHit, Stage.PERSPECTIVE, log)
            }
        }
        grayUp.release()
        binary.release()

        // Stage 6 — alternative preprocessing (inverted + noise-cleaned).
        checkActive()
        log += Stage.ALTERNATIVE
        val alternative = alternativePreprocess(image)
        val alternativeHit = firstMatch(wechat.decode(alternative), expected)
        alternative.release()
        alternativeHit?.let { return RecoveryResult(it, Stage.ALTERNATIVE, log) }

        // Stage 7 — ML Kit fallback (secondary engine, last resort).
        if (secondary != null) {
            checkActive()
            log += Stage.ML_KIT_FALLBACK
            val bitmap = com.veilframe.app.cv.core.BitmapBridge.toBitmap(image)
            try {
                val text = try {
                    secondary.decode(bitmap)
                } catch (e: Exception) {
                    // ML Kit plugin failure: treat as no result, not a system error.
                    // Errors (OOM, etc.) propagate.
                    null
                }
                if (!text.isNullOrEmpty() && (expected == null || text == expected)) {
                    return RecoveryResult(
                        QrCvDetection(text, null, QrDecodeSource.ML_KIT),
                        Stage.ML_KIT_FALLBACK,
                        log,
                    )
                }
            } finally {
                bitmap.recycle()
            }
        }

        return RecoveryResult(null, log.last(), log)
    }

    private fun firstMatch(detections: List<QrCvDetection>, expected: String?): QrCvDetection? =
        detections.firstOrNull { expected == null || it.rawValue == expected }

    @Volatile
    private var cancelled = false

    /** Cooperative cancellation for live scanning paths. */
    fun cancel() {
        cancelled = true
    }

    fun resume() {
        cancelled = false
    }

    private fun ensureActive() {
        if (cancelled) throw RecoveryCancelled()
    }

    class RecoveryCancelled : RuntimeException("QR recovery cancelled")

    // ------------------------------------------------------------- stages

    private fun downscaleSmooth(image: Mat, targetMaxEdge: Int): Mat {
        val gray = Preprocessor.grayscale(image)
        val resized = Preprocessor.resize(gray, targetMaxEdge, allowUpscale = false, interpolation = Imgproc.INTER_AREA)
        gray.release()
        return resized
    }

    private fun grayPaddedUpscaled(image: Mat): Mat {
        val gray = Preprocessor.grayscale(image)
        val pad = (minOf(gray.cols(), gray.rows()) * 0.12).toInt().coerceIn(24, 96)
        val padded = Mat()
        Core.copyMakeBorder(gray, padded, pad, pad, pad, pad, Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0))
        gray.release()
        val targetEdge = minOf(
            maxOf(padded.cols(), padded.rows()).let { maxOf(it, 640) * 2 },
            MAX_UPSCALE_EDGE,
        )
        val up = Preprocessor.resize(padded, targetEdge, allowUpscale = true, interpolation = Imgproc.INTER_CUBIC)
        padded.release()
        return up
    }

    private fun gaussianBridged(gray: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.85)
        val thresh = Mat()
        Imgproc.threshold(blurred, thresh, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        blurred.release()
        return thresh
    }

    /**
     * Recovers pastel colored QRs (e.g. Bubble sky-blue #8ED1FC outlines on #FFFFFF):
     * extracts per-pixel min(R,G,B) on a quiet-zone-padded 512px frame and applies a high
     * cutoff threshold (or adaptive threshold) while keeping hollow ambient bubble interiors
     * intact so light modules are not flipped to black.
     */
    private fun chromaThresholded(image: Mat, gaussianBridge: Boolean): Mat {
        val scaled = Preprocessor.resize(image, 512, allowUpscale = true, interpolation = Imgproc.INTER_AREA)
        val channels = ArrayList<Mat>(4)
        val minRgb = Mat()
        val padded = Mat()
        val bin = Mat()
        try {
            Core.split(scaled, channels)
            Core.min(channels[0], channels[1], minRgb)
            Core.min(minRgb, channels[2], minRgb)

            val pad = (minOf(minRgb.cols(), minRgb.rows()) * 0.12).toInt().coerceIn(24, 64)
            Core.copyMakeBorder(minRgb, padded, pad, pad, pad, pad, Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0))

            val corner = padded.submat(0, minOf(12, padded.rows()), 0, minOf(12, padded.cols()))
            val bgMean = Core.mean(corner).`val`[0]
            corner.release()

            val sourceForThresh = if (gaussianBridge) {
                val blurred = Mat()
                Imgproc.GaussianBlur(padded, blurred, Size(3.0, 3.0), 0.9)
                blurred
            } else {
                padded
            }
            try {
                if (bgMean > 220.0) {
                    val cutoff = (bgMean - 22.0).coerceIn(170.0, 236.0)
                    Imgproc.threshold(sourceForThresh, bin, cutoff, 255.0, Imgproc.THRESH_BINARY)
                } else {
                    val norm = Mat()
                    Core.normalize(sourceForThresh, norm, 0.0, 255.0, Core.NORM_MINMAX)
                    Imgproc.adaptiveThreshold(norm, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 6.0)
                    norm.release()
                }
            } finally {
                if (sourceForThresh !== padded) sourceForThresh.release()
            }
            return bin.clone()
        } finally {
            scaled.release()
            for (c in channels) c.release()
            minRgb.release()
            padded.release()
            bin.release()
        }
    }

    private fun enhancedBinary(gray: Mat): Mat {
        val clahe = Preprocessor.normalize(gray, clipLimit = 3.0)
        val binary = Preprocessor.adaptiveThreshold(clahe, blockSize = 31, c = 8.0)
        clahe.release()
        return binary
    }

    private fun perspectiveCorrected(original: Mat): Mat? {
        val detection = com.veilframe.app.cv.geometry.QuadDetector.detect(original)
            ?: return null
        return try {
            com.veilframe.app.cv.geometry.PerspectiveCorrector.correct(
                original,
                detection.corners,
                outputSize = null,
                interpolation = Imgproc.INTER_CUBIC,
            )
        } catch (_: org.opencv.core.CvException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun alternativePreprocess(image: Mat): Mat {
        val gray = Preprocessor.grayscale(image)
        val denoised = Preprocessor.denoise(gray, strength = 3, method = com.veilframe.app.cv.preprocess.DenoiseMethod.MEDIAN)
        val inverted = Mat()
        Core.bitwise_not(denoised, inverted)
        val stretched = Mat()
        inverted.convertTo(stretched, CvType.CV_8UC1, 1.2, 10.0)
        val blurred = Mat()
        Imgproc.GaussianBlur(stretched, blurred, Size(3.0, 3.0), 0.0)
        gray.release()
        denoised.release()
        inverted.release()
        stretched.release()
        return blurred
    }
}
