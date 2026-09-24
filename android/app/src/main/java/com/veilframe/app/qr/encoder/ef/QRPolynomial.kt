package com.veilframe.app.qr.encoder.ef

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
                nums[i + j] = nums[i + j] xor QRMath.gexp(QRMath.glog(get(i)) + QRMath.glog(e[j]))
            }
        }
        return QRPolynomial(nums)
    }

    fun mod(e: QRPolynomial): QRPolynomial {
        if (count - e.count < 0) {
            return this
        }
        val ratio = QRMath.glog(get(0)) - QRMath.glog(e[0])
        val nums = IntArray(count)
        for (i in 0 until count) {
            nums[i] = get(i)
        }
        for (i in 0 until e.count) {
            nums[i] = nums[i] xor QRMath.gexp(QRMath.glog(e[i]) + ratio)
        }
        return QRPolynomial(nums).mod(e)
    }

    fun moded(e: QRPolynomial): QRPolynomial = mod(e)

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
