package com.veilframe.app.qr.geometry

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.ShapeGeometry
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry
import java.util.Locale
import kotlin.random.Random

/**
 * Builds a unified, authoritative [QrGeometryIr] representation for [com.veilframe.app.qr.QrStyle.BASIC].
 *
 * Implements EFQRCode 7.0.3 SVG document generation semantics:
 * 1. Document Backdrop (Full BackgroundStyle support: Solid, LinearGradient, RadialGradient, Image, Transparent)
 * 2. Position Patterns (Canonical EF 3x3 inner / 6x6 outer geometry via [VeilPositionPatternGeometry])
 * 3. Timing Patterns (Role-aware shape, scale, and color with EF profile support)
 * 4. Alignment Patterns (Role-aware shape, scale, and color with EF profile support)
 * 5. Format & Version Information (Profile-driven: EF data-style parity or VeilFrame forced-square safety)
 * 6. Data Modules (Role-aware shape, backend-neutral gradient fill, EF size/4 corner radius)
 * 7. Logo Artwork (Base64 embedded vector/bitmap node via [VeilIconPipeline])
 *
 * Provides a single mathematical source of truth across Android Canvas rasterization
 * and SVG vector emission (H-05 / P3.2).
 */
object BasicGeometryBuilder {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        profile: BasicGeometryProfile = design.basicProfile
    ): QrGeometryIr {
        val nCount = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val totalWidth = geometry.outputWidthFloat
        val totalHeight = geometry.outputHeightFloat

        val nodes = mutableListOf<QrGeometryNode>()
        val defs = mutableListOf<String>()
        val masks = mutableMapOf<String, QrMaskDefinition>()

        val rng = Random(design.effects.seed)

        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crStr = SvgExporter.formatCornerRadius(design.backdropStyle.cornerRadius)
        val twStr = SvgExporter.formatCoord(totalWidth.toDouble())
        val thStr = SvgExporter.formatCoord(totalHeight.toDouble())

        val hasGradient = (design.palette.gradientType != GradientType.NONE ||
            design.moduleStyle.fill == ModuleFill.LINEAR_GRADIENT ||
            design.moduleStyle.fill == ModuleFill.RADIAL_GRADIENT) &&
            design.palette.gradientStart != null && design.palette.gradientEnd != null
        val isRadial = design.palette.gradientType == GradientType.RADIAL ||
            design.moduleStyle.fill == ModuleFill.RADIAL_GRADIENT
        val isMasked = design.moduleStyle.fill == ModuleFill.IMAGE_MASKED

        if (hasCornerClip) {
            defs.add("""<clipPath id="rounded-corners"><rect width="$twStr" height="$thStr" rx="$crStr" ry="$crStr"/></clipPath>""")
        }

        val dataGeomFill: GeometryFill? = if (hasGradient) {
            val gradStart = design.palette.gradientStart!!
            val gradEnd = design.palette.gradientEnd!!
            val startStop = formatStop("0%", gradStart)
            val endStop = formatStop("100%", gradEnd)
            val cx = totalWidth / 2f
            val cy = totalHeight / 2f
            val radius = maxOf(totalWidth, totalHeight) / 2f
            val cxStr = SvgExporter.formatCoord(cx.toDouble())
            val cyStr = SvgExporter.formatCoord(cy.toDouble())
            val rStr = SvgExporter.formatCoord(radius.toDouble())

            if (isRadial) {
                defs.add("""<radialGradient id="qrGrad" gradientUnits="userSpaceOnUse" cx="$cxStr" cy="$cyStr" r="$rStr">$startStop$endStop</radialGradient>""")
                GeometryFill.RadialGradient(
                    centerColor = gradStart,
                    edgeColor = gradEnd,
                    cx = cx,
                    cy = cy,
                    radius = radius
                )
            } else {
                defs.add("""<linearGradient id="qrGrad" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="$twStr" y2="$thStr">$startStop$endStop</linearGradient>""")
                GeometryFill.LinearGradient(
                    startColor = gradStart,
                    endColor = gradEnd,
                    x0 = 0f,
                    y0 = 0f,
                    x1 = totalWidth,
                    y1 = totalHeight
                )
            }
        } else null

        if (isMasked) {
            val maskSb = StringBuilder()
            maskSb.append("""<mask id="qrDataMask"><rect width="$twStr" height="$thStr" fill="black" />""")
            if (design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER) {
                val clusters = com.veilframe.app.qr.renderer.BubbleClusterEngine.computeClusters(matrix, design)
                val qzLeft = geometry.offsetX / cs
                val qzTop = geometry.offsetY / cs
                for (cluster in clusters) {
                    if (cluster.isAmbient) continue
                    val cx = cluster.cx + qzLeft
                    val cy = cluster.cy + qzTop
                    val r = cluster.radius
                    maskSb.append("""<circle cx="$cx" cy="$cy" r="$r" fill="white" />""")
                    if (cluster.hasInnerDot && cluster.innerRadius > 0f) {
                        maskSb.append("""<circle cx="$cx" cy="$cy" r="${cluster.innerRadius}" fill="white" />""")
                    }
                }
            } else {
                val maskScale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f).toDouble()
                val maskOffset = (1.0 - maskScale) / 2.0
                val qzLeft = (geometry.offsetX / cs).toDouble()
                val qzTop = (geometry.offsetY / cs).toDouble()
                for (col in 0 until matrix.size) {
                    for (row in 0 until matrix.size) {
                        if (!matrix.isDark(col, row)) continue
                        if (design.imageSource.scope == com.veilframe.app.qr.model.ImageMaskScope.DATA_ONLY && matrix.isProtected(col, row)) continue
                        val module = matrix.moduleAt(col, row)
                        val mx = col + qzLeft + maskOffset
                        val my = row + qzTop + maskOffset
                        val cx = col + qzLeft + 0.5
                        val cy = row + qzTop + 0.5
                        val elem = ShapeGeometry.buildSvgElement(
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
                        maskSb.append(elem)
                    }
                }
            }
            maskSb.append("</mask>")
            defs.add(maskSb.toString())
        }

        // 1. BACKDROP: Output canvas background rectangle & image (all BackgroundStyle variants)
        appendBackdrop(nodes, defs, design, geometry)

        // 2. FINDERS: Canonical position patterns
        appendFinders(nodes, nCount, cs, ox, oy, design, profile)

        // 3. TIMING: Timing pattern modules
        appendTiming(nodes, matrix, nCount, geometry, design, rng, profile)

        // 4. ALIGNMENT: Alignment pattern modules
        appendAlignment(nodes, matrix, nCount, geometry, design, rng, profile)

        // 5. FORMAT & VERSION: Protected function patterns
        appendFormatAndVersion(nodes, matrix, nCount, geometry, design, profile, rng, hasGradient, dataGeomFill)

        // 6. DATA: Primary QR data modules
        appendData(nodes, matrix, nCount, geometry, design, rng, hasGradient, dataGeomFill, profile)

        // 7. LOGO: Icon node if present
        appendLogo(nodes, defs, masks, design, ox, oy, nCount, cs)

        val cornerPath = if (hasCornerClip) {
            val cr = maxOf(0f, design.backdropStyle.cornerRadius)
            Path().apply {
                addRoundRect(0f, 0f, totalWidth, totalHeight, cr, cr, Path.Direction.CW)
            }
        } else null

        val finalNodes = if (hasCornerClip) {
            listOf(
                GroupNode(
                    children = nodes,
                    clipPathId = "rounded-corners",
                    clipPath = cornerPath
                )
            )
        } else {
            nodes
        }

        if (hasCornerClip && cornerPath != null) {
            masks["rounded-corners"] = QrMaskDefinition("rounded-corners", clipPath = cornerPath)
        }

        return QrGeometryIr(
            width = geometry.outputWidthFloat,
            height = geometry.outputHeightFloat,
            defs = defs,
            masks = masks,
            rootNodes = finalNodes
        )
    }

    private fun appendBackdrop(
        nodes: MutableList<QrGeometryNode>,
        defs: MutableList<String>,
        design: QrDesign,
        geometry: QrGeometry
    ) {
        val tw = geometry.outputWidthFloat
        val th = geometry.outputHeightFloat
        if (tw <= 0f || th <= 0f) return
        val twStr = SvgExporter.formatCoord(tw.toDouble())
        val thStr = SvgExporter.formatCoord(th.toDouble())

        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background

        when (val background = design.background) {
            is BackgroundStyle.Solid -> {
                val effectiveColor = if (design.backdropStyle.color != null) resolvedBackdropColor else background.color
                val bgHex = IrSvgRenderer.colorToHex(effectiveColor)
                val bgAlpha = ((effectiveColor ushr 24) and 0xFF) / 255f
                nodes.add(
                    RectNode(
                        x = 0f,
                        y = 0f,
                        width = tw,
                        height = th,
                        fill = effectiveColor,
                        fillString = bgHex,
                        geometryFill = GeometryFill.Solid(effectiveColor),
                        opacity = bgAlpha,
                        alwaysEmitOpacity = true
                    )
                )
            }
            is BackgroundStyle.LinearGradient -> {
                val startStop = formatStop("0%", background.startColor)
                val endStop = formatStop("100%", background.endColor)
                defs.add("""<linearGradient id="bgGrad" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="$twStr" y2="$thStr">$startStop$endStop</linearGradient>""")
                nodes.add(
                    RectNode(
                        x = 0f,
                        y = 0f,
                        width = tw,
                        height = th,
                        fill = background.startColor,
                        fillString = "url(#bgGrad)",
                        geometryFill = GeometryFill.LinearGradient(
                            startColor = background.startColor,
                            endColor = background.endColor,
                            x0 = 0f,
                            y0 = 0f,
                            x1 = tw,
                            y1 = th
                        )
                    )
                )
            }
            is BackgroundStyle.RadialGradient -> {
                val startStop = formatStop("0%", background.centerColor)
                val endStop = formatStop("100%", background.edgeColor)
                val radius = maxOf(tw, th) / 2f
                val cxStr = SvgExporter.formatCoord((tw / 2f).toDouble())
                val cyStr = SvgExporter.formatCoord((th / 2f).toDouble())
                val rStr = SvgExporter.formatCoord(radius.toDouble())
                defs.add("""<radialGradient id="bgRadGrad" gradientUnits="userSpaceOnUse" cx="$cxStr" cy="$cyStr" r="$rStr">$startStop$endStop</radialGradient>""")
                nodes.add(
                    RectNode(
                        x = 0f,
                        y = 0f,
                        width = tw,
                        height = th,
                        fill = background.centerColor,
                        fillString = "url(#bgRadGrad)",
                        geometryFill = GeometryFill.RadialGradient(
                            centerColor = background.centerColor,
                            edgeColor = background.edgeColor,
                            cx = tw / 2f,
                            cy = th / 2f,
                            radius = radius
                        )
                    )
                )
            }
            is BackgroundStyle.Image -> {
                val bgHex = IrSvgRenderer.colorToHex(resolvedBackdropColor)
                val bgAlpha = ((resolvedBackdropColor ushr 24) and 0xFF) / 255f
                nodes.add(
                    RectNode(
                        x = 0f,
                        y = 0f,
                        width = tw,
                        height = th,
                        fill = resolvedBackdropColor,
                        fillString = bgHex,
                        geometryFill = GeometryFill.Solid(resolvedBackdropColor),
                        opacity = bgAlpha,
                        alwaysEmitOpacity = true
                    )
                )
                if (!background.bitmap.isRecycled) {
                    val scaleMode = if (background.fitCenter) ImageScaleMode.ASPECT_FIT else ImageScaleMode.ASPECT_FILL
                    val preprocessed = EfImagePreprocessor.preprocess(
                        source = background.bitmap,
                        canvasWidth = tw,
                        canvasHeight = th,
                        mode = scaleMode
                    )
                    val base64 = IrSvgRenderer.bitmapToBase64(preprocessed)
                    nodes.add(
                        ImageNode(
                            x = 0f,
                            y = 0f,
                            width = tw,
                            height = th,
                            bitmap = preprocessed,
                            base64Data = base64,
                            opacity = background.alpha.coerceIn(0f, 1f),
                            key = "bgImg",
                            preserveAspectRatio = if (background.fitCenter) "xMidYMid meet" else "xMidYMid slice"
                        )
                    )
                }
            }
            BackgroundStyle.Transparent -> {
                // Transparent background: emit no backdrop rect
            }
        }

        // Draw backdrop image if configured and background is not already BackgroundStyle.Image
        val backdropImg = design.backdropStyle.image
        if (backdropImg != null && !backdropImg.isRecycled && design.background !is BackgroundStyle.Image) {
            val preprocessed = EfImagePreprocessor.preprocess(
                source = backdropImg,
                canvasWidth = tw,
                canvasHeight = th,
                mode = design.backdropStyle.imageScaleMode
            )
            val base64 = IrSvgRenderer.bitmapToBase64(preprocessed)
            nodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = tw,
                    height = th,
                    bitmap = preprocessed,
                    base64Data = base64,
                    opacity = design.backdropStyle.imageAlpha,
                    key = "bi",
                    preserveAspectRatio = ""
                )
            )
        } else if (design.backgroundImage != null && !design.backgroundImage.isRecycled && design.background !is BackgroundStyle.Image) {
            val preprocessed = EfImagePreprocessor.preprocess(
                source = design.backgroundImage,
                canvasWidth = tw,
                canvasHeight = th,
                mode = ImageScaleMode.ASPECT_FILL
            )
            val base64 = IrSvgRenderer.bitmapToBase64(preprocessed)
            nodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = tw,
                    height = th,
                    bitmap = preprocessed,
                    base64Data = base64,
                    opacity = design.backgroundImageAlpha,
                    key = "bgImgBackdrop",
                    preserveAspectRatio = "xMidYMid slice"
                )
            )
        }
    }

    private fun appendFinders(
        nodes: MutableList<QrGeometryNode>,
        nCount: Int,
        cs: Float,
        ox: Float,
        oy: Float,
        design: QrDesign,
        profile: BasicGeometryProfile
    ) {
        val posColor = design.eyeStyle.outerColor ?: design.palette.foreground
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize
        val bgColor = design.backdropStyle.color ?: design.palette.background
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(nCount - 4, 3),
            Pair(3, nCount - 4)
        )
        for ((fx, fy) in finderCenters) {
            nodes.addAll(
                VeilPositionPatternGeometry.toIrNodes(
                    x = fx,
                    y = fy,
                    moduleSize = cs,
                    offsetX = ox,
                    offsetY = oy,
                    style = posStyle,
                    size = posSize,
                    color = posColor,
                    bgColor = bgColor
                )
            )
        }
    }

    private fun appendTiming(
        nodes: MutableList<QrGeometryNode>,
        matrix: QrMatrix,
        nCount: Int,
        geometry: QrGeometry,
        design: QrDesign,
        rng: Random,
        profile: BasicGeometryProfile
    ) {
        if (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite) return

        // Timing shape defaults to SQUARE (model default) and honors explicit customization
        val timingShape = design.timingStyle.shape
        val timingScale = design.timingStyle.scale.coerceIn(0.5f, 1.0f)
        val timingColor = design.timingStyle.color ?: design.timingColor ?: design.palette.foreground

        for (col in 0 until nCount) {
            for (row in 0 until nCount) {
                if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                if (!matrix.isDark(col, row)) continue
                if (VeilPositionPatternGeometry.isFinderArea(col, row, nCount)) continue

                val rect = geometry.moduleRect(col, row, timingScale)
                nodes.add(
                    BasicShapeGeometry.buildNode(
                        shape = timingShape,
                        rect = rect,
                        fill = timingColor,
                        design = design,
                        rng = rng,
                        profile = profile
                    )
                )
            }
        }
    }

    private fun appendAlignment(
        nodes: MutableList<QrGeometryNode>,
        matrix: QrMatrix,
        nCount: Int,
        geometry: QrGeometry,
        design: QrDesign,
        rng: Random,
        profile: BasicGeometryProfile
    ) {
        if (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite) return

        // Alignment shape defaults to SQUARE (model default) and honors explicit customization
        val alignShape = design.alignmentStyle.shape
        val alignScale = design.alignmentStyle.scale.coerceIn(0.5f, 1.0f)
        val alignColor = design.alignmentStyle.color ?: design.alignmentColor ?: design.palette.foreground

        for (col in 0 until nCount) {
            for (row in 0 until nCount) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue
                if (!matrix.isDark(col, row)) continue
                if (VeilPositionPatternGeometry.isFinderArea(col, row, nCount)) continue

                val rect = geometry.moduleRect(col, row, alignScale)
                nodes.add(
                    BasicShapeGeometry.buildNode(
                        shape = alignShape,
                        rect = rect,
                        fill = alignColor,
                        design = design,
                        rng = rng,
                        profile = profile
                    )
                )
            }
        }
    }

    private fun appendFormatAndVersion(
        nodes: MutableList<QrGeometryNode>,
        matrix: QrMatrix,
        nCount: Int,
        geometry: QrGeometry,
        design: QrDesign,
        profile: BasicGeometryProfile,
        rng: Random,
        hasGradient: Boolean,
        dataGeomFill: GeometryFill?
    ) {
        val fgColor = design.palette.foreground
        val dataShape = design.moduleStyle.shape
        val dataScale = if (profile == BasicGeometryProfile.EF_PARITY) {
            design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        } else 1.0f
        val fillString = if (hasGradient && profile == BasicGeometryProfile.EF_PARITY) "url(#qrGrad)" else null
        val geomFill = if (hasGradient && profile == BasicGeometryProfile.EF_PARITY) dataGeomFill else null

        for (col in 0 until nCount) {
            for (row in 0 until nCount) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION) continue
                if (!matrix.isDark(col, row)) continue
                if (VeilPositionPatternGeometry.isFinderArea(col, row, nCount)) continue

                val rect = geometry.moduleRect(col, row, dataScale)
                if (profile == BasicGeometryProfile.EF_PARITY && dataShape != ModuleShape.SQUARE) {
                    // Under EF_PARITY: format & version modules follow EF data style
                    nodes.add(
                        BasicShapeGeometry.buildNode(
                            shape = dataShape,
                            rect = rect,
                            fill = fgColor,
                            design = design,
                            rng = rng,
                            fillString = fillString,
                            geometryFill = geomFill,
                            profile = profile
                        )
                    )
                } else {
                    // Under VEILFRAME (or EF rectangle data): standard forced square blocks
                    val w = rect.right - rect.left
                    val h = rect.bottom - rect.top
                    nodes.add(
                        RectNode(
                            x = rect.left,
                            y = rect.top,
                            width = w,
                            height = h,
                            fill = fgColor,
                            fillString = fillString,
                            geometryFill = geomFill
                        )
                    )
                }
            }
        }
    }

    private fun appendData(
        nodes: MutableList<QrGeometryNode>,
        matrix: QrMatrix,
        nCount: Int,
        geometry: QrGeometry,
        design: QrDesign,
        rng: Random,
        hasGradient: Boolean,
        dataGeomFill: GeometryFill?,
        profile: BasicGeometryProfile
    ) {
        val dataShape = design.moduleStyle.shape
        val dataScale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val dataColor = design.palette.foreground
        val fillString = if (hasGradient) "url(#qrGrad)" else null

        for (col in 0 until nCount) {
            for (row in 0 until nCount) {
                if (matrix.roleAt(col, row) != QrModuleRole.DATA) continue
                if (!matrix.isDark(col, row)) continue
                if (VeilPositionPatternGeometry.isFinderArea(col, row, nCount)) continue

                val rect = geometry.moduleRect(col, row, dataScale)
                nodes.add(
                    BasicShapeGeometry.buildNode(
                        shape = dataShape,
                        rect = rect,
                        fill = dataColor,
                        design = design,
                        rng = rng,
                        fillString = fillString,
                        geometryFill = dataGeomFill,
                        profile = profile
                    )
                )
            }
        }
    }

    private fun appendLogo(
        nodes: MutableList<QrGeometryNode>,
        defs: MutableList<String>,
        masks: MutableMap<String, QrMaskDefinition>,
        design: QrDesign,
        ox: Float,
        oy: Float,
        nCount: Int,
        cs: Float
    ) {
        if (design.logo?.effectiveBitmap != null && !design.logo.effectiveBitmap!!.isRecycled) {
            VeilIconPipeline.appendIconNodes(
                nodes = nodes,
                defs = defs,
                design = design,
                ox = ox,
                oy = oy,
                qrPixelSize = nCount * cs,
                masks = masks
            )
        }
    }

    private fun formatStop(offset: String, color: Int): String {
        val hex = String.format(Locale.US, "#%06X", 0xFFFFFF and color)
        val alpha = ((color ushr 24) and 0xFF) / 255f
        val opacityAttr = if (alpha < 1.0f) {
            val opStr = String.format(Locale.US, "%.4f", alpha).trimEnd('0').trimEnd('.')
            """ stop-opacity="$opStr""""
        } else ""
        return """<stop offset="$offset" stop-color="$hex"$opacityAttr />"""
    }
}

