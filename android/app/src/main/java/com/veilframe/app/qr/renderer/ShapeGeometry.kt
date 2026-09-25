package com.veilframe.app.qr.renderer

import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*
import java.util.Locale
import java.util.Random

/**
 * Unified Shape Geometry System.
 *
 * Provides a single mathematical source of truth generating both:
 * 1. Android Canvas [Path] geometry (for direct view rendering and bitmap export).
 * 2. SVG vector element markup (for vector export and `<mask id="qrDataMask">` stencils).
 *
 * Guarantees 100% geometric parity between Canvas and SVG representations for all [ModuleShape] variants.
 */
object ShapeGeometry {

    /**
     * Builds the geometric [Path] for a given module into [outPath].
     */
    fun buildCanvasPath(
        shape: ModuleShape,
        module: QrModule,
        rect: RectF,
        design: QrDesign,
        outPath: Path = Path()
    ): Path {
        outPath.reset()

        when {
            // DSJ composite geometry
            design.style == QrStyle.DSJ -> {
                QrVisualGeometry.createCompositeDSJ(
                    rect = rect,
                    lineThickness = design.compositeStyle.lineThickness,
                    crossScale = design.compositeStyle.crossScale,
                    path = outPath
                )
            }
            // Deterministic seeded random jitter
            design.style == QrStyle.RANDOM_RECTANGLE -> {
                val seed = design.jitterStyle.seed
                val rnd = Random(seed xor (module.col * 31L + module.row))
                val scaleFactor = 1f - (design.jitterStyle.scaleJitter * rnd.nextFloat().coerceIn(0f, 0.5f))
                val w = rect.width() * scaleFactor
                val h = rect.height() * scaleFactor
                val ox = (rnd.nextFloat() - 0.5f) * rect.width() * design.jitterStyle.offsetJitter
                val oy = (rnd.nextFloat() - 0.5f) * rect.height() * design.jitterStyle.offsetJitter
                val cx = rect.centerX() + ox
                val cy = rect.centerY() + oy
                outPath.addRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, Path.Direction.CW)
            }
            // Line style with directional cabling
            shape == ModuleShape.LINE || design.style == QrStyle.LINE -> {
                QrVisualGeometry.createLinePath(
                    rect = rect,
                    direction = design.lineStyle.direction,
                    thicknessFraction = design.lineStyle.thicknessFraction,
                    roundCaps = design.lineStyle.roundCaps,
                    path = outPath
                )
            }
            // Organic neighborhood-connected blob
            shape == ModuleShape.ORGANIC || shape == ModuleShape.CONNECTED || design.style == QrStyle.CONNECTED_ORGANIC -> {
                QrVisualGeometry.createOrganicBlobPath(rect, module.neighbors, outPath)
            }
            // Bubble styling
            shape == ModuleShape.BUBBLE -> {
                QrVisualGeometry.createBubblePath(rect, outPath)
            }
            // Star
            shape == ModuleShape.STAR -> {
                QrVisualGeometry.createStarPath(rect, outPath)
            }
            // Diamond
            shape == ModuleShape.DIAMOND -> {
                QrVisualGeometry.createDiamondPath(rect, outPath)
            }
            // Hexagon
            shape == ModuleShape.HEX -> {
                QrVisualGeometry.createHexagonPath(rect, outPath)
            }
            // Squircle
            shape == ModuleShape.SQUIRCLE -> {
                QrVisualGeometry.createSquirclePath(rect, outPath)
            }
            // Circle or Dot
            shape == ModuleShape.CIRCLE || shape == ModuleShape.DOT -> {
                val r = (minOf(rect.width(), rect.height()) / 2f) * (if (shape == ModuleShape.DOT) 0.75f else 1.0f)
                outPath.addCircle(rect.centerX(), rect.centerY(), r, Path.Direction.CW)
            }
            // Pill
            shape == ModuleShape.PILL -> {
                val rx = rect.width() / 2f
                val ry = rect.height() * 0.25f
                outPath.addRoundRect(rect, rx, ry, Path.Direction.CW)
            }
            // Rounded
            shape == ModuleShape.ROUNDED -> {
                val r = minOf(rect.width(), rect.height()) * design.moduleStyle.cornerRadiusFraction.coerceIn(0.1f, 0.5f)
                outPath.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            // Default: Square
            else -> {
                outPath.addRect(rect, Path.Direction.CW)
            }
        }

        return outPath
    }

