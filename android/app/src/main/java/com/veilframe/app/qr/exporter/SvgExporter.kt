package com.veilframe.app.qr.exporter

import android.graphics.Bitmap
import android.util.Base64
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.ModuleFill
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Resolution-independent Vector SVG Exporter.
 *
 * Generates true vector markup (<svg>, <rect>, <circle>, <polygon>, <defs>, <linearGradient>, <radialGradient>, <image>)
 * directly from [QrMatrix] and [QrDesign] without rasterizing to PNG.
 * Includes the 4-module Quiet Zone in the viewBox and supports:
 * - Linear & Radial vector gradients
 * - Planets, DSJ, Soft, Frame, Rounded, Circle, Classic finders
 * - Star, Bubble, Pill, Diamond, Hex, Squircle module shapes
 * - Per-zone coloring (Timing & Alignment patterns)
 * - Base64-embedded logos with aspect ratio preservation
 */
object SvgExporter {

    fun generateSvg(matrix: QrMatrix, design: QrDesign): String {
        val qz = design.quietZoneModules
        val totalSize = matrix.size + (2 * qz)
        val fgHex = hexColor(design.palette.foreground)
        val bgHex = hexColor(design.palette.background)
        val eyeOuterHex = design.eyeStyle.outerColor?.let { hexColor(it) } ?: fgHex
        val eyeInnerHex = design.eyeStyle.innerColor?.let { hexColor(it) } ?: fgHex
        val timingHex = (design.timingStyle.color ?: design.timingColor)?.let { hexColor(it) }
        val alignmentHex = (design.alignmentStyle.color ?: design.alignmentColor)?.let { hexColor(it) }

        val hasGradient = (design.palette.gradientType != GradientType.NONE ||
            design.moduleStyle.fill == ModuleFill.LINEAR_GRADIENT ||
            design.moduleStyle.fill == ModuleFill.RADIAL_GRADIENT) &&
            design.palette.gradientStart != null && design.palette.gradientEnd != null

        val gradStartHex = design.palette.gradientStart?.let { hexColor(it) } ?: fgHex
        val gradEndHex = design.palette.gradientEnd?.let { hexColor(it) } ?: fgHex
        val isRadial = design.palette.gradientType == GradientType.RADIAL ||
            design.moduleStyle.fill == ModuleFill.RADIAL_GRADIENT
        val dataFill = if (hasGradient) "url(#qrGrad)" else fgHex

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")

        // 1. Defs (Gradients)
        if (hasGradient) {
            sb.append("  <defs>\n")
            if (isRadial) {
                sb.append("""    <radialGradient id="qrGrad" cx="50%" cy="50%" r="50%">""").append("\n")
                sb.append("""      <stop offset="0%" stop-color="$gradStartHex" />""").append("\n")
                sb.append("""      <stop offset="100%" stop-color="$gradEndHex" />""").append("\n")
                sb.append("    </radialGradient>\n")
            } else {
                sb.append("""    <linearGradient id="qrGrad" x1="0%" y1="0%" x2="100%" y2="100%">""").append("\n")
                sb.append("""      <stop offset="0%" stop-color="$gradStartHex" />""").append("\n")
                sb.append("""      <stop offset="100%" stop-color="$gradEndHex" />""").append("\n")
                sb.append("    </linearGradient>\n")
            }
            sb.append("  </defs>\n")
        }

        // 2. Background Layer
        sb.append("""  <rect width="$totalSize" height="$totalSize" fill="$bgHex" />""").append("\n")
        val bgBmp = design.backgroundLayer.bitmap ?: design.backgroundImage
        if (design.backgroundLayer.enabled && bgBmp != null) {
            val bgBase64 = bitmapToBase64(bgBmp)
            if (bgBase64.isNotEmpty()) {
                val opacity = String.format(Locale.US, "%.2f", design.backgroundLayer.opacity)
                sb.append("""  <image href="data:image/png;base64,$bgBase64" width="$totalSize" height="$totalSize" preserveAspectRatio="xMidYMid slice" opacity="$opacity" />""").append("\n")
            }
        }

        // 3. Finders (TL: 0,0; BL: 0, n-7; TR: n-7, 0) offset by qz
        val finders = listOf(
            Pair(qz, qz),
            Pair(qz, qz + matrix.size - 7),
            Pair(qz + matrix.size - 7, qz)
        )

        for ((fx, fy) in finders) {
            val cx = fx + 3.5
            val cy = fy + 3.5

            when (design.eyeStyle.style) {
                FinderStyle.CIRCLE -> {
                    sb.append("""  <circle cx="$cx" cy="$cy" r="3.5" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="$cy" r="2.5" fill="$bgHex" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="$cy" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                }
                FinderStyle.ROUNDED -> {
                    sb.append("""  <rect x="$fx" y="$fy" width="7" height="7" rx="2" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${fx + 1}" y="${fy + 1}" width="5" height="5" rx="1.5" fill="$bgHex" />""").append("\n")
                    sb.append("""  <rect x="${fx + 2}" y="${fy + 2}" width="3" height="3" rx="1" fill="$eyeInnerHex" />""").append("\n")
                }
                FinderStyle.SOFT -> {
                    sb.append("""  <rect x="$fx" y="$fy" width="7" height="7" rx="1.5" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${fx + 1}" y="${fy + 1}" width="5" height="5" rx="0.8" fill="$bgHex" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="$cy" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                }
                FinderStyle.FRAME -> {
                    sb.append("""  <rect x="${fx + 0.4}" y="${fy + 0.4}" width="6.2" height="6.2" rx="1" fill="none" stroke="$eyeOuterHex" stroke-width="0.8" />""").append("\n")
                    val pts = "${cx},${cy - 1.5} ${cx + 1.5},${cy} ${cx},${cy + 1.5} ${cx - 1.5},${cy}"
                    sb.append("""  <polygon points="$pts" fill="$eyeInnerHex" />""").append("\n")
                }
                FinderStyle.PLANETS -> {
                    sb.append("""  <circle cx="$cx" cy="$cy" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="$cy" r="3" fill="none" stroke="$eyeOuterHex" stroke-width="0.35" stroke-dasharray="0.5,0.5" />""").append("\n")
                    sb.append("""  <circle cx="${cx - 3}" cy="$cy" r="0.6" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="${cx + 3}" cy="$cy" r="0.6" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="${cy - 3}" r="0.6" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="${cy + 3}" r="0.6" fill="$eyeOuterHex" />""").append("\n")
                }
                FinderStyle.DSJ -> {
                    sb.append("""  <rect x="${cx - 1.5}" y="${cy - 1.5}" width="3" height="3" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${cx - 3.5}" y="${cy - 1.5}" width="1" height="3" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${cx + 2.5}" y="${cy - 1.5}" width="1" height="3" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${cx - 1.5}" y="${cy - 3.5}" width="3" height="1" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${cx - 1.5}" y="${cy + 2.5}" width="3" height="1" fill="$eyeOuterHex" />""").append("\n")
                }
                else -> {
                    sb.append("""  <rect x="$fx" y="$fy" width="7" height="7" fill="$eyeOuterHex" rx="0.5" />""").append("\n")
                    sb.append("""  <rect x="${fx + 1}" y="${fy + 1}" width="5" height="5" fill="$bgHex" rx="0.3" />""").append("\n")
                    sb.append("""  <rect x="${fx + 2}" y="${fy + 2}" width="3" height="3" fill="$eyeInnerHex" rx="0.2" />""").append("\n")
                }
            }
        }

        // 4. Data & Functional Modules
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val shape = design.moduleStyle.shape
        val is25D = design.effects.is25D || design.style == com.veilframe.app.qr.QrStyle.D25

        val d25LeftHex = hexColor(design.depthStyle.leftColor)
        val d25RightHex = hexColor(design.depthStyle.rightColor)
        val d25Depth = design.depthStyle.depth * 0.35

        for (col in 0 until matrix.size) {
            for (row in 0 until matrix.size) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val x = col + qz
                val y = row + qz
                val module = matrix.moduleAt(col, row)

                // Per-zone color override: Timing, Alignment, or Sampled Image
                val role = matrix.roleAt(col, row)
                val isSampled = design.moduleStyle.fill == ModuleFill.IMAGE_SAMPLED || design.imageFillMode || design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE
                val fill = when {
                    role == QrModuleRole.TIMING && timingHex != null -> timingHex
                    (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) && alignmentHex != null -> alignmentHex
                    isSampled && bgBmp != null -> hexColor(com.veilframe.app.qr.renderer.FillEngine.resolveModuleColor(module, matrix.size, design))
                    else -> dataFill
                }

                val offset = (1.0 - scale) / 2.0
                val mx = x + offset
                val my = y + offset
                val cx = x + 0.5
                val cy = y + 0.5

                // 2.5D Isometric extruded side faces in SVG
                if (is25D) {
                    val ptsLeft = "${String.format(Locale.US, "%.2f", mx)},${String.format(Locale.US, "%.2f", my + scale)} " +
                            "${String.format(Locale.US, "%.2f", mx + d25Depth)},${String.format(Locale.US, "%.2f", my + scale + d25Depth)} " +
                            "${String.format(Locale.US, "%.2f", mx + scale + d25Depth)},${String.format(Locale.US, "%.2f", my + scale + d25Depth)} " +
                            "${String.format(Locale.US, "%.2f", mx + scale)},${String.format(Locale.US, "%.2f", my + scale)}"
                    sb.append("""  <polygon points="$ptsLeft" fill="$d25LeftHex" />""").append("\n")

                    val ptsRight = "${String.format(Locale.US, "%.2f", mx + scale)},${String.format(Locale.US, "%.2f", my)} " +
                            "${String.format(Locale.US, "%.2f", mx + scale + d25Depth)},${String.format(Locale.US, "%.2f", my + d25Depth)} " +
                            "${String.format(Locale.US, "%.2f", mx + scale + d25Depth)},${String.format(Locale.US, "%.2f", my + scale + d25Depth)} " +
                            "${String.format(Locale.US, "%.2f", mx + scale)},${String.format(Locale.US, "%.2f", my + scale)}"
                    sb.append("""  <polygon points="$ptsRight" fill="$d25RightHex" />""").append("\n")
                }

                when {
                    // Line style
                    shape == ModuleShape.LINE || design.style == com.veilframe.app.qr.QrStyle.LINE -> {
                        val strokeW = scale * design.lineStyle.thicknessFraction.coerceIn(0.15f, 0.9f)
                        when (design.lineStyle.direction) {
                            com.veilframe.app.qr.model.LineDirection.VERTICAL -> {
                                sb.append("""  <line x1="$cx" y1="$my" x2="$cx" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />""").append("\n")
                            }
                            com.veilframe.app.qr.model.LineDirection.CROSS -> {
                                sb.append("""  <line x1="$mx" y1="$cy" x2="${mx + scale}" y2="$cy" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />""").append("\n")
                                sb.append("""  <line x1="$cx" y1="$my" x2="$cx" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />""").append("\n")
                            }
                            com.veilframe.app.qr.model.LineDirection.X -> {
                                sb.append("""  <line x1="$mx" y1="$my" x2="${mx + scale}" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />""").append("\n")
                                sb.append("""  <line x1="${mx + scale}" y1="$my" x2="$mx" y2="${my + scale}" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />""").append("\n")
                            }
                            else -> {
                                sb.append("""  <line x1="$mx" y1="$cy" x2="${mx + scale}" y2="$cy" stroke="$fill" stroke-width="$strokeW" stroke-linecap="round" />""").append("\n")
                            }
                        }
                    }
                    // Organic / Connected blob
                    shape == ModuleShape.ORGANIC || shape == ModuleShape.CONNECTED || design.style == com.veilframe.app.qr.QrStyle.CONNECTED_ORGANIC -> {
                        val neighbors = module.neighbors
                        val r = (scale * 0.42).toString()
                        val rx = if (neighbors.isIsolated) r else (scale * 0.22).toString()
                        sb.append("""  <rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" fill="$fill" />""").append("\n")
                    }
                    // Circle or Dot
                    shape == ModuleShape.CIRCLE || shape == ModuleShape.DOT -> {
                        val r = (scale / 2.0) * (if (shape == ModuleShape.DOT) 0.75 else 1.0)
                        sb.append("""  <circle cx="$cx" cy="$cy" r="$r" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.PILL -> {
                        val rx = scale * 0.45
                        sb.append("""  <rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.ROUNDED -> {
                        val rx = scale * 0.25
                        sb.append("""  <rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.SQUIRCLE -> {
                        val rx = scale * 0.35
                        sb.append("""  <rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.BUBBLE -> {
                        val rx = scale * 0.42
                        sb.append("""  <rect x="$mx" y="$my" width="$scale" height="$scale" rx="$rx" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.STAR -> {
                        val outerR = scale / 2.0
                        val innerR = outerR * 0.45
                        val pts = (0 until 10).joinToString(" ") { i ->
                            val r = if (i % 2 == 0) outerR else innerR
                            val angle = -Math.PI / 2.0 + (i * Math.PI / 5.0)
                            val px = cx + r * Math.cos(angle)
                            val py = cy + r * Math.sin(angle)
                            "${String.format(Locale.US, "%.2f", px)},${String.format(Locale.US, "%.2f", py)}"
                        }
                        sb.append("""  <polygon points="$pts" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.DIAMOND -> {
                        val half = scale / 2.0
                        val pts = "${cx},${cy - half} ${cx + half},${cy} ${cx},${cy + half} ${cx - half},${cy}"
                        sb.append("""  <polygon points="$pts" fill="$fill" />""").append("\n")
                    }
                    shape == ModuleShape.HEX -> {
                        val half = scale / 2.0
                        val qtr = half * 0.5
                        val pts = "${cx},${cy - half} ${cx + half},${cy - qtr} ${cx + half},${cy + qtr} ${cx},${cy + half} ${cx - half},${cy + qtr} ${cx - half},${cy - qtr}"
                        sb.append("""  <polygon points="$pts" fill="$fill" />""").append("\n")
                    }
                    else -> {
                        sb.append("""  <rect x="$mx" y="$my" width="$scale" height="$scale" fill="$fill" />""").append("\n")
                    }
                }
            }
        }

        // 5. Embedded Logo (if present)
        design.logo?.bitmap?.let { logoBmp ->
            val fraction = design.logo.scaleFraction.coerceIn(0.10f, 0.35f)
            val logoSize = totalSize * fraction
            val logoX = (totalSize - logoSize) / 2.0
            val logoY = (totalSize - logoSize) / 2.0

            // White / custom backing card for high contrast
            val cardPadding = 0.5
            sb.append("""  <rect x="${logoX - cardPadding}" y="${logoY - cardPadding}" width="${logoSize + 2 * cardPadding}" height="${logoSize + 2 * cardPadding}" rx="1.5" fill="$bgHex" />""").append("\n")

            val base64 = bitmapToBase64(logoBmp)
            if (base64.isNotEmpty()) {
                sb.append("""  <image href="data:image/png;base64,$base64" x="$logoX" y="$logoY" width="$logoSize" height="$logoSize" preserveAspectRatio="xMidYMid meet" />""").append("\n")
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun hexColor(color: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and color)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }
}
