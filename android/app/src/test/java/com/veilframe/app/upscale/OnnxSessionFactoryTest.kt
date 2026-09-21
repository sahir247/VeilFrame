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

    enum class DummyNnapiFlags {
        CPU_DISABLED,
        USE_FP16,
        USE_NCHW
    }

    @Test
    fun testEnumSetNoneOfReflectionWithEnum() {
        // Verifies that EnumSet.noneOf produces a valid EnumSet<T> without IllegalArgumentException

        val flagsClass = DummyNnapiFlags::class.java
        val noneOfMethod = java.util.EnumSet::class.java.getMethod("noneOf", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        val flagSet = noneOfMethod.invoke(null, flagsClass) as java.util.Set<Any>

        val cpuDisabled = java.lang.Enum.valueOf(flagsClass, "CPU_DISABLED")
        val fp16 = java.lang.Enum.valueOf(flagsClass, "USE_FP16")
        flagSet.add(cpuDisabled)
        flagSet.add(fp16)

        assertTrue(flagSet is java.util.EnumSet<*>)
        assertTrue(flagSet.contains(cpuDisabled))
        assertTrue(flagSet.contains(fp16))
    }

    @Test
    fun testProbeNnapiCoverageOnJvmReturnsFallback() {
        val env = try { ai.onnxruntime.OrtEnvironment.getEnvironment() } catch (_: Throwable) { null }
        if (env != null) {
            val dummyModel = java.io.File.createTempFile("dummy_model", ".ort")
            try {
                val coverage = OnnxSessionFactory.probeNnapiCoverage(
                    env = env,
                    modelFile = dummyModel,
                    precision = com.veilframe.app.upscale.inference.InferencePrecisionMode.DEFAULT,
                    nnapiUseNchw = false
                )
                // On pure JVM without Android NNAPI, coverage should be UNAVAILABLE or PARTIAL
                assertTrue(
                    coverage == com.veilframe.app.upscale.inference.NnapiExecutionCoverage.UNAVAILABLE ||
                    coverage == com.veilframe.app.upscale.inference.NnapiExecutionCoverage.PARTIAL_NNAPI_WITH_ORT_CPU_FALLBACK
                )
            } finally {
                dummyModel.delete()
            }
        }
    }
}
