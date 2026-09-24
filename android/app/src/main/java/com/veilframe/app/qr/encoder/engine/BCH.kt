package com.veilframe.app.qr.encoder.engine

/**
 * BCH error-correcting code calculator matching QRCodeSwift's `BCH`.
 */
object BCH {
    private const val g15 = 0b10100110111
    private const val g18 = 0b1111100100101
    private const val g15Mask = 0b101010000010010
    private val g15Digit = digit(g15)
    private val g18Digit = digit(g18)

    fun typeInfo(data: Int): Int {
        var d = data shl 10
        while (digit(d) - g15Digit >= 0) {
            d = d xor (g15 shl (digit(d) - g15Digit))
        }
        return ((data shl 10) or d) xor g15Mask
    }

    fun typeNumber(data: Int): Int {
        var d = data shl 12
        while (digit(d) - g18Digit >= 0) {
            d = d xor (g18 shl (digit(d) - g18Digit))
        }
        return (data shl 12) or d
    }

    private fun digit(data: Int): Int {
        var d = data
        var c = 0
        while (d != 0) {
            c++
            d = d ushr 1
        }
        return c
    }
}
