package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.veilframe.app.qr.geometry.*
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 6 — IMAGE (VeilFrameStyleImage Parity)
 *
 * Full multi-layer VeilFrame Art Engine architecture implemented via unified [QrGeometryIr]:
 * 1. Background layer: Solid backdrop in [QrDesign.palette.background].
 * 2. Optional pre-pass: When [allowTransparent] is true, renders underlying data modules
 *    (dark with [dataColorDark], light with [dataColorLight]) before the image.
 * 3. Image layer with finder cutout: Continuous image scaled over the QR matrix, with
 *    8x8 finder boxes cut out (mask "#hole") so image never enters finder areas.
 * 4. Position Patterns: 8x8 solid backing rect with [posLightColor], followed by outer ring
 *    and inner core in [posDarkColor] (styles: rectangle, round, roundedRectangle, planets, dsj).
 * 5. Timing Tracks: Dedicated dark and light module rendering with custom shapes/sizes.
 * 6. Alignment Patterns: Dedicated dark and light module rendering with custom shapes/sizes.
 * 7. Data Modules: Dark and light data modules drawn on top of the image with their respective
 *    colors, shapes, and scale.
 * 8. Center Logo: Rendered centered on top if present.
 */
class ImageRenderer : QrRenderer {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val mSize = geometry.moduleSize
        val x0 = geometry.offsetX
        val y0 = geometry.offsetY
        val width = geometry.outputWidth.toFloat()
        val height = geometry.outputHeight.toFloat()
        val sourceImage = design.imageSource.bitmap

        val nodes = mutableListOf<QrGeometryNode>()

        // 1. Background Canvas
        val bgAlpha = colorAlpha(design.palette.background)
        if (bgAlpha > 0) {
            nodes.add(RectNode(x = 0f, y = 0f, width = width, height = height, fill = design.palette.background))
        }


        val dataShape = design.moduleStyle.shape
        val dataScale = maxOf(0f, (design.imageDataScale ?: design.moduleStyle.scale))
        val dataDarkColor = design.dataColorDark
        val dataLightColor = design.dataColorLight
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

