package com.veilframe.app.qr.decoder

import android.graphics.Bitmap
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.qr.WeChatQrEngine

/**
 * WeChatQrDecoder — primary QR decoder adapter.
 *
 * Exposes `cv::wechat_qrcode::WeChatQRCode` (the PRIMARY engine of VeilFrame's
 * QR stack) through the existing [QrDecoder] surface so generator-side
 * validation, scanner fallbacks and export gates all exercise the SAME engine
 * users will rely on.
 *
 * Chain contract (see ScanabilityValidator / QrScanner):
 *   WeChatQRCode (this)  →  Google ML Kit  →  ZXing (legacy coverage only)
 *
 * `cv::QRCodeDetector` is NOT part of this chain (diagnostic use only).
 */
class WeChatQrDecoder : QrDecoder {

    override val id: String = ID

    /** False when OpenCV native code or the Caffe models could not be loaded. */
    val isAvailable: Boolean get() = WeChatQrEngine.isAvailable()

    override suspend fun decode(bitmap: Bitmap): DecodeResult {
        val start = System.currentTimeMillis()
        val engine = WeChatQrEngine.get()
        if (!engine.isAvailable) {
            return DecodeResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = "WeChatQRCode engine unavailable",
                decoderId = id,
            )
        }
        val mat = BitmapBridge.toMat(bitmap)
        return try {
            val report = engine.decodeReport(mat)
            val detection = report.detections.firstOrNull()
            val latency = System.currentTimeMillis() - start
            if (detection != null) {
                DecodeResult(
                    success = true,
                    text = detection.rawValue,
                    latencyMs = latency,
                    decoderId = id,
                )
            } else {
                DecodeResult(
                    success = false,
                    latencyMs = latency,
                    // Engine failure ≠ "no QR" — keep them distinguishable.
                    error = report.engineError
                        ?.let { "WeChatQRCode engine failure: ${it.message ?: it::class.java.simpleName}" }
                        ?: "WeChatQRCode found no decodable QR",
                    decoderId = id,
                )
            }
        } catch (t: Throwable) {
            DecodeResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = t.message ?: "WeChatQRCode decode failed",
                decoderId = id,
            )
        } finally {
            mat.release()
        }
    }

    override suspend fun decodeMultiple(bitmap: Bitmap): List<DecodeResult> {
        val start = System.currentTimeMillis()
        val engine = WeChatQrEngine.get()
        if (!engine.isAvailable) return emptyList()
        val mat = BitmapBridge.toMat(bitmap)
        return try {
            engine.decode(mat).map {
                DecodeResult(
                    success = true,
                    text = it.rawValue,
                    latencyMs = System.currentTimeMillis() - start,
                    decoderId = id,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            mat.release()
        }
    }

    companion object {
        const val ID = "WeChatQRCode"
    }
}
