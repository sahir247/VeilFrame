package com.veilframe.app.cv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [CvImage] ownership semantics.
 *
 * These tests run on the host JVM without requiring loaded OpenCV native
 * binaries. A dummy [org.opencv.core.Mat] instance is created via Unsafe
 * without invoking JNI constructors, and release side-effects are verified
 * directly against [CvImage]'s [ownsMat] property and [matReleaser] handler.
 */
class CvImageOwnershipTest {

    private class ReleaseTracker : AutoCloseable {
        var released = false
        override fun close() { released = true }
    }

    companion object {
        private fun allocateDummyMat(): org.opencv.core.Mat {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, org.opencv.core.Mat::class.java) as org.opencv.core.Mat
        }
    }

    @Test
    fun `wrap sets ownsMat to false`() {
        val mat = allocateDummyMat()
        val image = CvImage.wrap(mat, ColorSpace.GRAY)
        assertFalse("wrap() must not claim ownership", image.ownsMat)
    }

    @Test
    fun `own sets ownsMat to true`() {
        val mat = allocateDummyMat()
        val image = CvImage.own(mat, ColorSpace.GRAY)
        assertTrue("own() must claim ownership", image.ownsMat)
    }

    @Test
    fun `close on wrap image does NOT release mat`() {
        val mat = allocateDummyMat()
        val matTracker = ReleaseTracker()
        val ownerTracker = ReleaseTracker()
        val image = CvImage(
            mat = mat,
            colorSpace = ColorSpace.GRAY,
            ownsMat = false,
            owner = ownerTracker,
            matReleaser = { matTracker.close() },
        )

        image.close()

        assertTrue("Closing an image invokes owner.close() for resource cleanup", ownerTracker.released)
        assertFalse("Closing a wrap() image must NOT release the caller-owned Mat", matTracker.released)
        assertTrue("Image must report isClosed after close()", image.isClosed)
    }

    @Test
    fun `close on own image DOES release mat`() {
        val mat = allocateDummyMat()
        val matTracker = ReleaseTracker()
        val ownerTracker = ReleaseTracker()
        val image = CvImage(
            mat = mat,
            colorSpace = ColorSpace.GRAY,
            ownsMat = true,
            owner = ownerTracker,
            matReleaser = { matTracker.close() },
        )

        image.close()

        assertTrue("Closing an image invokes owner.close()", ownerTracker.released)
        assertTrue("Closing an own() image MUST release the owned Mat", matTracker.released)
        assertTrue("Image must report isClosed after close()", image.isClosed)
    }

    @Test
    fun `double close on own image is idempotent`() {
        val mat = allocateDummyMat()
        var matReleaseCount = 0
        var ownerCloseCount = 0
        val image = CvImage(
            mat = mat,
            colorSpace = ColorSpace.GRAY,
            ownsMat = true,
            owner = AutoCloseable { ownerCloseCount++ },
            matReleaser = { matReleaseCount++ },
        )

        image.close()
        image.close()

        assertEquals(1, matReleaseCount)
        assertEquals(1, ownerCloseCount)
    }

    @Test
    fun `double close on wrap image is idempotent and never releases mat`() {
        val mat = allocateDummyMat()
        var matReleaseCount = 0
        var ownerCloseCount = 0
        val image = CvImage(
            mat = mat,
            colorSpace = ColorSpace.GRAY,
            ownsMat = false,
            owner = AutoCloseable { ownerCloseCount++ },
            matReleaser = { matReleaseCount++ },
        )

        image.close()
        image.close()

        assertEquals(0, matReleaseCount)
        assertEquals(1, ownerCloseCount)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
