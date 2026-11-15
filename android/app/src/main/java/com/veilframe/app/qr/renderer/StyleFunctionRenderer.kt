package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.geometry.BasicGeometryBuilder
import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.GeometryFill
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.FunctionStyle
import com.veilframe.app.qr.model.FunctionType
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Style 11 — STYLE_FUNCTION (Parametric spatial function morphology + canvas shader)
 *
 * Applies a continuous parametric spatial wave/field ([FunctionStyle.type], frequency,
 * amplitude, phase, seed) across the QR data grid, driving module scale, corner morphing
 * (square -> squircle -> dot), subtle centroid undulation, and luminance modulation while
 * backing data fill with the shared `styleFuncGrad` gradient definition for unified Canvas/SVG output.
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
        val funcStyle = design.functionStyle
        val baseScale = (design.moduleStyle.scale * funcStyle.scale).coerceIn(0.45f, 1.0f)
        val freq = funcStyle.frequency.coerceIn(0.05f, 3.0f)
        val amp = funcStyle.amplitude.coerceIn(0.0f, 1.0f)
        val phase = funcStyle.phase

        val defs = mutableListOf<String>()
        val nodes = mutableListOf<QrGeometryNode>()

        // 1. Build canvas gradient definition (preserves id="styleFuncGrad" for SVG & tests)
        val dataBounds = geometry.dataRegionBounds()
        val gradStart = design.palette.gradientStart ?: fgColor
        val gradEnd = design.palette.gradientEnd ?: complementaryColor(fgColor)

        val x0 = dataBounds.left
        val y0 = dataBounds.top
        val x1 = dataBounds.right
        val y1 = dataBounds.bottom

        val startStop = BasicGeometryBuilder.formatStop("0%", gradStart)
        val endStop = BasicGeometryBuilder.formatStop("100%", gradEnd)

        val isRadial = design.palette.gradientType == GradientType.RADIAL ||
            funcStyle.type == FunctionType.RADIAL ||
            funcStyle.type == FunctionType.RIPPLE

        val geomFill: GeometryFill = if (isRadial) {
            val cxGrad = dataBounds.centerX()
            val cyGrad = dataBounds.centerY()
            val rGrad = maxOf(dataBounds.width(), dataBounds.height()) * 0.55f
            val cxStr = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(cxGrad.toDouble())
            val cyStr = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(cyGrad.toDouble())
            val rStr = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(rGrad.toDouble())
            defs.add("""<radialGradient id="styleFuncGrad" gradientUnits="userSpaceOnUse" cx="$cxStr" cy="$cyStr" r="$rStr">$startStop$endStop</radialGradient>""")
            GeometryFill.RadialGradient(
                centerColor = gradStart,
                edgeColor = gradEnd,
                cx = cxGrad,
                cy = cyGrad,
                radius = rGrad
            )
        } else {
            val x0Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(x0.toDouble())
            val y0Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(y0.toDouble())
            val x1Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(x1.toDouble())
            val y1Str = com.veilframe.app.qr.exporter.SvgExporter.formatCoord(y1.toDouble())
            defs.add("""<linearGradient id="styleFuncGrad" gradientUnits="userSpaceOnUse" x1="$x0Str" y1="$y0Str" x2="$x1Str" y2="$y1Str">$startStop$endStop</linearGradient>""")
            GeometryFill.LinearGradient(
                startColor = gradStart,
                endColor = gradEnd,
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1
            )
        }

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
                    bgColor = bgColor,
                    innerColor = design.eyeStyle.innerColor ?: fgColor
                )
            )
        }

        // 3. Draw remaining modules (Timing, Alignment, and Parametric Wave Data)
        val nFloat = maxOf(1, n).toFloat()
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val type = matrix.functionMask[col, row]
                if (type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER
                ) {
                    val rect = geometry.moduleRect(col, row, baseScale.coerceAtLeast(0.75f))
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
                    continue
                }

                val u = (col + 0.5f) / nFloat
                val v = (row + 0.5f) / nFloat
                val sample = evaluateField(
                    type = funcStyle.type,
                    u = u,
                    v = v,
                    col = col,
                    row = row,
                    n = n,
                    freq = freq,
                    phase = phase,
                    seed = funcStyle.seed
                )

                // Modulate scale safely between [0.46f, 1.0f] so QR stays robustly decodable
                val modulation = (sample - 0.5f) * 2.0f // [-1, +1]
                val modScale = (baseScale * (1.0f - amp * 0.42f * (1.0f - sample)) + modulation * amp * 0.08f)
                    .coerceIn(0.46f, 1.0f)

                val modW = cs * modScale
                // Optional subtle spatial wave displacement for directional waves while staying well inside cell bounds
                val maxShift = cs * (1.0f - modScale) * 0.35f
                val shiftX = when (funcStyle.type) {
                    FunctionType.WAVE, FunctionType.ORGANIC -> modulation * amp * maxShift
                    FunctionType.SPIRAL -> sin(atan2(v - 0.5f, u - 0.5f).toDouble()).toFloat() * amp * maxShift
                    else -> 0f
                }
                val shiftY = when (funcStyle.type) {
                    FunctionType.WAVE, FunctionType.ORGANIC -> -modulation * amp * maxShift
                    FunctionType.SPIRAL -> -cos(atan2(v - 0.5f, u - 0.5f).toDouble()).toFloat() * amp * maxShift
                    else -> 0f
                }

                val cxCell = ox + (col + 0.5f) * cs + shiftX
                val cyCell = oy + (row + 0.5f) * cs + shiftY

                // Morph corner rounding per wave type and wave crest vs trough
                val roundFactor = when (funcStyle.type) {
                    FunctionType.WAVE -> (0.15f + 0.35f * sample).coerceIn(0.10f, 0.50f)
                    FunctionType.RADIAL -> (0.08f + 0.42f * (1f - sample)).coerceIn(0.05f, 0.50f)
                    FunctionType.RIPPLE -> if (sample > 0.52f) 0.50f else 0.20f
                    FunctionType.NOISE -> (0.12f + 0.34f * sample).coerceIn(0.10f, 0.48f)
                    FunctionType.SPIRAL -> (0.18f + 0.32f * sample).coerceIn(0.12f, 0.50f)
                    FunctionType.CHECKER -> if (sample >= 0.5f) 0.08f else 0.48f
                    FunctionType.ORGANIC -> (0.28f + 0.22f * sample).coerceIn(0.20f, 0.50f)
                    FunctionType.RANDOM -> (0.05f + 0.45f * sample).coerceIn(0.05f, 0.50f)
                }

                val modOpacity = (1.0f - amp * 0.28f * (1.0f - sample)).coerceIn(0.68f, 1.0f)

                if (roundFactor >= 0.495f && (funcStyle.type == FunctionType.RIPPLE || funcStyle.type == FunctionType.RADIAL)) {
                    nodes.add(
                        CircleNode(
                            cx = cxCell,
                            cy = cyCell,
                            radius = modW * 0.5f,
                            fillString = "url(#styleFuncGrad)",
                            geometryFill = geomFill,
                            opacity = modOpacity
                        )
                    )
                } else {
                    val rx = modW * roundFactor
                    nodes.add(
                        RectNode(
                            x = cxCell - modW * 0.5f,
                            y = cyCell - modW * 0.5f,
                            width = modW,
                            height = modW,
                            rx = rx,
                            ry = rx,
                            fillString = "url(#styleFuncGrad)",
                            geometryFill = geomFill,
                            opacity = modOpacity
                        )
                    )
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

    internal fun evaluateField(
        type: FunctionType,
        u: Float,
        v: Float,
        col: Int,
        row: Int,
        n: Int,
        freq: Float,
        phase: Float,
        seed: Long
    ): Float {
        val twoPi = (2.0 * PI).toFloat()
        val dx = u - 0.5f
        val dy = v - 0.5f
        val radius = sqrt(dx * dx + dy * dy) * 2.0f // ~0 at center, ~1.414 at corners
        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
        val cycles = (freq * 3.2f).coerceAtLeast(0.4f)

        val raw = when (type) {
            FunctionType.WAVE -> {
                val primary = sin((u * 0.78f + v * 0.62f) * cycles * twoPi + phase)
                val harmonic = cos((v * cycles * 1.15f * PI.toFloat()) - phase * 0.5f) * 0.35f
                0.5f + 0.5f * ((primary + harmonic) / 1.35f)
            }
            FunctionType.RADIAL -> {
                val normR = (radius / 1.4142f).coerceIn(0f, 1f)
                val envelope = 1.0f - normR
                val pulse = 0.5f + 0.5f * cos(radius * cycles * PI.toFloat() * 0.85f + phase)
                (envelope * 0.65f + pulse * 0.35f)
            }
            FunctionType.RIPPLE -> {
                val damping = exp((-radius * 0.55f).toDouble()).toFloat()
                val ring = sin(radius * cycles * twoPi * 1.15f + phase) * damping
                0.5f + 0.5f * ring
            }
            FunctionType.NOISE -> {
                valueNoise2D(u * (cycles * 2.5f + 2f), v * (cycles * 2.5f + 2f), seed)
            }
            FunctionType.SPIRAL -> {
                val arms = maxOf(2, (freq * 4f).toInt().coerceIn(2, 6))
                0.5f + 0.5f * sin(angle * arms + radius * cycles * PI.toFloat() * 1.4f + phase)
            }
            FunctionType.CHECKER -> {
                val cellsPerBlock = maxOf(1, ((1.4f / freq).coerceIn(1f, 5f)).toInt())
                val bx = floor((col.toFloat() / cellsPerBlock).toDouble()).toInt()
                val by = floor((row.toFloat() / cellsPerBlock).toDouble()).toInt()
                val waveMod = 0.15f * sin((u + v) * twoPi + phase)
                val base = if (((bx + by) and 1) == 0) 0.92f else 0.14f
                (base + waveMod).coerceIn(0f, 1f)
            }
            FunctionType.ORGANIC -> {
                val c1 = cos(u * cycles * twoPi * 0.9f + sin(v * cycles * twoPi * 0.85f + phase))
                val c2 = sin(v * cycles * twoPi * 0.9f - cos(u * cycles * twoPi * 0.85f - phase))
                0.5f + 0.25f * (c1 + c2)
            }
            FunctionType.RANDOM -> {
                cellHash(col, row, seed)
            }
        }
        return raw.coerceIn(0f, 1f)
    }

    private fun cellHash(x: Int, y: Int, seed: Long): Float {
        var h = seed xor (x.toLong() * 374761393L) xor (y.toLong() * 668265263L)
        h = (h xor (h ushr 30)) * -4658895280553007687L
        h = (h xor (h ushr 27)) * -7723592293110705685L
        h = h xor (h ushr 31)
        return ((h ushr 16) and 0xFFFFL) / 65535.0f
    }

    private fun valueNoise2D(x: Float, y: Float, seed: Long): Float {
        val x0 = floor(x.toDouble()).toInt()
        val y0 = floor(y.toDouble()).toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val fx = x - x0
        val fy = y - y0
        val u = fx * fx * (3f - 2f * fx)
        val v = fy * fy * (3f - 2f * fy)
        val n00 = cellHash(x0, y0, seed)
        val n10 = cellHash(x1, y0, seed)
        val n01 = cellHash(x0, y1, seed)
        val n11 = cellHash(x1, y1, seed)
        val nx0 = n00 + u * (n10 - n00)
        val nx1 = n01 + u * (n11 - n01)
        return nx0 + v * (nx1 - nx0)
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
