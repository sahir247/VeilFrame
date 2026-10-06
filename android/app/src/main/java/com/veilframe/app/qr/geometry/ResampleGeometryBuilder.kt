package com.veilframe.app.qr.geometry

import android.graphics.Color
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.*
import java.util.Locale

/**
 * Builds a unified [QrGeometryIr] intermediate representation for [com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE].
 * Single source of truth consumed by both [IrCanvasRenderer] and [IrSvgRenderer].
 */
object ResampleGeometryBuilder {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        pixelSource: PixelSource? = null
    ): QrGeometryIr {
        val n = matrix.size
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val width = geometry.outputWidthFloat
        val height = geometry.outputHeightFloat
        val nodes = mutableListOf<QrGeometryNode>()

        val defs = mutableListOf<String>()
        val masks = mutableMapOf<String, QrMaskDefinition>()
        val cornerRadius = design.resampleStyle.backdropCornerRadius
        val backdropPath = if (cornerRadius > 0f) {
            android.graphics.Path().apply {
                addRoundRect(0f, 0f, width, height, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
            }
        } else null

        if (cornerRadius > 0f) {
            defs.add(
                String.format(
                    java.util.Locale.US,
                    "<clipPath id=\"backdropClip\"><rect x=\"0\" y=\"0\" width=\"%.4f\" height=\"%.4f\" rx=\"%.4f\" ry=\"%.4f\" /></clipPath>",
                    width, height, cornerRadius, cornerRadius
                )
            )
            if (backdropPath != null) {
                masks["backdropClip"] = QrMaskDefinition(id = "backdropClip", clipPath = backdropPath)
            }
        }

        // 1. Canvas background
        val resolvedBg = design.backdropStyle.color ?: design.palette.background
        val bgAlpha = (resolvedBg ushr 24) and 0xFF
        if (bgAlpha > 0) {
            nodes.add(RectNode(x = 0f, y = 0f, width = width, height = height, rx = cornerRadius, ry = cornerRadius, fill = resolvedBg))
        }

        // 2. Continuous Backdrop Image (supports independent backdrop or reused source image)
        val backdropBmp = design.resampleStyle.backdropBitmap ?: if (design.resampleStyle.useSourceAsBackdrop) design.imageSource.bitmap else null
        val resampleBmp = design.imageSource.bitmap

        if (design.resampleStyle.hasBackdrop && (backdropBmp != null || design.resampleStyle.useSourceAsBackdrop || design.resampleStyle.backdropBitmap != null)) {
            val base64 = if (backdropBmp != null && !backdropBmp.isRecycled) IrSvgRenderer.bitmapToBase64(backdropBmp) else "#sourceBackdrop"
            val aspect = when (design.resampleStyle.backdropScaleMode) {
                com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT -> "xMidYMid meet"
                com.veilframe.app.qr.model.ImageScaleMode.STRETCH -> "none"
                else -> "xMidYMid slice"
            }
            val blendStyle = when (design.resampleStyle.backdropBlendMode) {
                com.veilframe.app.qr.model.BackdropBlendMode.NORMAL -> null
                com.veilframe.app.qr.model.BackdropBlendMode.MULTIPLY -> "mix-blend-mode: multiply;"
                com.veilframe.app.qr.model.BackdropBlendMode.SCREEN -> "mix-blend-mode: screen;"
                com.veilframe.app.qr.model.BackdropBlendMode.OVERLAY -> "mix-blend-mode: overlay;"
            }
            val clipId = if (cornerRadius > 0f) "backdropClip" else null
            nodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = width,
                    height = height,
                    bitmap = backdropBmp,
                    base64Data = base64,
                    opacity = design.resampleStyle.backdropOpacity.coerceIn(0f, 1f),
                    preserveAspectRatio = aspect,
                    clipPathId = clipId,
                    maskId = null,
                    clipPath = backdropPath,
                    style = blendStyle
                )
            )
            val tint = design.resampleStyle.backdropTint
            if (tint != null) {
                val tintRgb = tint or 0xFF000000.toInt()
                val tintAlpha = ((tint ushr 24) and 0xFF) / 255f
                nodes.add(
                    RectNode(
                        x = 0f,
                        y = 0f,
                        width = width,
                        height = height,
                        rx = cornerRadius,
                        ry = cornerRadius,
                        fill = tintRgb,
                        opacity = tintAlpha,
                        opacityString = String.format(java.util.Locale.US, "%.2f", tintAlpha),
                        alwaysEmitOpacity = true
                    )
                )
            }
        }

        val fgColor = design.palette.foreground
        val dataColor = if (design.style == QrStyle.IMAGE_RESAMPLE) design.dataColorDark else fgColor

        // 3. Subpixel stochastic non-center dots from ResampleSubpixelEngine (EF: writeResImage before writeQRCode)
        val isAnimated = design.imageSource.isAnimated && !design.imageSource.animatedFrames.isNullOrEmpty()
        val hasSourceImage = isAnimated || pixelSource != null || (resampleBmp != null && !resampleBmp.isRecycled)
        if (isAnimated) {
            val frames = design.imageSource.animatedFrames!!
            val delays = design.imageSource.frameDelaysMs ?: List(frames.size) { 100 }
            val frameNodesList = mutableListOf<List<QrGeometryNode>>()
            for (frameBmp in frames) {
                val frameDots = mutableListOf<QrGeometryNode>()
                if (!frameBmp.isRecycled) {
                    ResampleSubpixelEngine.traverseSubpixels(
                        matrix = matrix,
                        source = frameBmp,
                        style = design.imageSource,
                        seed = design.resampleStyle.seed,
                        policy = ArtisticResamplePolicy.from(design),
                        includeCenterAnchors = false
                    ) { col, row, sx, sy, isCenterAnchor ->
                        if (!isCenterAnchor) {
                            val rect = SubpixelGeometry.computeEfCanvasRect(
                                col = col,
                                row = row,
                                offsetX = ox,
                                offsetY = oy,
                                moduleSize = mSize,
                                subX = sx,
                                subY = sy
                            )
                            frameDots.add(RectNode(x = rect.left, y = rect.top, width = rect.width, height = rect.height, fill = dataColor))
                        }
                    }
                }
                frameNodesList.add(frameDots)
            }
            nodes.add(AnimatedGroupNode(framePrefix = "resfm", frameNodes = frameNodesList, frameDelaysMs = delays))
        } else if (pixelSource != null) {
            ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                pixelSource = pixelSource,
                style = design.imageSource,
                seed = design.resampleStyle.seed,
                policy = ArtisticResamplePolicy.from(design),
                includeCenterAnchors = false
            ) { col, row, sx, sy, isCenterAnchor ->
                if (!isCenterAnchor) {
                    val rect = SubpixelGeometry.computeEfCanvasRect(
                        col = col,
                        row = row,
                        offsetX = ox,
                        offsetY = oy,
                        moduleSize = mSize,
                        subX = sx,
                        subY = sy
                    )
                    nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width, height = rect.height, fill = dataColor))
                }
            }
        } else if (resampleBmp != null && !resampleBmp.isRecycled) {
            ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                source = resampleBmp,
                style = design.imageSource,
                seed = design.resampleStyle.seed,
                policy = ArtisticResamplePolicy.from(design),
                includeCenterAnchors = false
            ) { col, row, sx, sy, isCenterAnchor ->
                if (!isCenterAnchor) {
                    val rect = SubpixelGeometry.computeEfCanvasRect(
                        col = col,
                        row = row,
                        offsetX = ox,
                        offsetY = oy,
                        moduleSize = mSize,
                        subX = sx,
                        subY = sy
                    )
                    nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width, height = rect.height, fill = dataColor))
                }
            }
        } else {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val role = matrix.roleAt(col, row)
                    if (role != QrModuleRole.DATA && role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION) continue
                    if (!matrix.isDark(col, row)) continue
                    nodes.add(RectNode(x = ox + col * mSize, y = oy + row * mSize, width = mSize, height = mSize, fill = dataColor))
                }
            }
        }

        // 4. Finders (EF: writeQRCode overlays resampled dots directly without solid/hollow background branching)
        val eyeOuter = design.eyeStyle.outerColor
            ?: if (design.style == QrStyle.IMAGE_RESAMPLE) design.positionDarkColor else fgColor
        val eyeInner = design.eyeStyle.innerColor
            ?: if (design.style == QrStyle.IMAGE_RESAMPLE) design.positionDarkColor else eyeOuter

        val finders = listOf(
            Pair(0, 0),
            Pair(n - 7, 0),
            Pair(0, n - 7)
        )
        val posSize = design.positionSize
        for ((col, row) in finders) {
            val fx = ox + col * mSize
            val fy = oy + row * mSize
            val cx = fx + 3.5f * mSize
            val cy = fy + 3.5f * mSize
            when (design.eyeStyle.style) {
                FinderStyle.CIRCLE -> {
                    // EF .round: inner circle r=4.5 (1.5 modules), outer circle stroke r=9 (3.0 modules)
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    nodes.add(CircleNode(cx, cy, 3.0f * mSize, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                }
                FinderStyle.ROUNDED -> {
                    // EF .roundedRectangle: inner circle r=4.5 (1.5 modules), outer sq25 cubic bezier squircle path
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    val squirclePath = QrVisualGeometry.createSquirclePath(
                        android.graphics.RectF(fx + 0.5f * mSize, fy + 0.5f * mSize, fx + 6.5f * mSize, fy + 6.5f * mSize)
                    )
                    nodes.add(
                        PathNode(
                            svgPathData = VeilPositionPatternGeometry.SQ25_PATH,
                            androidPath = squirclePath,
                            fill = null,
                            stroke = eyeOuter,
                            strokeWidth = 100f / 6f * posSize,
                            canvasStrokeWidth = posSize * mSize,
                            transform = "translate(${fx + 0.5f * mSize},${fy + 0.5f * mSize}) scale(${6f * mSize / 100f},${6f * mSize / 100f})"
                        )
                    )
                }
                FinderStyle.PLANETS -> {
                    // EF .planets: Inner circle r=1.5, outer dashed orbit r=3.0, 4 orbiting planet circles
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    nodes.add(CircleNode(cx, cy, 3.0f * mSize, stroke = eyeOuter, strokeWidth = 0.15f * mSize, strokeDashArray = "${0.5f * mSize},${0.5f * mSize}", fill = null))
                    val planetRadius = 0.5f * posSize * mSize
                    val offsets = floatArrayOf(-3f, 3f)
                    for (dx in offsets) {
                        nodes.add(CircleNode(cx + dx * mSize, cy, planetRadius, fill = eyeOuter))
                    }
                    for (dy in offsets) {
                        nodes.add(CircleNode(cx, cy + dy * mSize, planetRadius, fill = eyeOuter))
                    }
                }
                FinderStyle.DSJ -> {
                    val widthVal = (3.0f - (1.0f - posSize)) * mSize
                    val xTemp = fx + (1.0f - posSize) / 2.0f * mSize
                    val yTemp = fy + (1.0f - posSize) / 2.0f * mSize
                    nodes.add(RectNode(xTemp - mSize, yTemp - mSize, widthVal, widthVal, fill = eyeInner))
                    nodes.add(RectNode(xTemp - 3f * mSize, yTemp - mSize, posSize * mSize, widthVal, fill = eyeOuter))
                    nodes.add(RectNode(xTemp + 3f * mSize, yTemp - mSize, posSize * mSize, widthVal, fill = eyeOuter))
                    nodes.add(RectNode(xTemp - mSize, yTemp - 3f * mSize, widthVal, posSize * mSize, fill = eyeOuter))
                    nodes.add(RectNode(xTemp - mSize, yTemp + 3f * mSize, widthVal, posSize * mSize, fill = eyeOuter))
                }
                FinderStyle.FRAME -> {
                    nodes.add(RectNode(fx + 0.4f * mSize, fy + 0.4f * mSize, 6.2f * mSize, 6.2f * mSize, rx = 1f * mSize, ry = 1f * mSize, stroke = eyeOuter, strokeWidth = 0.8f * mSize, fill = null))
                    val pts = listOf(
                        Pair(cx, cy - 1.5f * mSize),
                        Pair(cx + 1.5f * mSize, cy),
                        Pair(cx, cy + 1.5f * mSize),
                        Pair(cx - 1.5f * mSize, cy)
                    )
                    val pointsStr = pts.joinToString(" ") { (px, py) ->
                        String.format(Locale.US, "%.4f,%.4f", px, py)
                    }
                    nodes.add(PolygonNode(points = pointsStr, pointsList = pts, fill = eyeInner))
                }
                FinderStyle.SOFT -> {
                    nodes.add(RectNode(fx + 0.5f * mSize, fy + 0.5f * mSize, 6f * mSize, 6f * mSize, rx = 1.5f * mSize, ry = 1.5f * mSize, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                }
                else -> {
                    // EF .rectangle (CLASSIC): Exact sharp square inner 3x3 at fx+2, outer 6x6 stroke at fx+0.5
                    nodes.add(RectNode(fx + 2f * mSize, fy + 2f * mSize, 3f * mSize, 3f * mSize, rx = 0f, ry = 0f, fill = eyeInner))
                    nodes.add(RectNode(fx + 0.5f * mSize, fy + 0.5f * mSize, 6f * mSize, 6f * mSize, rx = 0f, ry = 0f, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                }
            }
        }

        // 5. Timing tracks (EF writeQRCode timing handling)
        val timingColor = (design.timingStyle.color ?: design.timingColor)
            ?: if (design.style == QrStyle.IMAGE_RESAMPLE) design.timingDarkColor else fgColor
        val timingOnlyWhite = design.timingStyle.onlyWhite
        val timingShape = design.timingStyle.shape
        val timingSize = design.timingSize

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                if (!matrix.isDark(col, row)) continue
                if (timingShape != ModuleShape.NONE && !timingOnlyWhite) {
                    // EF #Btb: width = 3.02 * timingSize, offset = (posX - 0.01, posY - 0.01)
                    val sizePx = (3.02f / 3f) * mSize * timingSize
                    val x = ox + col * mSize - (0.01f / 3f) * mSize
                    val y = oy + row * mSize - (0.01f / 3f) * mSize
                    when (timingShape) {
                        ModuleShape.CIRCLE -> {
                            val r = sizePx / 2f
                            nodes.add(CircleNode(x + r, y + r, r, fill = timingColor))
                        }
                        ModuleShape.ROUNDED -> {
                            val cd = sizePx / 4f
                            nodes.add(RectNode(x, y, sizePx, sizePx, rx = cd, ry = cd, fill = timingColor))
                        }
                        else -> {
                            nodes.add(RectNode(x, y, sizePx, sizePx, fill = timingColor))
                        }
                    }
                } else {
                    // EF #Stb: width = 1.02 * timingSize, offset = (posX + 1, posY + 1) -> strictly NO -0.01 offset!
                    val dim = (1.02f / 3f) * mSize * timingSize
                    val x = ox + (col + 1f / 3f) * mSize
                    val y = oy + (row + 1f / 3f) * mSize
                    nodes.add(RectNode(x, y, dim, dim, fill = timingColor))
                }
            }
        }

        // 6. Alignment patterns (EF writeQRCode alignment handling)
        val alignColor = (design.alignmentStyle.color ?: design.alignmentColor)
            ?: if (design.style == QrStyle.IMAGE_RESAMPLE) design.alignDarkColor else fgColor
        val alignOnlyWhite = design.alignmentStyle.onlyWhite
        val alignShape = design.alignmentStyle.shape
        val alignSize = design.alignSize

        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue
                if (!matrix.isDark(col, row)) continue
                if (alignShape != ModuleShape.NONE && !alignOnlyWhite) {
                    // EF #Bab: width = 3.02 * alignSize, offset = (posX - 0.01, posY - 0.01)
                    val sizePx = (3.02f / 3f) * mSize * alignSize
                    val x = ox + col * mSize - (0.01f / 3f) * mSize
                    val y = oy + row * mSize - (0.01f / 3f) * mSize
                    when (alignShape) {
                        ModuleShape.CIRCLE -> {
                            val r = sizePx / 2f
                            nodes.add(CircleNode(x + r, y + r, r, fill = alignColor))
                        }
                        ModuleShape.ROUNDED -> {
                            val cd = sizePx / 4f
                            nodes.add(RectNode(x, y, sizePx, sizePx, rx = cd, ry = cd, fill = alignColor))
                        }
                        else -> {
                            nodes.add(RectNode(x, y, sizePx, sizePx, fill = alignColor))
                        }
                    }
                } else {
                    // EF #Sab: width = 1.02 * alignSize, offset = (posX + 1 - 0.01, posY + 1 - 0.01) -> HAS -0.01 offset!
                    val dim = (1.02f / 3f) * mSize * alignSize
                    val x = ox + (col + 1f / 3f) * mSize - (0.01f / 3f) * mSize
                    val y = oy + (row + 1f / 3f) * mSize - (0.01f / 3f) * mSize
                    nodes.add(RectNode(x, y, dim, dim, fill = alignColor))
                }
            }
        }

        // 7. Ordinary dark-module Sb center anchors (EF writeQRCode data/format/version emission outside animated group)
        if (hasSourceImage) {
            val sbDim = (1.02f / 3f) * mSize
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val role = matrix.roleAt(col, row)
                    if (role != QrModuleRole.DATA && role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION) continue
                    if (!matrix.isDark(col, row)) continue
                    val x = ox + (col + 1f / 3f) * mSize
                    val y = oy + (row + 1f / 3f) * mSize
                    nodes.add(RectNode(x = x, y = y, width = sbDim, height = sbDim, fill = dataColor))
                }
            }
        }

        // 8. Center logo on QR matrix (EFQRCodeStyleResampleImage.swift:471,673 writeIcon parity)
        VeilIconPipeline.appendIconNodes(
            nodes = nodes,
            defs = defs,
            design = design,
            ox = ox,
            oy = oy,
            qrPixelSize = n * mSize,
            masks = masks
        )

        return QrGeometryIr(
            width = width,
            height = height,
            viewBox = QrGeometryIr.defaultViewBox(width, height),
            defs = defs,
            masks = masks,
            rootNodes = nodes
        )
    }
}
