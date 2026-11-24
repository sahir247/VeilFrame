package com.veilframe.app.cv.motion

import org.junit.Assert.*
import org.junit.Test

class MotionLabRawFramePipeTest {
    @Test fun packsArgbPixelsInBgrOrderAndIgnoresAlpha() {
        val row = ByteArray(6)
        MotionLabRawFramePipe.packArgbAsBgr(intArrayOf(0xff123456.toInt(), 0x00789abc),row)
        assertArrayEquals(byteArrayOf(0x56,0x34,0x12,0xbc.toByte(),0x9a.toByte(),0x78),row)
    }

    @Test fun boundsGeometryBeforeAllocation() {
        assertEquals(3840L*2160*3, MotionLabRawFramePipe.Geometry(3840,2160).frameBytes)
        for ((w,h) in listOf(0 to 10, 8193 to 1, 8192 to 8192, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { MotionLabRawFramePipe.Geometry(w,h) }
        }
    }
}
