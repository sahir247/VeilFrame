package com.veilframe.app.cv.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.veilframe.app.cv.core.BitmapBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented round-trip tests: the WeChatQRCode engine that users scan with
 * must decode what the QR pipeline renders, and the reliability gate must be
 * runnable end-to-end on device.
 *
 * These tests intentionally use the SAME engine instance path as production
 * ([WeChatQrEngine.install]) — validator and scanner share it by design.
 */
@RunWith(AndroidJUnit4::class)
class WeChatQrRoundTripTest {

    private val payload = "https://veilframe.local/cv-gate"

    private fun renderQr(payload: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    @Test
    fun wechatQrCodeDecodesBasicRaster() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assumeTrue("WeChatQRCode unavailable in this environment", engine.isAvailable)

        val mat = BitmapBridge.toMat(renderQr(payload))
        try {
            val detections = engine.decode(mat)
            assertTrue("WeChatQRCode decoded nothing", detections.isNotEmpty())
            assertEquals(payload, detections.first().rawValue)
            assertEquals(QrDecodeSource.WECHAT, detections.first().source)
        } finally {
            mat.release()
        }
    }

    @Test
    fun reliabilityGateApprovesBasicRaster() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assumeTrue("WeChatQRCode unavailable in this environment", engine.isAvailable)

        val mat = BitmapBridge.toMat(renderQr(payload))
        try {
            val report = QrReliabilityGate.run(
                image = mat,
                expectedPayload = payload,
                probe = QrReliabilityGate.wechatThenMlKitProbe { null },
            )
            assertEquals(
                "gate blocked a basic raster: ${report.reasons}",
                QrReliabilityGate.Level.PASS,
                report.level,
            )
            assertTrue(report.releaseApproved)
            assertEquals("${QrStressMatrix.ALL.size}/${QrStressMatrix.ALL.size}", report.wechatCoverage)
        } finally {
            mat.release()
        }
    }

    @Test
    fun reliabilityGateBlocksCorruptedImage() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assumeTrue("WeChatQRCode unavailable in this environment", engine.isAvailable)

        // Pure noise: nothing resembling a QR symbol exists.
        val noise = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        val pixels = IntArray(512 * 512) { random.nextInt() or (0xFF shl 24) }
        noise.setPixels(pixels, 0, 512, 0, 0, 512, 512)

        val mat = BitmapBridge.toMat(noise)
        try {
            val report = QrReliabilityGate.run(
                image = mat,
                expectedPayload = payload,
                probe = QrReliabilityGate.wechatThenMlKitProbe { null },
            )
            assertEquals(QrReliabilityGate.Level.FAIL, report.level)
            assertTrue(!report.releaseApproved)
        } finally {
            mat.release()
        }
    }
}
