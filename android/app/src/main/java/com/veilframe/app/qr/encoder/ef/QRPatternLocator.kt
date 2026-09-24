package com.veilframe.app.qr.encoder.ef

/**
 * Alignment pattern locator positions for QR Code versions 1 through 40.
 * Matches QRCodeSwift's `QRPatternLocator`.
 */
object QRPatternLocator {
    private val PATTERN_POSITION_TABLE: Array<IntArray> = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
        intArrayOf(6, 30, 54),
        intArrayOf(6, 32, 58),
        intArrayOf(6, 34, 62),
        intArrayOf(6, 26, 46, 66),
        intArrayOf(6, 26, 48, 70),
        intArrayOf(6, 26, 50, 74),
        intArrayOf(6, 30, 54, 78),
        intArrayOf(6, 30, 56, 82),
        intArrayOf(6, 30, 58, 86),
        intArrayOf(6, 34, 62, 90),
        intArrayOf(6, 28, 50, 72, 94),
        intArrayOf(6, 26, 50, 74, 98),
        intArrayOf(6, 30, 54, 78, 102),
        intArrayOf(6, 28, 54, 80, 106),
        intArrayOf(6, 32, 58, 84, 110),
        intArrayOf(6, 30, 58, 86, 114),
        intArrayOf(6, 34, 62, 90, 118),
        intArrayOf(6, 26, 50, 74, 98, 122),
        intArrayOf(6, 30, 54, 78, 102, 126),
        intArrayOf(6, 26, 52, 78, 104, 130),
        intArrayOf(6, 30, 56, 82, 108, 134),
        intArrayOf(6, 34, 60, 86, 112, 138),
        intArrayOf(6, 30, 58, 86, 114, 142),
        intArrayOf(6, 34, 62, 90, 118, 146),
        intArrayOf(6, 30, 54, 78, 102, 126, 150),
        intArrayOf(6, 24, 50, 76, 102, 128, 154),
        intArrayOf(6, 28, 54, 80, 106, 132, 158),
        intArrayOf(6, 32, 58, 84, 110, 136, 162),
        intArrayOf(6, 26, 54, 82, 110, 138, 166),
        intArrayOf(6, 30, 58, 86, 114, 142, 170)
    )

    operator fun get(typeNumber: Int): IntArray = PATTERN_POSITION_TABLE[typeNumber - 1]
}

/**
 * QR Reed-Solomon block descriptor and block table for versions 1..40.
 * Matches QRCodeSwift's `QRRSBlock`.
 */
