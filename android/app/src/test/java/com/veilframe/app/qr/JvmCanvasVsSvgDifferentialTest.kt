package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.ModuleShape as DesignModuleShape
import com.veilframe.app.qr.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * JVM-based Canvas ↔ SVG Pixel Differential Test Suite using Robolectric native graphics (Skia).
 *
 * Runs the exact same mathematical pixel differential pipeline on the JVM host environment:
 *   same QrMatrix
 *   same QrDesign
 *   same QrGeometry
 *           |
 *           +----> Canvas -> Bitmap A
 *           |
 *           +----> SVG -> DeterministicSvgRasterizer -> Bitmap B
 *
 * Compares pixels and enforces tolerances (MAE <= 1.0, differing pixels <= 5.0%) across
 * the 10 core primitive test cases without requiring an attached physical Android device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class JvmCanvasVsSvgDifferentialTest {

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
                "[JVM PIXEL DIFFERENTIAL] %-28s | Total: %6d | Diff: %5d (%5.2f%%) | MaxDelta: %3d | MAE: %.4f",
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

    @Test
    fun case01_squareModules() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.SQUARE),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        val res = executeDifferentialCase("01_square_modules", design)
        assertTrue(res.differingPixels < res.totalPixels * 0.01)
    }

    @Test
    fun case02_roundedModules() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.ROUNDED, cornerRadiusFraction = 0.25f),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("02_rounded_modules", design)
    }

    @Test
    fun case03_circleModules() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.CIRCLE),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("03_circle_modules", design)
    }

    @Test
    fun case04_diamondModules() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.DIAMOND),
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("04_diamond_modules", design)
    }

    @Test
    fun case05_finderShapes() {
        val finderStyles = listOf(
            FinderStyle.CLASSIC,
            FinderStyle.ROUNDED,
            FinderStyle.CIRCLE,
            FinderStyle.PLANETS,
            FinderStyle.DSJ
        )
        for (fStyle in finderStyles) {
            val design = QrDesign(
                style = QrStyle.BASIC,
                eyeStyle = EyeStyle(style = fStyle),
                palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
            )
            executeDifferentialCase("05_finder_${fStyle.name.lowercase(Locale.US)}", design)
        }
    }

    @Test
    fun case06_gradientModules() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.SQUARE, fill = ModuleFill.LINEAR_GRADIENT),
            palette = PaletteStyle(
                foreground = Color.BLACK,
                background = Color.WHITE,
                gradientStart = Color.RED,
                gradientEnd = Color.BLUE,
                gradientType = GradientType.LINEAR
            )
        )
        executeDifferentialCase("06_gradient_modules", design)
    }

    @Test
    fun case07_gradientBackground() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(shape = DesignModuleShape.SQUARE),
            background = BackgroundStyle.LinearGradient(Color.YELLOW, Color.CYAN, angleDegrees = 90f),
            palette = PaletteStyle(foreground = Color.BLACK)
        )
        executeDifferentialCase("07_gradient_background", design)
    }

    @Test
    fun case08_cornerRadius() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(cornerRadius = 20f, color = Color.WHITE),
            palette = PaletteStyle(foreground = Color.BLACK)
        )
        executeDifferentialCase("08_corner_radius", design)
    }

    @Test
    fun case09_quietZone() {
        for (qz in listOf(0, 1, 4)) {
            val design = QrDesign(
                style = QrStyle.BASIC,
                explicitQuietZone = qz,
                quietZoneModules = qz,
                palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
            )
            executeDifferentialCase("09_quiet_zone_$qz", design)
        }
    }

    @Test
    fun case10_nonSquareDimensions() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase(
            caseName = "10_non_square_400x560",
            design = design,
            width = 400,
            height = 560
        )
    }
}