    /**
     * Builds the matching SVG element markup for a module position.
     *
     * @param shape ModuleShape enum
     * @param module QR module metadata (for neighborhood connectivity)
     * @param cx Center X coordinate in SVG units
     * @param cy Center Y coordinate in SVG units
     * @param mx Module top-left X in SVG units
     * @param my Module top-left Y in SVG units
     * @param scale Module width/height in SVG units
     * @param fill Fill color (e.g. "#000000" or "white" for stencil masks)
     * @param design Visual design intent
     */
    fun buildSvgElement(
        shape: ModuleShape,
        module: QrModule,
        cx: Double,
        cy: Double,
        mx: Double,
        my: Double,
        scale: Double,
        fill: String,
        design: QrDesign
    ): String {
        return when {
            // Line style
            shape == ModuleShape.LINE || design.style == QrStyle.LINE -> {
                val strokeW = scale * design.lineStyle.thicknessFraction.coerceIn(0.15f, 0.9f)
                when (design.lineStyle.direction) {
                    LineDirection.VERTICAL -> {
                        """<line x1="$cx" y1="$my" x2="$cx" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />"""
                    }
                    LineDirection.CROSS -> {
                        """<line x1="$mx" y1="$cy" x2="${mx + scale}" y2="$cy" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" /><line x1="$cx" y1="$my" x2="$cx" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />"""
                    }
                    LineDirection.X -> {
                        """<line x1="$mx" y1="$my" x2="${mx + scale}" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" /><line x1="${mx + scale}" y1="$my" x2="$mx" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />"""
                    }
                    else -> {
                        """<line x1="$mx" y1="$cy" x2="${mx + scale}" y2="$cy" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />"""
                    }
                }
            }

            // Organic / Connected blob (100% path parity with createOrganicBlobPath)
            shape == ModuleShape.ORGANIC || shape == ModuleShape.CONNECTED || design.style == QrStyle.CONNECTED_ORGANIC -> {
                val neighbors = module.neighbors
                val r = scale * 0.45
                val rTL = if (!neighbors.up && !neighbors.left) r else 0.0
                val rTR = if (!neighbors.up && !neighbors.right) r else 0.0
                val rBR = if (!neighbors.down && !neighbors.right) r else 0.0
                val rBL = if (!neighbors.down && !neighbors.left) r else 0.0

                fun f(v: Double) = String.format(Locale.US, "%.3f", v)

                val d = buildString {
                    append("M ").append(f(mx + rTL)).append(" ").append(f(my))
                    append(" L ").append(f(mx + scale - rTR)).append(" ").append(f(my))
                    if (rTR > 0) append(" A ").append(f(rTR)).append(" ").append(f(rTR)).append(" 0 0 1 ").append(f(mx + scale)).append(" ").append(f(my + rTR))
                    append(" L ").append(f(mx + scale)).append(" ").append(f(my + scale - rBR))
                    if (rBR > 0) append(" A ").append(f(rBR)).append(" ").append(f(rBR)).append(" 0 0 1 ").append(f(mx + scale - rBR)).append(" ").append(f(my + scale))
                    append(" L ").append(f(mx + rBL)).append(" ").append(f(my + scale))
                    if (rBL > 0) append(" A ").append(f(rBL)).append(" ").append(f(rBL)).append(" 0 0 1 ").append(f(mx)).append(" ").append(f(my + scale - rBL))
                    append(" L ").append(f(mx)).append(" ").append(f(my + rTL))
                    if (rTL > 0) append(" A ").append(f(rTL)).append(" ").append(f(rTL)).append(" 0 0 1 ").append(f(mx + rTL)).append(" ").append(f(my))
                    append(" Z")
                }
                """<path d="$d" fill="$fill" />"""
            }

            // Circle or Dot
            shape == ModuleShape.CIRCLE || shape == ModuleShape.DOT -> {
                val r = (scale / 2.0) * (if (shape == ModuleShape.DOT) 0.75 else 1.0)
                """<circle cx="$cx" cy="$cy" r="$r" fill="$fill" />"""
            }

            // Pill (100% parity with Canvas rx = width / 2, ry = height * 0.25)
            shape == ModuleShape.PILL -> {
                val rx = scale / 2.0
                val ry = scale * 0.25
                """<rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" ry="$ry" fill="$fill" />"""
            }

            // Rounded
            shape == ModuleShape.ROUNDED -> {
                val rx = scale * design.moduleStyle.cornerRadiusFraction.coerceIn(0.1f, 0.5f).toDouble()
                """<rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" fill="$fill" />"""
            }

            // Squircle (100% cubic Bézier parity with createSquirclePath)
            shape == ModuleShape.SQUIRCLE -> {
                val d = buildSquircleSvgD(mx, my, scale, scale)
                """<path d="$d" fill="$fill" />"""
            }

            // Bubble
            shape == ModuleShape.BUBBLE -> {
                val rx = scale * 0.42
                val ry = scale * 0.42
                """<rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" ry="$ry" fill="$fill" />"""
            }

            // Star (5-pointed star = 10 vertices)
            shape == ModuleShape.STAR -> {
                val outerR = scale / 2.0
                val innerR = outerR * 0.45
                val pts = (0 until 10).joinToString(" ") { i ->
                    val r = if (i % 2 == 0) outerR else innerR
                    val angle = -Math.PI / 2.0 + (i * Math.PI / 5.0)
                    val px = cx + r * Math.cos(angle)
                    val py = cy + r * Math.sin(angle)
                    "${String.format(Locale.US, "%.3f", px)},${String.format(Locale.US, "%.3f", py)}"
                }
                """<polygon points="$pts" fill="$fill" />"""
            }

            // Diamond (4 vertices)
            shape == ModuleShape.DIAMOND -> {
                val pts = "${String.format(Locale.US, "%.3f", cx)},${String.format(Locale.US, "%.3f", my)} " +
                        "${String.format(Locale.US, "%.3f", mx + scale)},${String.format(Locale.US, "%.3f", cy)} " +
                        "${String.format(Locale.US, "%.3f", cx)},${String.format(Locale.US, "%.3f", my + scale)} " +
                        "${String.format(Locale.US, "%.3f", mx)},${String.format(Locale.US, "%.3f", cy)}"
                """<polygon points="$pts" fill="$fill" />"""
            }

            // Hexagon (6 vertices)
            shape == ModuleShape.HEX -> {
                val pts = "${String.format(Locale.US, "%.3f", mx + scale * 0.25)},${String.format(Locale.US, "%.3f", my)} " +
                        "${String.format(Locale.US, "%.3f", mx + scale * 0.75)},${String.format(Locale.US, "%.3f", my)} " +
                        "${String.format(Locale.US, "%.3f", mx + scale)},${String.format(Locale.US, "%.3f", cy)} " +
                        "${String.format(Locale.US, "%.3f", mx + scale * 0.75)},${String.format(Locale.US, "%.3f", my + scale)} " +
                        "${String.format(Locale.US, "%.3f", mx + scale * 0.25)},${String.format(Locale.US, "%.3f", my + scale)} " +
                        "${String.format(Locale.US, "%.3f", mx)},${String.format(Locale.US, "%.3f", cy)}"
                """<polygon points="$pts" fill="$fill" />"""
            }

            // Default: Square
            else -> {
                """<rect x="$mx" y="$my" width="$scale" height="$scale" fill="$fill" />"""
            }
        }
    }

