package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.SessionStrategy
import com.veilframe.app.upscale.inference.UpscaleMemoryPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleMemoryPlannerTest {

    @Test
    fun testEmergencyHardCeilingRejectsCatastrophicInputs() {
        // 10000 x 10001 = 100,010,000 pixels (exceeds 100 MP ceiling)
        val plan = UpscaleMemoryPlanner.plan(
            sourceWidth = 5000,
            sourceHeight = 5001,
            scale = 2,
            isAiModel = true
        )

        assertFalse("100+ MP target output must be rejected by emergency ceiling", plan.isSafe)
        assertNotNull(plan.warningMessage)
        assertTrue(plan.warningMessage!!.contains("emergency safety ceiling"))
    }

    @Test
    fun testWorkerBudgetCalculationFormula() {
        val headroom = 1024L * 1024 * 1024 // 1 GB headroom
        val sourceW = 1920
        val sourceH = 1080
        val scale = 4
        val tileSize = 256

        val breakdown = UpscaleMemoryPlanner.calculateWorkerBudget(
            availableHeadroomBytes = headroom,
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            scale = scale,
            tileSize = tileSize,
            isAiModel = true,
            sessionStrategy = SessionStrategy.SHARED_SESSION
        )

        val expectedSrcBytes = 1920L * 1080L * 4L
        val expectedOutBytes = (1920L * 4) * (1080L * 4) * 4L
        val expectedBaseRuntime = 48L * 1024 * 1024L
        val expectedSafetyReserve = (headroom * 0.15).toLong()

        assertEquals(expectedSrcBytes, breakdown.sourceBitmapBytes)
        assertEquals(expectedOutBytes, breakdown.outputBitmapBytes)
        assertEquals(expectedBaseRuntime, breakdown.runtimeSessionBytes)
        assertEquals(expectedSafetyReserve, breakdown.safetyReserveBytes)

        val expectedJavaBudget = (headroom - expectedSrcBytes - expectedOutBytes - expectedSafetyReserve).coerceAtLeast(0L)
        val expectedNativeBudget = (headroom - expectedBaseRuntime - expectedSafetyReserve).coerceAtLeast(0L)

        assertEquals(expectedJavaBudget, breakdown.javaBudgetBytes)
        assertEquals(expectedNativeBudget, breakdown.nativeBudgetBytes)
        assertEquals(minOf(expectedJavaBudget, expectedNativeBudget), breakdown.workerBudgetBytes)
        assertTrue("Max workers must be at least 1 without artificial ceiling", breakdown.maxMemorySafeWorkers >= 1)
    }

    @Test
    fun testSessionPoolStrategyDoublesRuntimeSessionEstimate() {
        val headroom = 1024L * 1024 * 1024
        val sharedBreakdown = UpscaleMemoryPlanner.calculateWorkerBudget(
            availableHeadroomBytes = headroom,
            sourceWidth = 1000,
            sourceHeight = 1000,
            scale = 2,
            tileSize = 256,
            isAiModel = true,
            sessionStrategy = SessionStrategy.SHARED_SESSION
        )

        val poolBreakdown = UpscaleMemoryPlanner.calculateWorkerBudget(
            availableHeadroomBytes = headroom,
            sourceWidth = 1000,
            sourceHeight = 1000,
            scale = 2,
            tileSize = 256,
            isAiModel = true,
            sessionStrategy = SessionStrategy.SESSION_POOL
        )

        assertEquals(sharedBreakdown.runtimeSessionBytes * 2, poolBreakdown.runtimeSessionBytes)
    }
}
