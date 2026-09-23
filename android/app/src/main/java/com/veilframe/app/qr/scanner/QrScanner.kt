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
) : ImageAnalysis.Analyzer, java.io.Closeable {

    // Bundled offline ML Kit barcode client
    private val mlKitScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    override fun close() {
        try {
            mlKitScanner.close()
        } catch (_: Exception) {}
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

    @Volatile
    private var cachedYBuffer: ByteArray? = null

    private fun getOrCreateYBuffer(size: Int): ByteArray {
        val current = cachedYBuffer
        return if (current != null && current.size >= size) {
            current
        } else {
            val newBuf = ByteArray(size)
            cachedYBuffer = newBuf
            newBuf
        }
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        // 1. Frame gate throttling (~8-10 processed FPS) & single-flight acquisition.
        // If not acquired, imageProxy is closed inside acquireFrameToken.
        val token = controller.acquireFrameToken(imageProxy) ?: return

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            controller.finishFrameProcessing(token)
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 2. Primary Path: Bundled ML Kit processing mediaImage directly
        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            mlKitScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (!controller.isTokenActive(token)) {
                        token.close()
                        return@addOnSuccessListener
                    }

                    // Prioritize barcode closest to the viewfinder center
                    val centerX = inputImage.width / 2f
                    val centerY = inputImage.height / 2f
                    val bestBarcode = if (barcodes.size > 1) {
                        barcodes.minByOrNull { b ->
                            val box = b.boundingBox
                            if (box != null) {
                                val dx = box.centerX() - centerX
                                val dy = box.centerY() - centerY
                                dx * dx + dy * dy
                            } else {
                                Float.MAX_VALUE
                            }
                        }
                    } else {
                        barcodes.firstOrNull()
                    }

                    val detected = bestBarcode?.rawValue
                    if (detected != null) {
                        if (controller.onPayloadDecoded(detected)) {
                            onResult(detected)
                        }
                        controller.finishFrameProcessing(token)
                    } else {
                        // ML Kit found no barcode -> Fallback to ZXing
                        fallbackToZxing(token, mediaImage)
                    }
                }
                .addOnFailureListener {
                    if (!controller.isTokenActive(token)) {
                        token.close()
                        return@addOnFailureListener
                    }
                    // Fallback to ZXing on ML Kit error
                    fallbackToZxing(token, mediaImage)
                }
        } catch (_: Exception) {
            fallbackToZxing(token, mediaImage)
        }
    }

    private fun fallbackToZxing(token: FrameToken, mediaImage: android.media.Image) {
        if (!controller.isTokenActive(token)) {
            token.close()
            return
        }

        try {
            val width = mediaImage.width
            val height = mediaImage.height
            val yPlane = mediaImage.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            // Stride-aware Y-plane packing into pooled contiguous byte array (avoids ~2MB GC allocation/frame)
            val packedY = getOrCreateYBuffer(width * height)
            yBuffer.rewind()

            if (rowStride == width && pixelStride == 1) {
                yBuffer.get(packedY, 0, width * height)
            } else {
                for (row in 0 until height) {
                    val rowStart = row * rowStride
                    val rowDest = row * width
                    for (col in 0 until width) {
                        packedY[rowDest + col] = yBuffer.get(rowStart + (col * pixelStride))
                    }
                }
            }

            // Central 70% reticle ROI to accelerate binarization and focus on viewfinder area
            val cropLeft = (width * 0.15f).toInt()
            val cropTop = (height * 0.15f).toInt()
            val cropW = (width * 0.70f).toInt()
            val cropH = (height * 0.70f).toInt()

            val source = PlanarYUVLuminanceSource(
                packedY,
                width, height,
                cropLeft, cropTop,
                cropW, cropH,
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
            controller.finishFrameProcessing(token)
        }
    }

    companion object {
        /**
         * Synchronously decodes a QR code from a [Bitmap] (e.g. gallery pick).
         * Features multi-stage decoding:
         * 1. Downscaled bitmap (<=1280px) for standard photos.
         * 2. High-resolution center crop for high-density or distant QR codes in full photos.
         * 3. Full-resolution pass if memory allows.
         */
        fun decode(bitmap: Bitmap): String? {
            // Stage 1: Try downscaled (<= 1280px)
            val scaledBmp = if (bitmap.width > 1280 || bitmap.height > 1280) {
                val scale = 1280f / maxOf(bitmap.width, bitmap.height)
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            val stage1Result = decodeInternal(scaledBmp)
            if (scaledBmp != bitmap) {
                scaledBmp.recycle()
            }
            if (stage1Result != null) {
                return stage1Result
            }

            // Stage 2: If downscaling failed and original is high-res, try central 65% crop of full-res image
            if (bitmap.width > 1280 || bitmap.height > 1280) {
                try {
                    val cropW = (bitmap.width * 0.65f).toInt()
                    val cropH = (bitmap.height * 0.65f).toInt()
                    val cropX = (bitmap.width - cropW) / 2
                    val cropY = (bitmap.height - cropH) / 2
                    val centerCrop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                    val stage2Result = decodeInternal(centerCrop)
                    centerCrop.recycle()
                    if (stage2Result != null) {
                        return stage2Result
                    }
                } catch (_: Exception) {}

                // Stage 3: Full-resolution pass
                try {
                    val stage3Result = decodeInternal(bitmap)
                    if (stage3Result != null) {
                        return stage3Result
                    }
                } catch (_: Exception) {}
            }

            return null
        }

        private fun decodeInternal(bmp: Bitmap): String? {
            return try {
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
                val binary = BinaryBitmap(HybridBinarizer(source))
                val reader = MultiFormatReader().apply {
                    setHints(mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true
                    ))
                }
                val res = reader.decode(binary)
                reader.reset()
                res.text
            } catch (_: Exception) {
                null
            }
        }
    }
}
