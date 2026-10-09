package com.veilframe.app.cv.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat

class FrameBufferPoolTest {

    private fun allocateDummyMat(): Mat {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Mat::class.java) as Mat
    }

    @Test
    fun testBufferPoolDimensionCalculationsPortrait() {
        // Sensor: 1920x1080 with 90 deg rotation -> Upright: 1080x1920
        // maxEdge: 1024 -> scale = 1024 / 1920 ~= 0.5333
        // analysisWidth: 1080 * 0.5333 = 576, analysisHeight: 1920 * 0.5333 = 1024
        val pool = FrameBufferPool(
            width = 1920,
            height = 1080,
            rotationDegrees = 90,
            maxEdge = 1024,
            matFactory = { _, _, _ -> allocateDummyMat() }
        )

        assertEquals(1920 * 1080, pool.yPlaneBytes.size)
        assertEquals(1080, pool.uprightWidth)
        assertEquals(1920, pool.uprightHeight)
        assertEquals(576, pool.analysisWidth)
        assertEquals(1024, pool.analysisHeight)
        assertTrue(pool.scale < 1.0)
        assertFalse(pool.isReleased)

        pool.release()
        assertTrue(pool.isReleased)
    }

    @Test
    fun testBufferPoolDimensionCalculationsLandscape() {
        // Sensor: 1920x1080 with 0 deg rotation -> Upright: 1920x1080
        // maxEdge: 1024 -> scale = 1024 / 1920 ~= 0.5333
        // analysisWidth: 1024, analysisHeight: 576
        val pool = FrameBufferPool(
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            maxEdge = 1024,
            matFactory = { _, _, _ -> allocateDummyMat() }
        )

        assertEquals(1920, pool.uprightWidth)
        assertEquals(1080, pool.uprightHeight)
        assertEquals(1024, pool.analysisWidth)
        assertEquals(576, pool.analysisHeight)
        assertFalse(pool.isReleased)

        pool.release()
        assertTrue(pool.isReleased)
    }

    @Test
    fun testBufferPoolSmallImageNoScalingNeeded() {
        // Sensor: 640x480 with 0 deg rotation -> already <= 1024
        val pool = FrameBufferPool(
            width = 640,
            height = 480,
            rotationDegrees = 0,
            maxEdge = 1024,
            matFactory = { _, _, _ -> allocateDummyMat() }
        )

        assertEquals(640, pool.uprightWidth)
        assertEquals(480, pool.uprightHeight)
        assertEquals(640, pool.analysisWidth)
        assertEquals(480, pool.analysisHeight)
        assertEquals(1.0, pool.scale, 0.0001)

        pool.release()
        assertTrue(pool.isReleased)
    }

    @Test
    fun testMatchesLogic() {
        val pool = FrameBufferPool(
            width = 1280,
            height = 720,
            rotationDegrees = 90,
            matFactory = { _, _, _ -> allocateDummyMat() }
        )

        assertTrue(pool.matches(1280, 720, 90))
        assertFalse(pool.matches(1920, 1080, 90))
        assertFalse(pool.matches(1280, 720, 0))
        assertFalse(pool.matches(720, 1280, 90))

        pool.release()
        assertFalse("Released pool must not match", pool.matches(1280, 720, 90))
    }

    @Test
    fun testReleaseIsIdempotent() {
        val pool = FrameBufferPool(
            width = 640,
            height = 480,
            rotationDegrees = 0,
            matFactory = { _, _, _ -> allocateDummyMat() }
        )

        assertFalse(pool.isReleased)
        pool.release()
        assertTrue(pool.isReleased)
        // Second release must not throw
        pool.release()
        assertTrue(pool.isReleased)
    }

    @Test
    fun testJvmSafeWhenNativeNotLoaded() {
        // When running on JVM host without native OpenCV library loaded,
        // constructor must complete safely without throwing UnsatisfiedLinkError.
        val pool = FrameBufferPool(
            width = 1280,
            height = 720,
            rotationDegrees = 0,
            matFactory = null
        )

        assertEquals(1280 * 720, pool.yPlaneBytes.size)
        assertFalse(pool.isReleased)
        pool.release()
        assertTrue(pool.isReleased)
    }
}
