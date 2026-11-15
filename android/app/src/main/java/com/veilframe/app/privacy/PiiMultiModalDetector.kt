package com.veilframe.app.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.qr.WeChatQrEngine
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.util.concurrent.TimeUnit

/**
 * Unified offline PII detector leveraging all bundled Vision & Neural subsystems:
 * 1. NCNN UltraFace RFB-320 (faces) & YOLOv8n (people, vehicles/plates, screens/laptops, phones/documents)
 * 2. WeChatQRCode + ML Kit Barcode Scanning (QR codes, 1D/2D barcodes)
 * 3. ML Kit Text Recognition + [PiiTextClassifier] (emails, phones, card numbers, SSN/PAN/National IDs, API keys/tokens)
 */
object PiiMultiModalDetector {

    private const val TAG = "VeilFrame.PiiMultiModal"

    fun detectAll(
        context: Context,
        ncnnEngine: PiiNcnnEngine?,
        bitmap: Bitmap,
        targetWidth: Int = bitmap.width,
        targetHeight: Int = bitmap.height,
        confThreshold: Float = 0.38f,
        iouThreshold: Float = 0.45f,
        includeNcnn: Boolean = true,
        includeBarcodes: Boolean = true,
        includeOcrText: Boolean = true
    ): List<PiiDetection> {
        val srcW = bitmap.width.coerceAtLeast(1)
        val srcH = bitmap.height.coerceAtLeast(1)
        val candidates = mutableListOf<PiiDetection>()

        // 1. NCNN: Faces (UltraFace RFB-320) + Vehicles/Plates, Screens/Laptops, Phones/Documents, People (YOLOv8n)
        if (includeNcnn && ncnnEngine != null) {
            runCatching {
                candidates += ncnnEngine.detect(
                    bitmap = bitmap,
                    targetWidth = srcW,
                    targetHeight = srcH,
                    confThreshold = confThreshold,
                    iouThreshold = iouThreshold
                )
            }.onFailure {
                Log.w(TAG, "NCNN PII pass failed: ${it.message}")
            }
        }

        // 2. QR Codes & Barcodes (WeChatQRCode + ML Kit BarcodeScanning)
        if (includeBarcodes) {
            candidates += detectBarcodesAndQr(bitmap)
        }

        // 3. Sensitive OCR Text (Emails, Phones, Card Numbers, National IDs, Tokens/Credentials, IPs)
        if (includeOcrText) {
            candidates += detectSensitiveTextPii(bitmap)
        }

        val deduplicated = PiiNcnnEngine.applyNMS(candidates, iouThreshold)
        return if (targetWidth != srcW || targetHeight != srcH) {
            PiiNcnnEngine.scaleDetections(deduplicated, srcW, srcH, targetWidth, targetHeight)
        } else {
            deduplicated
        }
    }

    private fun detectBarcodesAndQr(bitmap: Bitmap): List<PiiDetection> {
        val found = mutableListOf<PiiDetection>()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // 2a. WeChatQRCode (OpenCV native)
        if (CvRuntime.isNativeAvailable) {
            runCatching {
                val engine = WeChatQrEngine.get()
                if (engine.isAvailable) {
                    val mat = Mat()
                    try {
                        Utils.bitmapToMat(bitmap, mat)
                        for (det in engine.decode(mat)) {
                            val corners = det.corners
                            if (!corners.isNullOrEmpty()) {
                                val left = corners.minOf { it.x }.toFloat().coerceIn(0f, w)
                                val top = corners.minOf { it.y }.toFloat().coerceIn(0f, h)
                                val right = corners.maxOf { it.x }.toFloat().coerceIn(0f, w)
                                val bottom = corners.maxOf { it.y }.toFloat().coerceIn(0f, h)
                                if (right > left && bottom > top) {
                                    found += PiiDetection(
                                        rect = padRect(RectF(left, top, right, bottom), 0.04f, w, h),
                                        piiClass = PiiClass.BARCODE,
                                        score = 0.96f,
                                        label = "QR Code"
                                    )
                                }
                            }
                        }
                    } finally {
                        mat.release()
                    }
                }
            }
        }

        // 2b. ML Kit Barcode Scanning (1D/2D barcodes & QR)
        var scanner: com.google.mlkit.vision.barcode.BarcodeScanner? = null
        try {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
            scanner = BarcodeScanning.getClient(options)
            val barcodes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 3500L, TimeUnit.MILLISECONDS)
            for (bc in barcodes) {
                val box: Rect = bc.boundingBox ?: continue
                if (box.right > box.left && box.bottom > box.top) {
                    val label = if (bc.format == Barcode.FORMAT_QR_CODE) "QR Code" else "Barcode"
                    found += PiiDetection(
                        rect = padRect(RectF(box), 0.04f, w, h),
                        piiClass = PiiClass.BARCODE,
                        score = 0.92f,
                        label = label
                    )
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "ML Kit barcode scan skipped: ${t.message}")
        } finally {
            runCatching { scanner?.close() }
        }

        return found
    }

    private fun detectSensitiveTextPii(bitmap: Bitmap): List<PiiDetection> {
        val found = mutableListOf<PiiDetection>()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 4000L, TimeUnit.MILLISECONDS)
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val lineLabel = PiiTextClassifier.classifySensitiveText(line.text)
                    val lineBox = line.boundingBox
                    if (lineLabel != null && lineBox != null && lineBox.right > lineBox.left && lineBox.bottom > lineBox.top) {
                        val snippet = line.text.trim().take(36)
                        found += PiiDetection(
                            rect = padRect(RectF(lineBox), 0.04f, w, h),
                            piiClass = PiiClass.TEXT_PII,
                            score = 0.90f,
                            label = "$lineLabel: $snippet"
                        )
                    } else {
                        // Also check dense multi-field lines token by token
                        for (element in line.elements) {
                            val elemLabel = PiiTextClassifier.classifySensitiveText(element.text) ?: continue
                            val elemBox = element.boundingBox ?: continue
                            if (elemBox.right > elemBox.left && elemBox.bottom > elemBox.top) {
                                val snippet = element.text.trim().take(30)
                                found += PiiDetection(
                                    rect = padRect(RectF(elemBox), 0.05f, w, h),
                                    piiClass = PiiClass.TEXT_PII,
                                    score = 0.88f,
                                    label = "$elemLabel: $snippet"
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "ML Kit text PII scan skipped: ${t.message}")
        } finally {
            runCatching { recognizer?.close() }
        }
        return found
    }

    private fun padRect(rect: RectF, padFraction: Float, maxW: Float, maxH: Float): RectF {
        val padX = rect.width() * padFraction
        val padY = rect.height() * padFraction
        return RectF(
            (rect.left - padX).coerceIn(0f, maxW),
            (rect.top - padY).coerceIn(0f, maxH),
            (rect.right + padX).coerceIn(0f, maxW),
            (rect.bottom + padY).coerceIn(0f, maxH)
        )
    }
}

