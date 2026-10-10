package com.veilframe.app.cv.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.opencv.core.Mat

class DocumentPostProcessorTest {

    private fun allocateDummyMat(): Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Mat::class.java) as Mat
    }

    @Test
    fun testSauvolaWindowCalculation() {
        val w1 = DocumentPostProcessor.sauvolaWindow(1000)
        assertEquals(101, w1) // (1000 / 10) = 100 or 1 = 101

        val w2 = DocumentPostProcessor.sauvolaWindow(50)
        assertEquals(15, w2) // coerced to min 15

        val w3 = DocumentPostProcessor.sauvolaWindow(20000)
        assertEquals(1001, w3) // coerced to max 1001
    }

    @Test
    fun testMultiScaleRetinexJvmFallbackOnEmpty() {
        val dummy = allocateDummyMat()
        val result = DocumentPostProcessor.multiScaleRetinexOnL(dummy)
        assertNotNull(result)
    }

    @Test
    fun testGrayscaleJvmFallbackOnEmpty() {
        val dummy = allocateDummyMat()
        val result = DocumentPostProcessor.enhanceGrayscaleImage(dummy)
        assertNotNull(result)
    }

    @Test
    fun testBinarizeJvmFallbackOnEmpty() {
        val dummy = allocateDummyMat()
        val result = DocumentPostProcessor.binarizeDocument(dummy)
        assertNotNull(result)
    }
}
