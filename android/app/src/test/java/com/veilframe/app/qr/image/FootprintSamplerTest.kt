package com.veilframe.app.qr.image

import com.veilframe.app.qr.model.ModuleShape
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests verifying FootprintSampler distribution calculations,
 * shape-aware offset bounds, and statistical objective resolution.
 */
class FootprintSamplerTest {

    @Test
    fun testComputeDistributionUniform() {
        val samples = (0..10).map { it / 10.0f } // 0.0, 0.1, ..., 1.0
        val dist = FootprintSampler.computeDistribution(samples)

        assertEquals("min", 0.0f, dist.min, 0.001f)
        assertEquals("p10", 0.10f, dist.p10, 0.001f)
        assertEquals("p25", 0.25f, dist.p25, 0.001f)
        assertEquals("median", 0.50f, dist.median, 0.001f)
        assertEquals("p75", 0.75f, dist.p75, 0.001f)
        assertEquals("p90", 0.90f, dist.p90, 0.001f)
        assertEquals("max", 1.0f, dist.max, 0.001f)
        assertEquals("mean", 0.50f, dist.mean, 0.001f)
        assertEquals(11, dist.sampleCount)
    }

    @Test
    fun testComputeDistributionMonotonicity() {
        val randomSamples = listOf(0.85f, 0.12f, 0.99f, 0.02f, 0.45f, 0.67f, 0.33f, 0.50f)
        val dist = FootprintSampler.computeDistribution(randomSamples)

        assertTrue("min <= p10", dist.min <= dist.p10)
        assertTrue("p10 <= p25", dist.p10 <= dist.p25)
        assertTrue("p25 <= median", dist.p25 <= dist.median)
        assertTrue("median <= p75", dist.median <= dist.p75)
        assertTrue("p75 <= p90", dist.p75 <= dist.p90)
        assertTrue("p90 <= max", dist.p90 <= dist.max)
    }

    @Test
    fun testSingleSampleDistribution() {
        val dist = FootprintSampler.computeDistribution(listOf(0.42f))
        assertEquals(0.42f, dist.min, 0.0001f)
        assertEquals(0.42f, dist.median, 0.0001f)
        assertEquals(0.42f, dist.max, 0.0001f)
        assertEquals(0.42f, dist.mean, 0.0001f)
        assertEquals(1, dist.sampleCount)
    }

    @Test
    fun testFootprintOffsetBoundsSquare() {
        val dataScale = 0.5f
        val maxBound = dataScale * 0.5f // half module
        val offsets = FootprintSampler.generateFootprintOffsets(dataScale, ModuleShape.SQUARE)

        assertEquals("Standard grid has 9 points", 9, offsets.size)
        for ((du, dv) in offsets) {
            assertTrue("du must be within [-maxBound, maxBound]", du in -maxBound..maxBound)
            assertTrue("dv must be within [-maxBound, maxBound]", dv in -maxBound..maxBound)
        }
    }

    @Test
    fun testFootprintOffsetBoundsCircle() {
        val dataScale = 0.35f
        val radius = dataScale * 0.5f
        val offsets = FootprintSampler.generateFootprintOffsets(dataScale, ModuleShape.CIRCLE)

        assertEquals("Radial pattern has 9 points", 9, offsets.size)
        for ((du, dv) in offsets) {
            val dist = sqrt(du * du + dv * dv)
            assertTrue("Point must be strictly within circle radius ($dist <= $radius)", dist <= radius + 0.0001f)
        }
    }

    @Test
    fun testFootprintOffsetBoundsDiamond() {
        val dataScale = 0.75f
        val half = dataScale * 0.5f
        val offsets = FootprintSampler.generateFootprintOffsets(dataScale, ModuleShape.DIAMOND)

        assertEquals("Diamond pattern has 9 points", 9, offsets.size)
        for ((du, dv) in offsets) {
            // Manhattan distance for diamond: |du| + |dv| <= half
            val manhattan = kotlin.math.abs(du) + kotlin.math.abs(dv)
            assertTrue("Point must be inside diamond boundary ($manhattan <= $half)", manhattan <= half + 0.0001f)
        }
    }

    @Test
    fun testResolveObjectiveLuminance() {
        val samples = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
        val dist = FootprintSampler.computeDistribution(samples)

        // EXTREMA
        assertEquals(dist.max, FootprintSampler.resolveObjectiveLuminance(dist, isDark = true, SamplingObjective.EXTREMA), 0.001f)
        assertEquals(dist.min, FootprintSampler.resolveObjectiveLuminance(dist, isDark = false, SamplingObjective.EXTREMA), 0.001f)

        // PERCENTILE_90_10
        assertEquals(dist.p90, FootprintSampler.resolveObjectiveLuminance(dist, isDark = true, SamplingObjective.PERCENTILE_90_10), 0.001f)
        assertEquals(dist.p10, FootprintSampler.resolveObjectiveLuminance(dist, isDark = false, SamplingObjective.PERCENTILE_90_10), 0.001f)

        // PERCENTILE_75_25
        assertEquals(dist.p75, FootprintSampler.resolveObjectiveLuminance(dist, isDark = true, SamplingObjective.PERCENTILE_75_25), 0.001f)
        assertEquals(dist.p25, FootprintSampler.resolveObjectiveLuminance(dist, isDark = false, SamplingObjective.PERCENTILE_75_25), 0.001f)

        // MEDIAN & MEAN
        assertEquals(dist.median, FootprintSampler.resolveObjectiveLuminance(dist, isDark = true, SamplingObjective.MEDIAN), 0.001f)
        assertEquals(dist.mean, FootprintSampler.resolveObjectiveLuminance(dist, isDark = true, SamplingObjective.MEAN), 0.001f)
    }
}
