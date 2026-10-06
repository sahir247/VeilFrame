package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.geometry.BasicGeometryBuilder
import com.veilframe.app.qr.geometry.GeometryFill
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix

/**
 * Style 11 — STYLE_FUNCTION (Style-level function override)
 *
 * A "meta-style" that applies a gradient shader over the entire QR canvas,
 * then draws modules as rounded rectangles masked by the gradient — giving
 * the appearance that the color of each module is determined by its position
 * in the canvas (style-level function, not per-module).
 *
 * Uses design-level gradient configuration. If no gradient is configured,
 * falls back to a diagonal gradient from foreground to its complementary hue.
 *
 * Implements [IrBackedQrRenderer] ensuring identical mathematical geometry
 * and gradient definition across Canvas rasterization and SVG vector emission.
 */
class StyleFunctionRenderer : IrBackedQrRenderer {

    override fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val totalWidth = geometry.outputWidthFloat
        val totalHeight = geometry.outputHeightFloat
        val fgColor = design.palette.foreground
        val bgColor = design.backdropStyle.color ?: design.palette.background
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)

        val defs = mutableListOf<String>()
        val nodes = mutableListOf<QrGeometryNode>()

        // 1. Build diagonal gradient for data modules
        val dataBounds = geometry.dataRegionBounds()
        val gradStart = design.palette.gradientStart ?: fgColor
        val gradEnd = design.palette.gradientEnd ?: complementaryColor(fgColor)

        val x0 = dataBounds.left
        val y0 = dataBounds.top
        val x1 = dataBounds.right
        val y1 = dataBounds.bottom

        val startStop = BasicGeometryBuilder.formatStop("0%", gradStart)
        val endStop = BasicGeometryBuilder.formatStop("100%", gradEnd)
        val x0Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(x0.toDouble())
        val y0Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(y0.toDouble())
        val x1Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(x1.toDouble())
        val y1Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(y1.toDouble())

        defs.add("""<linearGradient id="styleFuncGrad" gradientUnits="userSpaceOnUse" x1="$x0Str" y1="$y0Str" x2="$x1Str" y2="$y1Str">$startStop$endStop</linearGradient>""")

        val linearGrad = GeometryFill.LinearGradient(
            startColor = gradStart,
            endColor = gradEnd,
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1
        )

        // 2. Draw finders via canonical position pattern geometry
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(n - 4, 3),
            Pair(3, n - 4)
        )
        for ((fx, fy) in finderCenters) {
            nodes.addAll(
                VeilPositionPatternGeometry.toIrNodes(
                    x = fx,
                    y = fy,
                    moduleSize = cs,
                    offsetX = ox,
                    offsetY = oy,
                    style = design.eyeStyle.style,
                    size = design.positionSize,
                    color = design.eyeStyle.outerColor ?: fgColor,
                    bgColor = bgColor
                )
            )
        }

        // 3. Draw remaining modules (Timing, Alignment, Data)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val type = matrix.functionMask[col, row]
                val rect = geometry.moduleRect(col, row, scale)

                when {
                    type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER -> {
                        val rx = rect.width() * 0.25f
                        nodes.add(
                            RectNode(
                                x = rect.left,
                                y = rect.top,
                                width = rect.width(),
                                height = rect.height(),
                                rx = rx,
                                ry = rx,
                                fill = fgColor
                            )
                        )
                    }
                    else -> {
                        val half = cs * scale / 2f
                        val rx = half * 0.45f
                        nodes.add(
                            RectNode(
                                x = rect.left,
                                y = rect.top,
                                width = rect.width(),
                                height = rect.height(),
                                rx = rx,
                                ry = rx,
                                fillString = "url(#styleFuncGrad)",
                                geometryFill = linearGrad
                            )
                        )
                    }
                }
            }
        }

        return QrGeometryIr(
            width = totalWidth,
            height = totalHeight,
            defs = defs,
            rootNodes = nodes
        )
    }

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val ir = generateGeometry(matrix, design, geometry)
        IrCanvasRenderer.render(ir, canvas)
        drawLogo(canvas, design, geometry, context)
    }

    /** Produces a rough complementary color by rotating hue by 180°. */
    private fun complementaryColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        return Color.HSVToColor(hsv)
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
