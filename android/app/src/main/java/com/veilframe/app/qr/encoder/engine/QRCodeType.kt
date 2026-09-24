package com.veilframe.app.qr.encoder.engine

/**
 * QR Code capacity limits lookup table matching QRCodeSwift's `QRCodeType`.
 */
object QRCodeType {
    val QRCodeLimitLength: Array<IntArray> = arrayOf(
        intArrayOf(17, 14, 11, 7),
        intArrayOf(32, 26, 20, 14),
        intArrayOf(53, 42, 32, 24),
        intArrayOf(78, 62, 46, 34),
        intArrayOf(106, 84, 60, 44),
        intArrayOf(134, 106, 74, 58),
        intArrayOf(154, 122, 86, 64),
        intArrayOf(192, 152, 108, 84),
        intArrayOf(230, 180, 130, 98),
        intArrayOf(271, 213, 151, 119),
        intArrayOf(321, 251, 177, 137),
        intArrayOf(367, 287, 203, 155),
        intArrayOf(425, 331, 241, 177),
        intArrayOf(458, 362, 258, 194),
        intArrayOf(520, 412, 292, 220),
        intArrayOf(586, 450, 322, 250),
        intArrayOf(644, 504, 364, 280),
        intArrayOf(718, 560, 394, 310),
        intArrayOf(792, 624, 442, 338),
        intArrayOf(858, 666, 482, 382),
        intArrayOf(929, 711, 509, 403),
        intArrayOf(1003, 779, 565, 439),
        intArrayOf(1091, 857, 611, 461),
        intArrayOf(1171, 911, 661, 511),
        intArrayOf(1273, 997, 715, 535),
        intArrayOf(1367, 1059, 751, 593),
        intArrayOf(1465, 1125, 805, 625),
        intArrayOf(1528, 1190, 868, 658),
        intArrayOf(1628, 1264, 908, 698),
        intArrayOf(1732, 1370, 982, 742),
        intArrayOf(1840, 1452, 1030, 790),
        intArrayOf(1952, 1538, 1112, 842),
        intArrayOf(2068, 1628, 1168, 898),
        intArrayOf(2188, 1722, 1228, 958),
        intArrayOf(2303, 1809, 1283, 983),
        intArrayOf(2431, 1911, 1351, 1051),
        intArrayOf(2563, 1989, 1423, 1093),
        intArrayOf(2699, 2099, 1499, 1139),
        intArrayOf(2809, 2213, 1579, 1219),
        intArrayOf(2953, 2331, 1663, 1273)
    )

    fun typeNumber(forLength: Int, errorCorrectLevel: VeilCorrectionLevel): Int {
        for (i in QRCodeLimitLength.indices) {
            if (forLength <= QRCodeLimitLength[i][errorCorrectLevel.offset]) {
                return i + 1
            }
        }
        throw IllegalArgumentException("Data length ($forLength bytes) exceeds maximum QR Code capacity")
    }
}
