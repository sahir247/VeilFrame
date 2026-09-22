package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.clamp255
import com.veilframe.app.upscale.inference.float16ToFloat
import com.veilframe.app.upscale.inference.floatToFloat16
import com.veilframe.app.upscale.inference.mixColors
import com.veilframe.app.upscale.inference.roundedUpTo
import com.veilframe.app.upscale.inference.smoothStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProcessorArchitectureTest {

    @Test
    fun testRoundedUpTo() {
        assertEquals(8, 1.roundedUpTo(8))
        assertEquals(8, 7.roundedUpTo(8))
        assertEquals(8, 8.roundedUpTo(8))
        assertEquals(16, 9.roundedUpTo(8))
        assertEquals(512, 510.roundedUpTo(8))
        assertEquals(512, 512.roundedUpTo(8))
        assertEquals(520, 513.roundedUpTo(8))
    }

    @Test
    fun testClamp255() {
        assertEquals(0, clamp255(-10f))
        assertEquals(0, clamp255(0f))
        assertEquals(128, clamp255(128.2f))
        assertEquals(255, clamp255(255f))
        assertEquals(255, clamp255(300f))
    }

    @Test
    fun testFloat16Roundtrip() {
        val testValues = floatArrayOf(0.0f, 0.5f, 1.0f, -1.0f, 10.0f, 255.0f, 0.001f)
        for (v in testValues) {
            val fp16 = floatToFloat16(v)
            val recovered = float16ToFloat(fp16)
            assertEquals("FP16 roundtrip for $v", v, recovered, 0.01f)
        }
    }

    @Test
    fun testSmoothStepBoundsAndMonotonicity() {
        val length = 32
        assertEquals(0f, smoothStep(0, length), 0.001f)
        assertEquals(1f, smoothStep(31, length), 0.001f)
        assertEquals(1f, smoothStep(40, length), 0.001f) // clamped

        var prev = 0f
        for (i in 0 until length) {
            val current = smoothStep(i, length)
            assertTrue("smoothStep must be monotonically non-decreasing at step $i", current >= prev)
            prev = current
        }
        assertEquals(0.5f, smoothStep(length / 2, length), 0.05f)
    }

    @Test
    fun testMixColorsInterpolation() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()

        // 0% white -> black
        val mixed0 = mixColors(white, black, 1f)
        assertEquals(black, mixed0)

        // 100% white -> white
        val mixed100 = mixColors(black, white, 1f)
        assertEquals(white, mixed100)

        // 50% midpoint
        val mixed50 = mixColors(black, white, 0.5f)
        val r = (mixed50 ushr 16) and 0xff
        val g = (mixed50 ushr 8) and 0xff
        val b = mixed50 and 0xff
        assertTrue("Gray midpoint around 127/128", r in 127..128 && g in 127..128 && b in 127..128)
    }
}
