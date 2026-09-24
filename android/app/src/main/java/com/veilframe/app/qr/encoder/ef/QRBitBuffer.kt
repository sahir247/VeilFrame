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
