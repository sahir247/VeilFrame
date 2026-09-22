package com.veilframe.app.qr.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/**
 * Stateless QR code reader that wraps ZXing's MultiFormatReader.
 *
 * Designed for use with CameraX [ImageAnalysis.Analyzer]:
 *
 * ```kotlin
 * val scanner = QrScanner { raw ->
 *     val action = PayloadParser.parse(raw)
 *     // show bottom sheet with action details
 * }
 * imageAnalysis.setAnalyzer(executor, scanner)
 * ```
 *
 * Also exposes a static [decode] helper for analyzing [Bitmap] directly
 * (useful for gallery import).
 */
class QrScanner(
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        ))
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }

        val planes = mediaImage.planes
        val buffer = planes[0].buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = PlanarYUVLuminanceSource(
            data,
            mediaImage.width, mediaImage.height,
            0, 0,
            mediaImage.width, mediaImage.height,
            false
        )
        val binary = BinaryBitmap(HybridBinarizer(source))
        try {
            val result = reader.decodeWithState(binary)
            onResult(result.text)
        } catch (_: NotFoundException) {
            // No QR found in this frame — normal
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    companion object {
        /**
         * Synchronously decodes a QR code from a [Bitmap].
         * Returns the decoded string or null if no QR code is found.
         */
        fun decode(bitmap: Bitmap): String? {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            return try {
                MultiFormatReader().decode(binary).text
            } catch (_: Exception) {
                null
            }
        }
    }
}
