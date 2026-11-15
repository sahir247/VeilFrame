package com.veilframe.app.qr.decoder

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Result of attempting to decode a QR code image.
 */
data class DecodeResult(
    val success: Boolean,
    val text: String? = null,
    val latencyMs: Long = 0,
    val error: String? = null,
    val decoderId: String = ""
)

/**
 * Universal interface for QR decoders.
 */
interface QrDecoder : java.io.Closeable {
    val id: String
    suspend fun decode(bitmap: Bitmap): DecodeResult
    suspend fun decodeMultiple(bitmap: Bitmap): List<DecodeResult> {
        val single = decode(bitmap)
        return if (single.success && single.text != null) listOf(single) else emptyList()
    }
    override fun close() {}
}

/**
 * Deterministic generator-side validator using ZXing Java.
 *
 * Configured per technical requirements:
 * - Thread-safe per-thread [MultiFormatReader] with decodeWithState().
 * - Sets [DecodeHintType.POSSIBLE_FORMATS] to QR_CODE.
 * - Sets [DecodeHintType.TRY_HARDER] to true.
 * - Strictly OMITS [DecodeHintType.PURE_BARCODE] because artistic QRs feature
 *   gradients, rounded modules, antialiasing, and 2.5D effects.
 */
class ZxingQrDecoder : QrDecoder {

    override val id: String = "ZXing-Java"

    private val threadLocalReader = ThreadLocal.withInitial {
        MultiFormatReader().apply {
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
            setHints(hints)
        }
    }

    override suspend fun decode(bitmap: Bitmap): DecodeResult {
        val startTime = System.currentTimeMillis()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val reader = threadLocalReader.get()!!

        // Pass 1: Standard HybridBinarizer (adaptive thresholding)
        try {
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            return DecodeResult(
                success = true,
                text = result.text,
                latencyMs = latency,
                decoderId = id
            )
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 2: GlobalHistogramBinarizer (resilient against soft gradients & noise)
        try {
            val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            return DecodeResult(
                success = true,
                text = result.text,
                latencyMs = latency,
                decoderId = id
            )
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 3: Inverted HybridBinarizer (dark backdrop or inverted luminance)
        try {
            val invertedSource = source.invert()
            val binaryBitmap = BinaryBitmap(HybridBinarizer(invertedSource))
            val result = reader.decodeWithState(binaryBitmap)
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            return DecodeResult(
                success = true,
                text = result.text,
                latencyMs = latency,
                decoderId = id
            )
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 4: Inverted GlobalHistogramBinarizer
        try {
            val invertedSource = source.invert()
            val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(invertedSource))
            val result = reader.decodeWithState(binaryBitmap)
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            return DecodeResult(
                success = true,
                text = result.text,
                latencyMs = latency,
                decoderId = id
            )
        } catch (e: Exception) {
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            return DecodeResult(
                success = false,
                text = null,
                latencyMs = latency,
                error = e.message ?: "ZXing decode failed",
                decoderId = id
            )
        }
    }

    override suspend fun decodeMultiple(bitmap: Bitmap): List<DecodeResult> {
        val startTime = System.currentTimeMillis()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val multiReader = QRCodeMultiReader()
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )

        val allResults = mutableListOf<DecodeResult>()

        fun tryMulti(binBitmap: BinaryBitmap): Boolean {
            return try {
                val results = multiReader.decodeMultiple(binBitmap, hints)
                val latency = System.currentTimeMillis() - startTime
                if (results != null && results.isNotEmpty()) {
                    for (r in results) {
                        if (!r.text.isNullOrEmpty()) {
                            allResults.add(
                                DecodeResult(
                                    success = true,
                                    text = r.text,
                                    latencyMs = latency,
                                    decoderId = id
                                )
                            )
                        }
                    }
                    true
                } else false
            } catch (_: Exception) {
                false
            }
        }

        // Pass 1: HybridBinarizer
        if (!tryMulti(BinaryBitmap(HybridBinarizer(source)))) {
            // Pass 2: GlobalHistogramBinarizer
            if (!tryMulti(BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                val inverted = source.invert()
                // Pass 3: Inverted Hybrid
                if (!tryMulti(BinaryBitmap(HybridBinarizer(inverted)))) {
                    // Pass 4: Inverted GlobalHistogram
                    tryMulti(BinaryBitmap(GlobalHistogramBinarizer(inverted)))
                }
            }
        }

        if (allResults.isNotEmpty()) {
            return allResults.distinctBy { it.text }
        }

        val single = decode(bitmap)
        return if (single.success && single.text != null) listOf(single) else emptyList()
    }

    override fun close() {
        threadLocalReader.remove()
    }
}

/**
 * Android offline scanner / validator wrapping Google ML Kit Barcode Scanning.
 */
class MlKitQrDecoder : QrDecoder {

    override val id: String = "MLKit"

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .build()
        BarcodeScanning.getClient(options)
    }

    data class DetectionPassResult(
        val decodeResult: DecodeResult,
        val candidateBoxes: List<android.graphics.Rect> = emptyList(),
        val runtimeUnavailable: Boolean = false
    )

    private fun isRuntimeUnavailableMessage(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        return msg.contains("MlKitContext", ignoreCase = true) ||
            msg.contains("not initialized", ignoreCase = true) ||
            msg.contains("dynamite", ignoreCase = true) ||
            msg.contains("UnsatisfiedLinkError", ignoreCase = true)
    }

    suspend fun decodeWithCandidateBoxes(bitmap: Bitmap): DetectionPassResult = suspendCancellableCoroutine { cont ->
        val startTime = System.currentTimeMillis()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val executor = Dispatchers.Default.asExecutor()
            val task = scanner.process(image)
            task.addOnSuccessListener(executor) { barcodes ->
                if (!cont.isActive) return@addOnSuccessListener
                val latency = System.currentTimeMillis() - startTime
                val found = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                val boxes = barcodes.mapNotNull { it.boundingBox }.filter { it.width() > 0 && it.height() > 0 }
                if (found?.rawValue != null) {
                    cont.resume(
                        DetectionPassResult(
                            decodeResult = DecodeResult(
                                success = true,
                                text = found.rawValue,
                                latencyMs = latency,
                                decoderId = id
                            ),
                            candidateBoxes = boxes
                        )
                    )
                } else {
                    cont.resume(
                        DetectionPassResult(
                            decodeResult = DecodeResult(
                                success = false,
                                text = null,
                                latencyMs = latency,
                                error = "No barcode found",
                                decoderId = id
                            ),
                            candidateBoxes = boxes
                        )
                    )
                }
            }
            task.addOnFailureListener(executor) { exception ->
                if (!cont.isActive) return@addOnFailureListener
                val latency = System.currentTimeMillis() - startTime
                val msg = exception.message ?: "ML Kit error"
                cont.resume(
                    DetectionPassResult(
                        decodeResult = DecodeResult(
                            success = false,
                            text = null,
                            latencyMs = latency,
                            error = msg,
                            decoderId = id
                        ),
                        runtimeUnavailable = isRuntimeUnavailableMessage(msg)
                    )
                )
            }
            cont.invokeOnCancellation {
                // Cooperative cancellation check in callbacks via cont.isActive
            }
        } catch (t: Throwable) {
            val latency = System.currentTimeMillis() - startTime
            val msg = t.message ?: "Failed to process bitmap"
            if (cont.isActive) {
                cont.resume(
                    DetectionPassResult(
                        decodeResult = DecodeResult(
                            success = false,
                            text = null,
                            latencyMs = latency,
                            error = msg,
                            decoderId = id
                        ),
                        runtimeUnavailable = t is LinkageError || isRuntimeUnavailableMessage(msg)
                    )
                )
            }
        }
    }

