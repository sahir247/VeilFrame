package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.ModuleShape as DesignModuleShape
import com.veilframe.app.qr.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs

/**
 * True Canvas vs. SVG Pixel Differential Test Suite (GUIDE.txt Issue 8 & AGENTS.md §8).
 *
 * Real differential test on physical Android hardware/software pipeline:
 *
 *   same QrMatrix
 *   same QrDesign
 *   same QrGeometry
 *           |
 *           +----> Canvas -> Bitmap A
 *           |
 *           +----> SVG -> DeterministicSvgRasterizer -> Bitmap B
 *
 * Compares pixels and reports:
 *  - total pixels
 *  - differing pixels
 *  - maximum channel delta
 *  - mean absolute error (MAE)
 *  - percentage differing
 *
 * Covers all 10 required test cases:
 *  1. BASIC square
 *  2. BASIC roundedRectangle
 *  3. BASIC circle
 *  4. BASIC diamond
 *  5. Finder shapes (CLASSIC, CIRCLE, ROUNDED, PLANETS, DSJ)
 *  6. Gradient modules
 *  7. Gradient background
 *  8. Corner radius
 *  9. Quiet zone
 *  10. Non-square canvas dimensions
 */
@RunWith(AndroidJUnit4::class)
class CanvasVsSvgPixelDifferentialTest {

    data class DifferentialResult(
        val caseName: String,
        val totalPixels: Int,
        val differingPixels: Int,
        val maxChannelDelta: Int,
        val meanAbsoluteError: Double,
        val percentageDiffering: Double
    )

    private fun compareBitmaps(
        bitmapA: Bitmap,
        bitmapB: Bitmap,
        caseName: String,
        tolerance: Int = 4
    ): DifferentialResult {
        assertEquals("Widths must match for $caseName", bitmapA.width, bitmapB.width)
        assertEquals("Heights must match for $caseName", bitmapA.height, bitmapB.height)

        val w = bitmapA.width
        val h = bitmapB.height
        val totalPixels = w * h
        var differingPixels = 0
        var maxChannelDelta = 0
        var totalChannelSum = 0L

        val pixelsA = IntArray(totalPixels)
        val pixelsB = IntArray(totalPixels)
        bitmapA.getPixels(pixelsA, 0, w, 0, 0, w, h)
        bitmapB.getPixels(pixelsB, 0, w, 0, 0, w, h)

        for (i in 0 until totalPixels) {
            val pA = pixelsA[i]
            val pB = pixelsB[i]

            val aA = (pA ushr 24) and 0xFF
            val rA = (pA ushr 16) and 0xFF
            val gA = (pA ushr 8) and 0xFF
            val bA = pA and 0xFF

            val aB = (pB ushr 24) and 0xFF
            val rB = (pB ushr 16) and 0xFF
            val gB = (pB ushr 8) and 0xFF
            val bB = pB and 0xFF

            val da = abs(aA - aB)
            val dr = abs(rA - rB)
            val dg = abs(gA - gB)
            val db = abs(bA - bB)

            val maxDelta = maxOf(da, dr, dg, db)
            if (maxDelta > maxChannelDelta) {
                maxChannelDelta = maxDelta
            }

            val channelSum = da + dr + dg + db
            totalChannelSum += channelSum

            if (maxDelta > tolerance) {
                differingPixels++
            }
        }

        val mae = totalChannelSum.toDouble() / (totalPixels * 4.0)
        val pct = (differingPixels.toDouble() / totalPixels.toDouble()) * 100.0

        val result = DifferentialResult(
            caseName = caseName,
            totalPixels = totalPixels,
            differingPixels = differingPixels,
            maxChannelDelta = maxChannelDelta,
            meanAbsoluteError = mae,
            percentageDiffering = pct
        )

        println(
            String.format(
                Locale.US,
                "[PIXEL DIFFERENTIAL] %-28s | Total: %6d | Diff: %5d (%5.2f%%) | MaxDelta: %3d | MAE: %.4f",
                caseName, totalPixels, differingPixels, pct, maxChannelDelta, mae
            )
        )
        return result
    }

    private fun executeDifferentialCase(
        caseName: String,
        design: QrDesign,
        width: Int = 400,
        height: Int = 400,
        payload: String = "https://veilframe.app/pixel-diff",
        maxAllowedMae: Double = 1.0,
        maxAllowedPctDiff: Double = 5.0
    ): DifferentialResult {
        val matrix = QrGenerator.generateMatrix(payload, design)
        val geometry = QrGeometry.fromDesign(matrix.size, width, height, design)

        // 1. Canvas -> Bitmap A
        val bitmapA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvasA = Canvas(bitmapA)
        QrGenerator.renderToCanvas(matrix, design, canvasA, geometry)

        // 2. SVG -> Rasterizer -> Bitmap B
        val svg = QrGenerator.generateSvg(matrix, design, geometry = geometry)
        val bitmapB = DeterministicSvgRasterizer.rasterize(svg, width, height)

        // 3. Compare pixel buffers
        val result = compareBitmaps(bitmapA, bitmapB, caseName)

        assertTrue(
            "MAE must be <= $maxAllowedMae for $caseName, was ${result.meanAbsoluteError}",
            result.meanAbsoluteError <= maxAllowedMae
        )
        assertTrue(
            "Differing pixels must be <= $maxAllowedPctDiff% for $caseName, was ${result.percentageDiffering}%",
            result.percentageDiffering <= maxAllowedPctDiff
        )

        return result
    }

