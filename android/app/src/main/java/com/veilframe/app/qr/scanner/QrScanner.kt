package com.veilframe.app.qr.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
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
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.TimeUnit

/**
 * High-performance CameraX [ImageAnalysis.Analyzer] combining:
 * 1. OpenCV WeChatQRCode (detector + super-resolution) as PRIMARY decoder on
 *    camera luma plane.
 * 2. Bundled offline ML Kit Barcode Scanning with [enableAllPotentialBarcodes]
 *    as SECONDARY detector/localizer/decoder.
 * 3. Automatic zoom suggestion calculation when potential barcodes are distant/small.
 * 4. Localized Candidate ROI coordinate mapping from upright space to camera buffer space.
 * 5. Resilient ZXing fallback with 3-pass binarization (Hybrid, GlobalHistogram, Invert).
 * 6. Throttled frame gating to ~8–10 processed FPS via [ScannerController].
 */
class QrScanner(
    private val controller: ScannerController = ScannerController(),
    var onZoomSuggestion: ((zoomMultiplier: Float) -> Unit)? = null,
    var onLowLightDetected: ((isLowLight: Boolean) -> Unit)? = null,
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer, java.io.Closeable {

    // Bundled offline ML Kit barcode client with potential barcode localization enabled
    private val mlKitScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
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

    @Volatile
    private var lastZoomSuggestionTimeMs = 0L

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

    @Volatile
    var isAnalysisPaused: Boolean = false

    fun pauseAnalysis() {
        isAnalysisPaused = true
    }

    fun resumeAnalysis() {
        isAnalysisPaused = false
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        // Fast drop if scanner analysis pipeline is paused (e.g. presenting result / scan another)
        if (isAnalysisPaused) {
            imageProxy.close()
            return
        }

        // 1. Frame gate throttling (~8-10 processed FPS) & single-flight acquisition.
        val token = controller.acquireFrameToken(imageProxy) ?: return

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            controller.finishFrameProcessing(token)
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Low-light ambient luminance check (Y-plane subsampling)
        if (onLowLightDetected != null && mediaImage.planes.isNotEmpty()) {
            try {
                val yPlane = mediaImage.planes[0]
                val yBuf = yPlane.buffer
                val rowStride = yPlane.rowStride
                val pixelStride = yPlane.pixelStride
                val w = mediaImage.width
                val h = mediaImage.height
                var sumLum = 0L
                var samples = 0
                val stepX = (w / 8).coerceAtLeast(1)
                val stepY = (h / 8).coerceAtLeast(1)
                for (y in 0 until h step stepY) {
                    for (x in 0 until w step stepX) {
                        val pos = y * rowStride + x * pixelStride
                        if (pos < yBuf.limit()) {
                            sumLum += (yBuf.get(pos).toInt() and 0xFF)
                            samples++
                        }
                    }
                }
                if (samples > 0) {
                    val avgLum = sumLum / samples
                    onLowLightDetected?.invoke(avgLum < 40)
                }
            } catch (_: Throwable) {}
        }

        // 2. PRIMARY engine: cv::wechat_qrcode::WeChatQRCode (detector + super
        //    resolution) on the camera luma plane. ML Kit below is the secondary
        //    fallback; ZXing stays as the last resort.
        if (tryWeChatDecode(mediaImage, token)) {
            return
        }

        // 3. Secondary Path: Bundled ML Kit processing mediaImage directly
        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            mlKitScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (!controller.isTokenActive(token)) {
                        token.close()
                        return@addOnSuccessListener
                    }

                    // A: Successfully decoded barcode
                    val decodedBarcode = findBestDecodedBarcode(barcodes, inputImage.width, inputImage.height)
                    if (decodedBarcode?.rawValue != null) {
                        val payload = decodedBarcode.rawValue!!
                        if (controller.onPayloadDecoded(payload)) {
                            onResult(payload)
                        }
                        controller.finishFrameProcessing(token)
                        return@addOnSuccessListener
                    }

                    // B: Potential barcode candidate localization
                    val potentialBarcode = findBestPotentialBarcode(barcodes, inputImage.width, inputImage.height)
                    val candidateBox = potentialBarcode?.boundingBox

                    // Auto-zoom suggestion if candidate is distant/small
                    if (candidateBox != null) {
                        considerZoomSuggestion(candidateBox, inputImage.width, inputImage.height)
                    }

                    // C: Fallback to secondary localized ZXing engine
                    fallbackToZxing(token, mediaImage, candidateBox, rotationDegrees)
                }
                .addOnFailureListener {
                    if (!controller.isTokenActive(token)) {
                        token.close()
                        return@addOnFailureListener
                    }
                    fallbackToZxing(token, mediaImage, null, rotationDegrees)
                }
        } catch (_: Exception) {
            fallbackToZxing(token, mediaImage, null, rotationDegrees)
        }
    }

    /**
     * PRIMARY decode stage — WeChatQRCode over the camera Y (luma) plane.
     *
     * @return true when the frame was fully handled (payload delivered and the
     *         frame token finished), false to let the secondary chain run.
     */
    private fun tryWeChatDecode(mediaImage: android.media.Image, token: FrameToken): Boolean {
        val engine = com.veilframe.app.cv.qr.WeChatQrEngine.get()
        if (!engine.isAvailable) return false
        return try {
            val mat = yPlaneToMat(mediaImage) ?: return false
            val detection = try {
                engine.decode(mat).firstOrNull()
            } finally {
                mat.release()
            }
            if (detection != null && detection.rawValue.isNotEmpty()) {
                if (controller.isTokenActive(token)) {
                    if (controller.onPayloadDecoded(detection.rawValue)) {
                        onResult(detection.rawValue)
                    }
                    controller.finishFrameProcessing(token)
                } else {
                    token.close()
                }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Camera Y plane -> CV_8UC1 Mat, decimated to <=1024px on the long edge. */
    private fun yPlaneToMat(mediaImage: android.media.Image): org.opencv.core.Mat? {
        val plane = mediaImage.planes.firstOrNull() ?: return null
        val width = mediaImage.width
        val height = mediaImage.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val data = ByteArray(width * height)
        try {
            if (rowStride == width) {
                buffer.position(0)
                buffer.get(data, 0, width * height)
            } else {
                var offset = 0
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(data, offset, width)
                    offset += width
                }
            }
        } catch (_: Throwable) {
            return null
        }
        val full = org.opencv.core.Mat(height, width, org.opencv.core.CvType.CV_8UC1)
        full.put(0, 0, data)
        val maxEdge = maxOf(width, height)
        return if (maxEdge <= 1024) {
            full
        } else {
            val scaled = org.opencv.core.Mat()
            val scale = 1024.0 / maxEdge
            org.opencv.imgproc.Imgproc.resize(
                full,
                scaled,
                org.opencv.core.Size(
                    maxOf(1, Math.round(width * scale).toInt()).toDouble(),
                    maxOf(1, Math.round(height * scale).toInt()).toDouble(),
                ),
                0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA,
            )
            full.release()
            scaled
        }
    }

    private fun findBestDecodedBarcode(barcodes: List<Barcode>, frameW: Int, frameH: Int): Barcode? {
        val decoded = barcodes.filter { it.rawValue != null }
        if (decoded.isEmpty()) return null
        if (decoded.size == 1) return decoded[0]

        val centerX = frameW / 2f
        val centerY = frameH / 2f
        return decoded.minByOrNull { b ->
            val box = b.boundingBox
            if (box != null) {
                val cX = (box.left + box.right) / 2f
                val cY = (box.top + box.bottom) / 2f
                val dx = cX - centerX
                val dy = cY - centerY
                dx * dx + dy * dy
            } else {
                Float.MAX_VALUE
            }
        }
    }

    private fun findBestPotentialBarcode(barcodes: List<Barcode>, frameW: Int, frameH: Int): Barcode? {
        val candidates = barcodes.filter { it.boundingBox != null }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        val centerX = frameW / 2f
        val centerY = frameH / 2f
        return candidates.minByOrNull { b ->
            val box = b.boundingBox!!
            val cX = (box.left + box.right) / 2f
            val cY = (box.top + box.bottom) / 2f
            val dx = cX - centerX
            val dy = cY - centerY
            dx * dx + dy * dy
        }
    }

    private fun considerZoomSuggestion(box: Rect, frameWidth: Int, frameHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastZoomSuggestionTimeMs < 1200L) return

        val boxW = box.right - box.left
        val boxH = box.bottom - box.top
        val boxSpan = maxOf(boxW, boxH)
        val frameSpan = minOf(frameWidth, frameHeight)
        if (frameSpan <= 0) return

        val coverage = boxSpan.toFloat() / frameSpan.toFloat()
        // If potential barcode occupies less than 28% of the viewfinder min-dimension, suggest zoom
        if (coverage in 0.03f..0.28f) {
            val targetCoverage = 0.50f
            val multiplier = (targetCoverage / coverage).coerceIn(1.2f, 3.0f)
            lastZoomSuggestionTimeMs = now
            onZoomSuggestion?.invoke(multiplier)
        }
    }

    private data class CropBounds(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun fallbackToZxing(
        token: FrameToken,
        mediaImage: android.media.Image,
        candidateBox: Rect?,
        rotationDegrees: Int
    ) {
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

            // Determine crop rectangle:
            // If candidateBox is present: crop localized ROI + 20% quiet-zone padding.
            // If candidateBox is null: process full frame (never discarding off-center QRs).
            val crop = if (candidateBox != null) {
                val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                val candW = candidateBox.right - candidateBox.left
                val candH = candidateBox.bottom - candidateBox.top
                val padX = (candW * 0.20f).toInt().coerceAtLeast(8)
                val padY = (candH * 0.20f).toInt().coerceAtLeast(8)
                val expandedUpright = Rect(
                    (candidateBox.left - padX).coerceIn(0, uprightW),
                    (candidateBox.top - padY).coerceIn(0, uprightH),
                    (candidateBox.right + padX).coerceIn(0, uprightW),
                    (candidateBox.bottom + padY).coerceIn(0, uprightH)
                )
                val bufferRect = mapUprightRectToBuffer(expandedUpright, rotationDegrees, width, height)
                val cL = bufferRect.left.coerceIn(0, width - 1)
                val cT = bufferRect.top.coerceIn(0, height - 1)
                val cW = (bufferRect.right - bufferRect.left).coerceIn(1, width - cL)
                val cH = (bufferRect.bottom - bufferRect.top).coerceIn(1, height - cT)
                CropBounds(cL, cT, cW, cH)
            } else {
                CropBounds(0, 0, width, height)
            }

            val source = PlanarYUVLuminanceSource(
                packedY,
                width, height,
                crop.left, crop.top,
                crop.width, crop.height,
                false
            )

            var decodedText: String? = null

            // Pass 1: HybridBinarizer (adaptive thresholding)
            try {
                val binary = BinaryBitmap(HybridBinarizer(source))
                val result = zxingReader.decodeWithState(binary)
                decodedText = result.text
            } catch (_: Exception) {} finally {
                zxingReader.reset()
            }

            // Pass 2: GlobalHistogramBinarizer (resilient against soft gradients & complex background patterns)
            if (decodedText == null) {
                try {
                    val binary = BinaryBitmap(GlobalHistogramBinarizer(source))
                    val result = zxingReader.decodeWithState(binary)
                    decodedText = result.text
                } catch (_: Exception) {} finally {
                    zxingReader.reset()
                }
            }

            // Pass 3: Inverted luminance (for light-on-dark / inverted artistic QR codes)
            if (decodedText == null) {
                try {
                    val binary = BinaryBitmap(HybridBinarizer(source.invert()))
                    val result = zxingReader.decodeWithState(binary)
                    decodedText = result.text
                } catch (_: Exception) {} finally {
                    zxingReader.reset()
                }
            }

            if (decodedText != null) {
                if (controller.onPayloadDecoded(decodedText)) {
                    onResult(decodedText)
                }
            } else {
                controller.onFrameMiss()
            }
        } catch (_: Exception) {
            controller.onFrameMiss()
        } finally {
            zxingReader.reset()
            controller.finishFrameProcessing(token)
        }
    }

    data class ScannerRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
    }

    companion object {
        /**
         * Transforms upright rectangle coordinates (from [InputImage] coordinate space)
         * back into the raw camera buffer coordinate space.
         */
        fun mapUprightCoordsToBuffer(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            rotationDegrees: Int,
            bufferWidth: Int,
            bufferHeight: Int
        ): ScannerRect {
            val l = left
            val t = top
            val r = right
            val b = bottom
            val w = (r - l).coerceAtLeast(1)
            val h = (b - t).coerceAtLeast(1)

            return when (rotationDegrees) {
                90 -> {
                    val bufLeft = t.coerceIn(0, bufferWidth)
                    val bufTop = (bufferHeight - r).coerceIn(0, bufferHeight)
                    val bufRight = (t + h).coerceIn(bufLeft, bufferWidth)
                    val bufBottom = (bufferHeight - l).coerceIn(bufTop, bufferHeight)
                    ScannerRect(bufLeft, bufTop, bufRight, bufBottom)
                }
                180 -> {
                    val bufLeft = (bufferWidth - r).coerceIn(0, bufferWidth)
                    val bufTop = (bufferHeight - b).coerceIn(0, bufferHeight)
                    val bufRight = (bufferWidth - l).coerceIn(bufLeft, bufferWidth)
                    val bufBottom = (bufferHeight - t).coerceIn(bufTop, bufferHeight)
                    ScannerRect(bufLeft, bufTop, bufRight, bufBottom)
                }
                270 -> {
                    val bufLeft = (bufferWidth - b).coerceIn(0, bufferWidth)
                    val bufTop = l.coerceIn(0, bufferHeight)
                    val bufRight = (bufferWidth - t).coerceIn(bufLeft, bufferWidth)
                    val bufBottom = (l + w).coerceIn(bufTop, bufferHeight)
                    ScannerRect(bufLeft, bufTop, bufRight, bufBottom)
                }
                else -> {
                    ScannerRect(
                        l.coerceIn(0, bufferWidth),
                        t.coerceIn(0, bufferHeight),
                        r.coerceIn(0, bufferWidth),
                        b.coerceIn(0, bufferHeight)
                    )
                }
            }
        }

        /**
         * Transforms an upright rectangle (from [InputImage] coordinate space)
         * back into the raw camera buffer coordinate space.
         */
        fun mapUprightRectToBuffer(
            uprightRect: Rect,
            rotationDegrees: Int,
            bufferWidth: Int,
            bufferHeight: Int
        ): Rect {
            return mapUprightCoordsToBuffer(
                uprightRect.left,
                uprightRect.top,
                uprightRect.right,
                uprightRect.bottom,
                rotationDegrees,
                bufferWidth,
                bufferHeight
            ).toAndroidRect()
        }

        /**
         * Synchronously decodes a QR code from a [Bitmap] (e.g. gallery pick).
         * Multi-stage pipeline:
         * 1. ML Kit primary pass with [enableAllPotentialBarcodes].
         * 2. If candidate bounding box localized, crops ROI with quiet zone for ZXing.
         * 3. Multi-resolution multi-binarizer fallback (Hybrid, GlobalHistogram, Invert).
         */
        fun decode(bitmap: Bitmap): String? {
            // Stage 0: ML Kit primary attempt on static bitmap
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAllPotentialBarcodes()
                    .build()
                val client = BarcodeScanning.getClient(options)
                val task = client.process(inputImage)
                val barcodes = Tasks.await(task, 2500, TimeUnit.MILLISECONDS)
                client.close()

                val direct = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                if (direct != null) return direct

                val candidateBox = barcodes.firstOrNull { it.boundingBox != null }?.boundingBox
                if (candidateBox != null) {
                    val candW = candidateBox.right - candidateBox.left
                    val candH = candidateBox.bottom - candidateBox.top
                    val padX = (candW * 0.20f).toInt().coerceAtLeast(8)
                    val padY = (candH * 0.20f).toInt().coerceAtLeast(8)
                    val cropL = (candidateBox.left - padX).coerceIn(0, bitmap.width - 1)
                    val cropT = (candidateBox.top - padY).coerceIn(0, bitmap.height - 1)
                    val cropR = (candidateBox.right + padX).coerceIn(cropL + 1, bitmap.width)
                    val cropB = (candidateBox.bottom + padY).coerceIn(cropT + 1, bitmap.height)
                    val roiBitmap = Bitmap.createBitmap(bitmap, cropL, cropT, cropR - cropL, cropB - cropT)
                    val roiResult = decodeInternal(roiBitmap)
                    roiBitmap.recycle()
                    if (roiResult != null) return roiResult
                }
            } catch (_: Throwable) {}

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

            // Stage 2: Central 70% crop of full-res image
            if (bitmap.width > 1280 || bitmap.height > 1280) {
                try {
                    val cropW = (bitmap.width * 0.70f).toInt()
                    val cropH = (bitmap.height * 0.70f).toInt()
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
                val reader = MultiFormatReader().apply {
                    setHints(mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true
                    ))
                }

                // Pass 1: HybridBinarizer
                try {
                    val binary = BinaryBitmap(HybridBinarizer(source))
                    val res = reader.decode(binary)
                    reader.reset()
                    return res.text
                } catch (_: Exception) {
                    reader.reset()
                }

                // Pass 2: GlobalHistogramBinarizer
                try {
                    val binary = BinaryBitmap(GlobalHistogramBinarizer(source))
                    val res = reader.decode(binary)
                    reader.reset()
                    return res.text
                } catch (_: Exception) {
                    reader.reset()
                }

                // Pass 3: Inverted luminance
                try {
                    val binary = BinaryBitmap(HybridBinarizer(source.invert()))
                    val res = reader.decode(binary)
                    reader.reset()
                    return res.text
                } catch (_: Exception) {
                    reader.reset()
                }

                null
            } catch (_: Exception) {
                null
            }
        }
    }
}

