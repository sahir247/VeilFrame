package com.veilframe.app.qr.image

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Unit tests verifying AdaptiveColorOptimizer v2:
 * 1. Oklab candidate generation and constant-hue gamut mapping.
 * 2. WCAG relative luminance contrast verification.
 * 3. Deviation minimization (Delta E_OK) preserving brand colors (e.g. Miku cyan).
 * 4. Explicit feasibility contract (targetMet vs. minimax best-achievable fallback).
 * 5. Joint optimization of dark and light modules with pair collision prevention.
 * 6. Strict preservation of transparent module semantics.
 * 7. Temporal worst-case multi-frame evaluation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AdaptiveColorOptimizerTest {

    @Test
    fun testSingleColorOptimizationTargetMet() {
        val frameLums = listOf(0.92f) // Bright white/light background
        val result = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = frameLums,
            referenceColor = Color.BLACK,
            targetContrast = 3.0f
        )

        assertTrue("targetMet must be true when target contrast is feasible", result.targetMet)
        assertTrue("Achieved CR (${result.contrastRatio}) must be >= target 3.0", result.contrastRatio >= 3.0f)
        assertEquals(3.0f, result.targetContrast, 0.001f)
        assertEquals(0f, result.adjustmentDistance, 0.001f) // Black to Black has 0 distance
        assertEquals(Color.BLACK, result.color)
    }

    @Test
    fun testMikuBrandColorPreservationInsteadOfCollapsingToBlack() {
        val mikuCyan = 0xFF39C5BC.toInt() // L_srgb ~ 0.43, L_oklab ~ 0.73
        val brightBackground = listOf(0.95f) // Near-white background

        val result = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = brightBackground,
            referenceColor = mikuCyan,
            targetContrast = 3.0f
        )

        assertTrue("Target contrast must be met", result.targetMet)
        assertTrue("CR must be >= 3.0f", result.contrastRatio >= 3.0f)

        // Critical requirement: Optimizer must NOT simply collapse to black (0x000000)
        // when a darker shade of the same hue can satisfy CR >= 3.0:1!
        assertNotEquals("Should preserve cyan hue rather than collapsing to pure black", Color.BLACK, result.color)

        // Perceptual distance to Miku cyan must be much smaller than distance from Miku cyan to Black
        val distToBlack = OklabColor.deltaEOk(mikuCyan, Color.BLACK)
        assertTrue(
            "Adjustment distance (${result.adjustmentDistance}) must be substantially less than distance to black ($distToBlack)",
            result.adjustmentDistance < distToBlack * 0.75f
        )

        // Verify hue is preserved along constant-hue ray
        val originalOklch = OklabColor.sRgbToOklch(mikuCyan)
        val resultOklch = OklabColor.sRgbToOklch(result.color)
        val hueDiff = abs(originalOklch.h - resultOklch.h)
        assertTrue("Hue must be preserved within 15 degrees (original: ${originalOklch.h}, result: ${resultOklch.h})", hueDiff <= 15.0f || hueDiff >= 345.0f)
    }

    @Test
    fun testMinimaxFallbackWhenTargetIsMathematicallyImpossible() {
        // Extreme background fluctuation: frame 1 is deep black, frame 2 is bright white
        val extremeFrames = listOf(0.01f, 0.99f)
        val impossibleTarget = 15.0f // Impossible for any static color to reach 15:1 against both 0.01 and 0.99

        val result = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = extremeFrames,
            referenceColor = Color.BLACK,
            targetContrast = impossibleTarget
        )

        // Must report targetMet = false honestly
        assertFalse("targetMet must be false when target contrast is mathematically impossible", result.targetMet)
        assertEquals(impossibleTarget, result.targetContrast, 0.001f)
        assertTrue("achieved CR must be > 1.0", result.contrastRatio > 1.0f)

        // The chosen color should be the minimax optimal candidate (maximizing minimum CR across frames)
        val chosenCr = ImageColorAnalyzer.minContrastRatio(result.color, extremeFrames)
        assertEquals("contrastRatio field must match verified WCAG min contrast", chosenCr, result.contrastRatio, 0.01f)
    }

    @Test
    fun testJointOptimizationOpaquePair() {
        val midBackground = listOf(0.20f) // At 0.20, both Black (CR=5.0) and White (CR=4.2) meet >= 3.0
        val joint = AdaptiveColorOptimizer.optimizeJointColorPair(
            frameLums = midBackground,
            defaultDark = Color.BLACK,
            defaultLight = Color.WHITE,
            targetContrast = 3.0f
        )

        assertTrue("Dark target met", joint.dark.targetMet)
        assertTrue("Light target met", joint.light.targetMet)
        assertTrue("Pair contrast ratio >= 3.0", joint.pairContrastRatio >= 3.0f)
        assertTrue("All targets met", joint.allTargetsMet)
        assertNotEquals("Dark and light must never collide on same color", joint.dark.color, joint.light.color)
    }

    @Test
    fun testJointOptimizationInfeasibleMidLuminanceReportsTargetNotMet() {
        // At L_bg = 0.50, the theoretical maximum contrast for any light module (White L=1.0) is (1.05/0.55) = 1.91
        // Thus target CR >= 3.0:1 is mathematically impossible for the light module
        val midBackground = listOf(0.50f)
        val joint = AdaptiveColorOptimizer.optimizeJointColorPair(
            frameLums = midBackground,
            defaultDark = Color.BLACK,
            defaultLight = Color.WHITE,
            targetContrast = 3.0f
        )

        assertTrue("Dark target met against 0.50", joint.dark.targetMet)
        assertFalse("Light target cannot meet 3.0:1 against 0.50 (max possible is 1.91)", joint.light.targetMet)
        assertFalse("allTargetsMet must be false", joint.allTargetsMet)
        assertEquals("Fallback must be best achievable candidate (White)", Color.WHITE, joint.light.color)
        assertTrue("Pair contrast ratio must still be preserved >= 3.0", joint.pairContrastRatio >= 3.0f)
    }

    @Test
    fun testTransparentLightModulePreserved() {
        val frameLums = listOf(0.90f)
        val joint = AdaptiveColorOptimizer.optimizeJointColorPair(
            frameLums = frameLums,
            defaultDark = Color.BLACK,
            defaultLight = Color.TRANSPARENT,
            targetContrast = 3.0f
        )

        assertEquals("Transparent light module must remain transparent", Color.TRANSPARENT, joint.light.color)
        assertTrue("Transparent module targetMet must be true", joint.light.targetMet)
        assertEquals(OptimizationStatus.TRANSPARENT_PRESERVED, joint.light.status)
        assertTrue("Dark module targetMet must be true", joint.dark.targetMet)
        assertEquals(OptimizationStatus.TARGET_MET, joint.dark.status)
        assertTrue("All targets met", joint.allTargetsMet)
        assertEquals(OptimizationStatus.TARGET_MET, joint.status)
    }

    @Test
    fun testTemporalWorstCaseFrameEvaluation() {
        val frames = listOf(0.70f, 0.20f)
        val result = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = frames,
            referenceColor = Color.BLACK,
            targetContrast = 3.0f
        )

        assertTrue(result.targetMet)
        assertEquals(OptimizationStatus.TARGET_MET, result.status)
        // The contrast ratio reported must be the worst-case frame (against L=0.20 -> CR = (0.20+0.05)/(0.0+0.05) = 5.0)
        assertEquals(5.0f, result.contrastRatio, 0.05f)
    }

    @Test
    fun testTrueJointCandidatePairEvaluationAvoidsForcedMonochrome() {
        val cyan = 0xFF39C5BC.toInt()
        val yellow = 0xFFFFF070.toInt()
        val bgLums = listOf(0.15f) // dark background: both cyan and yellow can contrast well

        val joint = AdaptiveColorOptimizer.optimizeJointColorPair(
            frameLums = bgLums,
            defaultDark = cyan,
            defaultLight = yellow,
            targetContrast = 3.0f
        )

        // Rather than forcing Color.BLACK or Color.WHITE, joint optimizer evaluates (cyan x yellow)
        // and preserves user seed hues
        assertNotEquals("Dark module should not collapse to pure black when colored pair is viable", Color.BLACK, joint.dark.color)
        assertNotEquals("Light module should not collapse to pure white when colored pair is viable", Color.WHITE, joint.light.color)
        assertTrue("Dark must meet target contrast against 0.15", joint.dark.contrastRatio >= 3.0f)
        assertTrue("Light must meet target contrast against 0.15", joint.light.contrastRatio >= 3.0f)
        assertTrue("Dark and light must contrast with each other", joint.pairContrastRatio >= 3.0f)
        assertTrue("All targets met", joint.allTargetsMet)
        assertEquals(OptimizationStatus.TARGET_MET, joint.status)
    }

    @Test
    fun testImageGeometryBuilderPopulatesOptimizationDiagnostics() {
        val bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        val matrix = com.veilframe.app.qr.model.QrMatrix("https://veilframe.app/diag", com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        val design = com.veilframe.app.qr.model.QrDesign(
            style = com.veilframe.app.qr.QrStyle.IMAGE,
            imageColorStrategy = com.veilframe.app.qr.model.ImageColorStrategy.ADAPTIVE_CONTRAST,
            imageSource = com.veilframe.app.qr.model.ImageSourceStyle(source = com.veilframe.app.qr.model.ImageSource.Memory(bmp)),
            dataColorDark = Color.BLACK,
            dataColorLight = Color.WHITE
        )
        val geom = com.veilframe.app.qr.model.QrGeometry(
            matrixSize = matrix.size,
            outputWidth = 250,
            outputHeight = 250,
            quietZoneModules = 1
        )

        val ir = com.veilframe.app.qr.geometry.ImageGeometryBuilder.generateGeometry(matrix, design, geom)
        assertNotNull("ImageGeometryBuilder must populate diagnostics when adaptive contrast is active", ir.diagnostics)
        val diag = ir.diagnostics!!
        assertTrue("Must record optimized data modules", diag.totalOptimizedModules > 0)
        assertTrue("Diagnostics must have measurements", diag.hasMeasurements)
        assertNotNull("Min contrast must not be null", diag.minAchievedContrast)
        assertTrue("Min achieved contrast must be > 1.0", diag.minAchievedContrast!! > 1.0f)
        assertNotNull("Mean contrast must not be null", diag.meanAchievedContrast)
        assertTrue("Mean achieved contrast must be > 1.0", diag.meanAchievedContrast!! > 1.0f)
    }

    @Test
    fun testFeasiblePairContractInternallyConsistent() {
        val darkBgLums = listOf(0.85f)
        val lightBgLums = listOf(0.85f)
        val joint = AdaptiveColorOptimizer.optimizeJointColorPair(
            darkFrameLums = darkBgLums,
            lightFrameLums = lightBgLums,
            defaultDark = Color.BLACK,
            defaultLight = Color.WHITE,
            targetContrast = 3.0f,
            minPairContrast = 2.0f
        )

        // Internal contract consistency: If a pair is feasible, allTargetsMet must be true
        assertTrue("Dark target met", joint.dark.targetMet)
        assertTrue("Light target met", joint.light.targetMet)
        assertTrue("Pair CR must be >= minPairContrast 2.0", joint.pairContrastRatio >= 2.0f)
        assertTrue("allTargetsMet must be true when feasible pair selected", joint.allTargetsMet)
        assertEquals(OptimizationStatus.TARGET_MET, joint.status)
    }

    @Test
    fun testMultiFrameLightFieldCompatibilityEvaluatedPerFrame() {
        // Multi-frame animation with 3 bright frames and 1 dark dip frame: [0.90, 0.90, 0.90, 0.20]
        val lightFrameLums = listOf(0.90f, 0.90f, 0.90f, 0.20f)
        val darkFrameLums = listOf(0.90f, 0.90f, 0.90f, 0.20f)

        val joint = AdaptiveColorOptimizer.optimizeJointColorPair(
            darkFrameLums = darkFrameLums,
            lightFrameLums = lightFrameLums,
            defaultDark = Color.BLACK,
            defaultLight = Color.WHITE,
            targetContrast = 3.0f
        )

        // White module matches the light field on the 0.90 frames,
        // and achieves CR(1.0, 0.20) = 4.2 >= 3.0 on the 0.20 frame.
        // It must NOT be rejected merely because min() is 0.20!
        assertTrue("White light module must be compatible with multi-frame light field", joint.light.targetMet)
        assertTrue("Dark module must contrast with frames", joint.dark.targetMet)
        assertTrue("All targets met", joint.allTargetsMet)
        assertEquals(OptimizationStatus.TARGET_MET, joint.status)
    }

    @Test
    fun testSpatialModuleContextPassedToOptimizer() {
        val dist1 = FootprintSampler.computeDistribution(listOf(0.80f, 0.85f, 0.90f, 0.95f))
        val dist2 = FootprintSampler.computeDistribution(listOf(0.70f, 0.75f, 0.80f, 0.85f))
        val spatialContext = SpatialModuleContext(listOf(dist1, dist2), SamplingObjective.PERCENTILE_90_10)

        val darkCandidates = AdaptiveColorOptimizer.generateCandidatePool(true, Color.BLACK)
        val lightCandidates = AdaptiveColorOptimizer.generateCandidatePool(false, Color.WHITE)

        val joint = AdaptiveColorOptimizer.optimizeJointWithSpatialContext(
            context = spatialContext,
            darkCandidates = darkCandidates,
            lightCandidates = lightCandidates,
            defaultDark = Color.BLACK,
            defaultLight = Color.WHITE,
            targetContrast = 3.0f
        )

        assertTrue("Dark target met via spatial context", joint.dark.targetMet)
        assertTrue("Light target met via spatial context", joint.light.targetMet)
        assertTrue("All targets met via spatial context", joint.allTargetsMet)
        assertEquals(OptimizationStatus.TARGET_MET, joint.status)
    }

    @Test
    fun testSemiTransparentCandidateColorEffectiveContrast() {
        // Semi-transparent black (alpha = 128 / ~50% opacity) over a white background (lum = 1.0)
        // Effective rendered luminance = 0.50 * 0.0 + 0.50 * 1.0 = 0.50
        // Effective rendered contrast ratio = (1.0 + 0.05) / (0.50 + 0.05) = 1.91:1
        // For target 3.0:1, targetMet must be FALSE, not falsely claimed as 21:1!
        val semiTransparentBlack = 0x80000000.toInt()
        val whiteBg = listOf(1.0f)

        val result = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = whiteBg,
            referenceColor = semiTransparentBlack,
            targetContrast = 3.0f
        )

        // Effective contrast of 50% opacity black over white is ~1.91, which is < 3.0
        assertTrue("Reported contrast must reflect actual rendered mark (< 2.5:1), was ${result.contrastRatio}", result.contrastRatio < 2.5f)
        assertFalse("targetMet must be false because effective rendered contrast does not reach 3.0:1", result.targetMet)
        assertEquals(OptimizationStatus.INFEASIBLE, result.status)
    }

    @Test
    fun testEmptyDiagnosticsReportsNullMetricsAndFalseHasMeasurements() {
        val emptyDiag = com.veilframe.app.qr.geometry.QrOptimizationDiagnostics(
            totalOptimizedModules = 0,
            targetMetCount = 0,
            minAchievedContrast = null,
            meanAchievedContrast = null,
            meanDeltaEOk = null,
            allTargetsMet = true
        )

        assertFalse("hasMeasurements must be false when totalOptimizedModules == 0", emptyDiag.hasMeasurements)
        assertNull("minAchievedContrast must be null", emptyDiag.minAchievedContrast)
        assertNull("meanAchievedContrast must be null", emptyDiag.meanAchievedContrast)
        assertNull("meanDeltaEOk must be null", emptyDiag.meanDeltaEOk)
        assertTrue("allTargetsMet is true by default for empty workload", emptyDiag.allTargetsMet)
    }
}