data class QRRSBlock(val totalCount: Int, val dataCount: Int) {
    companion object {
        val RS_BLOCK_TABLE: Array<IntArray> = arrayOf(
            intArrayOf(1, 26, 19),
            intArrayOf(1, 26, 16),
            intArrayOf(1, 26, 13),
            intArrayOf(1, 26, 9),
            intArrayOf(1, 44, 34),
            intArrayOf(1, 44, 28),
            intArrayOf(1, 44, 22),
            intArrayOf(1, 44, 16),
            intArrayOf(1, 70, 55),
            intArrayOf(1, 70, 44),
            intArrayOf(2, 35, 17),
            intArrayOf(2, 35, 13),
            intArrayOf(1, 100, 80),
            intArrayOf(2, 50, 32),
            intArrayOf(2, 50, 24),
            intArrayOf(4, 25, 9),
            intArrayOf(1, 134, 108),
            intArrayOf(2, 67, 43),
            intArrayOf(2, 33, 15, 2, 34, 16),
            intArrayOf(2, 33, 11, 2, 34, 12),
            intArrayOf(2, 86, 68),
            intArrayOf(4, 43, 27),
            intArrayOf(4, 43, 19),
            intArrayOf(4, 43, 15),
            intArrayOf(2, 98, 78),
            intArrayOf(4, 49, 31),
            intArrayOf(2, 32, 14, 4, 33, 15),
            intArrayOf(4, 39, 13, 1, 40, 14),
            intArrayOf(2, 121, 97),
            intArrayOf(2, 60, 38, 2, 61, 39),
            intArrayOf(4, 40, 18, 2, 41, 19),
            intArrayOf(4, 40, 14, 2, 41, 15),
            intArrayOf(2, 146, 116),
            intArrayOf(3, 58, 36, 2, 59, 37),
            intArrayOf(4, 36, 16, 4, 37, 17),
            intArrayOf(4, 36, 12, 4, 37, 13),
            intArrayOf(2, 86, 68, 2, 87, 69),
            intArrayOf(4, 69, 43, 1, 70, 44),
            intArrayOf(6, 43, 19, 2, 44, 20),
            intArrayOf(6, 43, 15, 2, 44, 16),
            intArrayOf(4, 101, 81),
            intArrayOf(1, 80, 50, 4, 81, 51),
            intArrayOf(4, 50, 22, 4, 51, 23),
            intArrayOf(3, 36, 12, 8, 37, 13),
            intArrayOf(2, 116, 92, 2, 117, 93),
            intArrayOf(6, 58, 36, 2, 59, 37),
            intArrayOf(4, 46, 20, 6, 47, 21),
            intArrayOf(7, 42, 14, 4, 43, 15),
            intArrayOf(4, 133, 107),
            intArrayOf(8, 59, 37, 1, 60, 38),
            intArrayOf(8, 44, 20, 4, 45, 21),
            intArrayOf(12, 33, 11, 4, 34, 12),
            intArrayOf(3, 145, 115, 1, 146, 116),
            intArrayOf(4, 64, 40, 5, 65, 41),
            intArrayOf(11, 36, 16, 5, 37, 17),
            intArrayOf(11, 36, 12, 5, 37, 13),
            intArrayOf(5, 109, 87, 1, 110, 88),
            intArrayOf(5, 65, 41, 5, 66, 42),
            intArrayOf(5, 54, 24, 7, 55, 25),
            intArrayOf(11, 36, 12, 7, 37, 13),
            intArrayOf(5, 122, 98, 1, 123, 99),
            intArrayOf(7, 73, 45, 3, 74, 46),
            intArrayOf(15, 43, 19, 2, 44, 20),
            intArrayOf(3, 45, 15, 13, 46, 16),
            intArrayOf(1, 135, 107, 5, 136, 108),
            intArrayOf(10, 74, 46, 1, 75, 47),
            intArrayOf(1, 50, 22, 15, 51, 23),
            intArrayOf(2, 42, 14, 17, 43, 15),
            intArrayOf(5, 150, 120, 1, 151, 121),
            intArrayOf(9, 69, 43, 4, 70, 44),
            intArrayOf(17, 50, 22, 1, 51, 23),
            intArrayOf(2, 42, 14, 19, 43, 15),
            intArrayOf(3, 141, 113, 4, 142, 114),
            intArrayOf(3, 70, 44, 11, 71, 45),
            intArrayOf(17, 47, 21, 4, 48, 22),
            intArrayOf(9, 39, 13, 16, 40, 14),
            intArrayOf(3, 135, 107, 5, 136, 108),
            intArrayOf(3, 67, 41, 13, 68, 42),
            intArrayOf(15, 54, 24, 5, 55, 25),
            intArrayOf(15, 43, 15, 10, 44, 16),
            intArrayOf(4, 144, 116, 4, 145, 117),
            intArrayOf(17, 68, 42),
            intArrayOf(17, 50, 22, 6, 51, 23),
            intArrayOf(19, 46, 16, 6, 47, 17),
            intArrayOf(2, 139, 111, 7, 140, 112),
            intArrayOf(17, 74, 46),
            intArrayOf(7, 54, 24, 16, 55, 25),
            intArrayOf(34, 37, 13),
            intArrayOf(4, 151, 121, 5, 152, 122),
            intArrayOf(4, 75, 47, 14, 76, 48),
            intArrayOf(11, 54, 24, 14, 55, 25),
            intArrayOf(16, 45, 15, 14, 46, 16),
            intArrayOf(6, 147, 117, 4, 148, 118),
            intArrayOf(6, 73, 45, 14, 74, 46),
            intArrayOf(11, 54, 24, 16, 55, 25),
            intArrayOf(30, 46, 16, 2, 47, 17),
            intArrayOf(8, 132, 106, 4, 133, 107),
            intArrayOf(8, 75, 47, 13, 76, 48),
            intArrayOf(7, 54, 24, 22, 55, 25),
            intArrayOf(22, 45, 15, 13, 46, 16),
            intArrayOf(10, 142, 114, 2, 143, 115),
            intArrayOf(19, 74, 46, 4, 75, 47),
            intArrayOf(28, 50, 22, 6, 51, 23),
            intArrayOf(33, 46, 16, 4, 47, 17),
            intArrayOf(8, 152, 122, 4, 153, 123),
            intArrayOf(22, 73, 45, 3, 74, 46),
            intArrayOf(8, 53, 23, 26, 54, 24),
            intArrayOf(12, 45, 15, 28, 46, 16),
            intArrayOf(3, 147, 117, 10, 148, 118),
            intArrayOf(3, 73, 45, 23, 74, 46),
            intArrayOf(4, 54, 24, 31, 55, 25),
            intArrayOf(11, 45, 15, 31, 46, 16),
            intArrayOf(7, 146, 116, 7, 147, 117),
            intArrayOf(21, 73, 45, 7, 74, 46),
            intArrayOf(1, 53, 23, 37, 54, 24),
            intArrayOf(19, 45, 15, 26, 46, 16),
            intArrayOf(5, 145, 115, 10, 146, 116),
            intArrayOf(19, 75, 47, 10, 76, 48),
            intArrayOf(15, 54, 24, 25, 55, 25),
            intArrayOf(23, 45, 15, 25, 46, 16),
            intArrayOf(13, 145, 115, 3, 146, 116),
            intArrayOf(2, 74, 46, 29, 75, 47),
            intArrayOf(42, 54, 24, 1, 55, 25),
            intArrayOf(23, 45, 15, 28, 46, 16),
            intArrayOf(17, 145, 115),
            intArrayOf(10, 74, 46, 23, 75, 47),
            intArrayOf(10, 54, 24, 35, 55, 25),
            intArrayOf(19, 45, 15, 35, 46, 16),
            intArrayOf(17, 145, 115, 1, 146, 116),
            intArrayOf(14, 74, 46, 21, 75, 47),
            intArrayOf(29, 54, 24, 19, 55, 25),
            intArrayOf(11, 45, 15, 46, 46, 16),
            intArrayOf(13, 145, 115, 6, 146, 116),
            intArrayOf(14, 74, 46, 23, 75, 47),
            intArrayOf(44, 54, 24, 7, 55, 25),
            intArrayOf(59, 46, 16, 1, 47, 17),
            intArrayOf(12, 151, 121, 7, 152, 122),
            intArrayOf(12, 75, 47, 26, 76, 48),
            intArrayOf(39, 54, 24, 14, 55, 25),
            intArrayOf(22, 45, 15, 41, 46, 16),
            intArrayOf(6, 151, 121, 14, 152, 122),
            intArrayOf(6, 75, 47, 34, 76, 48),
            intArrayOf(46, 54, 24, 10, 55, 25),
            intArrayOf(2, 45, 15, 64, 46, 16),
            intArrayOf(17, 152, 122, 4, 153, 123),
            intArrayOf(29, 74, 46, 14, 75, 47),
            intArrayOf(49, 54, 24, 10, 55, 25),
            intArrayOf(24, 45, 15, 46, 46, 16),
            intArrayOf(4, 152, 122, 18, 153, 123),
            intArrayOf(13, 74, 46, 32, 75, 47),
            intArrayOf(48, 54, 24, 14, 55, 25),
            intArrayOf(42, 45, 15, 32, 46, 16),
            intArrayOf(20, 147, 117, 4, 148, 118),
            intArrayOf(40, 75, 47, 7, 76, 48),
            intArrayOf(43, 54, 24, 22, 55, 25),
            intArrayOf(10, 45, 15, 67, 46, 16),
            intArrayOf(19, 148, 118, 6, 149, 119),
            intArrayOf(18, 75, 47, 31, 76, 48),
            intArrayOf(34, 54, 24, 34, 55, 25),
            intArrayOf(20, 45, 15, 61, 46, 16)
        )

        fun getRSBlocks(typeNumber: Int, errorCorrectLevel: EfCorrectionLevel): List<QRRSBlock> {
            val rsBlock = RS_BLOCK_TABLE[(typeNumber - 1) * 4 + errorCorrectLevel.offset]
            val length = rsBlock.size / 3
            val result = mutableListOf<QRRSBlock>()
            for (i in 0 until length) {
                val count = rsBlock[i * 3 + 0]
                val totalCount = rsBlock[i * 3 + 1]
                val dataCount = rsBlock[i * 3 + 2]
                val block = QRRSBlock(totalCount, dataCount)
                for (c in 0 until count) {
                    result.add(block)
                }
            }
            return result
        }
    }
}

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

    fun typeNumber(forLength: Int, errorCorrectLevel: EfCorrectionLevel): Int {
        for (i in QRCodeLimitLength.indices) {
            if (forLength <= QRCodeLimitLength[i][errorCorrectLevel.offset]) {
                return i + 1
            }
        }
        throw IllegalArgumentException("Data length ($forLength bytes) exceeds maximum QR Code capacity")
    }
}
