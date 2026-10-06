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
        GRAY_UPSCALE("grayscale + upscale"),
        THRESHOLD("contrast / adaptive threshold"),
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
     * Runs the escalation ladder on [image].
     *
     * @param expected when non-null, a decode with a different payload keeps
     *                 escalating (protects against stale/foreign QR in frame).
     */
    fun recover(
        image: Mat,
        expected: String? = null,
        secondary: SecondaryDecoder? = null,
    ): RecoveryResult {
        val log = mutableListOf<Stage>()
        val wechat = WeChatQrEngine.get()

        // Stage 0 — original.
        log += Stage.ORIGINAL
        firstMatch(wechat.decode(image), expected)?.let { return RecoveryResult(it, Stage.ORIGINAL, log) }

        // Stage 1 — grayscale + upscale.
        ensureActive()
        log += Stage.GRAY_UPSCALE
        val grayUp = grayUpscaled(image)
        firstMatch(wechat.decode(grayUp), expected)?.let {
            grayUp.release()
            return RecoveryResult(it, Stage.GRAY_UPSCALE, log)
        }

        // Stage 2 — contrast stretch + adaptive threshold.
        ensureActive()
        log += Stage.THRESHOLD
        val binary = enhancedBinary(grayUp)
        firstMatch(wechat.decode(binary), expected)?.let {
            grayUp.release()
            binary.release()
            return RecoveryResult(it, Stage.THRESHOLD, log)
        }

        // Stage 3 — perspective-corrected upscale of the binary (planar photos).
        ensureActive()
        log += Stage.PERSPECTIVE
        val perspective = perspectiveCorrected(image, binary)
        val perspectiveHit = firstMatch(wechat.decode(perspective), expected)
        grayUp.release()
        binary.release()
        perspective.release()
        perspectiveHit?.let { return RecoveryResult(it, Stage.PERSPECTIVE, log) }

        // Stage 4 — alternative preprocessing (inverted + noise-cleaned).
        ensureActive()
        log += Stage.ALTERNATIVE
        val alternative = alternativePreprocess(image)
        val alternativeHit = firstMatch(wechat.decode(alternative), expected)
        alternative.release()
        alternativeHit?.let { return RecoveryResult(it, Stage.ALTERNATIVE, log) }

        // Stage 5 — ML Kit fallback (secondary engine, last resort).
        if (secondary != null) {
            log += Stage.ML_KIT_FALLBACK
            val bitmap = com.veilframe.app.cv.core.BitmapBridge.toBitmap(image)
            val text = try {
                secondary.decode(bitmap)
            } catch (_: Throwable) {
                null
            }
            if (!text.isNullOrEmpty() && (expected == null || text == expected)) {
                return RecoveryResult(
                    QrCvDetection(text, null, QrDecodeSource.ML_KIT),
                    Stage.ML_KIT_FALLBACK,
                    log,
                )
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

    private fun grayUpscaled(image: Mat): Mat {
        val gray = Preprocessor.grayscale(image)
        // Cap the upscale: uncapped, `max(maxEdge, 640) * 2` turned a 4000px
        // source into an 8000px gray (4x the pixels) per recovery attempt.
        // 2048px on the long edge is plenty for module sampling.
        val targetEdge = minOf(
            maxOf(image.cols(), image.rows()).let { maxOf(it, 640) * 2 },
            MAX_UPSCALE_EDGE,
        )
        val up = Preprocessor.resize(gray, targetEdge, allowUpscale = true, interpolation = Imgproc.INTER_CUBIC)
        gray.release()
        return up
    }

    private fun enhancedBinary(gray: Mat): Mat {
        val clahe = Preprocessor.normalize(gray, clipLimit = 3.0)
        val binary = Preprocessor.adaptiveThreshold(clahe, blockSize = 31, c = 8.0)
        clahe.release()
        return binary
    }

    private fun perspectiveCorrected(original: Mat, binary: Mat): Mat {
        val detection = com.veilframe.app.cv.geometry.QuadDetector.detect(original)
            ?: return binary.clone()
        return try {
            com.veilframe.app.cv.geometry.PerspectiveCorrector.correct(
                original,
                detection.corners,
                outputSize = null,
                interpolation = Imgproc.INTER_CUBIC,
            )
        } catch (_: Throwable) {
            binary.clone()
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
