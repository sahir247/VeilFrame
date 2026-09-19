package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.Float16Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.abs

class Float16UtilsTest {

    @Test
    fun testZeroConversion() {
        val halfZero = Float16Utils.floatToHalf(0.0f)
        assertEquals(0.toShort(), halfZero)
        val recovered = Float16Utils.halfToFloat(halfZero)
        assertEquals(0.0f, recovered, 0.0f)
    }

    @Test
    fun testNormalizedValues() {
        val testValues = floatArrayOf(
            1.0f, -1.0f, 0.5f, -0.5f, 0.123f, 0.75f, 0.25f, 255.0f, 1000.0f
        )
        for (v in testValues) {
            val h = Float16Utils.floatToHalf(v)
            val recovered = Float16Utils.halfToFloat(h)
            val tolerance = abs(v) * 0.002f + 0.001f // Float16 has ~11 bits of precision (~0.1%)
            assertEquals("Mismatch for float $v", v, recovered, tolerance)
        }
    }

    @Test
    fun testSubnormalsAndExtremes() {
        val small = 0.0001f
        val h = Float16Utils.floatToHalf(small)
        val recovered = Float16Utils.halfToFloat(h)
        assertTrue(abs(small - recovered) < 0.00005f)

        val inf = Float.POSITIVE_INFINITY
        val hInf = Float16Utils.floatToHalf(inf)
        assertEquals(Float.POSITIVE_INFINITY, Float16Utils.halfToFloat(hInf), 0.0f)

        val negInf = Float.NEGATIVE_INFINITY
        val hNegInf = Float16Utils.floatToHalf(negInf)
        assertEquals(Float.NEGATIVE_INFINITY, Float16Utils.halfToFloat(hNegInf), 0.0f)
    }

    @Test
    fun testBatchFloat16BufferConversion() {
        val src = floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        val buffer = ShortBuffer.allocate(src.size)
        Float16Utils.fillShortBufferWithFloats(src, buffer)

        for (i in src.indices) {
            val recovered = Float16Utils.halfToFloat(buffer.get(i))
            assertEquals(src[i], recovered, 0.005f)
        }
    }
}
