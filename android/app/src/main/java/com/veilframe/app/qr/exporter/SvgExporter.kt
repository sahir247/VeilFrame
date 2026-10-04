package com.veilframe.app.qr.exporter

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.qr.model.VeilFunctionDataStyle
import com.veilframe.app.qr.model.VeilFunctionType
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.ModuleFill
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.geometry.ImageNode
import com.veilframe.app.qr.geometry.GroupNode
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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

    fun formatCoord(v: Double): String {
        return if (v % 1.0 == 0.0) {
            v.toLong().toString()
        } else {
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    fun generateSvg(
        matrix: QrMatrix,
        design: QrDesign,
        pixelSource: com.veilframe.app.qr.renderer.PixelSource? = null,
        geometry: com.veilframe.app.qr.model.QrGeometry? = null
    ): String {
        val resolvedQz = com.veilframe.app.qr.model.QrGeometry.resolveQuietZone(design, matrix.size)
        val qzLeft = resolvedQz.left.toDouble()
        val qzTop = resolvedQz.top.toDouble()
        val qzRight = resolvedQz.right.toDouble()
        val qzBottom = resolvedQz.bottom.toDouble()

        val totalWidth = geometry?.outputWidthFloat?.toDouble() ?: (matrix.size + qzLeft + qzRight)
        val totalHeight = geometry?.outputHeightFloat?.toDouble() ?: (matrix.size + qzTop + qzBottom)
        val totalSize = maxOf(totalWidth, totalHeight)
        val fgHex = hexColor(design.palette.foreground)
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val bgHex = toSvgColor(resolvedBackdropColor).hex
        val bgAlpha = colorAlpha(resolvedBackdropColor)
        val bgAlphaStr = formatOpacity(bgAlpha)
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
            return generateImageFillSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.IMAGE) {
            val effGeometry = geometry ?: com.veilframe.app.qr.model.QrGeometry.fromDesign(
                matrixSize = matrix.size,
                outputWidth = totalWidth.toFloat(),
                outputHeight = totalHeight.toFloat(),
                design = design
            )
            val ir = com.veilframe.app.qr.geometry.ImageGeometryBuilder.generateGeometry(
                matrix = matrix,
                design = design,
                geometry = effGeometry
            )
            return com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.D25) {
            return generate25DSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.BUBBLE) {
            return generateBubbleSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.DSJ) {
            return generateDsjSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.FUNCTION) {
            return generateFunctionSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.LINE) {
            return generateLineSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.RANDOM_RECTANGLE) {
            return generateRandomRectangleSvg(matrix, design, qzLeft, qzTop, qzRight, qzBottom)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE) {
            val resampleGeom = com.veilframe.app.qr.model.QrGeometry.fromDesign(matrix.size, totalWidth.toFloat(), totalHeight.toFloat(), design)
            val ir = com.veilframe.app.qr.geometry.ResampleGeometryBuilder.generateGeometry(matrix, design, resampleGeom, pixelSource)
            return com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        }
        if (design.style == com.veilframe.app.qr.QrStyle.BASIC) {
            val effGeometry = geometry ?: com.veilframe.app.qr.model.QrGeometry.fromDesign(
                matrixSize = matrix.size,
                outputWidth = totalWidth.toFloat(),
                outputHeight = totalHeight.toFloat(),
                design = design
            )
            val ir = com.veilframe.app.qr.geometry.BasicGeometryBuilder.generateGeometry(
                matrix = matrix,
                design = design,
                geometry = effGeometry,
                profile = design.basicProfile
            )
            return com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        }

        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crStr = formatCornerRadius(design.backdropStyle.cornerRadius)
        val hasBackdropImg = design.backdropStyle.image != null

        val twStr = formatCoord(totalWidth)
        val thStr = formatCoord(totalHeight)

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 $twStr $thStr" width="100%" height="100%">""").append("\n")

        // 1. Defs (Gradients & Masks & Corner Clip)
        val isMasked = design.moduleStyle.fill == ModuleFill.IMAGE_MASKED
        if (hasCornerClip || hasGradient || isMasked) {
            sb.append("  <defs>\n")
            if (hasCornerClip) {
                sb.append("""    <clipPath id="rounded-corners"><rect width="$twStr" height="$thStr" rx="$crStr" ry="$crStr"/></clipPath>""").append("\n")
            }
            if (hasGradient) {
                val startAlpha = ((design.palette.gradientStart!! ushr 24) and 0xFF) / 255f
                val endAlpha = ((design.palette.gradientEnd!! ushr 24) and 0xFF) / 255f
                val startOp = if (startAlpha < 1.0f) " stop-opacity=\"${com.veilframe.app.qr.geometry.IrSvgRenderer.formatOpacity(startAlpha)}\"" else ""
                val endOp = if (endAlpha < 1.0f) " stop-opacity=\"${com.veilframe.app.qr.geometry.IrSvgRenderer.formatOpacity(endAlpha)}\"" else ""
                if (isRadial) {
                    val cxStr = formatCoord(totalWidth / 2.0)
                    val cyStr = formatCoord(totalHeight / 2.0)
                    val rStr = formatCoord(maxOf(totalWidth, totalHeight) / 2.0)
                    sb.append("""    <radialGradient id="qrGrad" gradientUnits="userSpaceOnUse" cx="$cxStr" cy="$cyStr" r="$rStr">""").append("\n")
                    sb.append("""      <stop offset="0%" stop-color="$gradStartHex"$startOp />""").append("\n")
                    sb.append("""      <stop offset="100%" stop-color="$gradEndHex"$endOp />""").append("\n")
                    sb.append("    </radialGradient>\n")
                } else {
                    sb.append("""    <linearGradient id="qrGrad" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="$twStr" y2="$thStr">""").append("\n")
                    sb.append("""      <stop offset="0%" stop-color="$gradStartHex"$startOp />""").append("\n")
                    sb.append("""      <stop offset="100%" stop-color="$gradEndHex"$endOp />""").append("\n")
                    sb.append("    </linearGradient>\n")
                }
            }
            if (isMasked) {
                sb.append("""    <mask id="qrDataMask">""").append("\n")
                sb.append("""      <rect width="$twStr" height="$thStr" fill="black" />""").append("\n")
                if (design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER) {
                    val clusters = com.veilframe.app.qr.renderer.BubbleClusterEngine.computeClusters(matrix, design)
                    for (cluster in clusters) {
                        if (cluster.isAmbient) continue
                        val cx = cluster.cx + qzLeft
                        val cy = cluster.cy + qzTop
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
                            val mx = col + qzLeft + maskOffset
                            val my = row + qzTop + maskOffset
                            val cx = col + qzLeft + 0.5
                            val cy = row + qzTop + 0.5
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
        if (hasCornerClip) {
            sb.append("""  <g clip-path="url(#rounded-corners)">""").append("\n")
        }

        // 2. Background Layer (Outer backdrop rectangle)
        sb.append("""  <rect width="$twStr" height="$thStr" fill="$bgHex" opacity="$bgAlphaStr"/>""").append("\n")
        if (hasBackdropImg) {
            val preprocessedBackdrop = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = design.backdropStyle.image!!,
                canvasWidth = totalWidth.toFloat(),
                canvasHeight = totalHeight.toFloat(),
                mode = design.backdropStyle.imageScaleMode
            )
            val biB64 = bitmapToBase64(preprocessedBackdrop)
            val biAlpha = formatOpacity(design.backdropStyle.imageAlpha)
            sb.append("""  <image key="bi" opacity="$biAlpha" xlink:href="data:image/png;base64,$biB64" width="$twStr" height="$thStr" x="0" y="0"/>""").append("\n")
        }

        val bgBmp = design.backgroundLayer.bitmap ?: design.backgroundImage
        if (design.backgroundLayer.enabled && bgBmp != null) {
            val bgBase64 = bitmapToBase64(bgBmp)
            if (bgBase64.isNotEmpty()) {
                val opacity = String.format(Locale.US, "%.2f", design.backgroundLayer.opacity)
                sb.append("""  <image href="data:image/png;base64,$bgBase64" width="$twStr" height="$thStr" preserveAspectRatio="xMidYMid slice" opacity="$opacity" />""").append("\n")
            }
        }

        // 2b. Source Image as Continuous Backdrop (for IMAGE_RESAMPLE screenshot parity)
        if (design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE && design.resampleStyle.hasBackdrop) {
            val srcBmp = design.resampleStyle.backdropBitmap ?: if (design.resampleStyle.useSourceAsBackdrop) design.imageSource.bitmap else null
            val opacity = String.format(Locale.US, "%.2f", design.resampleStyle.backdropOpacity.coerceIn(0f, 1f))
            val aspect = when (design.resampleStyle.backdropScaleMode) {
                com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT -> "xMidYMid meet"
                com.veilframe.app.qr.model.ImageScaleMode.STRETCH -> "none"
                else -> "xMidYMid slice"
            }
            val blendStyle = when (design.resampleStyle.backdropBlendMode) {
                com.veilframe.app.qr.model.BackdropBlendMode.NORMAL -> ""
                com.veilframe.app.qr.model.BackdropBlendMode.MULTIPLY -> """ style="mix-blend-mode: multiply;""""
                com.veilframe.app.qr.model.BackdropBlendMode.SCREEN -> """ style="mix-blend-mode: screen;""""
                com.veilframe.app.qr.model.BackdropBlendMode.OVERLAY -> """ style="mix-blend-mode: overlay;""""
            }
            if (srcBmp != null && !srcBmp.isRecycled) {
                val srcBase64 = bitmapToBase64(srcBmp)
                if (srcBase64.isNotEmpty()) {
                    sb.append("""  <image href="data:image/png;base64,$srcBase64" width="$twStr" height="$thStr" preserveAspectRatio="$aspect" opacity="$opacity"$blendStyle />""").append("\n")
                }
            } else if (pixelSource != null || design.imageSource.source != null) {
                sb.append("""  <image href="#sourceBackdrop" width="$twStr" height="$thStr" preserveAspectRatio="$aspect" opacity="$opacity"$blendStyle />""").append("\n")
            }
            val tint = design.resampleStyle.backdropTint
            if (tint != null) {
                val tintColor = toSvgColor(tint)
                val tintHex = tintColor.hex
                val tintAlpha = String.format(Locale.US, "%.2f", tintColor.opacity)
                if (srcBmp != null && !srcBmp.isRecycled) {
                    val (_, dstRect) = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveSrcDst(
                        srcBmp.width,
                        srcBmp.height,
                        android.graphics.RectF(0f, 0f, totalWidth.toFloat(), totalHeight.toFloat()),
                        design.resampleStyle.backdropScaleMode
                    )
                    val tx = String.format(Locale.US, "%.3f", dstRect.left)
                    val ty = String.format(Locale.US, "%.3f", dstRect.top)
                    val tw = String.format(Locale.US, "%.3f", dstRect.right - dstRect.left)
                    val th = String.format(Locale.US, "%.3f", dstRect.bottom - dstRect.top)
                    sb.append("""  <rect x="$tx" y="$ty" width="$tw" height="$th" fill="$tintHex" opacity="$tintAlpha" />""").append("\n")
                } else {
                    sb.append("""  <rect width="$twStr" height="$thStr" fill="$tintHex" opacity="$tintAlpha" />""").append("\n")
                }
            }
        }

        // 3. Finders (TL: 0,0; BL: 0, n-7; TR: n-7, 0) offset by qzLeft, qzTop
        appendFinders(sb, design, qzLeft, qzTop, matrix.size, fgHex, bgHex, eyeOuterHex, eyeInnerHex)

        // 4. Data & Functional Modules
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
        val shape = design.moduleStyle.shape
        val is25D = design.effects.is25D || design.style == com.veilframe.app.qr.QrStyle.D25

        val d25LeftHex = hexColor(design.depthStyle.leftColor)
        val d25RightHex = hexColor(design.depthStyle.rightColor)
        val d25Depth = design.depthStyle.depth * 0.35

        val isResample = design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE
        var preScaledSource: com.veilframe.app.qr.renderer.PreScaledPixelSource? = null

        val resolvedResampleSource = pixelSource ?: if (isResample && design.imageSource.bitmap != null && !design.imageSource.bitmap!!.isRecycled) {
            preScaledSource = com.veilframe.app.qr.renderer.ImageScaleResolver.createPreScaledSource(
                design.imageSource.bitmap!!,
                3 * matrix.size,
                3 * matrix.size,
                design.imageSource.scaleMode
            )
            preScaledSource
        } else if (design.imageSource.bitmap != null && !design.imageSource.bitmap!!.isRecycled) {
            com.veilframe.app.qr.renderer.BitmapPixelSource(design.imageSource.bitmap!!)
        } else null

        val isResampleWithSource = isResample && resolvedResampleSource != null
        val isMaskedWithSource = design.moduleStyle.fill == ModuleFill.IMAGE_MASKED && design.imageSource.bitmap != null && !design.imageSource.bitmap!!.isRecycled

        try {
            if (isResampleWithSource) {
                com.veilframe.app.qr.renderer.ResampleSubpixelEngine.traverseSubpixels(
                    matrix = matrix,
                    pixelSource = resolvedResampleSource,
                    style = design.imageSource,
                    seed = design.resampleStyle.seed,
                    policy = com.veilframe.app.qr.renderer.ArtisticResamplePolicy.from(design),
                    includeCenterAnchors = false
                ) { col, row, subX, subY, isCenter ->
                    if (!isCenter) {
                        val rect = com.veilframe.app.qr.renderer.SubpixelGeometry.computeSvgRect(
                            col = col,
                            row = row,
                            quietZoneLeft = qzLeft,
                            quietZoneTop = qzTop,
                            subX = subX,
                            subY = subY
                        )
                        val sx = String.format(Locale.US, "%.3f", rect.left)
                        val sy = String.format(Locale.US, "%.3f", rect.top)
                        val subW = String.format(Locale.US, "%.3f", rect.width)
                        val subH = String.format(Locale.US, "%.3f", rect.height)
                        sb.append("""  <rect x="$sx" y="$sy" width="$subW" height="$subH" fill="$dataFill" />""").append("\n")
                    }
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
                        val x = col + qzLeft
                        val y = row + qzTop
                        if (role == QrModuleRole.TIMING) {
                            if (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite) continue
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
                            if (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite) continue
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
                        } else if (role == QrModuleRole.DATA || role == QrModuleRole.FORMAT || role == QrModuleRole.VERSION) {
                            val rect = com.veilframe.app.qr.renderer.SubpixelGeometry.computeSvgRect(
                                col = col,
                                row = row,
                                quietZoneLeft = qzLeft,
                                quietZoneTop = qzTop,
                                subX = 3 * col + 1,
                                subY = 3 * row + 1
                            )
                            val sx = String.format(Locale.US, "%.3f", rect.left)
                            val sy = String.format(Locale.US, "%.3f", rect.top)
                            val subW = String.format(Locale.US, "%.3f", rect.width)
                            val subH = String.format(Locale.US, "%.3f", rect.height)
                            sb.append("""  <rect x="$sx" y="$sy" width="$subW" height="$subH" fill="$dataFill" />""").append("\n")
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
                sb.append("""    <image href="data:image/png;base64,$srcBase64" x="$qzLeft" y="$qzTop" width="${matrix.size}" height="${matrix.size}" preserveAspectRatio="xMidYMid slice" opacity="$opacity" />""").append("\n")
                if (design.imageSource.maskAlpha > 0f) {
                    sb.append("""    <rect x="$qzLeft" y="$qzTop" width="${matrix.size}" height="${matrix.size}" fill="$maskColorHex" opacity="$maskAlpha" />""").append("\n")
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
                        val x = col + qzLeft
                        val y = row + qzTop
                        if (role == QrModuleRole.TIMING) {
                            if (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite) continue
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
                            if (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite) continue
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
                val cx = cluster.cx + qzLeft
                val cy = cluster.cy + qzTop
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
                    val x = col + qzLeft
                    val y = row + qzTop
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

                    val x = col + qzLeft
                    val y = row + qzTop
                    val module = matrix.moduleAt(col, row)

                    // Per-zone color override: Timing, Alignment, or Sampled Image
                    val role = matrix.roleAt(col, row)
                    if (role == QrModuleRole.TIMING && (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite)) {
                        continue
                    }
                    if ((role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) &&
                        (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite)) {
                        continue
                    }
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

        } finally {
            preScaledSource?.bitmap?.recycle()
        }

        if (hasCornerClip) {
            sb.append("  </g>\n")
        }

        // 5. Embedded Logo (if present)
        appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)

        sb.append("</svg>")
        return sb.toString()
    }

    data class SvgColor(val hex: String, val opacity: Float, val css: String) {
        override fun toString(): String = css
    }

    fun toSvgColor(color: Int): SvgColor {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        val opacity = a / 255f
        val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
        val css = if (a == 255) hex else String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", r, g, b, opacity)
        return SvgColor(hex, opacity, css)
    }

    private fun hexColor(color: Int): String {
        return toSvgColor(color).css
    }

    private fun colorAlpha(color: Int): Float = ((color ushr 24) and 0xFF) / 255f
    private fun colorAlphaInt(color: Int): Int = (color ushr 24) and 0xFF

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return try {
            val stream = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            if (ok && bytes.isNotEmpty()) {
                java.util.Base64.getEncoder().encodeToString(bytes)
            } else {
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
            }
        } catch (_: Throwable) {
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        }
    }

    private fun generateImageFillSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        qzRight: Double,
        qzBottom: Double
    ): String {
        val totalWidth = matrix.size + qzLeft + qzRight
        val totalHeight = matrix.size + qzTop + qzBottom
        val twStr = formatCoord(totalWidth)
        val thStr = formatCoord(totalHeight)
        val qzLeftStr = formatCoord(qzLeft)
        val qzTopStr = formatCoord(qzTop)

        val sourceBmp = design.imageSource.bitmap
        val preprocessedStatic = sourceBmp?.let {
            com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = it,
                canvasWidth = matrix.size.toFloat(),
                canvasHeight = matrix.size.toFloat(),
                mode = design.imageSource.scaleMode
            )
        }
        val imageBase64 = preprocessedStatic?.let { bitmapToBase64(it) } ?: ""
        val bgHex = toSvgColor(design.imageFillBackgroundColor).hex
        val bgAlpha = formatOpacity(colorAlpha(design.imageFillBackgroundColor))
        val maskHex = toSvgColor(design.imageFillMaskColor).hex
        val maskAlpha = formatOpacity(colorAlpha(design.imageFillMaskColor))
        val imageAlpha = formatOpacity(design.imageSource.opacity)

        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crStr = formatCornerRadius(design.backdropStyle.cornerRadius)
        val hasBackdropImg = design.backdropStyle.image != null

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 $twStr $thStr" width="100%" height="100%">""").append("\n")
        sb.append("  <defs>\n")
        if (hasCornerClip) {
            sb.append("""    <clipPath id="rounded-corners"><rect width="$twStr" height="$thStr" rx="$crStr" ry="$crStr"/></clipPath>""").append("\n")
        }
        sb.append("""    <mask id="hole">""").append("\n")
        sb.append("""      <rect x="0" y="0" width="$twStr" height="$thStr" fill="black"/>""").append("\n")
        for (col in 0 until matrix.size) {
            for (row in 0 until matrix.size) {
                if (matrix.isDark(col, row)) {
                    val mx = String.format(Locale.US, "%.2f", col + qzLeft - 0.01)
                    val my = String.format(Locale.US, "%.2f", row + qzTop - 0.01)
                    sb.append("""      <rect x="$mx" y="$my" width="1.02" height="1.02" fill="white"/>""").append("\n")
                }
            }
        }
        sb.append("    </mask>\n")
        sb.append("  </defs>\n")

        if (hasCornerClip) {
            sb.append("""  <g clip-path="url(#rounded-corners)">""").append("\n")
        }

        // Quiet-zone background (paints entire viewBox per EFQRCode backdrop contract)
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val canvasBgAlpha = colorAlpha(resolvedBackdropColor)
        val canvasBgHex = toSvgColor(resolvedBackdropColor).hex
        val alphaStr = formatOpacity(canvasBgAlpha)
        sb.append("""  <rect width="$twStr" height="$thStr" fill="$canvasBgHex" opacity="$alphaStr"/>""").append("\n")
        if (hasBackdropImg) {
            val preprocessedBackdrop = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = design.backdropStyle.image!!,
                canvasWidth = totalWidth.toFloat(),
                canvasHeight = totalHeight.toFloat(),
                mode = design.backdropStyle.imageScaleMode
            )
            val biB64 = bitmapToBase64(preprocessedBackdrop)
            val biAlpha = formatOpacity(design.backdropStyle.imageAlpha)
            sb.append("""  <image key="bi" opacity="$biAlpha" xlink:href="data:image/png;base64,$biB64" width="$twStr" height="$thStr" x="0" y="0"/>""").append("\n")
        }

        sb.append("""  <g x="0" y="0" width="$twStr" height="$thStr" mask="url(#hole)">""").append("\n")
        sb.append("""    <rect x="0" y="0" width="$twStr" height="$thStr" fill="$bgHex" opacity="$bgAlpha"/>""").append("\n")

        val isAnimated = design.imageSource.isAnimated || (design.imageSource.animatedFrames?.isNotEmpty() == true)
        val animatedFrames = design.imageSource.animatedFrames
        val frameDelaysMs = design.imageSource.frameDelaysMs ?: emptyList()

        if (isAnimated && animatedFrames != null && animatedFrames.isNotEmpty()) {
            val preprocessedFrames = animatedFrames.map { frame ->
                com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                    source = frame,
                    canvasWidth = matrix.size.toFloat(),
                    canvasHeight = matrix.size.toFloat(),
                    mode = design.imageSource.scaleMode
                )
            }
            val base64Frames = preprocessedFrames.map { bitmapToBase64(it) }
            val delaysMs = if (frameDelaysMs.isNotEmpty()) frameDelaysMs else List(base64Frames.size) { 100 }
            val totalDurationMs = maxOf(1, delaysMs.sum())
            val totalDurationSec = totalDurationMs / 1000.0
            val framePrefix = "${com.veilframe.app.qr.geometry.VeilIconPipeline.nextUniqueMark()}fm"

            sb.append("    <g>\n")
            sb.append("      <defs>\n")
            for ((idx, b64) in base64Frames.withIndex()) {
                sb.append("""        <image id="$framePrefix$idx" xlink:href="data:image/png;base64,$b64" x="$qzLeftStr" y="$qzTopStr" width="${matrix.size}" height="${matrix.size}" opacity="$imageAlpha"/>""").append("\n")
            }
            sb.append("      </defs>\n")

            var accumulatedMs = 0
            val keyTimes = mutableListOf<String>()
            for (delay in delaysMs) {
                val fraction = accumulatedMs.toDouble() / totalDurationMs
                keyTimes.add(String.format(Locale.US, "%.3f", fraction))
                accumulatedMs += delay
            }
            val valuesStr = base64Frames.indices.joinToString(";") { "#$framePrefix$it" }
            val keyTimesStr = keyTimes.joinToString(";")
            val durStr = String.format(Locale.US, "%.3f", totalDurationSec)

            sb.append("""      <use xlink:href="#${framePrefix}0">""").append("\n")
            sb.append("        <animate\n")
            sb.append("""          attributeName="xlink:href"""").append("\n")
            sb.append("""          values="$valuesStr"""").append("\n")
            sb.append("""          keyTimes="$keyTimesStr"""").append("\n")
            sb.append("""          dur="${durStr}s"""").append("\n")
            sb.append("""          repeatCount="indefinite"""").append("\n")
            sb.append("""          calcMode="discrete"""").append("\n")
            sb.append("        />\n")
            sb.append("      </use>\n")
            sb.append("    </g>\n")
        } else if (imageBase64.isNotEmpty()) {
            sb.append("""    <image href="data:image/png;base64,$imageBase64" x="$qzLeftStr" y="$qzTopStr" width="${matrix.size}" height="${matrix.size}" opacity="$imageAlpha"/>""").append("\n")
        }
        sb.append("""    <rect x="0" y="0" width="$twStr" height="$thStr" fill="$maskHex" opacity="$maskAlpha"/>""").append("\n")
        sb.append("  </g>\n")

        if (hasCornerClip) {
            sb.append("  </g>\n")
        }

        appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun generate25DSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double = 0.0,
        qzTop: Double = 0.0,
        qzRight: Double = 0.0,
        qzBottom: Double = 0.0
    ): String {
        val n = matrix.size
        val matrixString = "matrix(0.8660254037844386,0.5,-0.8660254037844386,0.5,0,0)"
        val topHex = hexColor(design.depthStyle.topColor)
        val topAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.depthStyle.topColor))
        val leftHex = hexColor(design.depthStyle.leftColor)
        val leftAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.depthStyle.leftColor))
        val rightHex = hexColor(design.depthStyle.rightColor)
        val rightAlpha = String.format(Locale.US, "%.2f", colorAlpha(design.depthStyle.rightColor))
        val dataH = design.depthStyle.depth.coerceAtLeast(0.0f)
        val posH = design.depthStyle.positionDepth.coerceAtLeast(0.0f)
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val bgHex = toSvgColor(resolvedBackdropColor).hex
        val bgAlpha = colorAlpha(resolvedBackdropColor)
        val bgAlphaStr = formatOpacity(bgAlpha)

        // EFQRCode canonical formula (EFQRCodeStyle25D.swift) evaluated for integer module counts:
        //   vbX = -(n + qzLeft)
        //   vbY = -(n/2 + qzTop)
        //   vbW = 2n + qzLeft + qzRight
        //   vbH = 2n + qzTop + qzBottom
        val vbX = -(n + qzLeft)
        val vbY = -(n / 2.0 + qzTop)
        val vbW = (2 * n + qzLeft + qzRight)
        val vbH = (2 * n + qzTop + qzBottom)

        val vbXStr = if (qzLeft == 0.0) "-$n" else if (vbX == vbX.toLong().toDouble()) "${vbX.toLong()}" else "$vbX"
        val vbYStr = if (vbY == vbY.toLong().toDouble()) "${vbY.toLong()}" else "$vbY"
        val vbWStr = "$vbW"
        val vbHStr = "$vbH"

        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crStr = formatCornerRadius(design.backdropStyle.cornerRadius)
        val hasBackdropImg = design.backdropStyle.image != null

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="$vbXStr $vbYStr $vbWStr $vbHStr" width="100%" height="100%">""").append("\n")

        if (hasCornerClip) {
            sb.append("  <defs>\n")
            sb.append("""    <clipPath id="rounded-corners"><rect x="$vbXStr" y="$vbYStr" width="$vbWStr" height="$vbHStr" rx="$crStr" ry="$crStr"/></clipPath>""").append("\n")
            sb.append("  </defs>\n")
            sb.append("""  <g clip-path="url(#rounded-corners)">""").append("\n")
        }

        sb.append("""  <rect x="$vbXStr" y="$vbYStr" width="$vbWStr" height="$vbHStr" fill="$bgHex" opacity="$bgAlphaStr" />""").append("\n")

        if (hasBackdropImg) {
            val preprocessedBackdrop = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = design.backdropStyle.image!!,
                canvasWidth = vbW.toFloat(),
                canvasHeight = vbH.toFloat(),
                mode = design.backdropStyle.imageScaleMode
            )
            val biB64 = bitmapToBase64(preprocessedBackdrop)
            val biAlpha = formatOpacity(design.backdropStyle.imageAlpha)
            sb.append("""  <image key="bi" opacity="$biAlpha" xlink:href="data:image/png;base64,$biB64" width="$vbW" height="$vbH" x="$vbXStr" y="$vbYStr"/>""").append("\n")
        }

        val dataScale = design.moduleStyle.scale.coerceIn(0.1f, 1.0f)
        val posScale = 1.0f

        // Iterate in EFQRCode order (x in 0..<n, y in 0..<n)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val isPosition = matrix.roleAt(col, row) == QrModuleRole.FINDER_INNER ||
                    matrix.roleAt(col, row) == QrModuleRole.FINDER_OUTER ||
                    matrix.functionMask.isFinder(col, row)
                val h = if (isPosition) posH else dataH
                val hStr = String.format(Locale.US, "%.2f", h)
                val size = if (isPosition) posScale else dataScale
                val xVal = col + (1.0f - size) / 2.0f
                val yVal = row + (1.0f - size) / 2.0f
                val sizeStr = if (size == 1.0f) "1" else String.format(Locale.US, "%.3f", size)
                val xStr = if (size == 1.0f) "$col" else String.format(Locale.US, "%.3f", xVal)
                val yStr = if (size == 1.0f) "$row" else String.format(Locale.US, "%.3f", yVal)
                val txLeft = if (size == 1.0f) "${col + 1}" else String.format(Locale.US, "%.3f", xVal + size)
                val tyRight = if (size == 1.0f) "${row + 1}" else String.format(Locale.US, "%.3f", yVal + size)

                // Top face
                sb.append("""  <rect opacity="$topAlpha" width="$sizeStr" height="$sizeStr" fill="$topHex" x="$xStr" y="$yStr" transform="$matrixString"/>""").append("\n")
                if (h > 0.0001f) {
                    // Left face
                    sb.append("""  <rect opacity="$leftAlpha" width="$hStr" height="$sizeStr" fill="$leftHex" x="0" y="0" transform="${matrixString}translate($txLeft,$yStr) skewY(45)"/>""").append("\n")
                    // Right face
                    sb.append("""  <rect opacity="$rightAlpha" width="$sizeStr" height="$hStr" fill="$rightHex" x="0" y="0" transform="${matrixString}translate($xStr,$tyRight) skewX(45)"/>""").append("\n")
                }
            }
        }

        // Draw Center Logo if present in isometric projection
        if (design.logo?.effectiveBitmap != null) {
            sb.append("""  <g transform="$matrixString">""").append("\n")
            com.veilframe.app.qr.geometry.VeilIconPipeline.appendIconSvg(sb, design, 0.0, 0.0, n.toDouble(), bgHex)
            sb.append("""  </g>""").append("\n")
        }

        if (hasCornerClip) {
            sb.append("  </g>\n")
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun generateDsjSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        qzRight: Double,
        qzBottom: Double
    ): String {
        val totalWidth = matrix.size + qzLeft + qzRight
        val totalHeight = matrix.size + qzTop + qzBottom
        val normGeometry = com.veilframe.app.qr.model.QrGeometry.fromDesign(matrix.size, totalWidth.toFloat(), totalHeight.toFloat(), design)
        val rawIr = com.veilframe.app.qr.renderer.DsjRenderer().generateGeometry(matrix, design, normGeometry)
        val ir = applyBackdropToIr(rawIr, design, totalWidth.toFloat(), totalHeight.toFloat())
        val svg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        return if (design.logo?.bitmap != null) {
            val bgHex = toSvgColor(design.backdropStyle.color ?: design.palette.background).hex
            val closeTag = if (design.backdropStyle.cornerRadius > 0f) "</g>\n</svg>" else "</svg>"
            val prefix = if (svg.endsWith(closeTag)) svg.removeSuffix(closeTag) else svg.removeSuffix("</svg>")
            val sb = StringBuilder(prefix)
            appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)
            sb.append(if (design.backdropStyle.cornerRadius > 0f) "  </g>\n</svg>" else "</svg>")
            sb.toString()
        } else {
            svg
        }
    }

    private fun generateFunctionSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        qzRight: Double,
        qzBottom: Double
    ): String {
        val nCount = matrix.size
        val totalWidth = nCount + qzLeft + qzRight
        val totalHeight = nCount + qzTop + qzBottom
        val twStr = formatCoord(totalWidth)
        val thStr = formatCoord(totalHeight)
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val bgHex = toSvgColor(resolvedBackdropColor).hex
        val bgAlpha = colorAlpha(resolvedBackdropColor)
        val bgAlphaStr = formatOpacity(bgAlpha)

        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crStr = formatCornerRadius(design.backdropStyle.cornerRadius)
        val hasBackdropImg = design.backdropStyle.image != null

        val posColorHex = hexColor(design.eyeStyle.outerColor ?: design.palette.foreground)
        val posAlpha = colorAlpha(design.eyeStyle.outerColor ?: design.palette.foreground)
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize

        val funcType = design.veilFunctionStyle.functionType
        val dataStyle = design.veilFunctionStyle.dataStyle
        val dataColor = design.veilFunctionStyle.dataColor
        val circleColor = design.veilFunctionStyle.circleColor

        val dataHex = hexColor(dataColor)
        val dataAlpha = String.format(Locale.US, "%.2f", colorAlpha(dataColor))
        val circleHex = hexColor(circleColor)
        val circleAlpha = String.format(Locale.US, "%.2f", colorAlpha(circleColor))

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 $twStr $thStr" width="100%" height="100%">""").append("\n")

        if (hasCornerClip) {
            sb.append("  <defs>\n")
            sb.append("""    <clipPath id="rounded-corners"><rect width="$twStr" height="$thStr" rx="$crStr" ry="$crStr"/></clipPath>""").append("\n")
            sb.append("  </defs>\n")
            sb.append("""  <g clip-path="url(#rounded-corners)">""").append("\n")
        }

        sb.append("""  <rect width="$twStr" height="$thStr" fill="$bgHex" opacity="$bgAlphaStr" />""").append("\n")

        if (hasBackdropImg) {
            val preprocessedBackdrop = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = design.backdropStyle.image!!,
                canvasWidth = totalWidth.toFloat(),
                canvasHeight = totalHeight.toFloat(),
                mode = design.backdropStyle.imageScaleMode
            )
            val biB64 = bitmapToBase64(preprocessedBackdrop)
            val biAlpha = formatOpacity(design.backdropStyle.imageAlpha)
            sb.append("""  <image key="bi" opacity="$biAlpha" xlink:href="data:image/png;base64,$biB64" width="$twStr" height="$thStr" x="0" y="0"/>""").append("\n")
        }

        var id = 0

        // Background ring if CIRCLE function + ROUND dataStyle
        if (funcType == VeilFunctionType.CIRCLE && dataStyle == VeilFunctionDataStyle.ROUND) {
            val ringSw = String.format(Locale.US, "%.3f", nCount.toDouble() / 15.0)
            val ringCx = String.format(Locale.US, "%.3f", nCount.toDouble() / 2.0 + qzLeft)
            val ringCy = String.format(Locale.US, "%.3f", nCount.toDouble() / 2.0 + qzTop)
            val ringR = String.format(Locale.US, "%.3f", nCount.toDouble() / 2.0 * kotlin.math.sqrt(2.0) * 13.0 / 40.0)
            sb.append("""  <circle opacity="$circleAlpha" key="$id" fill="none" stroke-width="$ringSw" stroke="$circleHex" cx="$ringCx" cy="$ringCy" r="$ringR"/>""").append("\n")
            id++
        }

        // Finders
        val finderCenters = listOf(Pair(3, 3), Pair(nCount - 4, 3), Pair(3, nCount - 4))
        for ((fx, fy) in finderCenters) {
            val (svgChunk, nextId) = com.veilframe.app.qr.renderer.VeilPositionPatternGeometry.buildSvgElements(
                x = fx,
                y = fy,
                qz = qzLeft,
                style = posStyle,
                size = posSize,
                colorHex = posColorHex,
                alpha = posAlpha,
                idStart = id,
                qzLeft = qzLeft,
                qzTop = qzTop
            )
            id = nextId
            sb.append(svgChunk)
        }

        val centerCoord = (nCount - 1).toDouble() / 2.0
        val maxDist = (nCount.toDouble() / 2.0) * kotlin.math.sqrt(2.0)

        for (x in 0 until nCount) {
            for (y in 0 until nCount) {
                if (com.veilframe.app.qr.renderer.VeilPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                val isDark = matrix.isDark(x, y)
                val dist = kotlin.math.sqrt(Math.pow(centerCoord - x, 2.0) + Math.pow(centerCoord - y, 2.0)) / maxDist
                val ax = x + qzLeft
                val ay = y + qzTop

                when (funcType) {
                    VeilFunctionType.FADE -> {
                        val sizeF = (1.0 - kotlin.math.cos(Math.PI * dist)) / 6.0 + 1.0 / 5.0
                        if (isDark) {
                            when (dataStyle) {
                                VeilFunctionDataStyle.RECTANGLE -> {
                                    val rectSize = sizeF + 0.2
                                    val rsStr = String.format(Locale.US, "%.3f", rectSize)
                                    val rx = String.format(Locale.US, "%.3f", ax + (1.0 - rectSize) / 2.0)
                                    val ry = String.format(Locale.US, "%.3f", ay + (1.0 - rectSize) / 2.0)
                                    sb.append("""  <rect opacity="$dataAlpha" width="$rsStr" height="$rsStr" key="$id" fill="$dataHex" x="$rx" y="$ry"/>""").append("\n")
                                    id++
                                }
                                VeilFunctionDataStyle.ROUND -> {
                                    val rStr = String.format(Locale.US, "%.3f", sizeF)
                                    val cx = String.format(Locale.US, "%.3f", ax + 0.5)
                                    val cy = String.format(Locale.US, "%.3f", ay + 0.5)
                                    sb.append("""  <circle opacity="$dataAlpha" r="$rStr" key="$id" fill="$dataHex" cx="$cx" cy="$cy"/>""").append("\n")
                                    id++
                                }
                            }
                        }
                    }
                    VeilFunctionType.CIRCLE -> {
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
                            sizeF = if (dataStyle == VeilFunctionDataStyle.RECTANGLE) 0.15 else 0.25
                        }

                        if (pointVisible) {
                            when (dataStyle) {
                                VeilFunctionDataStyle.RECTANGLE -> {
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
                                VeilFunctionDataStyle.ROUND -> {
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

        appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)

        if (hasCornerClip) {
            sb.append("  </g>\n")
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun generateLineSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        qzRight: Double,
        qzBottom: Double
    ): String {
        val totalWidth = matrix.size + qzLeft + qzRight
        val totalHeight = matrix.size + qzTop + qzBottom
        val normGeometry = com.veilframe.app.qr.model.QrGeometry.fromDesign(matrix.size, totalWidth.toFloat(), totalHeight.toFloat(), design)
        val rawIr = com.veilframe.app.qr.renderer.LineRenderer().generateGeometry(matrix, design, normGeometry)
        val ir = applyBackdropToIr(rawIr, design, totalWidth.toFloat(), totalHeight.toFloat())
        val svg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        return if (design.logo?.bitmap != null) {
            val bgHex = toSvgColor(design.backdropStyle.color ?: design.palette.background).hex
            val closeTag = if (design.backdropStyle.cornerRadius > 0f) "</g>\n</svg>" else "</svg>"
            val prefix = if (svg.endsWith(closeTag)) svg.removeSuffix(closeTag) else svg.removeSuffix("</svg>")
            val sb = StringBuilder(prefix)
            appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)
            sb.append(if (design.backdropStyle.cornerRadius > 0f) "  </g>\n</svg>" else "</svg>")
            sb.toString()
        } else {
            svg
        }
    }

    private fun generateBubbleSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        qzRight: Double,
        qzBottom: Double
    ): String {
        val totalWidth = matrix.size + qzLeft + qzRight
        val totalHeight = matrix.size + qzTop + qzBottom
        val normGeometry = com.veilframe.app.qr.model.QrGeometry.fromDesign(matrix.size, totalWidth.toFloat(), totalHeight.toFloat(), design)
        val rawIr = com.veilframe.app.qr.renderer.BubbleRenderer().generateGeometry(matrix, design, normGeometry)
        val ir = applyBackdropToIr(rawIr, design, totalWidth.toFloat(), totalHeight.toFloat())
        val svg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        return if (design.logo?.bitmap != null) {
            val bgHex = toSvgColor(design.backdropStyle.color ?: design.palette.background).hex
            val closeTag = if (design.backdropStyle.cornerRadius > 0f) "</g>\n</svg>" else "</svg>"
            val prefix = if (svg.endsWith(closeTag)) svg.removeSuffix(closeTag) else svg.removeSuffix("</svg>")
            val sb = StringBuilder(prefix)
            appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)
            sb.append(if (design.backdropStyle.cornerRadius > 0f) "  </g>\n</svg>" else "</svg>")
            sb.toString()
        } else {
            svg
        }
    }

    private fun generateRandomRectangleSvg(
        matrix: QrMatrix,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        qzRight: Double,
        qzBottom: Double
    ): String {
        val totalWidth = matrix.size + qzLeft + qzRight
        val totalHeight = matrix.size + qzTop + qzBottom
        val normGeometry = com.veilframe.app.qr.model.QrGeometry.fromDesign(matrix.size, totalWidth.toFloat(), totalHeight.toFloat(), design)
        val rawIr = com.veilframe.app.qr.renderer.RandomRectangleRenderer().generateGeometry(matrix, design, normGeometry)
        val ir = applyBackdropToIr(rawIr, design, totalWidth.toFloat(), totalHeight.toFloat())
        val svg = com.veilframe.app.qr.geometry.IrSvgRenderer.render(ir)
        return if (design.logo?.bitmap != null) {
            val bgHex = toSvgColor(design.backdropStyle.color ?: design.palette.background).hex
            val closeTag = if (design.backdropStyle.cornerRadius > 0f) "</g>\n</svg>" else "</svg>"
            val prefix = if (svg.endsWith(closeTag)) svg.removeSuffix(closeTag) else svg.removeSuffix("</svg>")
            val sb = StringBuilder(prefix)
            appendLogo(sb, design, matrix.size, qzLeft, qzTop, bgHex)
            sb.append(if (design.backdropStyle.cornerRadius > 0f) "  </g>\n</svg>" else "</svg>")
            sb.toString()
        } else {
            svg
        }
    }


    private fun appendFinders(
        sb: StringBuilder,
        design: QrDesign,
        qz: Double,
        matrixSize: Int,
        fgHex: String,
        bgHex: String,
        eyeOuterHex: String,
        eyeInnerHex: String
    ) {
        appendFinders(sb, design, qz, qz, matrixSize, fgHex, bgHex, eyeOuterHex, eyeInnerHex)
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
        appendFinders(sb, design, qz.toDouble(), qz.toDouble(), matrixSize, fgHex, bgHex, eyeOuterHex, eyeInnerHex)
    }

    private fun appendFinders(
        sb: StringBuilder,
        design: QrDesign,
        qzLeft: Int,
        qzTop: Int,
        matrixSize: Int,
        fgHex: String,
        bgHex: String,
        eyeOuterHex: String,
        eyeInnerHex: String
    ) {
        appendFinders(sb, design, qzLeft.toDouble(), qzTop.toDouble(), matrixSize, fgHex, bgHex, eyeOuterHex, eyeInnerHex)
    }

    private fun appendFinders(
        sb: StringBuilder,
        design: QrDesign,
        qzLeft: Double,
        qzTop: Double,
        matrixSize: Int,
        fgHex: String,
        bgHex: String,
        eyeOuterHex: String,
        eyeInnerHex: String
    ) {
        val finders = listOf(
            Pair(qzLeft, qzTop),
            Pair(qzLeft, qzTop + matrixSize - 7),
            Pair(qzLeft + matrixSize - 7, qzTop)
        )

        val isHollowFinder = (design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE && design.resampleStyle.hasBackdrop) || colorAlphaInt(design.palette.background) == 0

        for ((fx, fy) in finders) {
            val cx = fx + 3.5
            val cy = fy + 3.5
            val cxStr = formatCoord(cx)
            val cyStr = formatCoord(cy)
            val fxStr = formatCoord(fx)
            val fyStr = formatCoord(fy)

            when (design.eyeStyle.style) {
                FinderStyle.CIRCLE -> {
                    sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="3.5" fill="$eyeOuterHex" />""").append("\n")
                    if (!isHollowFinder) {
                        sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="2.5" fill="$bgHex" />""").append("\n")
                    }
                    sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                }
                FinderStyle.ROUNDED -> {
                    val posSize = design.positionSize.toDouble()
                    val strokeW = 100.0 / 6.0 * posSize
                    val tx = formatCoord(cx - 3.0)
                    val ty = formatCoord(cy - 3.0)
                    sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                    sb.append("""  <path d="${VeilPositionPatternGeometry.SQ25_PATH}" stroke="$eyeOuterHex" stroke-width="$strokeW" fill="none" transform="translate($tx,$ty) scale(0.06,0.06)" />""").append("\n")
                }
                FinderStyle.SOFT -> {
                    if (isHollowFinder) {
                        val fx05 = formatCoord(fx + 0.5)
                        val fy05 = formatCoord(fy + 0.5)
                        sb.append("""  <rect x="$fx05" y="$fy05" width="6" height="6" rx="1.5" fill="none" stroke="$eyeOuterHex" stroke-width="1" />""").append("\n")
                        sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                    } else {
                        val fx1 = formatCoord(fx + 1.0)
                        val fy1 = formatCoord(fy + 1.0)
                        sb.append("""  <rect x="$fxStr" y="$fyStr" width="7" height="7" rx="1.5" fill="$eyeOuterHex" />""").append("\n")
                        sb.append("""  <rect x="$fx1" y="$fy1" width="5" height="5" rx="0.8" fill="$bgHex" />""").append("\n")
                        sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                    }
                }
                FinderStyle.FRAME -> {
                    val fx04 = formatCoord(fx + 0.4)
                    val fy04 = formatCoord(fy + 0.4)
                    sb.append("""  <rect x="$fx04" y="$fy04" width="6.2" height="6.2" rx="1" fill="none" stroke="$eyeOuterHex" stroke-width="0.8" />""").append("\n")
                    val pts = "$cxStr,${formatCoord(cy - 1.5)} ${formatCoord(cx + 1.5)},$cyStr $cxStr,${formatCoord(cy + 1.5)} ${formatCoord(cx - 1.5)},$cyStr"
                    sb.append("""  <polygon points="$pts" fill="$eyeInnerHex" />""").append("\n")
                }
                FinderStyle.PLANETS -> {
                    sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="1.5" fill="$eyeInnerHex" />""").append("\n")
                    sb.append("""  <circle cx="$cxStr" cy="$cyStr" r="3" fill="none" stroke="$eyeOuterHex" stroke-width="0.15" stroke-dasharray="0.5,0.5" />""").append("\n")
                    val planetRadius = 0.5 * design.positionSize
                    sb.append("""  <circle cx="${formatCoord(cx - 3.0)}" cy="$cyStr" r="$planetRadius" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="${formatCoord(cx + 3.0)}" cy="$cyStr" r="$planetRadius" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="$cxStr" cy="${formatCoord(cy - 3.0)}" r="$planetRadius" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <circle cx="$cxStr" cy="${formatCoord(cy + 3.0)}" r="$planetRadius" fill="$eyeOuterHex" />""").append("\n")
                }
                FinderStyle.DSJ -> {
                    val posSize = design.positionSize.toDouble()
                    val widthVal = 2.0 + posSize
                    val armDim = posSize
                    val halfW = widthVal / 2.0
                    val halfArm = armDim / 2.0
                    val wStr = formatCoord(widthVal)
                    val aStr = formatCoord(armDim)
                    sb.append("""  <rect x="${formatCoord(cx - halfW)}" y="${formatCoord(cy - halfW)}" width="$wStr" height="$wStr" fill="$eyeInnerHex" />""").append("\n")
                    sb.append("""  <rect x="${formatCoord(cx - 3.0 - halfArm)}" y="${formatCoord(cy - halfW)}" width="$aStr" height="$wStr" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${formatCoord(cx + 3.0 - halfArm)}" y="${formatCoord(cy - halfW)}" width="$aStr" height="$wStr" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${formatCoord(cx - halfW)}" y="${formatCoord(cy - 3.0 - halfArm)}" width="$wStr" height="$aStr" fill="$eyeOuterHex" />""").append("\n")
                    sb.append("""  <rect x="${formatCoord(cx - halfW)}" y="${formatCoord(cy + 3.0 - halfArm)}" width="$wStr" height="$aStr" fill="$eyeOuterHex" />""").append("\n")
                }
                else -> {
                    if (isHollowFinder) {
                        val fx05 = formatCoord(fx + 0.5)
                        val fy05 = formatCoord(fy + 0.5)
                        val fx2 = formatCoord(fx + 2.0)
                        val fy2 = formatCoord(fy + 2.0)
                        sb.append("""  <rect x="$fx05" y="$fy05" width="6" height="6" fill="none" stroke="$eyeOuterHex" stroke-width="1" rx="0.5" />""").append("\n")
                        sb.append("""  <rect x="$fx2" y="$fy2" width="3" height="3" fill="$eyeInnerHex" rx="0.2" />""").append("\n")
                    } else {
                        val fx1 = formatCoord(fx + 1.0)
                        val fy1 = formatCoord(fy + 1.0)
                        val fx2 = formatCoord(fx + 2.0)
                        val fy2 = formatCoord(fy + 2.0)
                        sb.append("""  <rect x="$fxStr" y="$fyStr" width="7" height="7" fill="$eyeOuterHex" rx="0.5" />""").append("\n")
                        sb.append("""  <rect x="$fx1" y="$fy1" width="5" height="5" fill="$bgHex" rx="0.3" />""").append("\n")
                        sb.append("""  <rect x="$fx2" y="$fy2" width="3" height="3" fill="$eyeInnerHex" rx="0.2" />""").append("\n")
                    }
                }
            }
        }
    }

    private fun appendLogo(sb: StringBuilder, design: QrDesign, totalSize: Int, bgHex: String) {
        appendLogo(sb, design, totalSize, 0.0, 0.0, bgHex)
    }

    private fun appendLogo(
        sb: StringBuilder,
        design: QrDesign,
        totalWidth: Double,
        totalHeight: Double,
        bgHex: String
    ) {
        val minDim = minOf(totalWidth, totalHeight)
        val qzX = (totalWidth - minDim) / 2.0
        val qzY = (totalHeight - minDim) / 2.0
        appendLogo(sb, design, minDim.toInt(), qzX, qzY, bgHex)
    }

    private fun appendLogo(
        sb: StringBuilder,
        design: QrDesign,
        matrixSize: Int,
        qzLeft: Double,
        qzTop: Double,
        bgHex: String
    ) {
        com.veilframe.app.qr.geometry.VeilIconPipeline.appendIconSvg(
            sb = sb,
            design = design,
            ox = qzLeft,
            oy = qzTop,
            qrPixelSize = matrixSize.toDouble(),
            bgHex = bgHex
        )
    }

    fun applyBackdropToIr(
        rawIr: QrGeometryIr,
        design: QrDesign,
        totalWidth: Float,
        totalHeight: Float
    ): QrGeometryIr {
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val bgHex = toSvgColor(resolvedBackdropColor).hex
        val bgAlpha = colorAlpha(resolvedBackdropColor)
        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crStr = formatCornerRadius(design.backdropStyle.cornerRadius)
        val hasBackdropImg = design.backdropStyle.image != null

        val twStr = formatCoord(totalWidth.toDouble())
        val thStr = formatCoord(totalHeight.toDouble())
        val newDefs = rawIr.defs.toMutableList()
        if (hasCornerClip) {
            newDefs.add("""<clipPath id="rounded-corners"><rect width="$twStr" height="$thStr" rx="$crStr" ry="$crStr"/></clipPath>""")
        }

        val backdropNodes = mutableListOf<QrGeometryNode>()
        // Outer backdrop rectangle
        backdropNodes.add(
            RectNode(
                x = 0f,
                y = 0f,
                width = totalWidth,
                height = totalHeight,
                fillString = bgHex,
                opacity = bgAlpha,
                alwaysEmitOpacity = true
            )
        )

        // Backdrop image if present
        if (hasBackdropImg) {
            val preprocessed = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = design.backdropStyle.image!!,
                canvasWidth = totalWidth,
                canvasHeight = totalHeight,
                mode = design.backdropStyle.imageScaleMode
            )
            val base64 = bitmapToBase64(preprocessed)
            backdropNodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = totalWidth,
                    height = totalHeight,
                    base64Data = base64,
                    opacity = design.backdropStyle.imageAlpha,
                    key = "bi",
                    preserveAspectRatio = ""
                )
            )
        }

        // Filter out any existing canvas background rect to avoid duplicate backdrop
        val styleNodes = rawIr.rootNodes.filterNot { node ->
            node is RectNode && node.x == 0f && node.y == 0f &&
                node.width == totalWidth && node.height == totalHeight &&
                node.rx == 0f
        }
        backdropNodes.addAll(styleNodes)

        val cornerPath = if (hasCornerClip) {
            val cr = maxOf(0f, design.backdropStyle.cornerRadius)
            android.graphics.Path().apply {
                addRoundRect(0f, 0f, totalWidth, totalHeight, cr, cr, android.graphics.Path.Direction.CW)
            }
        } else null

        val finalNodes = if (hasCornerClip) {
            listOf(
                GroupNode(
                    children = backdropNodes,
                    clipPathId = "rounded-corners",
                    clipPath = cornerPath
                )
            )
        } else {
            backdropNodes
        }

        val updatedMasks = if (hasCornerClip && cornerPath != null) {
            rawIr.masks + ("rounded-corners" to com.veilframe.app.qr.geometry.QrMaskDefinition("rounded-corners", clipPath = cornerPath))
        } else {
            rawIr.masks
        }

        return rawIr.copy(
            width = totalWidth,
            height = totalHeight,
            viewBox = com.veilframe.app.qr.geometry.QrGeometryIr.defaultViewBox(totalWidth, totalHeight),
            defs = newDefs,
            masks = updatedMasks,
            rootNodes = finalNodes
        )
    }

    fun formatCornerRadius(radius: Float): String {
        val r = maxOf(0f, radius)
        return if (r % 1f == 0f) {
            r.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
        }
    }

    fun formatOpacity(alpha: Float): String {
        val clamped = alpha.coerceIn(0f, 1f)
        if (clamped >= 1f) return "1"
        if (clamped <= 0f) return "0"
        return String.format(Locale.US, "%.4f", clamped).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