        // 2. Transparent Pre-Pass
        if (allowTransparent) {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val role = matrix.roleAt(col, row)
                    val isTimingNone = role == QrModuleRole.TIMING && timingShape == ModuleShape.NONE
                    val isAlignNone = (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) && alignShape == ModuleShape.NONE
                    if (role != QrModuleRole.DATA && role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION && !isTimingNone && !isAlignNone) continue
                    val isDark = matrix.isDark(col, row)
                    val color = if (isDark) dataDarkColor else dataLightColor
                    if (colorAlpha(color) == 0) continue
                    nodes.add(createModuleShapeNode(x0 + col * mSize, y0 + row * mSize, mSize, dataShape, color))
                }
            }
        }

        // 3. Image Layer with 8x8 Finder Cutout Mask (#hole)
        //
        // M2 — EF parity: preprocess before embedding.
        // EFQRCodeStyle.swift:269 → mode.imageForContent(ofImage:inCanvasOfRatio:) is called
        // BEFORE pngBase64EncodedString(). We must match this by preprocessing the source
        // to the QR canvas ratio first, then base64-encoding the preprocessed result.
        // EF emits no preserveAspectRatio attribute; the embedded image already has correct
        // geometry, so no consumer-side scaling is needed.
        val canvasW = n * mSize
        val canvasH = n * mSize  // QR canvas is always square
        val isAnimated = design.imageSource.isAnimated || (design.imageSource.animatedFrames?.isNotEmpty() == true)
        val animatedFrames = design.imageSource.animatedFrames
        val frameDelaysMs = design.imageSource.frameDelaysMs ?: emptyList()

        val tlFinderRect = RectF(x0, y0, x0 + 8 * mSize, y0 + 8 * mSize)
        val trFinderRect = RectF(x0 + (n - 8) * mSize, y0, x0 + n * mSize, y0 + 8 * mSize)
        val blFinderRect = RectF(x0, y0 + (n - 8) * mSize, x0 + 8 * mSize, y0 + n * mSize)

        val defs = listOf(
            """<mask id="hole">
    <rect x="$x0" y="$y0" width="${canvasW}" height="${canvasH}" fill="white"/>
    <rect x="$x0" y="$y0" width="${8 * mSize}" height="${8 * mSize}" fill="black"/>
    <rect x="${x0 + (n - 8) * mSize}" y="$y0" width="${8 * mSize}" height="${8 * mSize}" fill="black"/>
    <rect x="$x0" y="${y0 + (n - 8) * mSize}" width="${8 * mSize}" height="${8 * mSize}" fill="black"/>
  </mask>"""
        )

        val aspect = when (design.imageSource.scaleMode) {
            com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT -> "xMidYMid meet"
            com.veilframe.app.qr.model.ImageScaleMode.STRETCH -> "none"
            else -> "xMidYMid slice"
        }

        if (isAnimated && animatedFrames != null && animatedFrames.isNotEmpty()) {
            val preprocessedFrames = animatedFrames.map { frame ->
                EfImagePreprocessor.preprocess(
                    source = frame,
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    mode = design.imageSource.scaleMode
                )
            }
            val base64Frames = preprocessedFrames.map { IrSvgRenderer.bitmapToBase64(it) }
            nodes.add(
                AnimatedImageNode(
                    x = x0,
                    y = y0,
                    width = canvasW,
                    height = canvasH,
                    frames = preprocessedFrames,
                    base64Frames = base64Frames,
                    frameDelaysMs = frameDelaysMs,
                    opacity = imageAlpha,
                    preserveAspectRatio = aspect,
                    maskId = "hole",
                    clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect),
                    framePrefix = "1fm"
                )
            )
        } else {
            val preprocessed = if (sourceImage != null) {
                EfImagePreprocessor.preprocess(
                    source = sourceImage,
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    mode = design.imageSource.scaleMode
                )
            } else null
            val base64 = if (preprocessed != null) IrSvgRenderer.bitmapToBase64(preprocessed) else ""

            nodes.add(
                ImageNode(
                    x = x0,
                    y = y0,
                    width = canvasW,
                    height = canvasH,
                    bitmap = preprocessed,
                    base64Data = base64,
                    opacity = imageAlpha,
                    preserveAspectRatio = aspect,
                    maskId = "hole",
                    clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect)
                )
            )
        }

        // 4. Finder Patterns (with 8x8 posLightColor backing)
        appendFinderIrNodes(nodes, x0, y0, 3.5f, 3.5f, 0, 0, mSize, posStyle, posDarkColor, posLightColor, posSize)
        appendFinderIrNodes(nodes, x0, y0, n - 3.5f, 3.5f, n - 8, 0, mSize, posStyle, posDarkColor, posLightColor, posSize)
        appendFinderIrNodes(nodes, x0, y0, 3.5f, n - 3.5f, 0, n - 8, mSize, posStyle, posDarkColor, posLightColor, posSize)

        // 5. Timing Tracks
        val timingOffset = (1.0f - timingSize) / 2.0f
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                val isDark = matrix.isDark(col, row)
                val color = if (isDark) timingDarkColor else timingLightColor
                if (colorAlpha(color) == 0) continue
                val tx = x0 + (col + timingOffset) * mSize
                val ty = y0 + (row + timingOffset) * mSize
                nodes.add(createModuleShapeNode(tx, ty, timingSize * mSize, timingShape, color))
            }
        }

        // 6. Alignment Patterns
        val alignOffset = (1.0f - alignSize) / 2.0f
        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue
                val isDark = matrix.isDark(col, row)
                val color = if (isDark) alignDarkColor else alignLightColor
                if (colorAlpha(color) == 0) continue
                val ax = x0 + (col + alignOffset) * mSize
                val ay = y0 + (row + alignOffset) * mSize
                nodes.add(createModuleShapeNode(ax, ay, alignSize * mSize, alignShape, color))
            }
        }

        // 7. Data, Format & Version Modules on top of Image
        val dataOffset = (1.0f - dataScale) / 2.0f
        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.DATA && role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION) continue
                val isDark = matrix.isDark(col, row)
                val color = if (isDark) dataDarkColor else dataLightColor
                if (colorAlpha(color) == 0) continue
                val dx = x0 + (col + dataOffset) * mSize
                val dy = y0 + (row + dataOffset) * mSize
                nodes.add(createModuleShapeNode(dx, dy, dataScale * mSize, dataShape, color))
            }
        }

        // 8. Center Logo
        val logo = design.logo
        val logoBmp = logo?.effectiveBitmap
        if (logo != null && logoBmp != null) {
            val scale = minOf(maxOf(0f, logo.scaleFraction), 0.33f)
            val iconSize = width * scale
            val iconXY = (width - iconSize) / 2f
            val iconOffset = iconXY * 0.024f
            val rectXY = iconXY - iconOffset
            val length = iconSize + 2f * iconOffset

            if (logo.shape == com.veilframe.app.qr.model.LogoShape.SQUIRCLE) {
                val bdColor = logo.borderColor ?: (if (logo.backgroundMode != com.veilframe.app.qr.model.LogoBackgroundMode.NONE) design.palette.background else null)
                if (bdColor != null) {
                    val bdAlpha = ((bdColor ushr 24) and 0xFF) / 255f
                    nodes.add(
                        PathNode(
                            svgPathData = VeilPositionPatternGeometry.SQ25_PATH,
                            fill = bdColor,
                            stroke = bdColor,
                            strokeWidth = 100f / iconSize,
                            opacity = bdAlpha,
                            transform = String.format(java.util.Locale.US, "translate(%.4f, %.4f) scale(%.6f, %.6f)", iconXY, iconXY, iconSize / 100f, iconSize / 100f)
                        )
                    )
                }
            } else {
                val cardPadding = 0.5f * mSize
                nodes.add(
                    RectNode(
                        x = iconXY - cardPadding,
                        y = iconXY - cardPadding,
                        width = iconSize + 2 * cardPadding,
                        height = iconSize + 2 * cardPadding,
                        rx = 1.5f * mSize,
                        ry = 1.5f * mSize,
                        fill = design.palette.background
                    )
                )
            }

            val iconOpacity = logo.alpha.coerceIn(0f, 1f)
            if (logo.isAnimated && logo.animatedFrames?.isNotEmpty() == true) {
                val logoFrames = logo.animatedFrames ?: emptyList()
                val logoDelays = logo.frameDelaysMs ?: emptyList()
                val preprocessedFrames = logoFrames.map { com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(it, length, length, logo.scaleMode) }
                nodes.add(
                    AnimatedImageNode(
                        x = rectXY,
                        y = rectXY,
                        width = length,
                        height = length,
                        frames = preprocessedFrames,
                        base64Frames = preprocessedFrames.map { IrSvgRenderer.bitmapToBase64(it) },
                        frameDelaysMs = logoDelays,
                        opacity = iconOpacity,
                        preserveAspectRatio = "",
                        framePrefix = "logofm"
                    )
                )
            } else {
                val preprocessedBmp = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(logoBmp, length, length, logo.scaleMode)
                nodes.add(
                    ImageNode(
                        x = rectXY,
                        y = rectXY,
                        width = length,
                        height = length,
                        bitmap = preprocessedBmp,
                        base64Data = IrSvgRenderer.bitmapToBase64(preprocessedBmp),
                        opacity = iconOpacity,
                        preserveAspectRatio = ""
                    )
                )
            }
        }

        return QrGeometryIr(
            width = width,
            height = height,
            viewBox = "0 0 ${width.toInt()} ${height.toInt()}",
            defs = defs,
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
        nodes.add(
            RectNode(
                x = x0 + bgCol * mSize,
                y = y0 + bgRow * mSize,
                width = 8 * mSize,
                height = 8 * mSize,
                fill = lightColor
            )
        )

        val centerPx = x0 + cx * mSize
        val centerPy = y0 + cy * mSize

        when (style) {
            FinderStyle.CIRCLE -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkColor))
                nodes.add(CircleNode(centerPx, centerPy, 3.0f * mSize, stroke = darkColor, strokeWidth = 1.0f * sizeFactor * mSize))
            }
            FinderStyle.ROUNDED -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkColor))
                val ox = centerPx - 0.5f * mSize
                val oy = centerPy - 0.5f * mSize
                val squirclePath = com.veilframe.app.qr.model.QrVisualGeometry.createSquirclePath(
                    android.graphics.RectF(ox - 2.5f * mSize, oy - 2.5f * mSize, ox + 3.5f * mSize, oy + 3.5f * mSize)
                )
                nodes.add(
                    PathNode(
                        svgPathData = VeilPositionPatternGeometry.SQ25_PATH,
                        androidPath = squirclePath,
                        fill = null,
                        stroke = darkColor,
                        strokeWidth = 100f / 6f * sizeFactor,
                        canvasStrokeWidth = 1f * sizeFactor * mSize,
                        transform = "translate(${ox - 2.5f * mSize},${oy - 2.5f * mSize}) scale(${6f * mSize / 100f},${6f * mSize / 100f})"
                    )
                )
            }
            FinderStyle.PLANETS -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkColor))
                nodes.add(CircleNode(centerPx, centerPy, 3.0f * mSize, stroke = darkColor, strokeWidth = 0.15f * mSize, strokeDashArray = "${0.5f * mSize},${0.5f * mSize}"))
                val planetRadius = 0.5f * sizeFactor * mSize
                val offsets = floatArrayOf(-3f, 3f)
                for (dx in offsets) {
                    nodes.add(CircleNode(centerPx + dx * mSize, centerPy, planetRadius, fill = darkColor))
                }
                for (dy in offsets) {
                    nodes.add(CircleNode(centerPx, centerPy + dy * mSize, planetRadius, fill = darkColor))
                }
            }
            FinderStyle.DSJ -> {
                val widthVal = (2.0f + sizeFactor) * mSize
                val armDim = sizeFactor * mSize
                nodes.add(RectNode(centerPx - widthVal / 2f, centerPy - widthVal / 2f, widthVal, widthVal, fill = darkColor))
                nodes.add(RectNode((centerPx - 3f * mSize) - armDim / 2f, centerPy - widthVal / 2f, armDim, widthVal, fill = darkColor))
                nodes.add(RectNode((centerPx + 3f * mSize) - armDim / 2f, centerPy - widthVal / 2f, armDim, widthVal, fill = darkColor))
                nodes.add(RectNode(centerPx - widthVal / 2f, (centerPy - 3f * mSize) - armDim / 2f, widthVal, armDim, fill = darkColor))
                nodes.add(RectNode(centerPx - widthVal / 2f, (centerPy + 3f * mSize) - armDim / 2f, widthVal, armDim, fill = darkColor))
            }
            else -> {
                nodes.add(RectNode(centerPx - 1.5f * mSize, centerPy - 1.5f * mSize, 3f * mSize, 3f * mSize, fill = darkColor))
                nodes.add(RectNode(centerPx - 3.0f * mSize, centerPy - 3.0f * mSize, 6f * mSize, 6f * mSize, stroke = darkColor, strokeWidth = 1.0f * sizeFactor * mSize))
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
    ): QrGeometryNode {
        val alpha = colorAlpha(color)
        val op = alpha / 255f
        return when (shape) {
            ModuleShape.CIRCLE, ModuleShape.DOT, ModuleShape.BUBBLE -> {
                CircleNode(
                    cx = x + size / 2f,
                    cy = y + size / 2f,
                    radius = size / 2f,
                    fill = color,
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
                    fill = color,
                    opacity = op
                )
            }
            else -> {
                RectNode(
                    x = x,
                    y = y,
                    width = size,
                    height = size,
                    fill = color,
                    opacity = op
                )
            }
        }
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