    override suspend fun decode(bitmap: Bitmap): DecodeResult = decodeWithCandidateBoxes(bitmap).decodeResult

    /**
     * Fallback verification pipeline mirroring QrScanner's multi-pass retry strategy:
     * 1. Direct bitmap (using enableAllPotentialBarcodes for ROI localization)
     * 2. Localized candidate ROI crops + 20% white quiet-zone margin
     * 3. Resolution normalization (512px halftone smoothing / 1024px upscale)
     * 4. Quiet-zone padded contrast-boosted bitmap
     * 5. Inverted luminance bitmap (dark-backdrop QRs)
     */
    suspend fun decodeWithRetries(
        bitmap: Bitmap,
        expectedContent: String? = null
    ): DetectionPassResult {
        fun matches(res: DecodeResult): Boolean =
            res.success && !res.text.isNullOrEmpty() && (expectedContent == null || res.text == expectedContent)

        val direct = decodeWithCandidateBoxes(bitmap)
        if (matches(direct.decodeResult) || direct.runtimeUnavailable) {
            return direct
        }

        // Retry 1: Candidate bounding-box ROI crops with white quiet-zone padding (QrScanner parity)
        for (box in direct.candidateBoxes.take(3)) {
            val roiBmp = cropWithQuietZone(bitmap, box) ?: continue
            try {
                val roiPass = decodeWithCandidateBoxes(roiBmp)
                if (matches(roiPass.decodeResult)) return roiPass
            } finally {
                if (roiBmp !== bitmap && !roiBmp.isRecycled) roiBmp.recycle()
            }
        }

        // Retry 2: 512px bilinear downscale (merges 3x3 halftone dots) or 1024px upscale (helps thin hairlines)
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val targetDim = when {
            maxDim > 640 -> 512
            maxDim < 720 -> 1024
            else -> null
        }
        if (targetDim != null) {
            val scaled = try {
                val scale = targetDim.toFloat() / maxDim
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } catch (_: Throwable) { null }
            if (scaled != null) {
                try {
                    val scaledPass = decodeWithCandidateBoxes(scaled)
                    if (matches(scaledPass.decodeResult)) return scaledPass
                } finally {
                    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                }
            }
        }

        // Retry 3: White quiet-zone padding + mild contrast boost (rescues tight qz=0/1 & shaded 2.5D borders)
        val paddedContrast = createPaddedContrastBitmap(bitmap, contrastGain = 1.45f, invert = false)
        if (paddedContrast != null) {
            try {
                val pass = decodeWithCandidateBoxes(paddedContrast)
                if (matches(pass.decodeResult)) return pass
            } finally {
                if (!paddedContrast.isRecycled) paddedContrast.recycle()
            }
        }

        // Retry 4: Inverted luminance pass (light modules on dark backdrop)
        val invertedBmp = createPaddedContrastBitmap(bitmap, contrastGain = 1.25f, invert = true)
        if (invertedBmp != null) {
            try {
                val pass = decodeWithCandidateBoxes(invertedBmp)
                if (matches(pass.decodeResult)) return pass
            } finally {
                if (!invertedBmp.isRecycled) invertedBmp.recycle()
            }
        }

        return direct
    }

