package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.model.BasicGeometryProfile
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

    @Test
    fun case11_strictScanabilityGatingQuietZone() {
        // C3 / P0.4: validateStrict must hard-gate quiet zone compliance
        val matrix = QrGenerator.generateMatrix("SCAN_GATE_TEST")
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val nonCompliantDesign = QrDesign(
            style = QrStyle.BASIC,
            basicProfile = BasicGeometryProfile.VEILFRAME,
            quietZoneModules = 2,
            explicitQuietZone = null
        )
        val report = kotlinx.coroutines.runBlocking {
            com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
                bmp,
                nonCompliantDesign,
                matrix,
                "SCAN_GATE_TEST"
            )
        }
        assertFalse("Strict validation must reject QR when quiet zone is non-compliant", report.isScanReady)
        assertTrue("Report must contain quiet zone warning", report.warnings.any { it.contains("Quiet zone") })
    }

    @Test
    fun case12_productionDeterministicSvgRasterizerExecution() {
        // C4 / P1.4: Production SvgRasterizer rasterizes vector SVG into Bitmap
        val matrix = QrMatrix("SVG_RASTER_TEST", ErrorCorrectionLevel.H)
        val design = QrDesign(style = QrStyle.BASIC, outputSize = 256)
        val svg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(matrix, design)

        val rasterBmp = com.veilframe.app.qr.raster.DeterministicSvgRasterizer.rasterize(svg, 256, 256)
        assertNotNull("DeterministicSvgRasterizer must successfully rasterize vector SVG", rasterBmp)
        assertEquals(256, rasterBmp!!.width)
        assertEquals(256, rasterBmp.height)
    }

    @Test
    fun case13_bubbleCanvasVsSvgDifferential() {
        // P1.6: Dedicated BubbleRenderer produces consistent geometry between Canvas and SVG
        val design = QrDesign(
            style = QrStyle.BUBBLE,
            palette = PaletteStyle(foreground = Color.BLACK, background = Color.WHITE)
        )
        executeDifferentialCase("13_bubble_style", design)
    }

    @Test
    fun case14_directLegacyApiEcLevelPropagation() {
        // C1 / P0.1 direct legacy API verification:
        // Calling QrGenerator.generate(..., ecLevel = L/H) directly and verifying bitmap generation and decode
        val params = QrStyleParams(style = QrStyle.BASIC)
        val bitmapL = QrGenerator.generate(
            content = "HELLO_LEGACY_EC_L",
            params = params,
            ecLevel = ErrorCorrectionLevel.L
        )
        assertNotNull("QrGenerator.generate with ecLevel=L must return non-null Bitmap", bitmapL)
        val decoder = com.veilframe.app.qr.decoder.ZxingQrDecoder()
        val resultL = kotlinx.coroutines.runBlocking { decoder.decode(bitmapL!!) }
        assertTrue("Decoded text must match payload for EC L: ${resultL.error}", resultL.success)
        assertEquals("HELLO_LEGACY_EC_L", resultL.text)

        val bitmapH = QrGenerator.generate(
            content = "HELLO_LEGACY_EC_H",
            params = params,
            ecLevel = ErrorCorrectionLevel.H
        )
        assertNotNull("QrGenerator.generate with ecLevel=H must return non-null Bitmap", bitmapH)
        val resultH = kotlinx.coroutines.runBlocking { decoder.decode(bitmapH!!) }
        assertTrue("Decoded text must match payload for EC H: ${resultH.error}", resultH.success)
        assertEquals("HELLO_LEGACY_EC_H", resultH.text)
    }

    @Test
    fun case15_svgRasterizerImageAndMaskSupport() {
        // C4 / P1.4: Verify that DeterministicSvgRasterizer handles <image>, <mask id="...">, and <use>
        val testPng = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val testCanvas = Canvas(testPng)
        testCanvas.drawColor(Color.RED)
        val b64 = com.veilframe.app.qr.geometry.IrSvgRenderer.bitmapToBase64(testPng)

        val svgString = """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 100" width="100%" height="100%">
              <defs>
                <mask id="hole">
                  <rect width="100" height="100" fill="white" />
                  <circle cx="50" cy="50" r="20" fill="black" />
                </mask>
                <rect id="subPath" width="20" height="20" fill="#00ff00" />
              </defs>
              <g mask="url(#hole)">
                <image href="data:image/png;base64,$b64" x="0" y="0" width="100" height="100" preserveAspectRatio="none" />
              </g>
              <use xlink:href="#subPath" x="2" y="2">
                <animate attributeName="xlink:href" calcMode="discrete" values="#subPath" dur="1s" repeatCount="indefinite" />
              </use>
            </svg>
        """.trimIndent()

        val rasterBmp = com.veilframe.app.qr.raster.DeterministicSvgRasterizer.rasterize(svgString, 100, 100)
        assertNotNull(rasterBmp)
        assertEquals(100, rasterBmp.width)
        assertEquals(100, rasterBmp.height)

        // Inside the circle (cx=50, cy=50), the mask filled with black -> clipped to transparent (alpha == 0)
        val centerPixel = rasterBmp.getPixel(50, 50)
        assertEquals("Center masked area must be clipped to transparent", 0, Color.alpha(centerPixel))

        // Outside the mask hole and outside the green rect (e.g. at 90, 90), mask was white -> image was red
        val outsidePixel = rasterBmp.getPixel(90, 90)
        assertTrue("Outside masked area must be visible", Color.alpha(outsidePixel) > 200)
        assertEquals("Outside area color red channel must be 255", 255, Color.red(outsidePixel))

        // At (10, 10), <use> rendered the green rect (#00ff00)
        val usePixel = rasterBmp.getPixel(10, 10)
        assertTrue("Use element must be rendered", Color.alpha(usePixel) > 200)
        assertEquals("Use element green channel must be 255", 255, Color.green(usePixel))
    }
}
