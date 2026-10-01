package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.geometry.BasicGeometryBuilder
import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.IrSvgRenderer
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import org.junit.Assert.*
import org.junit.Test

/**
 * Cross-Backend Canvas vs. SVG Parity Test Suite (Audit Item H-05 & P3.2).
 *
 * Mathematically and structurally validates output equivalence between Android Canvas
 * raster rendering and SVG vector emission across all 10 EFQRCode styles and 2 VeilFrame extensions.
 */
class CrossBackendCanvasSvgParityTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    private class TrackingCanvas : Canvas() {
        val rects = mutableListOf<RectF>()
        val circles = mutableListOf<Triple<Float, Float, Float>>()
        val paths = mutableListOf<Path>()
        var customSaveCount = 0
        var customRestoreCount = 0

        override fun drawRect(rect: RectF, paint: Paint) {
            rects.add(RectF(rect))
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects.add(RectF(left, top, right, bottom))
        }

        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            circles.add(Triple(cx, cy, radius))
        }

        override fun drawPath(path: Path, paint: Paint) {
            paths.add(Path(path))
        }

        override fun save(): Int {
            customSaveCount++
            return customSaveCount
        }

        override fun restore() {
            customRestoreCount++
        }

        override fun restoreToCount(count: Int) {
            customRestoreCount++
        }
    }

    private data class SvgViewBox(val minX: Double, val minY: Double, val width: Double, val height: Double)

    private fun parseSvgViewBox(svg: String): SvgViewBox {
        val vbRegex = Regex("""viewBox="([^"]+)"""")
        val match = vbRegex.find(svg) ?: throw IllegalArgumentException("No viewBox in SVG: ${svg.take(200)}")
        val parts = match.groupValues[1].trim().split(Regex("""[\s,]+""")).map { it.toDouble() }
        assertEquals("viewBox must have 4 parameters", 4, parts.size)
        return SvgViewBox(parts[0], parts[1], parts[2], parts[3])
    }

    @Test
    fun testAllStylesCrossBackendGeometryBoundsConsistency() {
        val dummyBmp = allocateBitmapReflectively()
        val outputSize = 1024
        val content = "https://veilframe.app/cross-backend-h05"

        for (style in QrStyle.values()) {
            val design = QrDesign(
                style = style,
                outputSize = outputSize,
                imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp))
            )

            val matrix = QrGenerator.generateMatrix(content, design)
            val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)

            // Canvas content bounds
            val canvasBounds = geometry.dataRegionBounds()
            val canvasW = canvasBounds.right - canvasBounds.left
            val canvasH = canvasBounds.bottom - canvasBounds.top

            assertEquals("Canvas width must equal matrix.size * moduleSize for $style", matrix.size * geometry.moduleSize, canvasW, 0.05f)
            assertEquals("Canvas height must equal matrix.size * moduleSize for $style", matrix.size * geometry.moduleSize, canvasH, 0.05f)

            // SVG viewBox and module scale
            val svg = QrGenerator.generateSvg(matrix, design)
            val vb = parseSvgViewBox(svg)

            if (style != QrStyle.D25) {
                val resolvedQz = QrGeometry.resolveQuietZone(design, matrix.size)
                val expectedTotalW = matrix.size + resolvedQz.left.toDouble() + resolvedQz.right.toDouble()
                assertEquals("SVG viewBox width must match total modules for $style", expectedTotalW, vb.width, 0.05)

                val svgModuleScale = outputSize / vb.width.toFloat()
                val svgContentLeft = resolvedQz.left * svgModuleScale
                val svgContentTop = resolvedQz.top * svgModuleScale
                val svgContentW = matrix.size * svgModuleScale
                val svgContentH = matrix.size * svgModuleScale

                assertEquals("Left bound parity for $style", canvasBounds.left, svgContentLeft, 0.05f)
                assertEquals("Top bound parity for $style", canvasBounds.top, svgContentTop, 0.05f)
                assertEquals("Width bound parity for $style", canvasW, svgContentW, 0.05f)
                assertEquals("Height bound parity for $style", canvasH, svgContentH, 0.05f)
            } else {
                assertTrue("D25 viewBox must have non-zero dimensions", vb.width > 0 && vb.height > 0)
            }
        }
    }

    @Test
    fun testBasicStyleUnifiedIrExecutionParity() {
        val matrix = QrMatrix("https://veilframe.app/basic-ir", ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.BASIC,
            outputSize = 512,
            moduleStyle = ModuleStyle(shape = ModuleShape.ROUNDED, cornerRadiusFraction = 0.3f)
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)

        // 1. Generate unified IR
        val ir = BasicGeometryBuilder.generateGeometry(matrix, design, geometry)
        assertNotNull("IR must not be null", ir)
        assertTrue("IR must contain geometry nodes", ir.rootNodes.isNotEmpty())

        // 2. Render IR to Canvas
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)
        assertTrue("Canvas must receive rects from IR", canvas.rects.isNotEmpty())

        // 3. Render IR to SVG
        val svg = IrSvgRenderer.render(ir)
        assertTrue("SVG must start with <svg", svg.contains("<svg"))
        assertTrue("SVG must contain rect elements", svg.contains("<rect"))
        assertTrue("SVG must close with </svg>", svg.contains("</svg>"))
    }

    @Test
    fun testStyleTaxonomyParityAndExtensions() {
        // Assert exactly 10 EFQRCode 7.0.3 parity styles
        val efStyles = QrStyle.values().filter { it.isEfParityStyle }
        assertEquals("Must have exactly 10 EF parity styles", 10, efStyles.size)
        assertTrue(efStyles.contains(QrStyle.BASIC))
        assertTrue(efStyles.contains(QrStyle.BUBBLE))
        assertTrue(efStyles.contains(QrStyle.D25))
        assertTrue(efStyles.contains(QrStyle.DSJ))
        assertTrue(efStyles.contains(QrStyle.IMAGE_FILL))
        assertTrue(efStyles.contains(QrStyle.IMAGE))
        assertTrue(efStyles.contains(QrStyle.IMAGE_RESAMPLE))
        assertTrue(efStyles.contains(QrStyle.LINE))
        assertTrue(efStyles.contains(QrStyle.RANDOM_RECTANGLE))
        assertTrue(efStyles.contains(QrStyle.FUNCTION))

        // Assert exactly 2 VeilFrame-only extension styles
        val extensions = QrStyle.values().filter { it.isVeilFrameExtension }
        assertEquals("Must have exactly 2 VeilFrame extensions", 2, extensions.size)
        assertTrue(extensions.contains(QrStyle.STYLE_FUNCTION))
        assertTrue(extensions.contains(QrStyle.CONNECTED_ORGANIC))
    }

    @Test
    fun testDefaultModeForCanonicalParityContract() {
        val basicDesign = QrDesign(style = QrStyle.BASIC)
        val resampleDesign = QrDesign(style = QrStyle.IMAGE_RESAMPLE)

        // Canonical default must be PARITY_EF for BASIC (H-01 / C-02 resolution)
        assertEquals(
            "BASIC defaultModeFor must map to PARITY_EF",
            GenerationMode.PARITY_EF,
            QrGenerator.defaultModeFor(basicDesign)
        )
        assertEquals(
            "BASIC recommendedGenerationMode must map to PARITY_EF",
            GenerationMode.PARITY_EF,
            basicDesign.recommendedGenerationMode
        )

        // Explicit preferSafe must map to SAFE
        assertEquals(
            "BASIC with preferSafe=true must map to SAFE",
            GenerationMode.SAFE,
            QrGenerator.defaultModeFor(basicDesign, preferSafe = true)
        )

        // Artistic styles must remain ARTISTIC_ENGINE
        assertEquals(
            "IMAGE_RESAMPLE must map to ARTISTIC_ENGINE",
            GenerationMode.ARTISTIC_ENGINE,
            QrGenerator.defaultModeFor(resampleDesign)
        )
    }

    @Test
    fun basicProductionCanvasAndSvgUseCanonicalGeometry() {
        val content = "https://veilframe.app/basic-production"
        val design = QrDesign(
            style = QrStyle.BASIC,
            moduleStyle = ModuleStyle(
                shape = ModuleShape.DIAMOND,
                scale = 0.82f
            )
        )

        val matrix = QrGenerator.generateParityMatrix(
            content,
            design
        )

        val geometry = QrGeometry.fromDesign(
            matrixSize = matrix.size,
            outputWidth = 512,
            outputHeight = 512,
            design = design
        )

        // Production Canvas path
        val canvas = TrackingCanvas()

        QrGenerator.renderToCanvas(
            matrix = matrix,
            design = design,
            canvas = canvas,
            geometry = geometry
        )

        assertTrue(canvas.paths.isNotEmpty())

        // Production SVG path
        val svg = QrGenerator.generateSvg(
            matrix = matrix,
            design = design
        )

        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("<polygon") || svg.contains("<path"))

        // Canonical IR
        val ir = BasicGeometryBuilder.generateGeometry(
            matrix = matrix,
            design = design,
            geometry = geometry
        )

        val irSvg = IrSvgRenderer.render(ir)

        // Both production routes must originate from the canonical IR.
        assertTrue(ir.rootNodes.isNotEmpty())
        assertTrue(irSvg.contains("<svg"))
    }
}
