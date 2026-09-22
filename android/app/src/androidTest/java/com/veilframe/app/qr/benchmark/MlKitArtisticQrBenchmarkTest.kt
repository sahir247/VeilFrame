package com.veilframe.app.qr.benchmark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation test measuring Google ML Kit Barcode Scanning
 * latency, detection accuracy, and offline execution on physical hardware/emulator.
 */
@RunWith(AndroidJUnit4::class)
class MlKitArtisticQrBenchmarkTest {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    @Test
    fun testMlKitOfflineBarcodeDetection() = runTest {
        val testContent = "https://veilframe.app/offline-benchmark"
        val encoded = QrEncoder.encode(testContent, ErrorCorrectionLevel.H)

        val size = 512
        val cellSize = size / (encoded.matrix.size + 8)
        val qz = 4
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        for (r in 0 until encoded.matrix.size) {
            for (c in 0 until encoded.matrix.size) {
                if (encoded.matrix.isDark(c, r)) {
                    val startX = (c + qz) * cellSize
                    val startY = (r + qz) * cellSize
                    for (y in startY until startY + cellSize) {
                        for (x in startX until startX + cellSize) {
                            if (x < size && y < size) {
                                bitmap.setPixel(x, y, Color.BLACK)
                            }
                        }
                    }
                }
            }
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val startTime = System.currentTimeMillis()
        val barcodes = scanner.process(inputImage).await()
        val latency = System.currentTimeMillis() - startTime

        assertFalse("ML Kit should detect barcode offline", barcodes.isEmpty())
        val decodedText = barcodes.first().rawValue
        assertEquals("Decoded content must match original text", testContent, decodedText)
        println("[MLKIT HARDWARE BENCHMARK] Latency: ${latency}ms, Result: $decodedText")
    }
}
