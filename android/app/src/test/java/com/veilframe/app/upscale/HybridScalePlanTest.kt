package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.HybridScalePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridScalePlanTest {

    @Test
    fun testExactNativeScalePlan() {
        val plan2x = HybridScalePlan.create(targetScale = 2, nativeScale = 2)
        assertEquals(2, plan2x.aiScale)
        assertEquals(1, plan2x.refinementScale)
        assertEquals(2, plan2x.finalScale)
        assertFalse(plan2x.requiresRefinement)

        val plan4x = HybridScalePlan.create(targetScale = 4, nativeScale = 4)
        assertEquals(4, plan4x.aiScale)
        assertEquals(1, plan4x.refinementScale)
        assertEquals(4, plan4x.finalScale)
        assertFalse(plan4x.requiresRefinement)
    }

    @Test
    fun testHybridIntegerMultipleScaling() {
        // 2x model targeting 4x: AI 2x + 2x refinement
        val plan2to4 = HybridScalePlan.create(targetScale = 4, nativeScale = 2)
        assertEquals(2, plan2to4.aiScale)
        assertEquals(2, plan2to4.refinementScale)
        assertEquals(4, plan2to4.finalScale)
        assertTrue(plan2to4.requiresRefinement)

        // 2x model targeting 8x: AI 2x + 4x refinement
        val plan2to8 = HybridScalePlan.create(targetScale = 8, nativeScale = 2)
        assertEquals(2, plan2to8.aiScale)
        assertEquals(4, plan2to8.refinementScale)
        assertEquals(8, plan2to8.finalScale)
        assertTrue(plan2to8.requiresRefinement)

        // 4x model targeting 8x: AI 4x + 2x refinement
        val plan4to8 = HybridScalePlan.create(targetScale = 8, nativeScale = 4)
        assertEquals(4, plan4to8.aiScale)
        assertEquals(2, plan4to8.refinementScale)
        assertEquals(8, plan4to8.finalScale)
        assertTrue(plan4to8.requiresRefinement)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRejectsTargetLessThanNativeScale() {
        // 4x model cannot be used directly for 2x target without downsampling
        HybridScalePlan.create(targetScale = 2, nativeScale = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRejectsNonIntegerMultiple() {
        // 4x model cannot scale to 6x (not an exact multiple)
        HybridScalePlan.create(targetScale = 6, nativeScale = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRejectsZeroOrNegativeScale() {
        HybridScalePlan.create(targetScale = 0, nativeScale = 2)
    }

    @Test
    fun testStageListRepresentation() {
        // 4x model targeting 8x: 4x AI + 2x Refinement = 8x
        val plan = HybridScalePlan.create(targetScale = 8, nativeScale = 4)
        assertEquals(2, plan.stages.size)
        assertEquals(com.veilframe.app.upscale.inference.ScaleStageType.AI_INFERENCE, plan.stages[0].type)
        assertEquals(4, plan.stages[0].scale)
        assertEquals(com.veilframe.app.upscale.inference.ScaleStageType.ALGORITHMIC_REFINEMENT, plan.stages[1].type)
        assertEquals(2, plan.stages[1].scale)
        assertEquals(8, plan.finalScale)
    }

    @Test
    fun testMultiPassNeuralScaling() {
        // 8x from 2x model via 2 AI passes: 2x AI -> 2x AI -> 2x Refinement = 8x (2 * 2 * 2 = 8)
        val multiPassPlan = HybridScalePlan.createMultiPass(targetScale = 8, nativeScale = 2, neuralPasses = 2)
        assertEquals(3, multiPassPlan.stages.size)
        assertEquals(com.veilframe.app.upscale.inference.ScaleStageType.AI_INFERENCE, multiPassPlan.stages[0].type)
        assertEquals(2, multiPassPlan.stages[0].scale)
        assertEquals(com.veilframe.app.upscale.inference.ScaleStageType.AI_INFERENCE, multiPassPlan.stages[1].type)
        assertEquals(2, multiPassPlan.stages[1].scale)
        assertEquals(com.veilframe.app.upscale.inference.ScaleStageType.ALGORITHMIC_REFINEMENT, multiPassPlan.stages[2].type)
        assertEquals(2, multiPassPlan.stages[2].scale)
        assertEquals(8, multiPassPlan.finalScale)

        // Verifying two 4x passes yields 16x (4 * 4 = 16), not 8x
        val sixteenPlan = HybridScalePlan.createMultiPass(targetScale = 16, nativeScale = 4, neuralPasses = 2)
        assertEquals(2, sixteenPlan.stages.size)
        assertEquals(4, sixteenPlan.stages[0].scale)
        assertEquals(4, sixteenPlan.stages[1].scale)
        assertEquals(16, sixteenPlan.finalScale)
    }
}
