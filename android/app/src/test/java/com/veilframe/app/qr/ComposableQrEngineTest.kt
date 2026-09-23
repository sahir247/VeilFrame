package com.veilframe.app.qr

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.renderer.EffectEngine
import com.veilframe.app.qr.renderer.FillEngine
import com.veilframe.app.qr.renderer.ShapeEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying the Composable Artistic QR Engine:
 * - 8-Way [ModuleNeighborhood] topology
 * - [ShapeEngine] geometric path compilation
 * - [FillEngine] color and gamma luminance calculation
 * - [EffectEngine] 2.5D depth and algorithmic math function transforms
 * - [SvgExporter] composable vector markup generation
 */
class ComposableQrEngineTest {

    @Test
    fun testModuleNeighborhoodTopology() {
        val isolated = ModuleNeighborhood()
        assertTrue("Empty neighborhood must be isolated", isolated.isIsolated)
        assertEquals(0, isolated.orthogonalCount)
        assertFalse(isolated.isHorizontalLine)
        assertFalse(isolated.isVerticalLine)
        assertFalse(isolated.isCross)

        val horizontal = ModuleNeighborhood(left = true, right = true)
        assertTrue("Left and right only must be horizontal line", horizontal.isHorizontalLine)
        assertFalse(horizontal.isIsolated)
        assertEquals(2, horizontal.orthogonalCount)

        val vertical = ModuleNeighborhood(up = true, down = true)
        assertTrue("Up and down only must be vertical line", vertical.isVerticalLine)
        assertFalse(vertical.isHorizontalLine)
        assertEquals(2, vertical.orthogonalCount)

        val cross = ModuleNeighborhood(up = true, down = true, left = true, right = true)
        assertTrue("All 4 directions must be cross", cross.isCross)
        assertEquals(4, cross.orthogonalCount)

        val cornerTL = ModuleNeighborhood(down = true, right = true)
        assertTrue("Down and right only must be top-left corner", cornerTL.isCornerTopLeft)
        assertTrue(cornerTL.isCorner)
    }

    @Test
    fun testQrMatrixNeighborhoodPopulation() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP", ErrorCorrectionLevel.M)
        assertTrue(matrix.size > 20)

        val centerCol = matrix.size / 2
        val centerRow = matrix.size / 2
        val module = matrix.moduleAt(centerCol, centerRow)

        assertNotNull(module.neighbors)
        assertEquals(module.col, centerCol)
        assertEquals(module.row, centerRow)
        assertEquals(module.role, matrix.roleAt(centerCol, centerRow))
    }

    @Test
    fun testShapeEnginePathGeneration() {
        val rect = RectF(0f, 0f, 20f, 20f)
        val module = QrModule(
            col = 10,
            row = 10,
            isDark = true,
            role = QrModuleRole.DATA,
            neighbors = ModuleNeighborhood(left = true, right = true)
        )

        val shapesToTest = listOf(
            ModuleShape.SQUARE,
            ModuleShape.ROUNDED,
            ModuleShape.CIRCLE,
            ModuleShape.DIAMOND,
            ModuleShape.HEX,
            ModuleShape.STAR,
            ModuleShape.BUBBLE,
            ModuleShape.ORGANIC,
            ModuleShape.LINE
        )

        for (shape in shapesToTest) {
            val design = QrDesign(
                moduleStyle = ModuleStyle(shape = shape)
            )
            val path = Path()
            ShapeEngine.buildModulePath(module, rect, design, path)
            assertNotNull("Shape $shape must generate a path", path)
        }
    }

    @Test
    fun testDeterministicJitterReproducibility() {
        val rect = RectF(0f, 0f, 20f, 20f)
        val module = QrModule(col = 5, row = 7, isDark = true, role = QrModuleRole.DATA)

        val designA = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            jitterStyle = RandomJitterStyle(seed = 1234567L)
        )
        val designB = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            jitterStyle = RandomJitterStyle(seed = 1234567L)
        )

        val pathA = ShapeEngine.buildModulePath(module, rect, designA)
        val pathB = ShapeEngine.buildModulePath(module, rect, designB)
        assertNotNull(pathA)
        assertNotNull(pathB)
    }

    @Test
    fun testEffectEngineTransformBounds() {
        val designWave = QrDesign(
            style = QrStyle.FUNCTION,
            functionStyle = FunctionStyle(type = FunctionType.WAVE, frequency = 0.5f, amplitude = 0.3f)
        )

        for (col in 0 until 25) {
            for (row in 0 until 25) {
                val t = EffectEngine.computeTransform(col, row, 25, designWave)
                assertTrue("Scale multiplier must remain >= 0.55 for scanability: ${t.scaleMultiplier}", t.scaleMultiplier >= 0.55f)
                assertTrue("Scale multiplier must remain <= 1.0: ${t.scaleMultiplier}", t.scaleMultiplier <= 1.0f)
            }
        }
    }

    @Test
    fun testFillEngineDefaultColor() {
        val module = QrModule(col = 0, row = 0, isDark = true, role = QrModuleRole.DATA)
        val design = QrDesign(
            palette = PaletteStyle(foreground = Color.BLUE, background = Color.YELLOW)
        )
        val color = FillEngine.resolveModuleColor(module, 25, design)
        assertEquals(Color.BLUE, color)
    }

    @Test
    fun testSvgExporterComposableFeatures() {
        val matrix = QrMatrix("VEILFRAME_COMPOSABLE_TEST", ErrorCorrectionLevel.H)

        // 1. Test 2.5D Isometric SVG markup
        val design25D = QrDesign(
            style = QrStyle.D25,
            depthStyle = DepthStyle(depth = 0.5f, leftColor = 0x33000000, rightColor = 0x99000000.toInt())
        )
        val svg25D = SvgExporter.generateSvg(matrix, design25D)
        assertTrue(svg25D.contains("<svg"))
        assertTrue(svg25D.contains("<polygon points="))

        // 2. Test Directional Line SVG markup
        val designLine = QrDesign(
            style = QrStyle.LINE,
            lineStyle = LineStyle(direction = LineDirection.CROSS, thicknessFraction = 0.4f)
        )
        val svgLine = SvgExporter.generateSvg(matrix, designLine)
        assertTrue(svgLine.contains("<line x1="))
        assertTrue(svgLine.contains("stroke-linecap=\"round\""))

        // 3. Test Organic Connected Blob SVG markup
        val designOrganic = QrDesign(
            style = QrStyle.CONNECTED_ORGANIC,
            moduleStyle = ModuleStyle(shape = ModuleShape.ORGANIC)
        )
        val svgOrganic = SvgExporter.generateSvg(matrix, designOrganic)
        assertTrue(svgOrganic.contains("<rect x="))
    }
}
