package com.veilframe.app.qr.encoder

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.FunctionPatternMask
import com.veilframe.app.qr.model.QrMatrix

/**
 * Result of encoding text/binary content into a logical QR representation.
 */
data class EncodedQr(
    val matrix: QrMatrix,
    val version: Int,
    val errorCorrection: ErrorCorrectionLevel,
    val functionMask: FunctionPatternMask
)

/**
 * QR Code Encoder wrapping ZXing's QR code encoder into the VeilFrame
 * internal logical model.
 */
object QrEncoder {

    fun encode(
        content: String,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): EncodedQr {
        if (content.isBlank()) {
            throw QrError.Input.EmptyContent
        }

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val qrCode: QRCode = try {
            Encoder.encode(content, errorCorrection, hints)
        } catch (t: Throwable) {
            throw QrError.fromThrowable(t, "ZXing encoder failure")
        }
        val byteMatrix = qrCode.matrix
        val size = byteMatrix.width
        val version = qrCode.version?.versionNumber ?: calculateVersion(size)

        val matrix = QrMatrix(
            size = size,
            version = version,
            errorCorrection = errorCorrection,
            isDarkPredicate = { col, row ->
                if (col in 0 until size && row in 0 until size) {
                    byteMatrix[col, row].toInt() != 0
                } else {
                    false
                }
            }
        )

        val functionMask = FunctionPatternMask(size, version)

        return EncodedQr(
            matrix = matrix,
            version = version,
            errorCorrection = errorCorrection,
            functionMask = functionMask
        )
    }

    private fun calculateVersion(size: Int): Int {
        // Size = 17 + 4 * version
        return ((size - 17) / 4).coerceAtLeast(1)
    }
}
