package com.veilframe.app.qr.encoder.engine

/**
 * VeilFrame Art Engine point classification type matching QRCodeSwift's `QRPointType`.
 */
enum class QRPointType(val rawValue: Int) {
    DATA(0),
    POS_CENTER(1),
    POS_OTHER(2),
    ALIGN_CENTER(3),
    ALIGN_OTHER(4),
    TIMING(5),
    FORMAT(6),
    VERSION(7);

    companion object {
        fun fromRawValue(value: Int): QRPointType =
            entries.firstOrNull { it.rawValue == value } ?: DATA
    }
}

/**
 * QR error correction levels for the VeilFrame Art Engine.
 */
enum class VeilCorrectionLevel(val pattern: Int, val offset: Int) {
    L(pattern = 1, offset = 0),
    M(pattern = 0, offset = 1),
    Q(pattern = 3, offset = 2),
    H(pattern = 2, offset = 3);
}

/**
 * QR mask patterns for the VeilFrame Art Engine.
 */
enum class VeilMaskPattern(val rawValue: Int) {
    _000(0), _001(1), _010(2), _011(3), _100(4), _101(5), _110(6), _111(7);

    fun getMask(i: Int, j: Int): Boolean {
        return when (this) {
            _000 -> (i + j) % 2 == 0
            _001 -> i % 2 == 0
            _010 -> j % 3 == 0
            _011 -> (i + j) % 3 == 0
            _100 -> (i / 2 + j / 3) % 2 == 0
            _101 -> (i * j) % 2 + (i * j) % 3 == 0
            _110 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0
            _111 -> ((i * j) % 3 + (i + j) % 2) % 2 == 0
        }
    }
}