    private fun cropWithQuietZone(bitmap: Bitmap, box: android.graphics.Rect): Bitmap? {
        return try {
            val cw = (box.right - box.left).coerceAtLeast(1)
            val ch = (box.bottom - box.top).coerceAtLeast(1)
            val pad = (maxOf(cw, ch) * 0.20f).toInt().coerceAtLeast(16)
            val cropL = (box.left - pad).coerceIn(0, bitmap.width - 1)
            val cropT = (box.top - pad).coerceIn(0, bitmap.height - 1)
            val cropR = (box.right + pad).coerceIn(cropL + 1, bitmap.width)
            val cropB = (box.bottom + pad).coerceIn(cropT + 1, bitmap.height)
            val srcW = cropR - cropL
            val srcH = cropB - cropT
            val out = Bitmap.createBitmap(srcW + pad * 2, srcH + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                android.graphics.Rect(cropL, cropT, cropR, cropB),
                android.graphics.Rect(pad, pad, pad + srcW, pad + srcH),
                null
            )
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun createPaddedContrastBitmap(bitmap: Bitmap, contrastGain: Float, invert: Boolean): Bitmap? {
        return try {
            val baseDim = maxOf(bitmap.width, bitmap.height).coerceIn(320, 1024)
            val scale = baseDim.toFloat() / maxOf(1, maxOf(bitmap.width, bitmap.height))
            val drawW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val drawH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val pad = (baseDim * 0.14f).toInt().coerceAtLeast(24)
            val out = Bitmap.createBitmap(drawW + pad * 2, drawH + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            val cm = if (invert) {
                val c = -contrastGain
                val t = 255f * (1f + (contrastGain - 1f) * 0.5f)
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            } else {
                val t = 128f * (1f - contrastGain)
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        contrastGain, 0f, 0f, 0f, t,
                        0f, contrastGain, 0f, 0f, t,
                        0f, 0f, contrastGain, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            }
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.Rect(pad, pad, pad + drawW, pad + drawH),
                paint
            )
            out
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun decodeMultiple(bitmap: Bitmap): List<DecodeResult> = suspendCancellableCoroutine { cont ->
        val startTime = System.currentTimeMillis()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val executor = Dispatchers.Default.asExecutor()
            val task = scanner.process(image)
            task.addOnSuccessListener(executor) { barcodes ->
                if (!cont.isActive) return@addOnSuccessListener
                val latency = System.currentTimeMillis() - startTime
                val list = barcodes.mapNotNull { b ->
                    b.rawValue?.let { text ->
                        DecodeResult(
                            success = true,
                            text = text,
                            latencyMs = latency,
                            decoderId = id
                        )
                    }
                }
                cont.resume(list)
            }
            task.addOnFailureListener(executor) { exception ->
                if (!cont.isActive) return@addOnFailureListener
                val latency = System.currentTimeMillis() - startTime
                cont.resume(
                    listOf(
                        DecodeResult(
                            success = false,
                            text = null,
                            latencyMs = latency,
                            error = exception.message ?: "ML Kit error",
                            decoderId = id
                        )
                    )
                )
            }
            cont.invokeOnCancellation {
                // Cooperative cancellation check in callbacks via cont.isActive
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            if (cont.isActive) {
                cont.resume(
                    listOf(
                        DecodeResult(
                            success = false,
                            text = null,
                            latencyMs = latency,
                            error = e.message ?: "Failed to process bitmap",
                            decoderId = id
                        )
                    )
                )
            }
        }
    }

    override fun close() {
        try {
            scanner.close()
        } catch (_: Exception) {}
    }
}

/**
 * EFQRCode multi-QR recognition parity facade.
 *
 * In EFQRCode:
 * `EFQRCode.recognize(image: CGImage) -> [String]?`
 */
object QrRecognizer {
    suspend fun recognize(
        bitmap: Bitmap,
        decoder: QrDecoder = ZxingQrDecoder()
    ): List<String> {
        val results = decoder.decodeMultiple(bitmap)
        return results.filter { it.success && !it.text.isNullOrEmpty() }.mapNotNull { it.text }
    }
}
