package com.veilframe.app.cv.qr

import org.opencv.core.Mat
import org.opencv.objdetect.QRCodeDetector

/**
 * QrCodeDetectorDiagnostic — DIAGNOSTIC USE ONLY.
 *
 * `cv::QRCodeDetector` is explicitly NOT the primary QR engine in VeilFrame:
 * it has no detector/super-resolution models and behaves poorly on artistic
 * rasters. It is kept as an extremely lightweight extra signal — e.g. to
 * distinguish "nothing resembles a QR" from "QR present but not decodable"
 * while debugging recovery stages.
 *
 * Never use this class as evidence for a PASS verdict.
 */
class QrCodeDetectorDiagnostic {
    private val detector = try {
        QRCodeDetector()
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        null
    }

    val isAvailable: Boolean get() = detector != null

    /** Best-effort single decode; payload only. */
    fun decodeProbe(image: Mat): String? {
        val d = detector ?: return null
        if (image.empty()) return null
        return try {
            val points = Mat()
            try {
                val text = d.detectAndDecode(image, points)
                text?.takeIf { it.isNotEmpty() }
            } finally {
                points.release()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Cheap presence probe: does anything look like a QR symbol at all? */
    fun looksLikeQr(image: Mat): Boolean {
        val d = detector ?: return false
        if (image.empty()) return false
        return try {
            val points = Mat()
            try {
                d.detectMulti(image, points)
            } finally {
                points.release()
            }
        } catch (e: Exception) {
            false
        }
    }
}
