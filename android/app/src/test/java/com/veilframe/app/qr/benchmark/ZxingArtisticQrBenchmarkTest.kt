package com.veilframe.app.qr.benchmark

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder
import com.veilframe.app.qr.model.QrMatrix
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

enum class FailureStage {
    NONE,
    STRUCTURE,
    DETECTION,
    DECODING,
    CONTENT_MISMATCH
}

data class DecodeBenchmarkResult(
    val success: Boolean,
    val latencyMs: Long,
    val degradation: String,
    val failureStage: FailureStage
)

/**
 * JVM-local benchmark suite measuring ZXing Java robustness against
 * artistic QR variations and real-world image degradation.
 *
 * Requirements:
 * - Operates locally on the JVM without device emulators.
 * - Reusable MultiFormatReader configured with TRY_HARDER, POSSIBLE_FORMATS=[QR_CODE].
 * - Strictly OMITS PURE_BARCODE.
 * - Evaluates across Error Correction levels (L, M, Q, H).
 * - Tests pristine and degraded variants (scaling, blur, contrast drift, noise).
 */
class ZxingArtisticQrBenchmarkTest {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        setHints(hints)
    }

    @Test
    fun testPristineDecodeAcrossAllErrorCorrectionLevels() {
        val testContent = "https://veilframe.app/qr"
        val ecLevels = listOf(
            ErrorCorrectionLevel.L,
            ErrorCorrectionLevel.M,
            ErrorCorrectionLevel.Q,
            ErrorCorrectionLevel.H
        )

        for (ec in ecLevels) {
            val encoded = QrEncoder.encode(testContent, ec)
            val cellSize = 10
            val qz = 4
            val dim = (encoded.matrix.size + 2 * qz) * cellSize
            val pixels = renderPristineQrPixels(encoded.matrix, cellSize = cellSize, quietZone = qz)

            val result = decodePixels(pixels, dim, dim, testContent, "Pristine")
            assertTrue("Expected ZXing to decode pristine EC $ec QR (failed: ${result.failureStage})", result.success)
            assertEquals(FailureStage.NONE, result.failureStage)
            println("[BENCHMARK] Pristine EC $ec: ${result.latencyMs}ms (PASS)")
        }
    }

    @Test
    fun testDegradationRobustness() {
        val testContent = "https://veilframe.app/vault/test"
        val encoded = QrEncoder.encode(testContent, ErrorCorrectionLevel.H)
        val cellSize = 10
        val qz = 4
        val dim = (encoded.matrix.size + 2 * qz) * cellSize
        val pristinePixels = renderPristineQrPixels(encoded.matrix, cellSize, qz)

        // 1. Pristine Baseline
        val baseResult = decodePixels(pristinePixels, dim, dim, testContent, "Baseline")
        assertTrue("Baseline pristine QR must decode", baseResult.success)

        // 2. Downscaled 0.5x
        val scaledDim = dim / 2
        val scaledPixels = downsample2x(pristinePixels, dim, dim)
        val scaleResult = decodePixels(scaledPixels, scaledDim, scaledDim, testContent, "Downscale 0.5x")
        assertTrue("0.5x downscaled QR should decode with H error correction", scaleResult.success)

        // 3. Contrast Drift (Lightened background, dimmed foreground)
        val driftedPixels = applyContrastDrift(pristinePixels)
        val contrastResult = decodePixels(driftedPixels, dim, dim, testContent, "Contrast Drift")
        assertTrue("Low contrast drift should still decode with TRY_HARDER", contrastResult.success)

        // 4. Gaussian Blur Simulation
        val blurredPixels = applyBoxBlur(pristinePixels, dim, dim)
        val blurResult = decodePixels(blurredPixels, dim, dim, testContent, "Mild Blur")
        assertTrue("Mildly blurred QR should decode", blurResult.success)

        // 5. Salt & Pepper Noise (Simulating 1% dirt/sensor noise on data area under EC H)
        val noisyPixels = applyNoise(pristinePixels, dim, dim, qz, cellSize, encoded.matrix.size, noiseRatio = 0.01f, seed = 999L)
        val noiseResult = decodePixels(noisyPixels, dim, dim, testContent, "1% Salt & Pepper Noise")
        assertTrue("1% damaged QR should decode under Error Correction H", noiseResult.success)

        println("[BENCHMARK] Baseline: ${baseResult.latencyMs}ms")
        println("[BENCHMARK] 0.5x Scale: ${scaleResult.latencyMs}ms")
        println("[BENCHMARK] Contrast Drift: ${contrastResult.latencyMs}ms")
        println("[BENCHMARK] Mild Blur: ${blurResult.latencyMs}ms")
        println("[BENCHMARK] 1% Noise: ${noiseResult.latencyMs}ms")
    }

    private fun decodePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        expectedContent: String,
        degradation: String
    ): DecodeBenchmarkResult {
        val startTime = System.currentTimeMillis()
        val source = RGBLuminanceSource(width, height, pixels)

        // Pass 1: HybridBinarizer (adaptive thresholding)
        try {
            val binary = BinaryBitmap(HybridBinarizer(source))
            val res = reader.decodeWithState(binary)
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            return if (res.text == expectedContent) {
                DecodeBenchmarkResult(true, latency, degradation, FailureStage.NONE)
            } else {
                DecodeBenchmarkResult(false, latency, degradation, FailureStage.CONTENT_MISMATCH)
            }
        } catch (e: Exception) {
            reader.reset()
            println("[BENCHMARK DEBUG] Pass 1 exception: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Pass 2: GlobalHistogramBinarizer (resilient fallback against noise and gradients)
        return try {
            val binary = BinaryBitmap(GlobalHistogramBinarizer(source))
            val res = reader.decodeWithState(binary)
            reader.reset()
            val latency = System.currentTimeMillis() - startTime
            if (res.text == expectedContent) {
                DecodeBenchmarkResult(true, latency, degradation, FailureStage.NONE)
            } else {
                DecodeBenchmarkResult(false, latency, degradation, FailureStage.CONTENT_MISMATCH)
            }
        } catch (e: Exception) {
            reader.reset()
            println("[BENCHMARK DEBUG] Pass 2 exception: ${e.javaClass.simpleName}: ${e.message}")
            val latency = System.currentTimeMillis() - startTime
            DecodeBenchmarkResult(false, latency, degradation, FailureStage.DECODING)
        }
    }

    private fun renderPristineQrPixels(matrix: QrMatrix, cellSize: Int, quietZone: Int): IntArray {
        val totalModules = matrix.size + 2 * quietZone
        val dim = totalModules * cellSize
        val pixels = IntArray(dim * dim) { 0xFFFFFFFF.toInt() } // White background

        for (r in 0 until matrix.size) {
            for (c in 0 until matrix.size) {
                if (matrix.isDark(c, r)) {
                    val startX = (c + quietZone) * cellSize
                    val startY = (r + quietZone) * cellSize
                    for (py in startY until startY + cellSize) {
                        for (px in startX until startX + cellSize) {
                            pixels[py * dim + px] = 0xFF000000.toInt() // Black module
                        }
                    }
                }
            }
        }
        return pixels
    }

    private fun downsample2x(src: IntArray, srcW: Int, srcH: Int): IntArray {
        val dstW = srcW / 2
        val dstH = srcH / 2
        val dst = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val p0 = src[(y * 2) * srcW + (x * 2)]
                dst[y * dstW + x] = p0
            }
        }
        return dst
    }

    private fun applyContrastDrift(src: IntArray): IntArray {
        val dst = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            if (p == 0xFF000000.toInt()) {
                dst[i] = 0xFF444444.toInt() // Dark grey instead of black
            } else {
                dst[i] = 0xFFCCCCCC.toInt() // Light grey instead of pure white
            }
        }
        return dst
    }

    private fun applyBoxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(src.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var sum = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += src[(y + dy) * w + (x + dx)] and 0xFF
                    }
                }
                val avg = sum / 9
                dst[y * w + x] = 0xFF000000.toInt() or (avg shl 16) or (avg shl 8) or avg
            }
        }
        return dst
    }

    private fun applyNoise(
        src: IntArray,
        width: Int,
        height: Int,
        quietZone: Int,
        cellSize: Int,
        matrixSize: Int,
        noiseRatio: Float,
        seed: Long
    ): IntArray {
        val dst = src.copyOf()
        val rng = Random(seed)
        val noiseCount = (src.size * noiseRatio).toInt()
        for (i in 0 until noiseCount) {
            val idx = rng.nextInt(src.size)
            val px = idx % width
            val py = idx / width
            val col = (px / cellSize) - quietZone
            val row = (py / cellSize) - quietZone
            // Protected patterns (finders + quiet zone) must not be corrupted
            val isFinder = (col in -1..8 && row in -1..8) ||
                           (col in (matrixSize - 9)..(matrixSize) && row in -1..8) ||
                           (col in -1..8 && row in (matrixSize - 9)..(matrixSize))
            val isQuietZone = col < 0 || row < 0 || col >= matrixSize || row >= matrixSize
            if (!isFinder && !isQuietZone) {
                dst[idx] = if (rng.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        return dst
    }
}
