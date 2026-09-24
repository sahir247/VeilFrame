package com.veilframe.app.qr.exporter

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.veilframe.app.qr.model.EfFunctionDataStyle
import com.veilframe.app.qr.model.EfFunctionType
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.ModuleFill
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max

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

        if (design.style == com.veilframe.app.qr.QrStyle.IMAGE_FILL) {
            return generateImageFillSvg(matrix, design, qz, totalSize)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.IMAGE) {
            return generateImageSvg(matrix, design, qz, totalSize)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.D25) {
            return generate25DSvg(matrix, design)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.DSJ) {
            return generateDsjSvg(matrix, design, qz, totalSize)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.FUNCTION) {
            return generateFunctionSvg(matrix, design, qz, totalSize)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.LINE) {
            return generateLineSvg(matrix, design, qz, totalSize)
        }

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
        appendFinders(sb, design, qz, matrix.size, fgHex, bgHex, eyeOuterHex, eyeInnerHex)

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
        appendLogo(sb, design, totalSize, bgHex)

        sb.append("</svg>")
        return sb.toString()
    }

    private fun hexColor(color: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and color)
    }

    private fun colorAlpha(color: Int): Float = ((color ushr 24) and 0xFF) / 255f
    private fun colorAlphaInt(color: Int): Int = (color ushr 24) and 0xFF

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun generateImageFillSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qz: Int,
        totalSize: Int
    ): String {
        val sourceBmp = design.imageSource.bitmap ?: design.backgroundImage
        val imageBase64 = sourceBmp?.let { bitmapToBase64(it) } ?: ""
        val bgHex = hexColor(design.imageFillBackgroundColor)
        val bgAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.imageFillBackgroundColor))
        val maskHex = hexColor(design.imageFillMaskColor)
        val maskAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.imageFillMaskColor))
        val imageAlpha = String.format(Locale.US, "%.2f", design.imageSource.opacity.coerceIn(0f, 1f))

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")
        sb.append("  <defs>\n")
        sb.append("""    <mask id="hole">""").append("\n")
        sb.append("""      <rect x="0" y="0" width="$totalSize" height="$totalSize" fill="black"/>""").append("\n")
        for (col in 0 until matrix.size) {
            for (row in 0 until matrix.size) {
                if (matrix.isDark(col, row)) {
                    val mx = String.format(Locale.US, "%.2f", col + qz - 0.01)
                    val my = String.format(Locale.US, "%.2f", row + qz - 0.01)
                    sb.append("""      <rect x="$mx" y="$my" width="1.02" height="1.02" fill="white"/>""").append("\n")
                }
            }
        }
        sb.append("    </mask>\n")
        sb.append("  </defs>\n")
        sb.append("""  <g x="0" y="0" width="$totalSize" height="$totalSize" mask="url(#hole)">""").append("\n")
        sb.append("""    <rect x="0" y="0" width="$totalSize" height="$totalSize" fill="$bgHex" opacity="$bgAlpha"/>""").append("\n")
        if (imageBase64.isNotEmpty()) {
            sb.append("""    <image href="data:image/png;base64,$imageBase64" x="$qz" y="$qz" width="${matrix.size}" height="${matrix.size}" opacity="$imageAlpha" preserveAspectRatio="xMidYMid slice"/>""").append("\n")
        }
        sb.append("""    <rect x="0" y="0" width="$totalSize" height="$totalSize" fill="$maskHex" opacity="$maskAlpha"/>""").append("\n")
        sb.append("  </g>\n")

        appendLogo(sb, design, totalSize, bgHex)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun generateImageSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qz: Int,
        totalSize: Int
    ): String {
        val sourceBmp = design.imageSource.bitmap ?: design.backgroundImage
        val imageBase64 = sourceBmp?.let { bitmapToBase64(it) } ?: ""
        val imageAlpha = String.format(Locale.US, "%.2f", design.imageSource.opacity.coerceIn(0f, 1f))
        val n = matrix.size
        val bgHex = hexColor(design.palette.background)

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")
        sb.append("  <defs>\n")
        sb.append("""    <mask id="hole">""").append("\n")
        sb.append("""      <rect x="$qz" y="$qz" width="$n" height="$n" fill="white"/>""").append("\n")
        sb.append("""      <rect x="$qz" y="$qz" width="8" height="8" fill="black"/>""").append("\n")
        sb.append("""      <rect x="${n - 8 + qz}" y="$qz" width="8" height="8" fill="black"/>""").append("\n")
        sb.append("""      <rect x="$qz" y="${n - 8 + qz}" width="8" height="8" fill="black"/>""").append("\n")
        sb.append("    </mask>\n")
        sb.append("  </defs>\n")

        // Canvas background
        sb.append("""  <rect width="$totalSize" height="$totalSize" fill="$bgHex"/>""").append("\n")

        // Transparent pre-pass
        if (design.allowTransparent) {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.roleAt(col, row) != QrModuleRole.DATA) continue
                    val isDark = matrix.isDark(col, row)
                    val color = if (isDark) design.dataColorDark else design.dataColorLight
                    val alpha = colorAlphaInt(color)
                    if (alpha == 0) continue
                    val hex = hexColor(color)
                    val op = String.format(Locale.US, "%.2f", alpha / 255f)
                    val x = col + qz
                    val y = row + qz
                    sb.append("""  <rect opacity="$op" width="1" height="1" fill="$hex" x="$x" y="$y"/>""").append("\n")
                }
            }
        }

        // Image layer with #hole mask
        sb.append("""  <g x="$qz" y="$qz" width="$n" height="$n" mask="url(#hole)">""").append("\n")
        if (imageBase64.isNotEmpty()) {
            sb.append("""    <image href="data:image/png;base64,$imageBase64" x="$qz" y="$qz" width="$n" height="$n" opacity="$imageAlpha" preserveAspectRatio="xMidYMid slice"/>""").append("\n")
        }
        sb.append("  </g>\n")

        // Finders (with 8x8 posLightColor backing)
        val posLightHex = hexColor(design.positionLightColor)
        val posLightAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.positionLightColor))
        val finderBgs = listOf(
            Pair(qz, qz),
            Pair(qz + n - 8, qz),
            Pair(qz, qz + n - 8)
        )
        for ((bx, by) in finderBgs) {
            sb.append("""  <rect opacity="$posLightAlpha" width="8" height="8" x="$bx" y="$by" fill="$posLightHex"/>""").append("\n")
        }

        val eyeOuterHex = design.eyeStyle.outerColor?.let { hexColor(it) } ?: hexColor(design.positionDarkColor)
        val eyeInnerHex = design.eyeStyle.innerColor?.let { hexColor(it) } ?: hexColor(design.positionDarkColor)
        appendFinders(sb, design, qz, n, eyeOuterHex, bgHex, eyeOuterHex, eyeInnerHex)

        // Timing modules
        val timingDarkHex = hexColor(design.timingDarkColor)
        val timingLightHex = hexColor(design.timingLightColor)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                val isDark = matrix.isDark(col, row)
                val color = if (isDark) design.timingDarkColor else design.timingLightColor
                val alpha = colorAlphaInt(color)
                if (alpha == 0) continue
                val hex = if (isDark) timingDarkHex else timingLightHex
                val op = String.format(Locale.US, "%.2f", alpha / 255f)
                val tOffset = (1.0 - design.timingSize) / 2.0
                val tx = String.format(Locale.US, "%.2f", col + qz + tOffset)
                val ty = String.format(Locale.US, "%.2f", row + qz + tOffset)
                val ts = String.format(Locale.US, "%.2f", design.timingSize)
                sb.append("""  <rect opacity="$op" width="$ts" height="$ts" x="$tx" y="$ty" fill="$hex"/>""").append("\n")
            }
        }

        // Alignment modules
        val alignDarkHex = hexColor(design.alignDarkColor)
        val alignLightHex = hexColor(design.alignLightColor)
        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue
                val isDark = matrix.isDark(col, row)
                val color = if (isDark) design.alignDarkColor else design.alignLightColor
                val alpha = colorAlphaInt(color)
                if (alpha == 0) continue
                val hex = if (isDark) alignDarkHex else alignLightHex
                val op = String.format(Locale.US, "%.2f", alpha / 255f)
                val aOffset = (1.0 - design.alignSize) / 2.0
                val ax = String.format(Locale.US, "%.2f", col + qz + aOffset)
                val ay = String.format(Locale.US, "%.2f", row + qz + aOffset)
                val asize = String.format(Locale.US, "%.2f", design.alignSize)
                sb.append("""  <rect opacity="$op" width="$asize" height="$asize" x="$ax" y="$ay" fill="$hex"/>""").append("\n")
            }
        }

        // Data modules on top of image
        val dataDarkHex = hexColor(design.dataColorDark)
        val dataLightHex = hexColor(design.dataColorLight)
        val dScale = design.moduleStyle.scale.coerceIn(0.1f, 1.0f).toDouble()
        val dOffset = (1.0 - dScale) / 2.0
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.DATA) continue
                val isDark = matrix.isDark(col, row)
                val color = if (isDark) design.dataColorDark else design.dataColorLight
                val alpha = colorAlphaInt(color)
                if (alpha == 0) continue
                val hex = if (isDark) dataDarkHex else dataLightHex
                val op = String.format(Locale.US, "%.2f", alpha / 255f)
                val dx = String.format(Locale.US, "%.2f", col + qz + dOffset)
                val dy = String.format(Locale.US, "%.2f", row + qz + dOffset)
                val ds = String.format(Locale.US, "%.2f", dScale)
                sb.append("""  <rect opacity="$op" width="$ds" height="$ds" x="$dx" y="$dy" fill="$hex"/>""").append("\n")
            }
        }

        appendLogo(sb, design, totalSize, bgHex)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun generate25DSvg(matrix: QrMatrix, design: QrDesign): String {
        val n = matrix.size
        val matrixString = "matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)"
        val topHex = hexColor(design.depthStyle.topColor)
        val topAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.depthStyle.topColor))
        val leftHex = hexColor(design.depthStyle.leftColor)
        val leftAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.depthStyle.leftColor))
        val rightHex = hexColor(design.depthStyle.rightColor)
        val rightAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.depthStyle.rightColor))
        val dataH = design.depthStyle.depth.coerceAtLeast(0.1f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.1f)
        val bgHex = hexColor(design.palette.background)

        val vbX = -n
        val vbY = -n / 2.0
        val vbW = n * 2.0
        val vbH = n * 2.0

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="$vbX $vbY $vbW $vbH" width="100%" height="100%">""").append("\n")
        sb.append("""  <rect x="$vbX" y="$vbY" width="$vbW" height="$vbH" fill="$bgHex" />""").append("\n")

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val isPosition = matrix.roleAt(col, row) == QrModuleRole.FINDER_INNER ||
                    matrix.roleAt(col, row) == QrModuleRole.FINDER_OUTER ||
                    matrix.functionMask.isFinder(col, row)
                val h = if (isPosition) posH else dataH
                val hStr = String.format(Locale.US, "%.2f", h)

                // Top face
                sb.append("""  <rect opacity="$topAlpha" width="1" height="1" fill="$topHex" x="$col" y="$row" transform="$matrixString"/>""").append("\n")
                // Left face
                sb.append("""  <rect opacity="$leftAlpha" width="$hStr" height="1" fill="$leftHex" x="0" y="0" transform="${matrixString}translate(${col + 1},$row) skewY(45)"/>""").append("\n")
                // Right face
                sb.append("""  <rect opacity="$rightAlpha" width="1" height="$hStr" fill="$rightHex" x="0" y="0" transform="${matrixString}translate($col,${row + 1}) skewX(45)"/>""").append("\n")
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun generateDsjSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qz: Int,
        totalSize: Int
    ): String {
        val nCount = matrix.size
        val bgHex = hexColor(design.palette.background)
        val posColorHex = hexColor(design.eyeStyle.outerColor ?: design.palette.foreground)
        val posAlpha = colorAlpha(design.eyeStyle.outerColor ?: design.palette.foreground)
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize

        val width2 = max(0f, design.efDsjStyle.lineSize)
        val width1 = max(0f, design.efDsjStyle.xSize)
        val sqrt8 = 2.82842712474619f

        val hHex = hexColor(design.efDsjStyle.horizontalLineColor)
        val hAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.efDsjStyle.horizontalLineColor))
        val vHex = hexColor(design.efDsjStyle.verticalLineColor)
        val vAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.efDsjStyle.verticalLineColor))
        val xHex = hexColor(design.efDsjStyle.xColor)
        val xAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.efDsjStyle.xColor))
        val w1Str = String.format(Locale.US, "%.3f", width1)
        val w2Str = String.format(Locale.US, "%.3f", width2)

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")
        sb.append("""  <rect width="$totalSize" height="$totalSize" fill="$bgHex" />""").append("\n")

        var id = 0
        val pointList = StringBuilder()
        val g1 = StringBuilder()
        val g2 = StringBuilder()

        val finderCenters = listOf(Pair(3, 3), Pair(nCount - 4, 3), Pair(3, nCount - 4))
        for ((fx, fy) in finderCenters) {
            val (svgChunk, nextId) = com.veilframe.app.qr.renderer.EfPositionPatternGeometry.buildSvgElements(
                x = fx,
                y = fy,
                qz = qz,
                style = posStyle,
                size = posSize,
                colorHex = posColorHex,
                alpha = posAlpha,
                idStart = id
            )
            id = nextId
            pointList.append(svgChunk)
        }

        val available = Array(nCount) { BooleanArray(nCount) { true } }
        val ava2 = Array(nCount) { BooleanArray(nCount) { true } }

        for (y in 0 until nCount) {
            for (x in 0 until nCount) {
                if (!matrix.isDark(x, y)) continue
                if (com.veilframe.app.qr.renderer.EfPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                val ax = x + qz
                val ay = y + qz

                // Stage 1: 3x3 X
                if (available[x][y] && ava2[x][y] && x < nCount - 2 && y < nCount - 2) {
                    var ctn = true
                    for (i in 0 until 3) {
                        for (j in 0 until 3) {
                            if (!ava2[x + i][y + j]) ctn = false
                        }
                    }
                    if (ctn && matrix.isDark(x + 2, y) && matrix.isDark(x + 1, y + 1) &&
                        matrix.isDark(x, y + 2) && matrix.isDark(x + 2, y + 2)
                    ) {
                        val d = width1 / sqrt8
                        val x1 = String.format(Locale.US, "%.3f", ax + d)
                        val y1 = String.format(Locale.US, "%.3f", ay + d)
                        val x2 = String.format(Locale.US, "%.3f", ax + 3f - d)
                        val y2 = String.format(Locale.US, "%.3f", ay + 3f - d)
                        g1.append("""  <line key="$id" opacity="$xAlpha" x1="$x1" y1="$y1" x2="$x2" y2="$y2" fill="none" stroke="$xHex" stroke-width="$w1Str"/>""").append("\n")
                        id++
                        g1.append("""  <line key="$id" opacity="$xAlpha" x1="$x2" y1="$y1" x2="$x1" y2="$y2" fill="none" stroke="$xHex" stroke-width="$w1Str"/>""").append("\n")
                        id++

                        available[x][y] = false
                        available[x + 2][y] = false
                        available[x][y + 2] = false
                        available[x + 2][y + 2] = false
                        available[x + 1][y + 1] = false

                        for (i in 0 until 3) {
                            for (j in 0 until 3) {
                                ava2[x + i][y + j] = false
                            }
                        }
                    }
                }

                // Stage 2: 2x2 X
                if (available[x][y] && ava2[x][y] && x < nCount - 1 && y < nCount - 1) {
                    var ctn = true
                    for (i in 0 until 2) {
                        for (j in 0 until 2) {
                            if (!ava2[x + i][y + j]) ctn = false
                        }
                    }
                    if (ctn && matrix.isDark(x + 1, y) && matrix.isDark(x, y + 1) && matrix.isDark(x + 1, y + 1)) {
                        val d = width1 / sqrt8
                        val x1 = String.format(Locale.US, "%.3f", ax + d)
                        val y1 = String.format(Locale.US, "%.3f", ay + d)
                        val x2 = String.format(Locale.US, "%.3f", ax + 2f - d)
                        val y2 = String.format(Locale.US, "%.3f", ay + 2f - d)
                        g1.append("""  <line key="$id" opacity="$xAlpha" x1="$x1" y1="$y1" x2="$x2" y2="$y2" fill="none" stroke="$xHex" stroke-width="$w1Str"/>""").append("\n")
                        id++
                        g1.append("""  <line key="$id" opacity="$xAlpha" x1="$x2" y1="$y1" x2="$x1" y2="$y2" fill="none" stroke="$xHex" stroke-width="$w1Str"/>""").append("\n")
                        id++

                        for (i in 0 until 2) {
                            for (j in 0 until 2) {
                                available[x + i][y + j] = false
                                ava2[x + i][y + j] = false
                            }
                        }
                    }
                }

                // Stage 3: Vertical runs
                if (available[x][y] && ava2[x][y]) {
                    if (y == 0 || !matrix.isDark(x, y - 1) || !ava2[x][y - 1]) {
                        val start = y
                        var end = y
                        var ctn = true
                        while (ctn && end < nCount) {
                            if (matrix.isDark(x, end) && ava2[x][end]) {
                                end++
                            } else {
                                ctn = false
                            }
                        }
                        if (end - start > 2) {
                            for (i in start until end) {
                                ava2[x][i] = false
                                available[x][i] = false
                            }
                            val rx = String.format(Locale.US, "%.3f", ax + (1f - width2) / 2f)
                            val ry = String.format(Locale.US, "%.3f", ay + (1f - width2) / 2f)
                            val rh = String.format(Locale.US, "%.3f", (end - start - 1).toFloat() - (1f - width2))
                            g2.append("""  <rect key="$id" opacity="$vAlpha" width="$w2Str" height="$rh" fill="$vHex" x="$rx" y="$ry"/>""").append("\n")
                            id++
                            val endY = String.format(Locale.US, "%.3f", (end - 1 + qz).toFloat() + (1f - width2) / 2f)
                            g2.append("""  <rect key="$id" opacity="$vAlpha" width="$w2Str" height="$w2Str" fill="$vHex" x="$rx" y="$endY"/>""").append("\n")
                            id++
                        }
                    }
                }

                // Stage 4: Horizontal runs
                if (available[x][y] && ava2[x][y]) {
                    if (x == 0 || !matrix.isDark(x - 1, y) || !ava2[x - 1][y]) {
                        val start = x
                        var end = x
                        var ctn = true
                        while (ctn && end < nCount) {
                            if (matrix.isDark(end, y) && ava2[end][y]) {
                                end++
                            } else {
                                ctn = false
                            }
                        }
                        if (end - start > 1) {
                            for (i in start until end) {
                                ava2[i][y] = false
                                available[i][y] = false
                            }
                            val rx = String.format(Locale.US, "%.3f", ax + (1f - width2) / 2f)
                            val ry = String.format(Locale.US, "%.3f", ay + (1f - width2) / 2f)
                            val rw = String.format(Locale.US, "%.3f", (end - start).toFloat() - (1f - width2))
                            g2.append("""  <rect key="$id" opacity="$hAlpha" width="$rw" height="$w2Str" fill="$hHex" x="$rx" y="$ry"/>""").append("\n")
                            id++
                        }
                    }
                }

                // Stage 5: Residual single cell
                if (available[x][y]) {
                    val rx = String.format(Locale.US, "%.3f", ax + (1f - width2) / 2f)
                    val ry = String.format(Locale.US, "%.3f", ay + (1f - width2) / 2f)
                    pointList.append("""  <rect key="$id" opacity="$hAlpha" width="$w2Str" height="$w2Str" fill="$hHex" x="$rx" y="$ry"/>""").append("\n")
                    id++
                }
            }
        }

        sb.append(pointList)
        sb.append(g1)
        sb.append(g2)

        appendLogo(sb, design, totalSize, bgHex)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun generateFunctionSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qz: Int,
        totalSize: Int
    ): String {
        val nCount = matrix.size
        val bgHex = hexColor(design.palette.background)
        val posColorHex = hexColor(design.eyeStyle.outerColor ?: design.palette.foreground)
        val posAlpha = colorAlpha(design.eyeStyle.outerColor ?: design.palette.foreground)
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize

        val funcType = design.efFunctionStyle.functionType
        val dataStyle = design.efFunctionStyle.dataStyle
        val dataColor = design.efFunctionStyle.dataColor
        val circleColor = design.efFunctionStyle.circleColor

        val dataHex = hexColor(dataColor)
        val dataAlpha = String.format(Locale.US, "%.2f", colorAlpha(dataColor))
        val circleHex = hexColor(circleColor)
        val circleAlpha = String.format(Locale.US, "%.2f", colorAlpha(circleColor))

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")
        sb.append("""  <rect width="$totalSize" height="$totalSize" fill="$bgHex" />""").append("\n")

        var id = 0

        // Background ring if CIRCLE function + ROUND dataStyle
        if (funcType == EfFunctionType.CIRCLE && dataStyle == EfFunctionDataStyle.ROUND) {
            val ringSw = String.format(Locale.US, "%.3f", nCount.toDouble() / 15.0)
            val ringCx = String.format(Locale.US, "%.3f", nCount.toDouble() / 2.0 + qz)
            val ringCy = String.format(Locale.US, "%.3f", nCount.toDouble() / 2.0 + qz)
            val ringR = String.format(Locale.US, "%.3f", nCount.toDouble() / 2.0 * kotlin.math.sqrt(2.0) * 13.0 / 40.0)
            sb.append("""  <circle opacity="$circleAlpha" key="$id" fill="none" stroke-width="$ringSw" stroke="$circleHex" cx="$ringCx" cy="$ringCy" r="$ringR"/>""").append("\n")
            id++
        }

        // Finders
        val finderCenters = listOf(Pair(3, 3), Pair(nCount - 4, 3), Pair(3, nCount - 4))
        for ((fx, fy) in finderCenters) {
            val (svgChunk, nextId) = com.veilframe.app.qr.renderer.EfPositionPatternGeometry.buildSvgElements(
                x = fx,
                y = fy,
                qz = qz,
                style = posStyle,
                size = posSize,
                colorHex = posColorHex,
                alpha = posAlpha,
                idStart = id
            )
            id = nextId
            sb.append(svgChunk)
        }

        val centerCoord = (nCount - 1).toDouble() / 2.0
        val maxDist = (nCount.toDouble() / 2.0) * kotlin.math.sqrt(2.0)

        for (x in 0 until nCount) {
            for (y in 0 until nCount) {
                if (com.veilframe.app.qr.renderer.EfPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                val isDark = matrix.isDark(x, y)
                val dist = kotlin.math.sqrt(Math.pow(centerCoord - x, 2.0) + Math.pow(centerCoord - y, 2.0)) / maxDist
                val ax = x + qz
                val ay = y + qz

                when (funcType) {
                    EfFunctionType.FADE -> {
                        val sizeF = (1.0 - kotlin.math.cos(Math.PI * dist)) / 6.0 + 1.0 / 5.0
                        if (isDark) {
                            when (dataStyle) {
                                EfFunctionDataStyle.RECTANGLE -> {
                                    val rectSize = sizeF + 0.2
                                    val rsStr = String.format(Locale.US, "%.3f", rectSize)
                                    val rx = String.format(Locale.US, "%.3f", ax + (1.0 - rectSize) / 2.0)
                                    val ry = String.format(Locale.US, "%.3f", ay + (1.0 - rectSize) / 2.0)
                                    sb.append("""  <rect opacity="$dataAlpha" width="$rsStr" height="$rsStr" key="$id" fill="$dataHex" x="$rx" y="$ry"/>""").append("\n")
                                    id++
                                }
                                EfFunctionDataStyle.ROUND -> {
                                    val rStr = String.format(Locale.US, "%.3f", sizeF)
                                    val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                                    val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                                    sb.append("""  <circle opacity="$dataAlpha" r="$rStr" key="$id" fill="$dataHex" cx="$cx" cy="$cy"/>""").append("\n")
                                    id++
                                }
                            }
                        }
                    }
                    EfFunctionType.CIRCLE -> {
                        var sizeF: Double
                        var activeHex = dataHex
                        var activeAlpha = dataAlpha
                        var pointVisible = isDark

                        if (dist > 5.0 / 20.0 && dist < 8.0 / 20.0) {
                            sizeF = 0.5
                            activeHex = circleHex
                            activeAlpha = circleAlpha
                            pointVisible = true
                        } else {
                            sizeF = if (dataStyle == EfFunctionDataStyle.RECTANGLE) 0.15 else 0.25
                        }

                        if (pointVisible) {
                            when (dataStyle) {
                                EfFunctionDataStyle.RECTANGLE -> {
                                    val baseSize = 2.0 * sizeF + 0.1
                                    val bsStr = String.format(Locale.US, "%.3f", baseSize)
                                    val rx = String.format(Locale.US, "%.3f", ax + (1.0 - baseSize) / 2.0)
                                    val ry = String.format(Locale.US, "%.3f", ay + (1.0 - baseSize) / 2.0)
                                    if (isDark) {
                                        sb.append("""  <rect opacity="$activeAlpha" width="$bsStr" height="$bsStr" key="$id" fill="$activeHex" x="$rx" y="$ry"/>""").append("\n")
                                        id++
                                    } else {
                                        val subSize = baseSize - 0.1
                                        val ssStr = String.format(Locale.US, "%.3f", subSize)
                                        val srx = String.format(Locale.US, "%.3f", ax + (1.0 - subSize) / 2.0)
                                        val sry = String.format(Locale.US, "%.3f", ay + (1.0 - subSize) / 2.0)
                                        sb.append("""  <rect opacity="$activeAlpha" width="$ssStr" height="$ssStr" key="$id" stroke="$activeHex" stroke-width="0.1" fill="white" x="$srx" y="$sry"/>""").append("\n")
                                        id++
                                    }
                                }
                                EfFunctionDataStyle.ROUND -> {
                                    val rStr = String.format(Locale.US, "%.3f", sizeF)
                                    val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                                    val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                                    if (isDark) {
                                        sb.append("""  <circle opacity="$activeAlpha" r="$rStr" key="$id" fill="$activeHex" cx="$cx" cy="$cy"/>""").append("\n")
                                        id++
                                    } else {
                                        sb.append("""  <circle opacity="$activeAlpha" r="$rStr" key="$id" stroke="$activeHex" stroke-width="0.1" fill="white" cx="$cx" cy="$cy"/>""").append("\n")
                                        id++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        appendLogo(sb, design, totalSize, bgHex)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun generateLineSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qz: Int,
        totalSize: Int
    ): String {
        val nCount = matrix.size
        val bgHex = hexColor(design.palette.background)
        val posColorHex = hexColor(design.lineStyle.positionColor ?: design.palette.foreground)
        val posAlpha = colorAlpha(design.lineStyle.positionColor ?: design.palette.foreground)
        val posStyle = design.lineStyle.positionStyle
        val posSize = design.lineStyle.positionSize

        val thickness = max(0.05f, design.lineStyle.thicknessFraction)
        val sizeStr = String.format(Locale.US, "%.3f", thickness)
        val halfSizeStr = String.format(Locale.US, "%.3f", thickness / 2f)
        val lineHex = hexColor(design.lineStyle.color ?: design.palette.foreground)
        val lineAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.lineStyle.color ?: design.palette.foreground))

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")
        sb.append("""  <rect width="$totalSize" height="$totalSize" fill="$bgHex" />""").append("\n")

        var id = 0

        // Finders
        val finderCenters = listOf(Pair(3, 3), Pair(nCount - 4, 3), Pair(3, nCount - 4))
        for ((fx, fy) in finderCenters) {
            val (svgChunk, nextId) = com.veilframe.app.qr.renderer.EfPositionPatternGeometry.buildSvgElements(
                x = fx,
                y = fy,
                qz = qz,
                style = posStyle,
                size = posSize,
                colorHex = posColorHex,
                alpha = posAlpha,
                idStart = id
            )
            id = nextId
            sb.append(svgChunk)
        }

        val available = Array(nCount) { BooleanArray(nCount) { true } }
        val ava2 = Array(nCount) { BooleanArray(nCount) { true } }

        val direction = when (design.lineStyle.direction) {
            LineDirection.DIAGONAL_FORWARD -> LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            LineDirection.DIAGONAL_BACKWARD -> LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT
            LineDirection.LOOP -> LineDirection.LOOPBACK
            else -> design.lineStyle.direction
        }

        for (x in 0 until nCount) {
            for (y in 0 until nCount) {
                if (!matrix.isDark(x, y)) continue
                if (com.veilframe.app.qr.renderer.EfPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                val ax = x + qz
                val ay = y + qz

                when (direction) {
                    LineDirection.HORIZONTAL -> {
                        if (x == 0 || (x > 0 && (!matrix.isDark(x - 1, y) || !ava2[x - 1][y]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount) {
                                if (matrix.isDark(x + end, y) && ava2[x + end][y]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y] = false
                                    available[x + i][y] = false
                                }
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y1" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        if (available[x][y]) {
                            val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                            val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                            sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$halfSizeStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                            id++
                        }
                    }

                    LineDirection.VERTICAL -> {
                        if (y == 0 || (y > 0 && (!matrix.isDark(x, y - 1) || !ava2[x][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount) {
                                if (matrix.isDark(x, y + end) && ava2[x][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x][y + i] = false
                                    available[x][y + i] = false
                                }
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val y2 = String.format(Locale.US, "%.3f", ay + end - 0.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x1" y2="$y2" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        if (available[x][y]) {
                            val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                            val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                            sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$halfSizeStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                            id++
                        }
                    }

                    LineDirection.CROSS -> {
                        if (y == 0 || (y > 0 && (!matrix.isDark(x, y - 1) || !ava2[x][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount && end <= 3) {
                                if (matrix.isDark(x, y + end) && ava2[x][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x][y + i] = false
                                    available[x][y + i] = false
                                }
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val y2 = String.format(Locale.US, "%.3f", ay + end - 0.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x1" y2="$y2" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        if (x == 0 || (x > 0 && (!matrix.isDark(x - 1, y) || !ava2[x - 1][y]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount && end <= 3) {
                                if (matrix.isDark(x + end, y) && ava2[x + end][y]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y] = false
                                    available[x + i][y] = false
                                }
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y1" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        if (available[x][y]) {
                            val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                            val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                            sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$halfSizeStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                            id++
                        }
                    }

                    LineDirection.LOOPBACK -> {
                        if ((x > y) != (x + y < nCount)) {
                            if (y == 0 || (y > 0 && (!matrix.isDark(x, y - 1) || !ava2[x][y - 1]))) {
                                var end = 0
                                var ctn = true
                                while (ctn && y + end < nCount && end <= 3) {
                                    if (matrix.isDark(x, y + end) && ava2[x][y + end]) {
                                        end++
                                    } else {
                                        ctn = false
                                    }
                                }
                                if (end > 1) {
                                    for (i in 0 until end) {
                                        ava2[x][y + i] = false
                                        available[x][y + i] = false
                                    }
                                    val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                    val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                    val y2 = String.format(Locale.US, "%.3f", ay + end - 0.5)
                                    sb.append("""  <line x1="$x1" y1="$y1" x2="$x1" y2="$y2" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                    id++
                                }
                            }
                        } else {
                            if (x == 0 || (x > 0 && (!matrix.isDark(x - 1, y) || !ava2[x - 1][y]))) {
                                var end = 0
                                var ctn = true
                                while (ctn && x + end < nCount && end <= 3) {
                                    if (matrix.isDark(x + end, y) && ava2[x + end][y]) {
                                        end++
                                    } else {
                                        ctn = false
                                    }
                                }
                                if (end > 1) {
                                    for (i in 0 until end) {
                                        ava2[x + i][y] = false
                                        available[x + i][y] = false
                                    }
                                    val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                    val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                    val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                    sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y1" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                    id++
                                }
                            }
                        }
                        if (available[x][y]) {
                            val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                            val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                            sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$halfSizeStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                            id++
                        }
                    }

                    LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT -> {
                        if (y == 0 || x == 0 || ((y > 0 && x > 0) && (!matrix.isDark(x - 1, y - 1) || !ava2[x - 1][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount && x + end < nCount) {
                                if (matrix.isDark(x + end, y + end) && ava2[x + end][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y + i] = false
                                    available[x + i][y + i] = false
                                }
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                val y2 = String.format(Locale.US, "%.3f", ay + end - 0.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        if (available[x][y]) {
                            val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                            val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                            sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$halfSizeStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                            id++
                        }
                    }

                    LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT -> {
                        if (x == 0 || y == nCount - 1 || ((x > 0 && y < nCount - 1) && (!matrix.isDark(x - 1, y + 1) || !ava2[x - 1][y + 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount && y - end >= 0) {
                                if (matrix.isDark(x + end, y - end) && available[x + end][y - end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y - i] = false
                                    available[x + i][y - i] = false
                                }
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                val y2 = String.format(Locale.US, "%.3f", ay - end + 1.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke-width="$sizeStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        if (available[x][y]) {
                            val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                            val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                            sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$halfSizeStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                            id++
                        }
                    }

                    LineDirection.X -> {
                        // Diagonal 1: (x+i, y-i)
                        if (x == 0 || y == nCount - 1 || ((x > 0 && y < nCount - 1) && (!matrix.isDark(x - 1, y + 1) || !ava2[x - 1][y + 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && x + end < nCount && y - end >= 0) {
                                if (matrix.isDark(x + end, y - end) && ava2[x + end][y - end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    ava2[x + i][y - i] = false
                                }
                                val sw = thickness / 2f * com.veilframe.app.qr.renderer.LineRenderer.pseudoRandom(x, y, 1, 0.3f, 1.0f)
                                val swStr = String.format(Locale.US, "%.3f", sw)
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                val y2 = String.format(Locale.US, "%.3f", ay - end + 1.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke-width="$swStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        // Diagonal 2: (x+i, y+i)
                        if (y == 0 || x == 0 || ((y > 0 && x > 0) && (!matrix.isDark(x - 1, y - 1) || !available[x - 1][y - 1]))) {
                            var end = 0
                            var ctn = true
                            while (ctn && y + end < nCount && x + end < nCount) {
                                if (matrix.isDark(x + end, y + end) && available[x + end][y + end]) {
                                    end++
                                } else {
                                    ctn = false
                                }
                            }
                            if (end > 1) {
                                for (i in 0 until end) {
                                    available[x + i][y + i] = false
                                }
                                val sw = thickness / 2f * com.veilframe.app.qr.renderer.LineRenderer.pseudoRandom(x, y, 2, 0.3f, 1.0f)
                                val swStr = String.format(Locale.US, "%.3f", sw)
                                val x1 = String.format(Locale.US, "%.3f", ax + 0.5)
                                val y1 = String.format(Locale.US, "%.3f", ay + 0.5)
                                val x2 = String.format(Locale.US, "%.3f", ax + end - 0.5)
                                val y2 = String.format(Locale.US, "%.3f", ay + end - 0.5)
                                sb.append("""  <line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke-width="$swStr" stroke="$lineHex" stroke-linecap="round" opacity="$lineAlpha" key="$id"/>""").append("\n")
                                id++
                            }
                        }
                        // Center dot
                        val r = 0.5f * com.veilframe.app.qr.renderer.LineRenderer.pseudoRandom(x, y, 3, 0.33f, 0.9f)
                        val rStr = String.format(Locale.US, "%.3f", r)
                        val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                        val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                        sb.append("""  <circle key="$id" opacity="$lineAlpha" r="$rStr" fill="$lineHex" cx="$cx" cy="$cy"/>""").append("\n")
                        id++
                    }
                    else -> {}
                }
            }
        }

        appendLogo(sb, design, totalSize, bgHex)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun appendFinders(
        sb: StringBuilder,
        design: QrDesign,
        qz: Int,
        matrixSize: Int,
        fgHex: String,
        bgHex: String,
        eyeOuterHex: String,
        eyeInnerHex: String
    ) {
        val finders = listOf(
            Pair(qz, qz),
            Pair(qz, qz + matrixSize - 7),
            Pair(qz + matrixSize - 7, qz)
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
    }

    private fun appendLogo(sb: StringBuilder, design: QrDesign, totalSize: Int, bgHex: String) {
        design.logo?.bitmap?.let { logoBmp ->
            val fraction = design.logo.scaleFraction.coerceIn(0.10f, 0.35f)
            val logoSize = totalSize * fraction
            val logoX = (totalSize - logoSize) / 2.0
            val logoY = (totalSize - logoSize) / 2.0

            val cardPadding = 0.5
            sb.append("""  <rect x="${logoX - cardPadding}" y="${logoY - cardPadding}" width="${logoSize + 2 * cardPadding}" height="${logoSize + 2 * cardPadding}" rx="1.5" fill="$bgHex" />""").append("\n")

            val base64 = bitmapToBase64(logoBmp)
            if (base64.isNotEmpty()) {
                sb.append("""  <image href="data:image/png;base64,$base64" x="$logoX" y="$logoY" width="$logoSize" height="$logoSize" preserveAspectRatio="xMidYMid meet" />""").append("\n")
            }
        }
    }
}
