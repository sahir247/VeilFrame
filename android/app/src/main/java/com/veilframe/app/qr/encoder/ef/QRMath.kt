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

/**
 * GF(2^8) polynomial operations matching QRCodeSwift's `QRPolynomial`.
 */
class QRPolynomial(nums: IntArray, shift: Int = 0) {
    private val numbers: IntArray

    init {
        require(nums.isNotEmpty()) { "polynomial should have at least 1 term" }
        var offset = 0
        while (offset < nums.size && nums[offset] == 0) {
            offset++
        }
        numbers = IntArray(nums.size - offset + shift)
        for (i in 0 until nums.size - offset) {
            numbers[i] = nums[i + offset]
        }
    }

    constructor(vararg nums: Int) : this(nums, 0)

    operator fun get(index: Int): Int = numbers[index]

    val count: Int get() = numbers.size

    fun multiplying(e: QRPolynomial): QRPolynomial {
        val nums = IntArray(count + e.count - 1)
        for (i in 0 until count) {
            for (j in 0 until e.count) {
                nums[i + j] = nums[i + j] xor QRMath.gexp(QRMath.glog(this[i]) + QRMath.glog(e[j]))
            }
        }
        return QRPolynomial(nums)
    }

    fun moded(by: QRPolynomial): QRPolynomial {
        if (count - by.count < 0) return this
        val ratio = QRMath.glog(this[0]) - QRMath.glog(by[0])
        val num = IntArray(count) { numbers[it] }
        for (i in 0 until by.count) {
            num[i] = num[i] xor QRMath.gexp(QRMath.glog(by[i]) + ratio)
        }
        return QRPolynomial(num).moded(by)
    }

    companion object {
        fun errorCorrectPolynomial(errorCorrectLength: Int): QRPolynomial {
            var a = QRPolynomial(1)
            for (i in 0 until errorCorrectLength) {
                a = a.multiplying(QRPolynomial(1, QRMath.gexp(i)))
            }
            return a
        }
    }
}
