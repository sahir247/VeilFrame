package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.OnnxSessionFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxSessionFactoryTest {

    @Test
    fun testCpuThreadsCalculationReturnsValidThreadCount() {
        val threads = OnnxSessionFactory.calculateCpuThreads()
        assertTrue("Thread count should be at least 1", threads >= 1)
        val available = Runtime.getRuntime().availableProcessors()
        assertTrue("Thread count should not exceed available processors", threads <= available)
    }
}
