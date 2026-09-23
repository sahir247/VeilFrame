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

    fun generateSvg(
        matrix: QrMatrix,
        design: QrDesign,
        pixelSource: com.veilframe.app.qr.renderer.PixelSource? = null
    ): String {
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

        // 1. Defs (Gradients & Masks)
        val isMasked = design.moduleStyle.fill == ModuleFill.IMAGE_MASKED
        if (hasGradient || isMasked) {
            sb.append("  <defs>\n")
            if (hasGradient) {
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
            }
            if (isMasked) {
                sb.append("""    <mask id="qrDataMask">""").append("\n")
                sb.append("""      <rect width="$totalSize" height="$totalSize" fill="black" />""").append("\n")
                if (design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER) {
                    val clusters = com.veilframe.app.qr.renderer.BubbleClusterEngine.computeClusters(matrix, design)
                    for (cluster in clusters) {
                        if (cluster.isAmbient) continue
                        val cx = cluster.cx + qz
                        val cy = cluster.cy + qz
                        val r = cluster.radius
                        sb.append("""      <circle cx="$cx" cy="$cy" r="$r" fill="white" />""").append("\n")
                        if (cluster.hasInnerDot && cluster.innerRadius > 0f) {
                            sb.append("""      <circle cx="$cx" cy="$cy" r="${cluster.innerRadius}" fill="white" />""").append("\n")
                        }
                    }
                } else {
                    val maskScale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
                    val maskOffset = (1.0 - maskScale) / 2.0
                    for (col in 0 until matrix.size) {
                        for (row in 0 until matrix.size) {
                            if (!matrix.isDark(col, row)) continue
                            if (design.imageSource.scope == com.veilframe.app.qr.model.ImageMaskScope.DATA_ONLY && matrix.isProtected(col, row)) continue
                            val module = matrix.moduleAt(col, row)
                            val mx = col + qz + maskOffset
                            val my = row + qz + maskOffset
                            val cx = col + qz + 0.5
                            val cy = row + qz + 0.5
                            val elem = com.veilframe.app.qr.renderer.ShapeGeometry.buildSvgElement(
                                shape = design.moduleStyle.shape,
                                module = module,
                                cx = cx,
                                cy = cy,
                                mx = mx,
                                my = my,
                                scale = maskScale,
                                fill = "white",
                                design = design
                            )
                            sb.append("      ").append(elem).append("\n")
                        }
                    }
                }
                sb.append("    </mask>\n")
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
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
        val shape = design.moduleStyle.shape
        val is25D = design.effects.is25D || design.style == com.veilframe.app.qr.QrStyle.D25

        val d25LeftHex = hexColor(design.depthStyle.leftColor)
        val d25RightHex = hexColor(design.depthStyle.rightColor)
        val d25Depth = design.depthStyle.depth * 0.35

        val resolvedResampleSource = pixelSource ?: if (design.imageSource.bitmap != null && !design.imageSource.bitmap!!.isRecycled) {
            com.veilframe.app.qr.renderer.BitmapPixelSource(design.imageSource.bitmap!!)
        } else null
        val isResampleWithSource = design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE && resolvedResampleSource != null
        val isMaskedWithSource = design.moduleStyle.fill == ModuleFill.IMAGE_MASKED && design.imageSource.bitmap != null && !design.imageSource.bitmap!!.isRecycled

        if (isResampleWithSource) {
            com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                pixelSource = resolvedResampleSource,
                style = design.imageSource,
                seed = 42L
            ) { col, row, subX, subY, _ ->
                val rect = com.veilframe.app.qr.renderer.SubpixelGeometry.computeSvgRect(
                    col = col,
                    row = row,
                    quietZone = qz,
                    subX = subX,
                    subY = subY
                )
                val sx = String.format(Locale.US, "%.3f", rect.left)
                val sy = String.format(Locale.US, "%.3f", rect.top)
                val subW = String.format(Locale.US, "%.3f", rect.width)
                val subH = String.format(Locale.US, "%.3f", rect.height)
                sb.append("""  <rect x="$sx" y="$sy" width="$subW" height="$subH" fill="$dataFill" />""").append("\n")
            }
            // Render protected timing and alignment modules for 3x3 resample
            val timingFill = timingHex ?: dataFill
            val alignFill = alignmentHex ?: dataFill
            val timingScale = design.timingStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
            val alignScale = design.alignmentStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
            for (col in 0 until matrix.size) {
                for (row in 0 until matrix.size) {
                    if (!matrix.isDark(col, row)) continue
                    val role = matrix.roleAt(col, row)
                    val x = col + qz
                    val y = row + qz
                    if (role == QrModuleRole.TIMING) {
                        val offset = (1.0 - timingScale) / 2.0
                        val mx = x + offset
                        val my = y + offset
                        val elem = com.veilframe.app.qr.renderer.ProtectedModuleGeometry.buildSvgElement(
                            shape = design.timingStyle.shape,
                            x = mx,
                            y = my,
                            size = timingScale,
                            fill = timingFill
                        )
                        sb.append("""  $elem""").append("\n")
                    } else if (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) {
                        val offset = (1.0 - alignScale) / 2.0
                        val mx = x + offset
                        val my = y + offset
                        val elem = com.veilframe.app.qr.renderer.ProtectedModuleGeometry.buildSvgElement(
                            shape = design.alignmentStyle.shape,
                            x = mx,
                            y = my,
                            size = alignScale,
                            fill = alignFill
                        )
                        sb.append("""  $elem""").append("\n")
                    }
                }
            }
        } else if (isMaskedWithSource) {
            val sourceBmp = design.imageSource.bitmap!!
            val srcBase64 = bitmapToBase64(sourceBmp)
            if (srcBase64.isNotEmpty()) {
                val opacity = String.format(Locale.US, "%.2f", design.imageSource.opacity)
                val maskAlpha = String.format(Locale.US, "%.2f", design.imageSource.maskAlpha)
                val maskColorHex = hexColor(design.imageSource.maskColor)
                sb.append("""  <g mask="url(#qrDataMask)">""").append("\n")
                sb.append("""    <image href="data:image/png;base64,$srcBase64" x="$qz" y="$qz" width="${matrix.size}" height="${matrix.size}" preserveAspectRatio="xMidYMid slice" opacity="$opacity" />""").append("\n")
                if (design.imageSource.maskAlpha > 0f) {
                    sb.append("""    <rect x="$qz" y="$qz" width="${matrix.size}" height="${matrix.size}" fill="$maskColorHex" opacity="$maskAlpha" />""").append("\n")
                }
                sb.append("""  </g>""").append("\n")
            }
            // If DATA_ONLY, also render timing and alignment modules
            if (design.imageSource.scope == com.veilframe.app.qr.model.ImageMaskScope.DATA_ONLY) {
                val timingFill = timingHex ?: dataFill
                val alignFill = alignmentHex ?: dataFill
                val timingScale = design.timingStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
                val alignScale = design.alignmentStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
                for (col in 0 until matrix.size) {
                    for (row in 0 until matrix.size) {
                        if (!matrix.isDark(col, row)) continue
                        val role = matrix.roleAt(col, row)
                        val x = col + qz
                        val y = row + qz
                        if (role == QrModuleRole.TIMING) {
                            val offset = (1.0 - timingScale) / 2.0
                            val mx = x + offset
                            val my = y + offset
                            val elem = com.veilframe.app.qr.renderer.ProtectedModuleGeometry.buildSvgElement(
                                shape = design.timingStyle.shape,
                                x = mx,
                                y = my,
                                size = timingScale,
                                fill = timingFill
                            )
                            sb.append("""  $elem""").append("\n")
                        } else if (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) {
                            val offset = (1.0 - alignScale) / 2.0
                            val mx = x + offset
                            val my = y + offset
                            val elem = com.veilframe.app.qr.renderer.ProtectedModuleGeometry.buildSvgElement(
                                shape = design.alignmentStyle.shape,
                                x = mx,
                                y = my,
                                size = alignScale,
                                fill = alignFill
                            )
                            sb.append("""  $elem""").append("\n")
                        }
                    }
                }
            }
        } else if (shape == ModuleShape.BUBBLE_CLUSTER) {
            val clusters = com.veilframe.app.qr.renderer.BubbleClusterEngine.computeClusters(matrix, design)
            for (cluster in clusters) {
                val cx = cluster.cx + qz
                val cy = cluster.cy + qz
                val r = cluster.radius
                if (cluster.isSolid) {
                    sb.append("""  <circle cx="$cx" cy="$cy" r="$r" fill="$dataFill" />""").append("\n")
                } else {
                    val strokeW = if (cluster.strokeWidthRatio > 0f) String.format(Locale.US, "%.2f", cluster.strokeWidthRatio) else "0.35"
                    sb.append("""  <circle cx="$cx" cy="$cy" r="$r" fill="$bgHex" stroke="$dataFill" stroke-width="$strokeW" />""").append("\n")
                    if (cluster.hasInnerDot && cluster.innerRadius > 0f) {
                        sb.append("""  <circle cx="$cx" cy="$cy" r="${cluster.innerRadius}" fill="$dataFill" />""").append("\n")
                    }
                }
            }
            // Render timing and alignment for bubble cluster
            val timingFill = timingHex ?: dataFill
            val alignFill = alignmentHex ?: dataFill
            val timingScale = design.timingStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
            val alignScale = design.alignmentStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
            for (col in 0 until matrix.size) {
                for (row in 0 until matrix.size) {
                    if (!matrix.isDark(col, row)) continue
                    val role = matrix.roleAt(col, row)
                    val x = col + qz
                    val y = row + qz
                    if (role == QrModuleRole.TIMING) {
                        val offset = (1.0 - timingScale) / 2.0
                        val mx = x + offset
                        val my = y + offset
                        val elem = com.veilframe.app.qr.renderer.ProtectedModuleGeometry.buildSvgElement(
                            shape = design.timingStyle.shape,
                            x = mx,
                            y = my,
                            size = timingScale,
                            fill = timingFill
                        )
                        sb.append("""  $elem""").append("\n")
                    } else if (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) {
                        val offset = (1.0 - alignScale) / 2.0
                        val mx = x + offset
                        val my = y + offset
                        val elem = com.veilframe.app.qr.renderer.ProtectedModuleGeometry.buildSvgElement(
                            shape = design.alignmentStyle.shape,
                            x = mx,
                            y = my,
                            size = alignScale,
                            fill = alignFill
                        )
                        sb.append("""  $elem""").append("\n")
                    }
                }
            }
        } else {
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
                        isSampled && design.imageSource.bitmap != null -> hexColor(com.veilframe.app.qr.renderer.FillEngine.resolveModuleColor(module, matrix.size, design))
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

                    val elem = com.veilframe.app.qr.renderer.ShapeGeometry.buildSvgElement(
                        shape = shape,
                        module = module,
                        cx = cx,
                        cy = cy,
                        mx = mx,
                        my = my,
                        scale = scale,
                        fill = fill,
                        design = design
                    )
                    sb.append("  ").append(elem).append("\n")
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
