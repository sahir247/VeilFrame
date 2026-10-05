package com.veilframe.app.qr.geometry

import android.graphics.Color
import android.graphics.RectF
import java.util.Locale
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.image.ImageColorAnalyzer
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry

/**
 * Builds a unified, authoritative [QrGeometryIr] representation for [com.veilframe.app.qr.QrStyle.IMAGE].
 *
 * Implements canonical EFQRCode 7.0.3 EFStyleImage layered rendering semantics:
 * 1. Document Backdrop: Solid canvas fill and optional preprocessed backdrop image.
 * 2. Optional Pre-Pass: Gated on (hasImage && allowTransparent) — renders underlying full-size 1x1
 *    data modules (dark with dataColorDark, light with dataColorLight) before the watermark image.
 * 3. Watermark Image & #hole Mask: Gated on (hasImage) — embedded scaled image with 8x8 finder
 *    cutout regions (TL, TR, BL) removed via mask so image never enters finder zones.
 * 4. Position Patterns: 8x8 solid backing rect with posLightColor, followed by outer ring and
 *    inner core in posDarkColor, sequenced in EF column-major order (TL -> BL -> TR).
 * 5. Timing Tracks: Dedicated dark and light module rendering with custom shapes/sizes.
 * 6. Alignment Patterns: Dedicated dark and light module rendering with custom shapes/sizes.
 * 7. Data Modules: Scaled data marks drawn on top of the image with their respective colors, shapes, and scale.
 * 8. Center Logo: Rendered centered on top if present.
 *
 * Provides a single mathematical source of truth across Android Canvas rasterization and SVG vector emission.
 */
object ImageGeometryBuilder {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val mSize = geometry.moduleSize
        val x0 = geometry.offsetX
        val y0 = geometry.offsetY
        val width = geometry.outputWidthFloat
        val height = geometry.outputHeightFloat

        val sourceImage = design.imageSource.bitmap
        val hasStaticImage = sourceImage != null && !sourceImage.isRecycled
        val animatedFrames = design.imageSource.animatedFrames
        val hasAnimatedFrames = !animatedFrames.isNullOrEmpty()
        val hasImage = hasStaticImage || hasAnimatedFrames

        val nodes = mutableListOf<QrGeometryNode>()
        val defs = mutableListOf<String>()
        val masks = mutableMapOf<String, QrMaskDefinition>()

        // 1. Background Canvas & Backdrop (EF generic backdrop contract)
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val bgAlpha = colorAlpha(resolvedBackdropColor)
        if (bgAlpha > 0) {
            val crPx = if (design.backdropStyle.cornerRadius > 0f) design.backdropStyle.cornerRadius * mSize else 0f
            val bgAlphaFloat = ((resolvedBackdropColor ushr 24) and 0xFF) / 255f
            nodes.add(
                RectNode(
                    x = 0f,
                    y = 0f,
                    width = width,
                    height = height,
                    rx = crPx,
                    ry = crPx,
                    fill = resolvedBackdropColor,
                    opacity = bgAlphaFloat,
                    alwaysEmitOpacity = true
                )
            )
        }