/**
 * Backend-neutral geometry node factory for individual QR module shapes.
 * Computes exact mathematical representations for both Android Canvas and SVG.
 */
object BasicShapeGeometry {

    /**
     * EFQRCode 7.0.3 corner radius formula for roundedRectangle: `size / 4.0`.
     */
    fun efRoundedRadius(rect: RectF): Float = minOf(rect.right - rect.left, rect.bottom - rect.top) / 4f

    fun buildNode(
        shape: ModuleShape,
        rect: RectF,
        fill: Int,
        design: QrDesign,
        rng: Random? = null,
        fillString: String? = null,
        geometryFill: GeometryFill? = null,
        profile: BasicGeometryProfile = BasicGeometryProfile.VEILFRAME
    ): QrGeometryNode {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top

        return when (shape) {
            ModuleShape.NONE -> {
                RectNode(x = rect.left, y = rect.top, width = 0f, height = 0f, fill = null, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.SQUARE -> {
                RectNode(x = rect.left, y = rect.top, width = w, height = h, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.CIRCLE -> {
                CircleNode(cx = cx, cy = cy, radius = w / 2f, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.DOT -> {
                CircleNode(cx = cx, cy = cy, radius = (w / 2f) * 0.75f, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.ROUNDED -> {
                val rx = if (profile == BasicGeometryProfile.EF_PARITY) {
                    efRoundedRadius(rect)
                } else {
                    w * design.moduleStyle.cornerRadiusFraction.coerceIn(0.1f, 0.5f)
                }
                RectNode(x = rect.left, y = rect.top, width = w, height = h, rx = rx, ry = rx, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.ORGANIC -> {
                // EF-compatible deterministic randomRound: in EFQRCode 7.0.3, randomRound computes
                // radius = (width / 2.0) * Double.random(in: 0.33..<1.0) using unseeded Swift RNG.
                // For cross-backend determinism, VeilFrame uses seeded RNG or stable default 0.85f.
                val factor = rng?.let { it.nextDouble(0.33, 1.0).toFloat() } ?: 0.85f
                CircleNode(cx = cx, cy = cy, radius = (w / 2f) * factor, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.SQUIRCLE -> {
                val d = ShapeGeometry.buildSquircleSvgD(rect.left.toDouble(), rect.top.toDouble(), w.toDouble(), h.toDouble())
                val path = QrVisualGeometry.createSquirclePath(rect)
                PathNode(svgPathData = d, androidPath = path, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.DIAMOND -> {
                val pts = listOf(
                    Pair(cx, rect.top),
                    Pair(rect.right, cy),
                    Pair(cx, rect.bottom),
                    Pair(rect.left, cy)
                )
                PolygonNode(
                    points = pts.joinToString(" ") { String.format(Locale.US, "%.3f,%.3f", it.first, it.second) },
                    pointsList = pts,
                    fill = fill,
                    fillString = fillString,
                    geometryFill = geometryFill
                )
            }
            ModuleShape.HEX -> {
                val pts = listOf(
                    Pair(rect.left + w * 0.25f, rect.top),
                    Pair(rect.left + w * 0.75f, rect.top),
                    Pair(rect.right, cy),
                    Pair(rect.left + w * 0.75f, rect.bottom),
                    Pair(rect.left + w * 0.25f, rect.bottom),
                    Pair(rect.left, cy)
                )
                PolygonNode(
                    points = pts.joinToString(" ") { String.format(Locale.US, "%.3f,%.3f", it.first, it.second) },
                    pointsList = pts,
                    fill = fill,
                    fillString = fillString,
                    geometryFill = geometryFill
                )
            }
            ModuleShape.STAR -> {
                val outerR = w / 2f
                val innerR = outerR * 0.45f
                val pts = (0 until 10).map { i ->
                    val r = if (i % 2 == 0) outerR else innerR
                    val angle = -Math.PI / 2.0 + (i * Math.PI / 5.0)
                    val px = cx + (r * Math.cos(angle)).toFloat()
                    val py = cy + (r * Math.sin(angle)).toFloat()
                    Pair(px, py)
                }
                PolygonNode(
                    points = pts.joinToString(" ") { String.format(Locale.US, "%.3f,%.3f", it.first, it.second) },
                    pointsList = pts,
                    fill = fill,
                    fillString = fillString,
                    geometryFill = geometryFill
                )
            }
            ModuleShape.BUBBLE -> {
                val rx = w * 0.42f
                val ry = h * 0.42f
                RectNode(x = rect.left, y = rect.top, width = w, height = h, rx = rx, ry = rx, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.PILL -> {
                val rx = w / 2f
                val ry = h * 0.25f
                RectNode(x = rect.left, y = cy - ry, width = w, height = ry * 2f, rx = rx, ry = ry, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
            ModuleShape.CONNECTED, ModuleShape.LINE, ModuleShape.BUBBLE_CLUSTER, ModuleShape.CUSTOM -> {
                val rx = w * 0.15f
                RectNode(x = rect.left, y = rect.top, width = w, height = h, rx = rx, ry = rx, fill = fill, fillString = fillString, geometryFill = geometryFill)
            }
        }
    }
}
