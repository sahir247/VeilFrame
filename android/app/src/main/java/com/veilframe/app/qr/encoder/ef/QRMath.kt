package com.veilframe.app.qr.encoder.ef

/**
 * Pure Galois Field GF(2^8) math matching QRCodeSwift's `QRMath`.
 */
object QRMath {
    val EXP_TABLE = IntArray(256)
    val LOG_TABLE = IntArray(256)

    init {
        for (i in 0 until 8) {
            EXP_TABLE[i] = 1 shl i
        }
        for (i in 8 until 256) {
            EXP_TABLE[i] = EXP_TABLE[i - 4] xor EXP_TABLE[i - 5] xor EXP_TABLE[i - 6] xor EXP_TABLE[i - 8]
        }
        for (i in 0 until 255) {
            LOG_TABLE[EXP_TABLE[i]] = i
        }
    }

    fun glog(n: Int): Int {
        require(n > 0) { "glog only works with n > 0, not $n" }
        return LOG_TABLE[n]
    }

    fun gexp(n: Int): Int {
        var v = n
        while (v < 0) v += 255
        while (v >= 256) v -= 255
        return EXP_TABLE[v]
    }
}
