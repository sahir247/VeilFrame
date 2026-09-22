package com.veilframe.app.qr.model

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder

/**
 * Pure logical representation of a QR Code grid.
 *
 * Contains dimensions, module occupancy states (dark / light), error correction level,
 * and canonical function pattern classification.
 *
 * Visual geometry (coordinates, rounding, sizing, Quiet Zone) is decoupled from this model.
 */
class QrMatrix(
    val size: Int,
    val version: Int,
    val errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    private val isDarkPredicate: (col: Int, row: Int) -> Boolean
) {
    /**
     * Backward-compatible taxonomy mirroring EFQRCode's QRPointType.
     */
    enum class ModuleType {
        DATA, POS_CENTER, POS_OTHER, ALIGN_CENTER, ALIGN_OTHER, TIMING
    }

    val ecLevel: ErrorCorrectionLevel get() = errorCorrection

    val functionMask: FunctionPatternMask by lazy {
        FunctionPatternMask(size, version)
    }

    /** True if module at (col, row) is dark. */
    fun isDark(col: Int, row: Int): Boolean = isDarkPredicate(col, row)

    /** Classification query. */
    fun typeAt(col: Int, row: Int): ModuleType {
        return when (functionMask[col, row]) {
            FunctionPatternType.FINDER_CORE -> ModuleType.POS_CENTER
            FunctionPatternType.FINDER_LIGHT,
            FunctionPatternType.FINDER_OUTER,
            FunctionPatternType.SEPARATOR -> ModuleType.POS_OTHER
            FunctionPatternType.ALIGNMENT_CENTER -> ModuleType.ALIGN_CENTER
            FunctionPatternType.ALIGNMENT_OTHER -> ModuleType.ALIGN_OTHER
            FunctionPatternType.TIMING -> ModuleType.TIMING
            else -> ModuleType.DATA
        }
    }

    /**
     * Convenience constructor that encodes [content] synchronously.
     */
    constructor(content: String, ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M) : this(
        size = QrEncoder.encode(content, ecLevel).matrix.size,
        version = QrEncoder.encode(content, ecLevel).version,
        errorCorrection = ecLevel,
        isDarkPredicate = { c, r -> QrEncoder.encode(content, ecLevel).matrix.isDark(c, r) }
    )
}

typealias ModuleType = QrMatrix.ModuleType
