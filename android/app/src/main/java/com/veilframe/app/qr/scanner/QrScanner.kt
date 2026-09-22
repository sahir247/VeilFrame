package com.veilframe.app.qr.scanner

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * High-performance CameraX [ImageAnalysis.Analyzer] combining:
 * 1. Bundled offline ML Kit Barcode Scanning as the primary camera detector/decoder.
 * 2. Stride-aware packed YUV ZXing as fallback on miss or potential barcode detection.
 * 3. Throttled frame gating to ~8–10 processed FPS via [ScannerController].
 */
class QrScanner(
    private val controller: ScannerController = ScannerController(),
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    // Bundled offline ML Kit barcode client
    private val mlKitScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    // Secondary fallback ZXing reader
    private val zxingReader by lazy {
        MultiFormatReader().apply {
            setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            ))
        }
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        // 1. Frame gate throttling (~8-10 processed FPS). Unselected frames are closed immediately.
        if (!controller.shouldProcessFrame(imageProxy)) {
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            controller.finishFrameProcessing()
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 2. Primary Path: Bundled ML Kit processing mediaImage directly
        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            mlKitScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val detected = barcodes.firstOrNull()?.rawValue
                    if (detected != null) {
                        if (controller.onPayloadDecoded(detected)) {
                            onResult(detected)
                        }
                        finalizeFrame(imageProxy)
                    } else {
                        // ML Kit found no barcode -> Fallback to ZXing
                        fallbackToZxing(imageProxy, mediaImage)
                    }
                }
                .addOnFailureListener {
                    // Fallback to ZXing on ML Kit error
                    fallbackToZxing(imageProxy, mediaImage)
                }
        } catch (_: Exception) {
            fallbackToZxing(imageProxy, mediaImage)
        }
    }

    private fun fallbackToZxing(imageProxy: ImageProxy, mediaImage: android.media.Image) {
        try {
            val width = mediaImage.width
            val height = mediaImage.height
            val yPlane = mediaImage.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            // Pack stride-aware Y-plane into contiguous byte array
            val packedY = ByteArray(width * height)
            yBuffer.rewind()

            if (rowStride == width && pixelStride == 1) {
                yBuffer.get(packedY)
            } else {
                for (row in 0 until height) {
                    val rowStart = row * rowStride
                    for (col in 0 until width) {
                        packedY[row * width + col] = yBuffer.get(rowStart + (col * pixelStride))
                    }
                }
            }

            val source = PlanarYUVLuminanceSource(
                packedY,
                width, height,
                0, 0,
                width, height,
                false
            )
            val binary = BinaryBitmap(HybridBinarizer(source))
            val result = zxingReader.decodeWithState(binary)
            zxingReader.reset()

            if (controller.onPayloadDecoded(result.text)) {
                onResult(result.text)
            }
        } catch (_: Exception) {
            controller.onFrameMiss()
        } finally {
            zxingReader.reset()
            finalizeFrame(imageProxy)
        }
    }

    private fun finalizeFrame(imageProxy: ImageProxy) {
        try {
            controller.finishFrameProcessing()
            imageProxy.close()
        } catch (_: Exception) {
            // Frame might already be closed
        }
    }

    companion object {
        /**
         * Synchronously decodes a QR code from a [Bitmap] (e.g. gallery pick).
         */
        fun decode(bitmap: Bitmap): String? {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                ))
            }
            return try {
                val res = reader.decode(binary)
                reader.reset()
                res.text
            } catch (_: Exception) {
                reader.reset()
                null
            }
        }
    }
}
