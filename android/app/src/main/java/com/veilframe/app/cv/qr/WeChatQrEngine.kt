package com.veilframe.app.cv.qr

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.wechat_qrcode.WeChatQRCode

/**
 * WeChatQrEngine — the PRIMARY QR decoding engine for VeilFrame.
 *
 * Wraps `cv::wechat_qrcode::WeChatQRCode` (WeChat detector + super-resolution
 * models). This is the engine budgeted around the OpenCV dependency, and it is
 * the engine the QR validator/release gate exercises — not a proxy decoder.
 *
 * Deliberately NOT primary:
 *   - `cv::QRCodeDetector` — see [QrCodeDetectorDiagnostic], diagnostic use only.
 * Deliberately secondary:
 *   - Google ML Kit Barcode Scanning — fallback when WeChat fails to decode.
 *
 * Lifecycle: [install] once (VeilFrameApplication.onCreate). Model files are
 * extracted from `assets/cv/wechat_qr` to app storage because the Caffe loader
 * requires real file paths. All decode calls are serialized on [lock] because
 * the native WeChatQRCode instance is not thread-safe.
 */
class WeChatQrEngine private constructor(
    private val detector: WeChatQRCode?,
    val superResolutionEnabled: Boolean,
    val availability: Availability,
    /**
     * Non-null when the explicit [org.opencv.android.OpenCVLoader.initLocal]
     * call failed. Diagnostic only — the engine may still work when the
     * OpenCVInitProvider already loaded the native library.
     */
    val initError: Throwable? = null,
) {
    enum class Availability {
        /** Detector + super-resolution models loaded. */
        FULL,

        /** Detector loaded without super-resolution models. */
        DETECTOR_ONLY,

        /** Engine could not be initialised (native/model failure). */
        UNAVAILABLE,
    }

    val isAvailable: Boolean get() = detector != null

    private val lock = Any()

    /**
     * Decodes all QR codes visible in [image] (8-bit gray/BGR/BGRA).
     * Returns detections with payload and corner points (TL, TR, BR, BL order
     * is NOT guaranteed by WeChat; callers that need ordering should use
     * com.veilframe.app.cv.geometry.Geometry.orderCorners).
     */
    /**
     * Decodes all QR codes visible in [image] (8-bit gray/BGR/BGRA).
     * Convenience wrapper over [decodeReport] that discards diagnostics.
     */
    fun decode(image: Mat): List<QrCvDetection> = decodeReport(image).detections

    /**
     * Decodes and reports WHY nothing was found: a native/engine failure is
     * [QrDecodeReport.engineError], a clean scan with no QR is simply empty.
     * "Engine failed" and "no QR in frame" must never be indistinguishable —
     * release validation of artistic QR depends on that difference.
     */
    fun decodeReport(image: Mat): QrDecodeReport {
        val engine = detector ?: return QrDecodeReport(
            detections = emptyList(),
            engineError = initError ?: IllegalStateException("WeChat QR engine unavailable (availability=$availability)"),
        )
        if (image.empty()) return QrDecodeReport(emptyList(), null)
        return synchronized(lock) {
            val points = ArrayList<Mat>()
            var engineError: Throwable? = null
            val texts: List<String> = try {
                engine.detectAndDecode(image, points)
            } catch (e: org.opencv.core.CvException) {
                engineError = e
                emptyList()
            } catch (e: Exception) {
                engineError = e
                emptyList()
            }
            if (texts.isNullOrEmpty()) {
                points.forEach { runCatching { it.release() } }
                return@synchronized QrDecodeReport(emptyList(), engineError)
            }
            val detections = texts.mapIndexedNotNull { index, text ->
                if (text.isNullOrEmpty()) return@mapIndexedNotNull null
                val corners = points.getOrNull(index)?.let { cornersOf(it) }
                QrCvDetection(
                    rawValue = text,
                    corners = corners,
                    source = QrDecodeSource.WECHAT,
                    confidence = null,
                )
            }.also {
                points.forEach { p -> runCatching { p.release() } }
            }
            QrDecodeReport(detections, engineError)
        }
    }

    private fun cornersOf(pointsMat: Mat): List<Point>? {
        return try {
            val elements = pointsMat.total().toInt()
            val channels = pointsMat.channels()
            if (elements <= 0 || channels < 2) {
                null
            } else {
                val data = DoubleArray(elements * channels)
                pointsMat.get(0, 0, data)
                (0 until elements).map { i -> Point(data[i * channels], data[i * channels + 1]) }
            }
        } catch (e: org.opencv.core.CvException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val ASSET_DIR = "cv/wechat_qr"
        private const val DETECTOR_PROTOTXT = "detect.prototxt"
        private const val DETECTOR_CAFFEMODEL = "detect.caffemodel"
        private const val SR_PROTOTXT = "sr.prototxt"
        private const val SR_CAFFEMODEL = "sr.caffemodel"

        /** Expected cryptographic SHA-256 hashes for all bundled WeChat QR Caffe models. */
        val EXPECTED_MODEL_HASHES = mapOf(
            DETECTOR_CAFFEMODEL to "cc49b8c9babaf45f3037610fe499df38c8819ebda29e90ca9f2e33270f6ef809",
            DETECTOR_PROTOTXT to "e8acfc395caf443a47f15686a9b9207b36cb8f7e6ceb8fbaf6466665e68a9466",
            SR_CAFFEMODEL to "e5d36889d8e6ef2f1c1f515f807cec03979320ac81792cd8fb927c31fd658ae3",
            SR_PROTOTXT to "8ae41acba97e8b4a8e741ee350481e49b8e01d787193f470a4c95ee1c02d5b61",
        )

        internal fun sha256Of(file: File): String? = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    digest.update(buf, 0, r)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }

        @Volatile
        private var instance: WeChatQrEngine? = null

        /** Extracts models and initialises the engine. Idempotent and cheap after the first call. */
        @JvmStatic
        @Synchronized
        fun install(context: Context): WeChatQrEngine {
            instance?.let { return it }
            val appContext = context.applicationContext
            // Belt & braces: the OpenCVInitProvider loads the native library at
            // app start, but explicit init keeps engine setup self-contained.
            // The outcome is RECORDED, not swallowed: a failed initLocal() is a
            // diagnosis fact even when the provider already loaded the lib.
            com.veilframe.app.cv.core.CvRuntime.initialize()
            val initError: Throwable? = com.veilframe.app.cv.core.CvRuntime.initError
            val engine = try {
                val detectorModels = extractModel(appContext, DETECTOR_PROTOTXT) to
                    extractModel(appContext, DETECTOR_CAFFEMODEL)
                val srModels = extractModel(appContext, SR_PROTOTXT) to
                    extractModel(appContext, SR_CAFFEMODEL)

                if (detectorModels.first == null || detectorModels.second == null) {
                    WeChatQrEngine(null, false, Availability.UNAVAILABLE, initError)
                } else {
                    val srAvailable = srModels.first != null && srModels.second != null
                    // SR is configured purely by constructor model paths in 4.14
                    // (no setUseSRModule API).
                    val detector = WeChatQRCode(
                        detectorModels.first!!.absolutePath,
                        detectorModels.second!!.absolutePath,
                        srModels.first?.absolutePath ?: "",
                        srModels.second?.absolutePath ?: "",
                    )
                    WeChatQrEngine(
                        detector = detector,
                        superResolutionEnabled = srAvailable,
                        availability = if (srAvailable) Availability.FULL else Availability.DETECTOR_ONLY,
                        initError = initError,
                    )
                }
            } catch (e: LinkageError) {
                // Native OpenCV missing, JNI symbols absent, or ABI mismatch.
                WeChatQrEngine(null, false, Availability.UNAVAILABLE, initError ?: e)
            } catch (e: org.opencv.core.CvException) {
                WeChatQrEngine(null, false, Availability.UNAVAILABLE, initError ?: e)
            } catch (e: Exception) {
                // Corrupt models, IO errors, or initialization failure.
                WeChatQrEngine(null, false, Availability.UNAVAILABLE, initError ?: e)
            }
            instance = engine
            return engine
        }

        /** The installed engine, or an UNAVAILABLE placeholder when [install] never ran. */
        @JvmStatic
        fun get(): WeChatQrEngine = instance ?: WeChatQrEngine(null, false, Availability.UNAVAILABLE)

        fun isAvailable(): Boolean = get().isAvailable

        @JvmStatic
        internal fun resetForTests() {
            instance = null
        }

        /**
         * Copies one model asset to app storage. Returns null when the asset is
         * absent (e.g. stripped build) — callers degrade gracefully.
         *
         * Atomic: the copy lands on a .tmp file and is renamed into place only
         * after the size check passes, so a process death mid-copy can never
         * leave a corrupt model at the final path.
         */
        private fun extractModel(context: Context, name: String): File? {
            val dir = File(context.filesDir, "cv_models/wechat_qr")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, name)
            val expectedHash = EXPECTED_MODEL_HASHES[name]

            // If target already exists and passes exact SHA-256 integrity, reuse immediately
            if (target.exists() && target.length() > 0L && expectedHash != null) {
                if (sha256Of(target).equals(expectedHash, ignoreCase = true)) {
                    return target
                }
            }

            val tmp = File(dir, "$name.tmp")
            return try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val expectedLen = context.assets.open("$ASSET_DIR/$name").use { input ->
                    FileOutputStream(tmp).use { out ->
                        val fd = out.fd
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                        }
                        out.flush()
                        try { fd.sync() } catch (_: Exception) {}
                        written
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                val hashValid = expectedHash == null || actualHash.equals(expectedHash, ignoreCase = true)

                if (expectedLen > 0 && tmp.length() == expectedLen && hashValid) {
                    try {
                        java.nio.file.Files.move(
                            tmp.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(
                            tmp.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    target
                } else {
                    if (tmp.exists()) tmp.delete()
                    null
                }
            } catch (e: java.io.IOException) {
                if (tmp.exists()) tmp.delete()
                null
            } catch (e: Exception) {
                if (tmp.exists()) tmp.delete()
                null
            }
        }
    }
}

/** Where a QR payload was decoded. */
enum class QrDecodeSource {
    /** cv::wechat_qrcode::WeChatQRCode — primary engine. */
    WECHAT,

    /** Google ML Kit Barcode Scanning — secondary fallback. */
    ML_KIT,

    /** ZXing Java — generator-side/legacy coverage only. */
    ZXING,

    /** cv::QRCodeDetector — diagnostic use only, never authoritative. */
    OPENCV_DIAGNOSTIC,
}

/**
 * Unified QR detection result across engines.
 *
 * The rest of VeilFrame must never see three different decoder APIs — every
 * decoder (WeChat, ML Kit, ZXing, diagnostic) is normalised into this shape.
 */
data class QrCvDetection(
    val rawValue: String,
    val corners: List<Point>?,
    val source: QrDecodeSource,
    val confidence: Double? = null,
)

/**
 * Outcome of one WeChat decode attempt.
 *
 * [engineError] non-null means the native engine failed (missing symbols,
 * corrupt model, JNI fault) — NOT that the frame lacked a QR code. Diagnostic
 * callers should surface this instead of reporting a bare "no QR found".
 */
data class QrDecodeReport(
    val detections: List<QrCvDetection>,
    val engineError: Throwable?,
) {
    val engineHealthy: Boolean get() = engineError == null
}
