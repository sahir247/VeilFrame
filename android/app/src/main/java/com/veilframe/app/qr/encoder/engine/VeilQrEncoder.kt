package com.veilframe.app.qr.encoder.engine

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.model.QrMatrix

/**
 * Result of encoding content with [VeilQrEncoder].
 */
data class VeilEncodedQr(
    val model: QRCodeModel,
    val matrix: QrMatrix,
    val typeTable: Array<Array<QRPointType>>,
    val version: Int,
    val errorCorrection: VeilCorrectionLevel,
    val maskPattern: VeilMaskPattern
) {
    fun pointTypeAt(col: Int, row: Int): QRPointType {
        if (row in 0 until model.moduleCount && col in 0 until model.moduleCount) {
            return typeTable[row][col]
        }
        return QRPointType.DATA
    }
}

/**
 * High-fidelity QR Code Encoder for VeilFrame Art Engine.
 *
 * Guarantees 100% bit-for-bit, module-for-module matrix identity for artistic QR generation by
 * using exact 8-bit byte mode encoding, Reed-Solomon polynomial math, penalty score mask
 * selection, and the canonical point type table.
 */
object VeilQrEncoder {

    fun encode(
        content: String,
        errorCorrection: VeilCorrectionLevel = VeilCorrectionLevel.H,
        maskPattern: VeilMaskPattern? = null,
        writeEci: Boolean = false
    ): VeilEncodedQr {
        val data = content.toByteArray(Charsets.UTF_8)
        return encode(data, errorCorrection, maskPattern, writeEci)
    }

    fun encode(
        data: ByteArray,
        errorCorrection: VeilCorrectionLevel = VeilCorrectionLevel.H,
        maskPattern: VeilMaskPattern? = null,
        writeEci: Boolean = false
    ): VeilEncodedQr {
        val model = QRCodeModel(data, errorCorrection, needTypeTable = true, explicitMaskPattern = maskPattern, writeEci = writeEci)
        val typeTable = model.getTypeTable()

        val matrix = QrMatrix(
            size = model.moduleCount,
            version = model.typeNumber,
            errorCorrection = errorCorrection.toZxingEcLevel(),
            typeTable = typeTable,
            isDarkPredicate = { col, row -> model.isDark(row, col) }
        )

        return VeilEncodedQr(
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
        zxingLevel: ErrorCorrectionLevel,
        writeEci: Boolean = false
    ): VeilEncodedQr {
        val level = when (zxingLevel) {
            ErrorCorrectionLevel.L -> VeilCorrectionLevel.L
            ErrorCorrectionLevel.M -> VeilCorrectionLevel.M
            ErrorCorrectionLevel.Q -> VeilCorrectionLevel.Q
            ErrorCorrectionLevel.H -> VeilCorrectionLevel.H
        }
        return encode(content, level, writeEci = writeEci)
    }
}

fun VeilCorrectionLevel.toZxingEcLevel(): ErrorCorrectionLevel = when (this) {
    VeilCorrectionLevel.L -> ErrorCorrectionLevel.L
    VeilCorrectionLevel.M -> ErrorCorrectionLevel.M
    VeilCorrectionLevel.Q -> ErrorCorrectionLevel.Q
    VeilCorrectionLevel.H -> ErrorCorrectionLevel.H
}
