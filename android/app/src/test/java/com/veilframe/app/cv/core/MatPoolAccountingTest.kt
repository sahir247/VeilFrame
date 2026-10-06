package com.veilframe.app.cv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType

class MatPoolAccountingTest {

    @Test
    fun accountingForNormalAllocationComputesExactBytes() {
        // 1000 x 1000 x CV_8UC1 = 1,000,000 bytes (~976 KiB)
        val accounting = SizeClass.accountingFor(1000, 1000, CvType.CV_8UC1)
        assertEquals(1_000_000L, accounting.actualBytes)
        assertTrue(accounting.retainable)
        assertTrue(accounting.classBytes >= accounting.actualBytes)

        val sizeClass = SizeClass.forMat(1000, 1000, CvType.CV_8UC1)
        org.junit.Assert.assertNotNull(sizeClass)
        assertEquals(1, sizeClass!!.channels)
        assertEquals(CvType.CV_8U, sizeClass.depth)
        assertEquals(accounting.classBytes.toInt(), sizeClass.byteClass)
    }

    @Test
    fun accountingForMultiChannelFloatComputesCorrectElemSize() {
        // 1920 x 1080 x CV_32FC2 = 1920 * 1080 * 8 = 16,588,800 bytes (~15.8 MiB)
        val accounting = SizeClass.accountingFor(1080, 1920, CvType.CV_32FC2)
        val expected = 1920L * 1080L * 8L
        assertEquals(expected, accounting.actualBytes)
        assertTrue(accounting.retainable)
        assertTrue(accounting.classBytes >= accounting.actualBytes)
    }

    @Test
    fun oversizeAllocationMarkedNonRetainable() {
        // 800 MiB Mat: 10,000 x 10,000 x CV_64FC1 = 100,000,000 * 8 = 800,000,000 bytes (~762.9 MiB)
        // > 512 MiB MAX_BYTE_CLASS
        val accounting = SizeClass.accountingFor(10000, 10000, CvType.CV_64FC1)
        val expectedBytes = 800_000_000L
        assertEquals(expectedBytes, accounting.actualBytes)
        assertFalse("Buffers exceeding 512 MiB must NOT be retainable in pool", accounting.retainable)
        assertEquals(expectedBytes, accounting.classBytes)

        // forMat must return null for non-retainable oversize buffers
        assertNull(SizeClass.forMat(10000, 10000, CvType.CV_64FC1))
    }

    @Test
    fun byteClassForReturnsMinusOneForOversize() {
        val maxClass = SizeClass.MAX_BYTE_CLASS.toLong()
        assertEquals(SizeClass.MAX_BYTE_CLASS, SizeClass.byteClassFor(maxClass))
        assertEquals(-1, SizeClass.byteClassFor(maxClass + 1))
        assertEquals(-1, SizeClass.byteClassFor(800L * 1024 * 1024))
    }
}
