package com.veilframe.app.qr.encoder.ef

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.model.QrMatrix

/**
 * Result of encoding content with [EfQrEncoder].
 */
data class EfEncodedQr(
    val model: QRCodeModel,
    val matrix: QrMatrix,
    val typeTable: Array<Array<QRPointType>>,
    val version: Int,
    val errorCorrection: EfCorrectionLevel,
    val maskPattern: EfMaskPattern
) {
    fun pointTypeAt(col: Int, row: Int): QRPointType {
        if (row in 0 until model.moduleCount && col in 0 until model.moduleCount) {
            return typeTable[row][col]
        }
        return QRPointType.DATA
    }
}

/**
 * High-fidelity QR Code Encoder port of EFQRCode's underlying `QRCodeSwift` engine.
 *
 * Guarantees 100% bit-for-bit, module-for-module matrix identity with EFQRCode by
 * using exact 8-bit byte mode encoding, Reed-Solomon polynomial math, qrcode.js
 * penalty score mask selection, and the canonical point type table.
 */
object EfQrEncoder {

    fun encode(
        content: String,
        errorCorrection: EfCorrectionLevel = EfCorrectionLevel.H
    ): EfEncodedQr {
        require(content.isNotBlank()) { "QR content must not be blank" }

        val data = content.toByteArray(Charsets.UTF_8)
        val model = QRCodeModel(data, errorCorrection, needTypeTable = true)
        val typeTable = model.getTypeTable()

        val matrix = QrMatrix(
            size = model.moduleCount,
            version = model.typeNumber,
            errorCorrection = errorCorrection.toZxingEcLevel(),
            typeTable = typeTable,
            isDarkPredicate = { col, row -> model.isDark(row, col) }
        )

        return EfEncodedQr(
            model = model,
            matrix = matrix,
            typeTable = typeTable,
            version = model.typeNumber,
            errorCorrection = errorCorrection,
            maskPattern = model.bestMaskPattern
        )
    }

    fun encode(
        content: String,
        zxingLevel: ErrorCorrectionLevel
    ): EfEncodedQr {
        val efLevel = when (zxingLevel) {
            ErrorCorrectionLevel.L -> EfCorrectionLevel.L
            ErrorCorrectionLevel.M -> EfCorrectionLevel.M
            ErrorCorrectionLevel.Q -> EfCorrectionLevel.Q
            ErrorCorrectionLevel.H -> EfCorrectionLevel.H
        }
        return encode(content, efLevel)
    }
}

fun EfCorrectionLevel.toZxingEcLevel(): ErrorCorrectionLevel = when (this) {
    EfCorrectionLevel.L -> ErrorCorrectionLevel.L
    EfCorrectionLevel.M -> ErrorCorrectionLevel.M
    EfCorrectionLevel.Q -> ErrorCorrectionLevel.Q
    EfCorrectionLevel.H -> ErrorCorrectionLevel.H
}
