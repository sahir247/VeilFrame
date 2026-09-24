package com.veilframe.app.qr.encoder.engine

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

    operator fun get(typeNumber: Int): IntArray {
        require(typeNumber in 1..40) { "typeNumber must be in 1..40, was $typeNumber" }
        return PATTERN_POSITION_TABLE[typeNumber - 1]
    }
}