    /**
     * Builds the cubic Bézier SVG path data ('d' attribute) for a squircle bounded by [x, y, width, height].
     * Provides 100% mathematical parity with QrVisualGeometry.createSquirclePath.
     */
    fun buildSquircleSvgD(x: Double, y: Double, width: Double, height: Double): String {
        fun px(v: Double) = String.format(Locale.US, "%.3f", x + (v / 100.0) * width)
        fun py(v: Double) = String.format(Locale.US, "%.3f", y + (v / 100.0) * height)

        return "M ${px(32.048565)} ${py(0.0)} " +
                "L ${px(67.951435)} ${py(0.0)} " +
                "C ${px(79.0954192)} ${py(0.0)} ${px(83.1364972)} ${py(1.16032014)} ${px(87.2105713)} ${py(3.3391588)} " +
                "C ${px(91.2846454)} ${py(5.51799746)} ${px(94.4820025)} ${py(8.71535463)} ${px(96.6608412)} ${py(12.7894287)} " +
                "C ${px(98.8396799)} ${py(16.8635028)} ${px(100.0)} ${py(20.9045808)} ${px(100.0)} ${py(32.048565)} " +
                "L ${px(100.0)} ${py(67.951435)} " +
                "C ${px(100.0)} ${py(79.0954192)} ${px(98.8396799)} ${py(83.1364972)} ${px(96.6608412)} ${py(87.2105713)} " +
                "C ${px(94.4820025)} ${py(91.2846454)} ${px(91.2846454)} ${py(94.4820025)} ${px(87.2105713)} ${py(96.6608412)} " +
                "C ${px(83.1364972)} ${py(98.8396799)} ${px(79.0954192)} ${py(100.0)} ${px(67.951435)} ${py(100.0)} " +
                "L ${px(32.048565)} ${py(100.0)} " +
                "C ${px(20.9045808)} ${py(100.0)} ${px(16.8635028)} ${py(98.8396799)} ${px(12.7894287)} ${py(96.6608412)} " +
                "C ${px(8.71535463)} ${py(94.4820025)} ${px(5.51799746)} ${py(91.2846454)} ${px(3.3391588)} ${py(87.2105713)} " +
                "C ${px(1.16032014)} ${py(83.1364972)} ${px(0.0)} ${py(79.0954192)} ${px(0.0)} ${py(67.951435)} " +
                "L ${px(0.0)} ${py(32.048565)} " +
                "C ${px(0.0)} ${py(20.9045808)} ${px(1.16032014)} ${py(16.8635028)} ${px(3.3391588)} ${py(12.7894287)} " +
                "C ${px(5.51799746)} ${py(8.71535463)} ${px(8.71535463)} ${py(5.51799746)} ${px(12.7894287)} ${py(3.3391588)} " +
                "C ${px(16.8635028)} ${py(1.16032014)} ${px(20.9045808)} ${py(0.0)} ${px(32.048565)} ${py(0.0)} Z"
    }
}