        val backdropImg = design.backdropStyle.image
        if (backdropImg != null && !backdropImg.isRecycled) {
            val preprocessedBackdrop = EfImagePreprocessor.preprocess(
                source = backdropImg,
                canvasWidth = width,
                canvasHeight = height,
                mode = design.backdropStyle.imageScaleMode
            )
            nodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = width,
                    height = height,
                    bitmap = preprocessedBackdrop,
                    opacity = design.backdropStyle.imageAlpha,
                    preserveAspectRatio = "",
                    key = "bi"
                )
            )
        }

        val dataShape = design.moduleStyle.shape
        val dataScale = maxOf(0f, (design.imageDataScale ?: design.moduleStyle.scale))
        val adaptiveColors = if (design.imageColorStrategy == ImageColorStrategy.ADAPTIVE_PALETTE) {
            val candidate = sourceImage ?: animatedFrames?.firstOrNull()
            if (candidate != null && !candidate.isRecycled) {
                ImageColorAnalyzer.analyze(candidate)
            } else null
        } else null
        val dataDarkColor = adaptiveColors?.darkColor ?: design.dataColorDark
        val dataLightColor = adaptiveColors?.lightColor ?: design.dataColorLight
        val allowTransparent = design.allowTransparent

        val posStyle = design.eyeStyle.style
        val posDarkColor = design.positionDarkColor
        val posLightColor = design.positionLightColor
        val posSize = design.positionSize

        val timingShape = design.timingStyle.shape
        val timingDarkColor = design.timingDarkColor
        val timingLightColor = design.timingLightColor
        val timingSize = design.timingSize

        val alignShape = design.alignmentStyle.shape
        val alignDarkColor = design.alignDarkColor
        val alignLightColor = design.alignLightColor
        val alignSize = design.alignSize

        val imageAlpha = design.imageSource.opacity.coerceIn(0f, 1f)

        // 2. Transparent Pre-Pass (EFQRCode: ONLY executed when hasImage && allowTransparent)
        if (hasImage && allowTransparent) {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val type = matrix.typeAt(col, row)
                    val isTimingNone = type == ModuleType.TIMING && timingShape == ModuleShape.NONE
                    val isAlignNone = (type == ModuleType.ALIGN_CENTER || type == ModuleType.ALIGN_OTHER) && alignShape == ModuleShape.NONE
                    if (type == ModuleType.POS_CENTER || type == ModuleType.POS_OTHER) {
                        // Skip finder modules in pre-pass
                    } else if (type == ModuleType.TIMING && !isTimingNone) {
                        // Skip active timing modules in pre-pass
                    } else if ((type == ModuleType.ALIGN_CENTER || type == ModuleType.ALIGN_OTHER) && !isAlignNone) {
                        // Skip active alignment modules in pre-pass
                    } else {
                        val isDark = matrix.isDark(col, row)
                        val color = if (isDark) dataDarkColor else dataLightColor
                        if (colorAlpha(color) > 0) {
                            createModuleShapeNode(x0 + col * mSize, y0 + row * mSize, mSize, dataShape, color)?.let { nodes.add(it) }
                        }
                    }
                }
            }
        }

        // 3. Image Layer with 8x8 Finder Cutout Mask (#hole) (EFQRCode: ONLY created when hasImage is true)
        val canvasW = n * mSize
        val canvasH = n * mSize

        if (hasImage) {
            val finderW = 8 * mSize
            val tlFinderRect = RectF(x0, y0, x0 + finderW, y0 + finderW)
            val trFinderRect = RectF(x0 + (n - 8) * mSize, y0, x0 + n * mSize, y0 + finderW)
            val blFinderRect = RectF(x0, y0 + (n - 8) * mSize, x0 + finderW, y0 + n * mSize)

            masks["hole"] = QrMaskDefinition(
                id = "hole",
                bounds = RectF(x0, y0, x0 + canvasW, y0 + canvasH),
                clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect),
                maskNodes = listOf(
                    RectNode(x = x0, y = y0, width = canvasW, height = canvasH, fill = 0xFFFFFFFF.toInt(), fillString = "white"),
                    RectNode(x = x0, y = y0, width = finderW, height = finderW, fill = 0xFF000000.toInt(), fillString = "black"),
                    RectNode(x = x0 + (n - 8) * mSize, y = y0, width = finderW, height = finderW, fill = 0xFF000000.toInt(), fillString = "black"),
                    RectNode(x = x0, y = y0 + (n - 8) * mSize, width = finderW, height = finderW, fill = 0xFF000000.toInt(), fillString = "black")
                )
            )

            if (animatedFrames != null && animatedFrames.isNotEmpty()) {
                val preprocessedFrames = animatedFrames.map { frame ->
                    EfImagePreprocessor.preprocess(
                        source = frame,
                        canvasWidth = canvasW,
                        canvasHeight = canvasH,
                        mode = design.imageSource.scaleMode
                    )
                }
                nodes.add(
                    AnimatedImageNode(
                        x = x0,
                        y = y0,
                        width = canvasW,
                        height = canvasH,
                        frames = preprocessedFrames,
                        frameDelaysMs = design.imageSource.frameDelaysMs ?: emptyList(),
                        opacity = imageAlpha,
                        preserveAspectRatio = "",
                        maskId = "hole",
                        clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect),
                        framePrefix = "${VeilIconPipeline.nextUniqueMark()}fm"
                    )
                )
            } else if (sourceImage != null && !sourceImage.isRecycled) {
                val preprocessed = EfImagePreprocessor.preprocess(
                    source = sourceImage,
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    mode = design.imageSource.scaleMode
                )
                nodes.add(
                    ImageNode(
                        x = x0,
                        y = y0,
                        width = canvasW,
                        height = canvasH,
                        bitmap = preprocessed,
                        opacity = imageAlpha,
                        preserveAspectRatio = "",
                        maskId = "hole",
                        clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect)
                    )
                )
            }
        }

        // 4. Single Canonical EFQRCode Traversal: x-major / y-minor (col in 0 until n, row in 0 until n)
        // Traversal priority per EFQRCodeStyleImage.swift:690-850:
        // ALIGN -> TIMING -> POS_CENTER -> POS_OTHER (skip) -> DATA
        val alignOffset = (1.0f - alignSize) / 2.0f
        val timingOffset = (1.0f - timingSize) / 2.0f
        val dataOffset = (1.0f - dataScale) / 2.0f

        for (col in 0 until n) {
            for (row in 0 until n) {
                val isDark = matrix.isDark(col, row)
                val type = matrix.typeAt(col, row)

                if (type == ModuleType.ALIGN_CENTER || type == ModuleType.ALIGN_OTHER) {
                    if (alignShape != ModuleShape.NONE) {
                        val color = if (isDark) alignDarkColor else alignLightColor
                        if (colorAlpha(color) > 0) {
                            val ax = x0 + (col + alignOffset) * mSize
                            val ay = y0 + (row + alignOffset) * mSize
                            createModuleShapeNode(ax, ay, alignSize * mSize, alignShape, color)?.let { nodes.add(it) }
                        }
                    }
                } else if (type == ModuleType.TIMING) {
                    if (timingShape != ModuleShape.NONE) {
                        val color = if (isDark) timingDarkColor else timingLightColor
                        if (colorAlpha(color) > 0) {
                            val tx = x0 + (col + timingOffset) * mSize
                            val ty = y0 + (row + timingOffset) * mSize
                            createModuleShapeNode(tx, ty, timingSize * mSize, timingShape, color)?.let { nodes.add(it) }
                        }
                    }
                } else if (type == ModuleType.POS_CENTER) {
                    val markArr = when {
                        col > row -> floatArrayOf(0f, -1f)
                        col < row -> floatArrayOf(-1f, 0f)
                        else -> floatArrayOf(-1f, -1f)
                    }
                    val bgCol = col - 4 - markArr[0].toInt()
                    val bgRow = row - 4 - markArr[1].toInt()
                    appendFinderIrNodes(
                        nodes = nodes,
                        x0 = x0,
                        y0 = y0,
                        cx = col + 0.5f,
                        cy = row + 0.5f,
                        bgCol = bgCol,
                        bgRow = bgRow,
                        mSize = mSize,
                        style = posStyle,
                        darkColor = posDarkColor,
                        lightColor = posLightColor,
                        sizeFactor = posSize
                    )
                } else if (type == ModuleType.POS_OTHER) {
                    continue
                } else {
                    if (dataShape != ModuleShape.NONE) {
                        val color = if (isDark) dataDarkColor else dataLightColor
                        if (colorAlpha(color) > 0) {
                            val dx = x0 + (col + dataOffset) * mSize
                            val dy = y0 + (row + dataOffset) * mSize
                            createModuleShapeNode(dx, dy, dataScale * mSize, dataShape, color)?.let { nodes.add(it) }
                        }
                    }
                }
            }
        }

        // 5. Center Logo
        VeilIconPipeline.appendIconNodes(
            nodes = nodes,
            defs = defs,
            design = design,
            ox = geometry.offsetX,
            oy = geometry.offsetY,
            qrPixelSize = matrix.size * geometry.moduleSize,
            masks = masks
        )

        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        if (hasCornerClip) {
            val crPx = design.backdropStyle.cornerRadius * mSize
            val cornerPath = android.graphics.Path().apply {
                addRoundRect(0f, 0f, width, height, crPx, crPx, android.graphics.Path.Direction.CW)
            }
            masks["rounded-corners"] = QrMaskDefinition(
                id = "rounded-corners",
                clipPath = cornerPath,
                bounds = RectF(0f, 0f, width, height),
                isClipPath = true,
                rx = crPx,
                ry = crPx
            )
        }

        return QrGeometryIr(
            width = width,
            height = height,
            viewBox = QrGeometryIr.defaultViewBox(width, height),
            defs = defs,
            masks = masks,
            rootNodes = nodes
        )
    }

    private fun appendFinderIrNodes(
        nodes: MutableList<QrGeometryNode>,
        x0: Float,
        y0: Float,
        cx: Float,
        cy: Float,
        bgCol: Int,
        bgRow: Int,
        mSize: Float,
        style: FinderStyle,
        darkColor: Int,
        lightColor: Int,
        sizeFactor: Float
    ) {
        // 8x8 backing rect
        val lightAlpha = colorAlpha(lightColor)
        val lightOp = lightAlpha / 255f
        val lightOpaque = (lightColor and 0x00FFFFFF) or (0xFF shl 24)
        nodes.add(
            RectNode(
                x = x0 + bgCol * mSize,
                y = y0 + bgRow * mSize,
                width = 8 * mSize,
                height = 8 * mSize,
                fill = lightOpaque,
                opacity = lightOp,
                opacityString = String.format(Locale.US, "%.2f", lightOp),
                alwaysEmitOpacity = true
            )
        )

        val centerPx = x0 + cx * mSize
        val centerPy = y0 + cy * mSize

        val darkAlpha = colorAlpha(darkColor)
        val darkOp = darkAlpha / 255f
        val darkOpaque = (darkColor and 0x00FFFFFF) or (0xFF shl 24)

        when (style) {
            FinderStyle.CIRCLE -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkOpaque, opacity = darkOp))
                val sw = 1.0f * sizeFactor * mSize
                val swStr = if (sw % 1f == 0f) sw.toInt().toString() else String.format(Locale.US, "%.4f", sw).trimEnd('0').trimEnd('.')
                nodes.add(
                    CircleNode(
                        cx = centerPx,
                        cy = centerPy,
                        radius = 3.0f * mSize,
                        stroke = darkOpaque,
                        strokeWidth = sw,
                        strokeWidthString = swStr,
                        opacity = darkOp
                    )
                )
            }
            FinderStyle.ROUNDED -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkOpaque, opacity = darkOp))
                val ox = centerPx - 0.5f * mSize
                val oy = centerPy - 0.5f * mSize
                val squirclePath = com.veilframe.app.qr.model.QrVisualGeometry.createSquirclePath(
                    RectF(ox - 2.5f * mSize, oy - 2.5f * mSize, ox + 3.5f * mSize, oy + 3.5f * mSize)
                )
                nodes.add(
                    PathNode(
                        svgPathData = VeilPositionPatternGeometry.SQ25_PATH,
                        androidPath = squirclePath,
                        fill = null,
                        stroke = darkOpaque,
                        strokeWidth = 100f / 6f * sizeFactor,
                        canvasStrokeWidth = 1f * sizeFactor * mSize,
                        opacity = darkOp,
                        transform = "translate(${ox - 2.5f * mSize},${oy - 2.5f * mSize}) scale(${6f * mSize / 100f},${6f * mSize / 100f})"
                    )
                )
            }
            FinderStyle.PLANETS -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkOpaque, opacity = darkOp))
                val sw = 0.15f * mSize
                val swStr = if (sw % 1f == 0f) sw.toInt().toString() else String.format(Locale.US, "%.4f", sw).trimEnd('0').trimEnd('.')
                val dash = String.format(Locale.US, "%.4f", 0.5f * mSize).trimEnd('0').trimEnd('.')
                nodes.add(
                    CircleNode(
                        cx = centerPx,
                        cy = centerPy,
                        radius = 3.0f * mSize,
                        stroke = darkOpaque,
                        strokeWidth = sw,
                        strokeWidthString = swStr,
                        strokeDashArray = "$dash,$dash",
                        opacity = darkOp
                    )
                )
                val planetRadius = 0.5f * sizeFactor * mSize
                val offsets = floatArrayOf(-3f, 3f)
                for (dx in offsets) {
                    nodes.add(CircleNode(centerPx + dx * mSize, centerPy, planetRadius, fill = darkOpaque, opacity = darkOp))
                }
                for (dy in offsets) {
                    nodes.add(CircleNode(centerPx, centerPy + dy * mSize, planetRadius, fill = darkOpaque, opacity = darkOp))
                }
            }
            FinderStyle.DSJ -> {
                val widthVal = (2.0f + sizeFactor) * mSize
                val armDim = sizeFactor * mSize
                nodes.add(RectNode(centerPx - widthVal / 2f, centerPy - widthVal / 2f, widthVal, widthVal, fill = darkOpaque, opacity = darkOp))
                nodes.add(RectNode((centerPx - 3f * mSize) - armDim / 2f, centerPy - widthVal / 2f, armDim, widthVal, fill = darkOpaque, opacity = darkOp))
                nodes.add(RectNode((centerPx + 3f * mSize) - armDim / 2f, centerPy - widthVal / 2f, armDim, widthVal, fill = darkOpaque, opacity = darkOp))
                nodes.add(RectNode(centerPx - widthVal / 2f, (centerPy - 3f * mSize) - armDim / 2f, widthVal, armDim, fill = darkOpaque, opacity = darkOp))
                nodes.add(RectNode(centerPx - widthVal / 2f, (centerPy + 3f * mSize) - armDim / 2f, widthVal, armDim, fill = darkOpaque, opacity = darkOp))
            }
            else -> {
                nodes.add(RectNode(centerPx - 1.5f * mSize, centerPy - 1.5f * mSize, 3f * mSize, 3f * mSize, fill = darkOpaque, opacity = darkOp))
                val sw = 1.0f * sizeFactor * mSize
                val swStr = if (sw % 1f == 0f) sw.toInt().toString() else String.format(Locale.US, "%.4f", sw).trimEnd('0').trimEnd('.')
                nodes.add(
                    RectNode(
                        x = centerPx - 3.0f * mSize,
                        y = centerPy - 3.0f * mSize,
                        width = 6f * mSize,
                        height = 6f * mSize,
                        stroke = darkOpaque,
                        strokeWidth = sw,
                        strokeWidthString = swStr,
                        opacity = darkOp
                    )
                )
            }
        }
    }

    private fun colorAlpha(color: Int): Int = (color ushr 24) and 0xFF

    private fun createModuleShapeNode(
        x: Float,
        y: Float,
        size: Float,
        shape: ModuleShape,
        color: Int
    ): QrGeometryNode? {
        if (shape == ModuleShape.NONE) return null
        val alpha = colorAlpha(color)
        if (alpha == 0) return null
        val op = alpha / 255f
        val opaqueColor = (color and 0x00FFFFFF) or (0xFF shl 24)
        return when (shape) {
            ModuleShape.CIRCLE, ModuleShape.DOT, ModuleShape.BUBBLE -> {
                CircleNode(
                    cx = x + size / 2f,
                    cy = y + size / 2f,
                    radius = size / 2f,
                    fill = opaqueColor,
                    opacity = op
                )
            }
            ModuleShape.ROUNDED, ModuleShape.SQUIRCLE -> {
                val rx = size * 0.25f
                RectNode(
                    x = x,
                    y = y,
                    width = size,
                    height = size,
                    rx = rx,
                    ry = rx,
                    fill = opaqueColor,
                    opacity = op
                )
            }
            else -> {
                RectNode(
                    x = x,
                    y = y,
                    width = size,
                    height = size,
                    fill = opaqueColor,
                    opacity = op
                )
            }
        }
    }
}
