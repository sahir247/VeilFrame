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
 * 1. Document Backdrop (Background color / quiet zone margin & corner clipping)
 * 2. Position Patterns (Canonical EF 3x3 inner / 6x6 outer geometry via [VeilPositionPatternGeometry])
 * 3. Timing Patterns (Role-aware shape, scale, and color)
 * 4. Alignment Patterns (Role-aware shape, scale, and color)
 * 5. Format & Version Information (Forced square modules per ISO/IEC 18004 and EF spec)
 * 6. Data Modules (Role-aware shape, scale, corner radius, and stochastic/seeded randomness)
 * 7. Logo Artwork (Base64 embedded vector/bitmap node via [VeilIconPipeline])
 *
 * Provides a single mathematical source of truth across Android Canvas rasterization
 * and SVG vector emission (H-05 / P3.2).
 */
object BasicGeometryBuilder {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
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
        if (hasGradient) {
            val gradStartHex = String.format(Locale.US, "#%06X", 0xFFFFFF and design.palette.gradientStart!!)
            val gradEndHex = String.format(Locale.US, "#%06X", 0xFFFFFF and design.palette.gradientEnd!!)
            if (isRadial) {
                defs.add("""<radialGradient id="qrGrad" cx="50%" cy="50%" r="50%"><stop offset="0%" stop-color="$gradStartHex" /><stop offset="100%" stop-color="$gradEndHex" /></radialGradient>""")
            } else {
                defs.add("""<linearGradient id="qrGrad" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" stop-color="$gradStartHex" /><stop offset="100%" stop-color="$gradEndHex" /></linearGradient>""")
            }
        }
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

        // 1. BACKDROP: Output canvas background rectangle & image
        appendBackdrop(nodes, design, geometry)

        // 2. FINDERS: Canonical position patterns
        appendFinders(nodes, nCount, cs, ox, oy, design)

        // 3. TIMING: Timing pattern modules
        appendTiming(nodes, matrix, nCount, geometry, design, rng)

        // 4. ALIGNMENT: Alignment pattern modules
        appendAlignment(nodes, matrix, nCount, geometry, design, rng)

        // 5. FORMAT & VERSION: Protected function patterns (strictly forced square per specification)
        appendFormatAndVersion(nodes, matrix, nCount, geometry, design)

        // 6. DATA: Primary QR data modules
        appendData(nodes, matrix, nCount, geometry, design, rng, hasGradient)

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
        design: QrDesign,
        geometry: QrGeometry
    ) {
        val tw = geometry.outputWidthFloat
        val th = geometry.outputHeightFloat
        if (tw <= 0f || th <= 0f) return

        val bgColor = design.backdropStyle.color ?: design.palette.background
        val bgHex = IrSvgRenderer.colorToHex(bgColor)
        val bgAlpha = ((bgColor ushr 24) and 0xFF) / 255f

        nodes.add(
            RectNode(
                x = 0f,
                y = 0f,
                width = tw,
                height = th,
                fill = bgColor,
                fillString = bgHex,
                opacity = bgAlpha,
                alwaysEmitOpacity = true
            )
        )

        if (design.backdropStyle.image != null) {
            val preprocessed = EfImagePreprocessor.preprocess(
                source = design.backdropStyle.image!!,
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
        }
    }

    private fun appendFinders(
        nodes: MutableList<QrGeometryNode>,
        nCount: Int,
        cs: Float,
        ox: Float,
        oy: Float,
        design: QrDesign
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
        rng: Random
    ) {
        if (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite) return

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
                        rng = rng
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
        rng: Random
    ) {
        if (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite) return

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
                        rng = rng
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
        design: QrDesign
    ) {
        val fgColor = design.palette.foreground

        for (col in 0 until nCount) {
            for (row in 0 until nCount) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION) continue
                if (!matrix.isDark(col, row)) continue
                if (VeilPositionPatternGeometry.isFinderArea(col, row, nCount)) continue

                // Format & Version patterns MUST remain standard square blocks
                val rect = geometry.moduleRect(col, row, 1.0f)
                val w = rect.right - rect.left
                val h = rect.bottom - rect.top
                nodes.add(
                    RectNode(
                        x = rect.left,
                        y = rect.top,
                        width = w,
                        height = h,
                        fill = fgColor
                    )
                )
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
        hasGradient: Boolean
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
                        fillString = fillString
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
}

/**
 * Backend-neutral geometry node factory for individual QR module shapes.
 * Computes exact mathematical representations for both Android Canvas and SVG.
 */
object BasicShapeGeometry {

    fun buildNode(
        shape: ModuleShape,
        rect: RectF,
        fill: Int,
        design: QrDesign,
        rng: Random? = null,
        fillString: String? = null
    ): QrGeometryNode {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top

        return when (shape) {
            ModuleShape.NONE -> {
                RectNode(x = rect.left, y = rect.top, width = 0f, height = 0f, fill = null, fillString = fillString)
            }
            ModuleShape.SQUARE -> {
                RectNode(x = rect.left, y = rect.top, width = w, height = h, fill = fill, fillString = fillString)
            }
            ModuleShape.CIRCLE -> {
                CircleNode(cx = cx, cy = cy, radius = w / 2f, fill = fill, fillString = fillString)
            }
            ModuleShape.DOT -> {
                CircleNode(cx = cx, cy = cy, radius = (w / 2f) * 0.75f, fill = fill, fillString = fillString)
            }
            ModuleShape.ROUNDED -> {
                val rx = w * design.moduleStyle.cornerRadiusFraction.coerceIn(0.1f, 0.5f)
                RectNode(x = rect.left, y = rect.top, width = w, height = h, rx = rx, ry = rx, fill = fill, fillString = fillString)
            }
            ModuleShape.ORGANIC -> {
                // EF randomRound: r = 0.5 * Double.random(in: 0.33..<1.0)
                val factor = rng?.let { it.nextDouble(0.33, 1.0).toFloat() } ?: 0.85f
                CircleNode(cx = cx, cy = cy, radius = (w / 2f) * factor, fill = fill, fillString = fillString)
            }
            ModuleShape.SQUIRCLE -> {
                val d = ShapeGeometry.buildSquircleSvgD(rect.left.toDouble(), rect.top.toDouble(), w.toDouble(), h.toDouble())
                val path = QrVisualGeometry.createSquirclePath(rect)
                PathNode(svgPathData = d, androidPath = path, fill = fill, fillString = fillString)
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
                    fillString = fillString
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
                    fillString = fillString
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
                    fillString = fillString
                )
            }
            ModuleShape.BUBBLE -> {
                val rx = w * 0.42f
                val ry = h * 0.42f
                RectNode(x = rect.left, y = rect.top, width = w, height = h, rx = rx, ry = rx, fill = fill, fillString = fillString)
            }
            ModuleShape.PILL -> {
                val rx = w / 2f
                val ry = h * 0.25f
                RectNode(x = rect.left, y = cy - ry, width = w, height = ry * 2f, rx = rx, ry = ry, fill = fill, fillString = fillString)
            }
            ModuleShape.CONNECTED, ModuleShape.LINE, ModuleShape.BUBBLE_CLUSTER, ModuleShape.CUSTOM -> {
                val rx = w * 0.15f
                RectNode(x = rect.left, y = rect.top, width = w, height = h, rx = rx, ry = rx, fill = fill, fillString = fillString)
            }
        }
    }
}