    // =========================================================================
    // 1. BASIC Square
    // =========================================================================

    @Test
    fun testCase1_BasicSquare() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.SQUARE),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        val result = executeDifferentialCase("1. BASIC Square", design, maxAllowedMae = 0.1, maxAllowedPctDiff = 1.0)
        assertEquals("Square modules with aligned bounds must have 0 max delta on integer boundaries", 0, result.maxChannelDelta)
    }

    // =========================================================================
    // 2. BASIC RoundedRectangle
    // =========================================================================

    @Test
    fun testCase2_BasicRoundedRectangle() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.ROUNDED, cornerRadiusFraction = 0.25f),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("2. BASIC RoundedRectangle", design, maxAllowedMae = 0.5, maxAllowedPctDiff = 3.0)
    }

    // =========================================================================
    // 3. BASIC Circle
    // =========================================================================

    @Test
    fun testCase3_BasicCircle() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.CIRCLE, scale = 0.85f),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("3. BASIC Circle", design, maxAllowedMae = 0.5, maxAllowedPctDiff = 3.0)
    }

    // =========================================================================
    // 4. BASIC Diamond
    // =========================================================================

    @Test
    fun testCase4_BasicDiamond() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.DIAMOND, scale = 0.9f),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("4. BASIC Diamond", design, maxAllowedMae = 0.5, maxAllowedPctDiff = 3.0)
    }

    // =========================================================================
    // 5. Finder Shapes (CLASSIC, CIRCLE, ROUNDED, PLANETS, DSJ)
    // =========================================================================

    @Test
    fun testCase5_FinderShapes() {
        val finderStyles = listOf(
            FinderStyle.CLASSIC,
            FinderStyle.CIRCLE,
            FinderStyle.ROUNDED,
            FinderStyle.PLANETS,
            FinderStyle.DSJ
        )
        for (fStyle in finderStyles) {
            val design = QrDesign(
                style = QrStyle.BASIC,
                eyeStyle = EyeStyle(style = fStyle),
                palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
            )
            executeDifferentialCase("5. Finder ${fStyle.name}", design, maxAllowedMae = 0.6, maxAllowedPctDiff = 3.5)
        }
    }

    // =========================================================================
    // 6. Gradient Modules
    // =========================================================================

    @Test
    fun testCase6_GradientModules() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.SQUARE, fill = ModuleFill.LINEAR_GRADIENT),
            palette = PaletteStyle(
                gradientStart = 0xFF003366.toInt(),
                gradientEnd = 0xFFCC3300.toInt(),
                background = Color.WHITE
            )
        )
        executeDifferentialCase("6. Gradient Modules", design, maxAllowedMae = 0.5, maxAllowedPctDiff = 2.0)
    }

    // =========================================================================
    // 7. Gradient Background
    // =========================================================================

    @Test
    fun testCase7_GradientBackground() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            background = BackgroundStyle.LinearGradient(
                startColor = 0xFFEEFFEE.toInt(),
                endColor = 0xFFCCDDFF.toInt(),
                angleDegrees = 45f
            ),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.TRANSPARENT)
        )
        executeDifferentialCase("7. Gradient Background", design, maxAllowedMae = 0.5, maxAllowedPctDiff = 2.0)
    }

    // =========================================================================
    // 8. Corner Radius (Backdrop Corner Clip)
    // =========================================================================

    @Test
    fun testCase8_CornerRadius() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(cornerRadius = 32f),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("8. Corner Radius", design, maxAllowedMae = 0.5, maxAllowedPctDiff = 2.0)
    }

    // =========================================================================
    // 9. Quiet Zone
    // =========================================================================

    @Test
    fun testCase9_QuietZone() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            quietZoneModules = 4,
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("9. Quiet Zone (QZ=4)", design, maxAllowedMae = 0.1, maxAllowedPctDiff = 1.0)
    }

    // =========================================================================
    // 10. Non-Square Canvas Dimensions
    // =========================================================================

    @Test
    fun testCase10_NonSquareCanvasDimensions() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            background = BackgroundStyle.LinearGradient(
                startColor = 0xFFFFEEEE.toInt(),
                endColor = 0xFFEEEEFF.toInt(),
                angleDegrees = 90f
            ),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.TRANSPARENT)
        )
        executeDifferentialCase(
            caseName = "10. Non-Square (400x560)",
            design = design,
            width = 400,
            height = 560,
            maxAllowedMae = 0.5,
            maxAllowedPctDiff = 2.0
        )
    }
}
