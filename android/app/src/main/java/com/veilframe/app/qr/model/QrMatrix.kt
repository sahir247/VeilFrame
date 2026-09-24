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
 * 8-way spatial connectivity matrix for a QR module.
 *
 * Models topological relationships with adjacent modules (orthogonal and diagonal),
 * enabling organic blob rendering, directional capsuling, and smooth corner filleting
 * while respecting the discrete module grid.
 */
data class ModuleNeighborhood(
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val upLeft: Boolean = false,
    val upRight: Boolean = false,
    val downLeft: Boolean = false,
    val downRight: Boolean = false
) {
    /** True if module has no dark orthogonal neighbors. */
    val isIsolated: Boolean get() = !up && !down && !left && !right

    /** True if module connects horizontally but not vertically. */
    val isHorizontalLine: Boolean get() = (left || right) && !up && !down

    /** True if module connects vertically but not horizontally. */
    val isVerticalLine: Boolean get() = (up || down) && !left && !right

    /** True if module connects in all 4 cardinal directions. */
    val isCross: Boolean get() = up && down && left && right

    /** Number of cardinal orthogonal connections (0 to 4). */
    val orthogonalCount: Int
        get() = (if (up) 1 else 0) + (if (down) 1 else 0) + (if (left) 1 else 0) + (if (right) 1 else 0)

    /** Number of diagonal connections (0 to 4). */
    val diagonalCount: Int
        get() = (if (upLeft) 1 else 0) + (if (upRight) 1 else 0) + (if (downLeft) 1 else 0) + (if (downRight) 1 else 0)

    /** Corner detection flags. */
    val isCornerTopLeft: Boolean get() = !up && !left && (down && right)
    val isCornerTopRight: Boolean get() = !up && !right && (down && left)
    val isCornerBottomLeft: Boolean get() = !down && !left && (up && right)
    val isCornerBottomRight: Boolean get() = !down && !right && (up && left)
    val isCorner: Boolean get() = isCornerTopLeft || isCornerTopRight || isCornerBottomLeft || isCornerBottomRight

    /** T-Junction detection flags. */
    val isTJunction: Boolean get() = orthogonalCount == 3
}

/**
 * Logical representation of a single QR module in the matrix.
 */
data class QrModule(
    val col: Int,
    val row: Int,
    val isDark: Boolean,
    val role: QrModuleRole,
    val neighbors: ModuleNeighborhood = ModuleNeighborhood()
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
    val typeTable: Array<Array<com.veilframe.app.qr.encoder.ef.QRPointType>>? = null,
    private val isDarkPredicate: (col: Int, row: Int) -> Boolean
) {
    /**
     * Backward-compatible taxonomy mirroring EFQRCode's QRPointType.
     */
    enum class ModuleType {
        DATA, POS_CENTER, POS_OTHER, ALIGN_CENTER, ALIGN_OTHER, TIMING, FORMAT, VERSION
    }

    val ecLevel: ErrorCorrectionLevel get() = errorCorrection

    val functionMask: FunctionPatternMask by lazy {
        FunctionPatternMask(size, version)
    }

    /** True if module at (col, row) is dark. */
    fun isDark(col: Int, row: Int): Boolean = isDarkPredicate(col, row)

    /** Returns the functional or data role of the module at (col, row). */
    fun roleAt(col: Int, row: Int): QrModuleRole = functionMask.roleAt(col, row)

    /** Computes the 8-way spatial neighborhood for the module at (col, row). */
    fun neighborhoodAt(
        col: Int,
        row: Int,
        predicate: (c: Int, r: Int) -> Boolean = { c, r -> isDark(c, r) }
    ): ModuleNeighborhood {
        fun safe(c: Int, r: Int): Boolean = c in 0 until size && r in 0 until size && predicate(c, r)
        return ModuleNeighborhood(
            up = safe(col, row - 1),
            down = safe(col, row + 1),
            left = safe(col - 1, row),
            right = safe(col + 1, row),
            upLeft = safe(col - 1, row - 1),
            upRight = safe(col + 1, row - 1),
            downLeft = safe(col - 1, row + 1),
            downRight = safe(col + 1, row + 1)
        )
    }

    /** Returns the complete [QrModule] descriptor at (col, row), including spatial neighborhood. */
    fun moduleAt(col: Int, row: Int): QrModule = QrModule(
        col = col,
        row = row,
        isDark = isDark(col, row),
        role = roleAt(col, row),
        neighbors = neighborhoodAt(col, row)
    )

    /** True if the module is part of a protected functional pattern. */
    fun isProtected(col: Int, row: Int): Boolean = roleAt(col, row).isProtected

    /**
     * Exact 1:1 classification matching QRCodeSwift's [com.veilframe.app.qr.encoder.ef.QRPointType].
     * Specifically, [com.veilframe.app.qr.encoder.ef.QRPointType.POS_CENTER] is strictly the 1-module center of each finder.
     */
    fun efPointTypeAt(col: Int, row: Int): com.veilframe.app.qr.encoder.ef.QRPointType {
        if (col !in 0 until size || row !in 0 until size) return com.veilframe.app.qr.encoder.ef.QRPointType.DATA

        if (typeTable != null) {
            return typeTable[row][col]
        }

        val isFinderCenter = (col == 3 && row == 3) ||
                (col == 3 && row == size - 4) ||
                (col == size - 4 && row == 3)
        if (isFinderCenter) return com.veilframe.app.qr.encoder.ef.QRPointType.POS_CENTER

        return when (functionMask[col, row]) {
            FunctionPatternType.FINDER_CORE,
            FunctionPatternType.FINDER_LIGHT,
            FunctionPatternType.FINDER_OUTER,
            FunctionPatternType.SEPARATOR -> com.veilframe.app.qr.encoder.ef.QRPointType.POS_OTHER
            FunctionPatternType.ALIGNMENT_CENTER -> com.veilframe.app.qr.encoder.ef.QRPointType.ALIGN_CENTER
            FunctionPatternType.ALIGNMENT_OTHER -> com.veilframe.app.qr.encoder.ef.QRPointType.ALIGN_OTHER
            FunctionPatternType.TIMING -> com.veilframe.app.qr.encoder.ef.QRPointType.TIMING
            FunctionPatternType.FORMAT -> com.veilframe.app.qr.encoder.ef.QRPointType.FORMAT
            FunctionPatternType.VERSION -> com.veilframe.app.qr.encoder.ef.QRPointType.VERSION
            FunctionPatternType.DATA -> com.veilframe.app.qr.encoder.ef.QRPointType.DATA
        }
    }

    /** Classification query (backward compatible). */
    fun typeAt(col: Int, row: Int): ModuleType {
        return when (efPointTypeAt(col, row)) {
            com.veilframe.app.qr.encoder.ef.QRPointType.POS_CENTER -> ModuleType.POS_CENTER
            com.veilframe.app.qr.encoder.ef.QRPointType.POS_OTHER -> ModuleType.POS_OTHER
            com.veilframe.app.qr.encoder.ef.QRPointType.ALIGN_CENTER -> ModuleType.ALIGN_CENTER
            com.veilframe.app.qr.encoder.ef.QRPointType.ALIGN_OTHER -> ModuleType.ALIGN_OTHER
            com.veilframe.app.qr.encoder.ef.QRPointType.TIMING -> ModuleType.TIMING
            com.veilframe.app.qr.encoder.ef.QRPointType.FORMAT -> ModuleType.FORMAT
            com.veilframe.app.qr.encoder.ef.QRPointType.VERSION -> ModuleType.VERSION
            com.veilframe.app.qr.encoder.ef.QRPointType.DATA -> ModuleType.DATA
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
