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
                    createModuleShapeNode(x0 + col * mSize, y0 + row * mSize, mSize, dataShape, color)?.let { nodes.add(it) }
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

        val defs = mutableListOf(
            """<mask id="hole">
    <rect x="$x0" y="$y0" width="${canvasW}" height="${canvasH}" fill="white"/>
    <rect x="$x0" y="$y0" width="${8 * mSize}" height="${8 * mSize}" fill="black"/>
    <rect x="${x0 + (n - 8) * mSize}" y="$y0" width="${8 * mSize}" height="${8 * mSize}" fill="black"/>
    <rect x="$x0" y="${y0 + (n - 8) * mSize}" width="${8 * mSize}" height="${8 * mSize}" fill="black"/>
  </mask>"""
        )

        // EF parity (EFQRCodeStyle.swift:271): preprocessed image matches canvas ratio;
        // no preserveAspectRatio attribute is emitted or re-interpreted.
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
                    preserveAspectRatio = "",
                    maskId = "hole",
                    clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect),
                    framePrefix = "${com.veilframe.app.qr.geometry.VeilIconPipeline.nextUniqueMark()}fm"
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
                    preserveAspectRatio = "",
                    maskId = "hole",
                    clipOutRects = listOf(tlFinderRect, trFinderRect, blFinderRect)
                )
            )
        }

        // 4. Finder Patterns (with 8x8 posLightColor backing)
        appendFinderIrNodes(nodes, x0, y0, 3.5f, 3.5f, 0, 0, mSize, posStyle, posDarkColor, posLightColor, posSize)
        appendFinderIrNodes(nodes, x0, y0, n - 3.5f, 3.5f, n - 8, 0, mSize, posStyle, posDarkColor, posLightColor, posSize)
        appendFinderIrNodes(nodes, x0, y0, 3.5f, n - 3.5f, 0, n - 8, mSize, posStyle, posDarkColor, posLightColor, posSize)

        // 5. Timing Tracks (skips entirely if timingShape is NONE)
        if (timingShape != ModuleShape.NONE) {
            val timingOffset = (1.0f - timingSize) / 2.0f
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                    val isDark = matrix.isDark(col, row)
                    val color = if (isDark) timingDarkColor else timingLightColor
                    if (colorAlpha(color) == 0) continue
                    val tx = x0 + (col + timingOffset) * mSize
                    val ty = y0 + (row + timingOffset) * mSize
                    createModuleShapeNode(tx, ty, timingSize * mSize, timingShape, color)?.let { nodes.add(it) }
                }
            }
        }

        // 6. Alignment Patterns (skips entirely if alignShape is NONE)
        if (alignShape != ModuleShape.NONE) {
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
                    createModuleShapeNode(ax, ay, alignSize * mSize, alignShape, color)?.let { nodes.add(it) }
                }
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
                createModuleShapeNode(dx, dy, dataScale * mSize, dataShape, color)?.let { nodes.add(it) }
            }
        }

        // 8. Center Logo
        com.veilframe.app.qr.geometry.VeilIconPipeline.appendIconNodes(
            nodes = nodes,
            defs = defs,
            design = design,
            ox = 0f,
            oy = 0f,
            qrPixelSize = width
        )

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
                opacity = lightOp
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
                nodes.add(CircleNode(centerPx, centerPy, 3.0f * mSize, stroke = darkOpaque, strokeWidth = 1.0f * sizeFactor * mSize, opacity = darkOp))
            }
            FinderStyle.ROUNDED -> {
                nodes.add(CircleNode(centerPx, centerPy, 1.5f * mSize, fill = darkOpaque, opacity = darkOp))
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
                nodes.add(CircleNode(centerPx, centerPy, 3.0f * mSize, stroke = darkOpaque, strokeWidth = 0.15f * mSize, strokeDashArray = "${0.5f * mSize},${0.5f * mSize}", opacity = darkOp))
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
                nodes.add(RectNode(centerPx - 3.0f * mSize, centerPy - 3.0f * mSize, 6f * mSize, 6f * mSize, stroke = darkOpaque, strokeWidth = 1.0f * sizeFactor * mSize, opacity = darkOp))
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

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val ir = generateGeometry(matrix, design, geometry)
        IrCanvasRenderer.render(ir, canvas, frameIndex = context.frameIndex)
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
