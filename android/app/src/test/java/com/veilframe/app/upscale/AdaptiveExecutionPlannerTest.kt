package com.veilframe.app.upscale

import android.os.PowerManager
import com.veilframe.app.upscale.inference.AdaptiveExecutionPlanner
import com.veilframe.app.upscale.inference.Backend
import com.veilframe.app.upscale.inference.DeviceCapabilityProfile
import com.veilframe.app.upscale.inference.ExecutionProfile
import com.veilframe.app.upscale.inference.InferenceAccelerationMode
import com.veilframe.app.upscale.inference.InferencePrecisionMode
import com.veilframe.app.upscale.inference.ModelExecutionCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveExecutionPlannerTest {

    private val planner = AdaptiveExecutionPlanner()

    @Test
    fun testBackendCandidatesAreBackendSpecificNotBlindCartesian() {
        val device = DeviceCapabilityProfile(
            manufacturer = "Qualcomm",
            model = "SM8650",
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            totalMemoryBytes = 12L * 1024 * 1024 * 1024,
            availableMemoryBytes = 6L * 1024 * 1024 * 1024,
            apiLevel = 34,
            lowRamDevice = false,
            supportsNnapi = true,
            supportsNnapiFp16 = true,
            supportsXnnpack = false,
            thermalStatus = 0
        )

        val modelCaps = ModelExecutionCapabilities(
            nativeScale = 4,
            supportsFp16 = true
        )

        val candidates = planner.generateBackendCandidates(
            userMode = InferenceAccelerationMode.AUTO,
            deviceProfile = device,
            modelCapabilities = modelCaps,
            tileSize = 256,
            overlap = 16,
            maxSafeWorkers = 4
        )

        assertTrue(candidates.isNotEmpty())

        // 1. NNAPI candidates must NOT have CPU thread configurations assigned
        val nnapiCandidates = candidates.filter { it.backend == Backend.NNAPI }
        assertTrue("NNAPI candidates must be present", nnapiCandidates.isNotEmpty())
        for (cand in nnapiCandidates) {
            assertNull("NNAPI candidates must have null intraOpThreads", cand.intraOpThreads)
            assertNull("NNAPI candidates must have null interOpThreads", cand.interOpThreads)
            assertTrue("NNAPI workers should be bounded (1 to 3)", cand.workers in 1..3)
        }

        // 2. CPU candidates must have coupled thread/worker pairs
        val cpuCandidates = candidates.filter { it.backend == Backend.CPU }
        assertTrue("CPU candidates must be present", cpuCandidates.isNotEmpty())
        for (cand in cpuCandidates) {
            assertNotNull("CPU candidate must have explicit intraOpThreads", cand.intraOpThreads)
            assertTrue("CPU candidate must have thread count >= 1", cand.intraOpThreads!! >= 1)
            assertEquals(InferencePrecisionMode.DEFAULT, cand.precision)
        }

        // 3. NNAPI candidates must support FP16 Relaxed on supported hardware
        val nnapiFp16Candidates = candidates.filter { it.backend == Backend.NNAPI && it.precision == InferencePrecisionMode.FP16_RELAXED }
        assertTrue("NNAPI candidates should include FP16 Relaxed precision when supported", nnapiFp16Candidates.isNotEmpty())
    }

    @Test
    fun testNnapiCandidateGenerationWithMultiWorkerOptimization() {
        val nnapiDevice = DeviceCapabilityProfile(
            manufacturer = "Google",
            model = "Pixel 8",
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            totalMemoryBytes = 12L * 1024 * 1024 * 1024,
            availableMemoryBytes = 6L * 1024 * 1024 * 1024,
            apiLevel = 34,
            lowRamDevice = false,
            supportsNnapi = true,
            supportsNnapiFp16 = true,
            supportsXnnpack = false,
            thermalStatus = 0
        )

        val candidates = planner.generateBackendCandidates(
            userMode = InferenceAccelerationMode.NNAPI,
            deviceProfile = nnapiDevice,
            modelCapabilities = ModelExecutionCapabilities(
                nativeScale = 4,
                supportsFp16 = true
            ),
            tileSize = 256,
            overlap = 16,
            maxSafeWorkers = 4
        )

        assertTrue("NNAPI candidates must be generated", candidates.isNotEmpty())
        assertTrue("All candidates must use NNAPI backend", candidates.all { it.backend == Backend.NNAPI })
        assertTrue("All NNAPI candidates must leave intraOpThreads null (managed by runtime)", candidates.all { it.intraOpThreads == null })
        assertTrue("Candidates should evaluate both 1 and 2 concurrent workers for throughput", candidates.any { it.workers == 1 } && candidates.any { it.workers == 2 })
    }

    @Test
    fun testProgressiveWorkerGrowthDecisionLogic() {
        val engine = com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine()

        // 1. Delta > 15% -> definitely CONTINUE
        val decisionBigGain = engine.evaluateWorkerGrowth(10.0, 12.0) // +20%
        assertEquals(com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine.WorkerGrowthDecision.CONTINUE, decisionBigGain)

        // 2. +5% < Delta <= 15% -> CONTINUE_IF_CONFIDENT
        val decisionModerateGain = engine.evaluateWorkerGrowth(10.0, 11.0) // +10%
        assertEquals(com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine.WorkerGrowthDecision.CONTINUE_IF_CONFIDENT, decisionModerateGain)

        // 3. 0% < Delta <= 5% -> STOP on standard workloads, CONTINUE_ONCE on long workloads
        val decisionMarginalStandard = engine.evaluateWorkerGrowth(10.0, 10.3, isLongWorkload = false) // +3%
        assertEquals(com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine.WorkerGrowthDecision.STOP, decisionMarginalStandard)

        val decisionMarginalLong = engine.evaluateWorkerGrowth(10.0, 10.3, isLongWorkload = true) // +3%
        assertEquals(com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine.WorkerGrowthDecision.CONTINUE_ONCE, decisionMarginalLong)

        // 4. Delta <= 0% -> definitely STOP
        val decisionNegative = engine.evaluateWorkerGrowth(10.0, 9.5) // -5%
        assertEquals(com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine.WorkerGrowthDecision.STOP, decisionNegative)

        val decisionFlat = engine.evaluateWorkerGrowth(10.0, 10.0) // 0%
        assertEquals(com.veilframe.app.upscale.inference.ExecutionBenchmarkEngine.WorkerGrowthDecision.STOP, decisionFlat)
    }

    @Test
    fun testThermalThrottlingHysteresis() {
        val baseProfile = ExecutionProfile(
            backend = Backend.CPU,
            precision = InferencePrecisionMode.DEFAULT,
            workers = 3,
            intraOpThreads = 2,
            interOpThreads = 1,
            tileSize = 256,
            overlap = 16
        )

        // 1. Moderate thermal event steps down worker count if > 2
        val throttledModerate = planner.adaptForThermalEvent(
            currentProfile = baseProfile,
            newThermalStatus = PowerManager.THERMAL_STATUS_MODERATE
        )
        assertEquals("Moderate thermal warning should step down workers from 3 to 2", 2, throttledModerate.workers)

        // 2. Severe thermal event steps down worker count toward sustainable profile (3 to 2)
        val throttledSevere = planner.adaptForThermalEvent(
            currentProfile = baseProfile,
            newThermalStatus = PowerManager.THERMAL_STATUS_SEVERE
        )
        assertEquals("Severe thermal event steps down workers toward sustainable profile (3 to 2)", 2, throttledSevere.workers)

        // 3. Emergency thermal event immediately forces single worker (3 to 1)
        val throttledEmergency = planner.adaptForThermalEvent(
            currentProfile = baseProfile,
            newThermalStatus = PowerManager.THERMAL_STATUS_EMERGENCY
        )
        assertEquals("Emergency thermal event must immediately force single worker", 1, throttledEmergency.workers)

        // 4. Recovery check within cooldown dwell time
        val duringCooldown = planner.adaptForThermalEvent(
            currentProfile = throttledEmergency,
            newThermalStatus = PowerManager.THERMAL_STATUS_NONE
        )
        assertEquals("Must remain throttled during cooldown dwell time", 1, duringCooldown.workers)
    }

    @Test
    fun testGenericHighWorkerThermalStepDown() {
        val highWorkerProfile = ExecutionProfile(
            backend = Backend.NNAPI,
            precision = InferencePrecisionMode.FP16_RELAXED,
            workers = 8,
            intraOpThreads = null,
            interOpThreads = null,
            tileSize = 256,
            overlap = 16
        )

        // 8 workers steps down to 6 on severe thermal pressure
        val steppedDown8to6 = planner.adaptForThermalEvent(
            currentProfile = highWorkerProfile,
            newThermalStatus = PowerManager.THERMAL_STATUS_SEVERE
        )
        assertEquals("8 workers should step down to 6 on SEVERE thermal status", 6, steppedDown8to6.workers)

        // 7 workers steps down to 5
        assertEquals(5, planner.calculateNextLowerSustainableWorkers(7))

        // 6 workers steps down to 4 on severe thermal pressure
        val steppedDown6to4 = planner.adaptForThermalEvent(
            currentProfile = highWorkerProfile.copy(workers = 6),
            newThermalStatus = PowerManager.THERMAL_STATUS_SEVERE
        )
        assertEquals("6 workers should step down to 4 on SEVERE thermal status", 4, steppedDown6to4.workers)

        // 5 workers steps down to 3, 4 to 3, 3 to 2, 2 to 1
        assertEquals(3, planner.calculateNextLowerSustainableWorkers(5))
        assertEquals(3, planner.calculateNextLowerSustainableWorkers(4))
        assertEquals(2, planner.calculateNextLowerSustainableWorkers(3))
        assertEquals(1, planner.calculateNextLowerSustainableWorkers(2))

        // Candidate-guided step down
        assertEquals(4, planner.calculateNextLowerSustainableWorkers(8, listOf(1, 2, 4)))
    }

    @Test
    fun testRealEsrganCapabilities() {
        val fpModelCaps = ModelExecutionCapabilities(nativeScale = 4, supportsFp16 = true)
        assertEquals(4, fpModelCaps.nativeScale)
        assertTrue("RealESRGAN floating-point model supports FP16 math", fpModelCaps.supportsFp16)
    }

    @Test
    fun testDecoupledAcceleratorWorkerScaling() {
        val cpuCores = 4
        val maxSafeWorkers = 12

        val cpuLimit = planner.getCandidateWorkerLimit(Backend.CPU, cpuCores, maxSafeWorkers)
        val nnapiLimit = planner.getCandidateWorkerLimit(Backend.NNAPI, cpuCores, maxSafeWorkers)

        assertEquals("CPU workers must be bounded by CPU cores", 4, cpuLimit)
        assertEquals("NNAPI workers should be bounded by safe workers / policy, exceeding CPU cores", 12, nnapiLimit)
    }

    @Test
    fun testDualCircuitBreakerConditions() {
        val cpuBaseline = 2.0

        // Case 1: Less than 0.05 MP/s -> pathological
        val underMinHealthy = 0.04
        val isPathological1 = (underMinHealthy < com.veilframe.app.upscale.inference.CachedPerformanceProfile.MIN_HEALTHY_THROUGHPUT_MP_PER_SEC) ||
                (cpuBaseline > 0.0 && underMinHealthy < (cpuBaseline * 0.20))
        assertTrue("Under 0.05 MP/s must trigger circuit breaker", isPathological1)

        // Case 2: Under 20% of CPU baseline -> pathological (e.g. 0.35 MP/s when CPU is 2.0 MP/s -> 17.5%)
        val under20PercentCpu = 0.35
        val isPathological2 = (under20PercentCpu < com.veilframe.app.upscale.inference.CachedPerformanceProfile.MIN_HEALTHY_THROUGHPUT_MP_PER_SEC) ||
                (cpuBaseline > 0.0 && under20PercentCpu < (cpuBaseline * 0.20))
        assertTrue("Under 20% of CPU baseline must trigger circuit breaker", isPathological2)

        // Case 3: Healthy throughput (e.g. 3.0 MP/s) -> not pathological
        val healthy = 3.0
        val isPathological3 = (healthy < com.veilframe.app.upscale.inference.CachedPerformanceProfile.MIN_HEALTHY_THROUGHPUT_MP_PER_SEC) ||
                (cpuBaseline > 0.0 && healthy < (cpuBaseline * 0.20))
        assertFalse("Healthy throughput must not trigger circuit breaker", isPathological3)
    }
}
