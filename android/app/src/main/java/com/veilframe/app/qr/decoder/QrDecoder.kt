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
interface QrDecoder {
    val id: String
    suspend fun decode(bitmap: Bitmap): DecodeResult
}

/**
 * Deterministic generator-side validator using ZXing Java.
 *
 * Configured per technical requirements:
 * - Reuses configured [MultiFormatReader] with decodeWithState().
 * - Sets [DecodeHintType.POSSIBLE_FORMATS] to QR_CODE.
 * - Sets [DecodeHintType.TRY_HARDER] to true.
 * - Strictly OMITS [DecodeHintType.PURE_BARCODE] because artistic QRs feature
 *   gradients, rounded modules, antialiasing, and 2.5D effects.
 */
class ZxingQrDecoder : QrDecoder {

    override val id: String = "ZXing-Java"

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        setHints(hints)
    }

    override suspend fun decode(bitmap: Bitmap): DecodeResult {
        val startTime = System.currentTimeMillis()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)

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
}

/**
 * Android offline scanner / validator wrapping Google ML Kit Barcode Scanning.
 */
class MlKitQrDecoder : QrDecoder {

    override val id: String = "MLKit"

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    override suspend fun decode(bitmap: Bitmap): DecodeResult = suspendCancellableCoroutine { cont ->
        val startTime = System.currentTimeMillis()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val latency = System.currentTimeMillis() - startTime
                    val found = barcodes.firstOrNull()
                    if (found != null && found.rawValue != null) {
                        cont.resume(
                            DecodeResult(
                                success = true,
                                text = found.rawValue,
                                latencyMs = latency,
                                decoderId = id
                            )
                        )
                    } else {
                        cont.resume(
                            DecodeResult(
                                success = false,
                                text = null,
                                latencyMs = latency,
                                error = "No barcode found",
                                decoderId = id
                            )
                        )
                    }
                }
                .addOnFailureListener { exception ->
                    val latency = System.currentTimeMillis() - startTime
                    cont.resume(
                        DecodeResult(
                            success = false,
                            text = null,
                            latencyMs = latency,
                            error = exception.message ?: "ML Kit error",
                            decoderId = id
                        )
                    )
                }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            cont.resume(
                DecodeResult(
                    success = false,
                    text = null,
                    latencyMs = latency,
                    error = e.message ?: "Failed to process bitmap",
                    decoderId = id
                )
            )
        }
    }
}
