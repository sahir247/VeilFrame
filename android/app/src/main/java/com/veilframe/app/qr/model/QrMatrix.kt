package com.veilframe.app.qr.model

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder

/**
 * Strongly-typed ISO/IEC 18004 QR module role taxonomy.
 *
 * Explicitly separates user data from protected functional structures
 * (finders, timing tracks, alignment markers, separators, format and version information).
 */
enum class QrModuleRole {
    DATA,               // User payload & Reed-Solomon error correction codewords
    FINDER_INNER,       // 3x3 central core and 5x5 light ring of the 3 positioning finders
    FINDER_OUTER,       // 7x7 dark outer border of the 3 positioning finders
    SEPARATOR,          // 1-module light separator border surrounding each finder
    TIMING,             // Row 6 and Column 6 alternating timing strips
    ALIGNMENT_CENTER,   // Central anchor dot of alignment patterns (Version >= 2)
    ALIGNMENT_BORDER,   // 5x5 outer ring of alignment patterns
    FORMAT,             // 15-bit format information bands adjacent to finders
    VERSION,            // 3x6 version information blocks (Version >= 7)
    QUIET_ZONE;         // Outer 4-module safety margin

    val isProtected: Boolean
        get() = this != DATA
}

/**
 * Logical representation of a single QR module in the matrix.
 */
data class QrModule(
    val col: Int,
    val row: Int,
    val isDark: Boolean,
    val role: QrModuleRole
) {
    val isProtected: Boolean get() = role.isProtected
}

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

    /** Returns the functional or data role of the module at (col, row). */
    fun roleAt(col: Int, row: Int): QrModuleRole = functionMask.roleAt(col, row)

    /** Returns the complete [QrModule] descriptor at (col, row). */
    fun moduleAt(col: Int, row: Int): QrModule = QrModule(
        col = col,
        row = row,
        isDark = isDark(col, row),
        role = roleAt(col, row)
    )

    /** True if the module is part of a protected functional pattern. */
    fun isProtected(col: Int, row: Int): Boolean = roleAt(col, row).isProtected

    /** Classification query (backward compatible). */
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
     * Convenience constructor that encodes [content] synchronously exactly once.
     */
    constructor(content: String, ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M) : this(
        encoded = QrEncoder.encode(content, ecLevel).matrix,
        ecLevel = ecLevel
    )

    private constructor(encoded: QrMatrix, ecLevel: ErrorCorrectionLevel) : this(
        size = encoded.size,
        version = encoded.version,
        errorCorrection = ecLevel,
        isDarkPredicate = { c, r -> encoded.isDark(c, r) }
    )
}

typealias ModuleType = QrMatrix.ModuleType
