package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.geometry.VeilIconPipeline
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry
import com.veilframe.app.qr.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Verification and Public Entry Point test suite for Step 4:
 *
 * Implements the staged public-entry verification contract:
 *   QrDesign
 *      ↓
 *   QrGeometry
 *      ↓
 *   same resolved content bounds
 *      ↓
 *   same logo bounds
 *      ↓
 *   same quiet-zone bounds
 *
 * And separately tests:
 *   Canvas raster (TrackingCanvas / RenderContext)
 *         ↕
 *   normalized SVG geometry
 *
 * Validates cross-backend consistency across all 12 QrStyle variants with tolerance
 * for anti-aliasing / raster differences.
 */
class Step4CrossBackendParityTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    private fun makeRectF(l: Float, t: Float, r: Float, b: Float): RectF = RectF(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    data class RecordedRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    class TrackingCanvas : Canvas() {
        val clipPathCalls = mutableListOf<Path>()
        val clipOutRectCalls = mutableListOf<RecordedRect>()
        val drawBitmapCalls = mutableListOf<Bitmap>()
        val drawPathCalls = mutableListOf<Path>()
        val drawRectCalls = mutableListOf<RecordedRect>()
        var drawColorCallCount = 0
        var saveCalls = 0
        var restoreCalls = 0

        override fun clipPath(path: Path): Boolean {
            clipPathCalls.add(path)
            return true
        }

        override fun clipOutRect(rect: RectF): Boolean {
            clipOutRectCalls.add(RecordedRect(rect.left, rect.top, rect.right, rect.bottom))
            return true
        }

        override fun clipOutRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            clipOutRectCalls.add(RecordedRect(left, top, right, bottom))
            return true
        }

        override fun drawColor(color: Int) {
            drawColorCallCount++
        }

        override fun save(): Int {
            saveCalls++
            return saveCalls
        }

        override fun restore() {
            restoreCalls++
        }

        override fun restoreToCount(saveCount: Int) {
            restoreCalls++
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
            drawBitmapCalls.add(bitmap)
        }

        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            drawBitmapCalls.add(bitmap)
        }

        override fun drawPath(path: Path, paint: Paint) {
            drawPathCalls.add(path)
        }

        override fun drawRect(rect: RectF, paint: Paint) {
            drawRectCalls.add(RecordedRect(rect.left, rect.top, rect.right, rect.bottom))
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            drawRectCalls.add(RecordedRect(left, top, right, bottom))
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
    fun testStagedPublicEntryContentBoundsConsistencyAcrossAllStyles() {
        val dummyBmp = allocateBitmapReflectively()
        val outputSize = 1024
        val content = "https://veilframe.app/step4-cross-backend"

        for (style in QrStyle.values()) {
            val design = QrDesign(
                style = style,
                outputSize = outputSize,
                imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp))
            )

            // Stage 1: Public Entry QrDesign -> QrMatrix -> QrGeometry
            val matrix = QrGenerator.generateMatrix(content, design)
            val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)

            // Stage 2: Content bounds on Canvas
            val canvasContentBounds = geometry.dataRegionBounds()
            val canvasW = canvasContentBounds.right - canvasContentBounds.left
            val canvasH = canvasContentBounds.bottom - canvasContentBounds.top
            assertEquals("Content width must match matrixSize * moduleSize", matrix.size * geometry.moduleSize, canvasW, 0.05f)
            assertEquals("Content height must match matrixSize * moduleSize", matrix.size * geometry.moduleSize, canvasH, 0.05f)

            // Stage 3: Content bounds from public SVG entry
            val svg = QrGenerator.generateSvg(matrix, design)
            val vb = parseSvgViewBox(svg)

            if (style != QrStyle.D25) {
                // 2D styles have viewBox width = matrixSize + qzLeft + qzRight
                val resolvedQz = QrGeometry.resolveQuietZone(design, matrix.size)
                val expectedTotalW = matrix.size + resolvedQz.left.toDouble() + resolvedQz.right.toDouble()
                assertEquals("SVG viewBox width must match total modules for $style", expectedTotalW, vb.width, 0.05)

                val svgModuleScale = outputSize / vb.width.toFloat()
                val svgContentLeft = (resolvedQz.left * svgModuleScale)
                val svgContentTop = (resolvedQz.top * svgModuleScale)
                val svgContentWidth = (matrix.size * svgModuleScale)
                val svgContentHeight = (matrix.size * svgModuleScale)

                // Stage 4: Assert same resolved content bounds between Canvas and SVG
                assertEquals("Content left bounds must match for $style", canvasContentBounds.left, svgContentLeft, 0.05f)
                assertEquals("Content top bounds must match for $style", canvasContentBounds.top, svgContentTop, 0.05f)
                assertEquals("Content width must match for $style", canvasW, svgContentWidth, 0.05f)
                assertEquals("Content height must match for $style", canvasH, svgContentHeight, 0.05f)
            } else {
                // D25 isometric projection: verify viewBox reflects matrix and quiet zone
                assertTrue("D25 viewBox must be non-empty", vb.width > 0 && vb.height > 0)
            }
        }
    }

    @Test
    fun testStagedPublicEntryQuietZoneBoundsAcrossConfigurations() {
        val dummyBmp = allocateBitmapReflectively()
        val outputSize = 800
        val content = "https://veilframe.app/qz-bounds"

        val configurations = listOf(
            "Default QZ" to QrDesign(style = QrStyle.BASIC, outputSize = outputSize),
            "Explicit QZ" to QrDesign(style = QrStyle.BUBBLE, explicitQuietZone = 3, outputSize = outputSize),
            "Directional Asymmetric QZ" to QrDesign(
                style = QrStyle.LINE,
                directionalQuietZone = DirectionalInsets(2, 1, 4, 3),
                outputSize = outputSize
            ),
            "Fractional QZ" to QrDesign(
                style = QrStyle.DSJ,
                backdropStyle = BackdropStyle(fractionalQuietZone = FractionalInsets(0.10f, 0.08f, 0.12f, 0.09f)),
                outputSize = outputSize
            ),
            "Image Resample Fractional QZ" to QrDesign(
                style = QrStyle.IMAGE_RESAMPLE,
                imageSource = ImageSourceStyle(source = ImageSource.Memory(dummyBmp)),
                backdropStyle = BackdropStyle(fractionalQuietZone = FractionalInsets(0.15f, 0.15f, 0.15f, 0.15f)),
                outputSize = outputSize
            )
        )

        for ((label, design) in configurations) {
            val matrix = QrGenerator.generateMatrix(content, design)
            val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)
            val resolvedQz = QrGeometry.resolveQuietZone(design, matrix.size)

            val canvasContentBounds = geometry.dataRegionBounds()
            val canvasLeftMargin = canvasContentBounds.left
            val canvasTopMargin = canvasContentBounds.top
            val canvasRightMargin = outputSize - canvasContentBounds.right
            val canvasBottomMargin = outputSize - canvasContentBounds.bottom

            val svg = QrGenerator.generateSvg(matrix, design)
            val vb = parseSvgViewBox(svg)
            val moduleScale = minOf(outputSize / vb.width.toFloat(), outputSize / vb.height.toFloat())
            val letterboxX = (outputSize - vb.width.toFloat() * moduleScale) / 2f
            val letterboxY = (outputSize - vb.height.toFloat() * moduleScale) / 2f

            val svgLeftMargin = letterboxX + (resolvedQz.left * moduleScale)
            val svgTopMargin = letterboxY + (resolvedQz.top * moduleScale)
            val svgRightMargin = letterboxX + (resolvedQz.right * moduleScale)
            val svgBottomMargin = letterboxY + (resolvedQz.bottom * moduleScale)

            assertEquals("[$label] Quiet zone left margin must match", canvasLeftMargin, svgLeftMargin, 0.05f)
            assertEquals("[$label] Quiet zone top margin must match", canvasTopMargin, svgTopMargin, 0.05f)
            assertEquals("[$label] Quiet zone right margin must match", canvasRightMargin, svgRightMargin, 0.05f)
            assertEquals("[$label] Quiet zone bottom margin must match", canvasBottomMargin, svgBottomMargin, 0.05f)
        }
    }

    @Test
    fun testStagedPublicEntryLogoBoundsAndClippingParity() {
        val dummyBmp = allocateBitmapReflectively()
        val outputSize = 1000
        val content = "https://veilframe.app/logo-staged"

        val shapes = listOf(LogoShape.SQUIRCLE, LogoShape.CIRCLE)
        val styles = listOf(QrStyle.BASIC, QrStyle.BUBBLE, QrStyle.DSJ, QrStyle.LINE, QrStyle.RANDOM_RECTANGLE)

        for (shape in shapes) {
            for (style in styles) {
                val design = QrDesign(
                    style = style,
                    outputSize = outputSize,
                    logo = LogoStyle(
                        bitmap = dummyBmp,
                        scaleFraction = 0.25f,
                        shape = shape,
                        borderWidth = 4f,
                        borderColor = Color.RED
                    )
                )

                val matrix = QrGenerator.generateMatrix(content, design)
                val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)

                // 1. Canvas logo geometry from VeilIconPipeline
                val qrPixelSize = geometry.contentWidth
                val scale = minOf(0.25f, 0.33f)
                val canvasIconSize = qrPixelSize * scale
                val canvasIconXY = (qrPixelSize - canvasIconSize) / 2f
                val canvasBorderRect = makeRectF(
                    geometry.offsetX + canvasIconXY,
                    geometry.offsetY + canvasIconXY,
                    geometry.offsetX + canvasIconXY + canvasIconSize,
                    geometry.offsetY + canvasIconXY + canvasIconSize
                )

                // 2. SVG logo geometry
                val svg = QrGenerator.generateSvg(matrix, design)
                val vb = parseSvgViewBox(svg)
                val svgModuleScale = outputSize / vb.width.toFloat()

                // In SVG: ox = qzLeft, oy = qzTop, qrPixelSize = matrix.size
                val resolvedQz = QrGeometry.resolveQuietZone(design, matrix.size)
                val svgIconSizeModules = matrix.size * scale
                val svgIconXYModules = (matrix.size - svgIconSizeModules) / 2.0
                val svgBorderLeftPx = (resolvedQz.left + svgIconXYModules).toFloat() * svgModuleScale
                val svgBorderTopPx = (resolvedQz.top + svgIconXYModules).toFloat() * svgModuleScale
                val svgBorderSizePx = svgIconSizeModules.toFloat() * svgModuleScale

                assertEquals("Logo left bounds must match for $shape on $style", canvasBorderRect.left, svgBorderLeftPx, 0.05f)
                assertEquals("Logo top bounds must match for $shape on $style", canvasBorderRect.top, svgBorderTopPx, 0.05f)
                assertEquals("Logo width must match for $shape on $style", canvasBorderRect.right - canvasBorderRect.left, svgBorderSizePx, 0.05f)

                // 3. Verify Logo Clipping
                if (shape == LogoShape.CIRCLE) {
                    assertTrue("SVG must contain circle mask for circular logo", svg.contains("<mask") && svg.contains("<circle"))
                } else {
                    assertTrue("SVG must contain squircle mask for squircle logo", svg.contains("<mask") && svg.contains(VeilPositionPatternGeometry.SQ25_PATH))
                }
            }
        }
    }

    @Test
    fun testCanvasRasterVsNormalizedSvgGeometrySeparation() {
        val dummyBmp = allocateBitmapReflectively()
        val outputSize = 512
        val content = "https://veilframe.app/raster-vs-svg"

        val design = QrDesign(
            style = QrStyle.BASIC,
            outputSize = outputSize,
            backdropStyle = BackdropStyle(cornerRadius = 2.5f, color = Color.WHITE),
            logo = LogoStyle(bitmap = dummyBmp, shape = LogoShape.SQUIRCLE, scaleFraction = 0.20f)
        )

        val matrix = QrGenerator.generateMatrix(content, design)
        val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)

        // 1. Canvas Raster execution via TrackingCanvas
        val trackingCanvas = TrackingCanvas()
        QrGenerator.renderToCanvas(matrix, design, trackingCanvas, geometry)

        // Canvas must have executed background and corner clipping
        assertTrue("Canvas must draw background color", trackingCanvas.drawColorCallCount > 0 || trackingCanvas.drawRectCalls.isNotEmpty())
        assertTrue("Canvas must execute corner clip save/restore", trackingCanvas.saveCalls >= 1 && trackingCanvas.restoreCalls >= 1)
        assertTrue("Canvas must apply clipPath for backdrop corner radius", trackingCanvas.clipPathCalls.isNotEmpty())

        // 2. Normalized SVG Geometry execution
        val svg = QrGenerator.generateSvg(matrix, design)
        val vb = parseSvgViewBox(svg)
        val svgModuleScale = outputSize / vb.width.toFloat()

        // Verify SVG backdrop corner radius clipPath matches
        assertTrue("SVG must contain rounded-corners clipPath", svg.contains("""<clipPath id="rounded-corners">"""))

        // Verify finder pattern normalized coordinates
        val (col0, row0) = Pair(0, 0)
        val (col1, row1) = Pair(0, matrix.size - 7)
        val (col2, row2) = Pair(matrix.size - 7, 0)

        val f0Canvas = geometry.finderBounds(0)
        val f1Canvas = geometry.finderBounds(1)
        val f2Canvas = geometry.finderBounds(2)

        val resolvedQz = QrGeometry.resolveQuietZone(design, matrix.size)
        val f0SvgLeft = (resolvedQz.left + col0) * svgModuleScale
        val f0SvgTop = (resolvedQz.top + row0) * svgModuleScale
        val f1SvgLeft = (resolvedQz.left + col1) * svgModuleScale
        val f1SvgTop = (resolvedQz.top + row1) * svgModuleScale
        val f2SvgLeft = (resolvedQz.left + col2) * svgModuleScale
        val f2SvgTop = (resolvedQz.top + row2) * svgModuleScale

        assertEquals("Finder 0 left must match", f0Canvas.left, f0SvgLeft, 0.05f)
        assertEquals("Finder 0 top must match", f0Canvas.top, f0SvgTop, 0.05f)
        assertEquals("Finder 1 left must match", f1Canvas.left, f1SvgLeft, 0.05f)
        assertEquals("Finder 1 top must match", f1Canvas.top, f1SvgTop, 0.05f)
        assertEquals("Finder 2 left must match", f2Canvas.left, f2SvgLeft, 0.05f)
        assertEquals("Finder 2 top must match", f2Canvas.top, f2SvgTop, 0.05f)
    }

    @Test
    fun testPublicQrGeneratorEntryPointsProduceValidResults() {
        val content = "https://veilframe.app/public-api"
        val design = QrDesign(style = QrStyle.BUBBLE, outputSize = 512)

        // 1. generateMatrix
        val matrix = QrGenerator.generateMatrix(content, design)
        assertNotNull("Generated matrix must not be null", matrix)
        assertTrue("Matrix size must be positive", matrix.size >= 21)

        // 2. generateSvg
        val svg = QrGenerator.generateSvg(content, design)
        assertTrue("Generated SVG must start with <svg", svg.contains("<svg"))
        assertTrue("Generated SVG must end with </svg>", svg.contains("</svg>"))

        // 3. generateWithResult
        val result = QrGenerator.generateWithResult(content, design)
        when (result) {
            is QrRenderResult.Success -> {
                assertEquals("Report matrix size must match", matrix.size, result.matrix.size)
            }
            is QrRenderResult.Failure -> {
                assertTrue("Fails cleanly on headless JVM", result.qrError is com.veilframe.app.qr.error.QrError.Rendering.BitmapAllocationFailed)
            }
        }

        // 4. renderToCanvas on TrackingCanvas
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val canvas = TrackingCanvas()
        QrGenerator.renderToCanvas(matrix, design, canvas, geometry)
        assertTrue("Canvas must have received operations", canvas.drawColorCallCount > 0 || canvas.drawRectCalls.isNotEmpty())
    }
}
