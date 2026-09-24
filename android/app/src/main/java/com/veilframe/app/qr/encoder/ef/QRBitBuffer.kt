package com.veilframe.app.qr.encoder.ef

/**
 * Bit buffer accumulator matching QRCodeSwift's `QRBitBuffer`.
 */
class QRBitBuffer {
    val buffer = mutableListOf<Int>()
    var bitCount: Int = 0
        private set

    operator fun get(index: Int): Boolean {
        val bufIndex = index / 8
        return ((buffer[bufIndex] ushr (7 - index % 8)) and 1) == 1
    }

    fun put(num: Long, length: Int) {
        for (i in 0 until length) {
            put(((num ushr (length - i - 1)) and 1L) == 1L)
        }
    }

    fun put(bit: Boolean) {
        val bufIndex = bitCount / 8
        if (buffer.size <= bufIndex) {
            buffer.add(0)
        }
        if (bit) {
            buffer[bufIndex] = buffer[bufIndex] or (0x80 ushr (bitCount % 8))
        }
        bitCount++
    }
}

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
        var d = data.toLong() and 0xFFFFFFFFL
        var digit = 0
        while (d != 0L) {
            digit++
            d = d ushr 1
        }
        return digit
    }
}
