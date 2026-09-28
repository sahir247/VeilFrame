package com.veilframe.app.qr.geometry

import android.graphics.Color
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.*

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
        val width = geometry.outputWidth.toFloat()
        val height = geometry.outputHeight.toFloat()
        val nodes = mutableListOf<QrGeometryNode>()

        // 1. Canvas background
        val bgAlpha = (design.palette.background ushr 24) and 0xFF
        if (bgAlpha > 0) {
            nodes.add(RectNode(x = 0f, y = 0f, width = width, height = height, fill = design.palette.background))
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
                        fill = tintRgb,
                        opacity = tintAlpha,
                        alwaysEmitOpacity = true
                    )
                )
            }
        }

        val fgColor = design.palette.foreground

        // 3. Subpixel dots & center anchors from ResampleSubpixelEngine (EF: writeResImage before writeQRCode)
        if (pixelSource != null) {
            ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                pixelSource = pixelSource,
                style = design.imageSource,
                seed = design.resampleStyle.seed,
                policy = ArtisticResamplePolicy.from(design)
            ) { col, row, sx, sy, _ ->
                val rect = SubpixelGeometry.computeCanvasRect(
                    col = col,
                    row = row,
                    offsetX = ox,
                    offsetY = oy,
                    moduleSize = mSize,
                    subX = sx,
                    subY = sy
                )
                nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width, height = rect.height, fill = fgColor))
            }
        } else if (resampleBmp != null && !resampleBmp.isRecycled) {
            ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                source = resampleBmp,
                style = design.imageSource,
                seed = design.resampleStyle.seed,
                policy = ArtisticResamplePolicy.from(design)
            ) { col, row, sx, sy, _ ->
                val rect = SubpixelGeometry.computeCanvasRect(
                    col = col,
                    row = row,
                    offsetX = ox,
                    offsetY = oy,
                    moduleSize = mSize,
                    subX = sx,
                    subY = sy
                )
                nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width, height = rect.height, fill = fgColor))
            }
        } else {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val role = matrix.roleAt(col, row)
                    if (role != QrModuleRole.DATA && role != QrModuleRole.FORMAT && role != QrModuleRole.VERSION) continue
                    if (!matrix.isDark(col, row)) continue
                    nodes.add(RectNode(x = ox + col * mSize, y = oy + row * mSize, width = mSize, height = mSize, fill = fgColor))
                }
            }
        }

        // 4. Finders (EF: writeQRCode overlays resampled dots)
        val eyeOuter = design.eyeStyle.outerColor ?: fgColor
        val eyeInner = design.eyeStyle.innerColor ?: fgColor
        val isHollowFinder = (design.style == QrStyle.IMAGE_RESAMPLE && design.resampleStyle.useSourceAsBackdrop) ||
            ((design.palette.background ushr 24) and 0xFF) == 0

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
                    if (isHollowFinder) {
                        nodes.add(CircleNode(cx, cy, 3.0f * mSize, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                        nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    } else {
                        nodes.add(CircleNode(cx, cy, 3.5f * mSize, fill = eyeOuter))
                        nodes.add(CircleNode(cx, cy, 2.5f * mSize, fill = design.palette.background))
                        nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    }
                }
                FinderStyle.ROUNDED -> {
                    // EF .roundedRectangle: Inner is <circle r="4.5"/> (1.5 modules), outer is rounded rect with rx=1.5 modules
                    if (isHollowFinder) {
                        nodes.add(RectNode(fx + 0.5f * mSize, fy + 0.5f * mSize, 6f * mSize, 6f * mSize, rx = 1.5f * mSize, ry = 1.5f * mSize, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                        nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    } else {
                        nodes.add(RectNode(fx, fy, 7f * mSize, 7f * mSize, rx = 1.5f * mSize, ry = 1.5f * mSize, fill = eyeOuter))
                        nodes.add(RectNode(fx + mSize, fy + mSize, 5f * mSize, 5f * mSize, rx = 1.0f * mSize, ry = 1.0f * mSize, fill = design.palette.background))
                        nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    }
                }
                FinderStyle.SOFT -> {
                    if (isHollowFinder) {
                        nodes.add(RectNode(fx + 0.5f * mSize, fy + 0.5f * mSize, 6f * mSize, 6f * mSize, rx = 1.5f * mSize, ry = 1.5f * mSize, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                        nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    } else {
                        nodes.add(RectNode(fx, fy, 7f * mSize, 7f * mSize, rx = 1.5f * mSize, ry = 1.5f * mSize, fill = eyeOuter))
                        nodes.add(RectNode(fx + mSize, fy + mSize, 5f * mSize, 5f * mSize, rx = 0.8f * mSize, ry = 0.8f * mSize, fill = design.palette.background))
                        nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    }
                }
                FinderStyle.FRAME -> {
                    nodes.add(RectNode(fx + 0.4f * mSize, fy + 0.4f * mSize, 6.2f * mSize, 6.2f * mSize, rx = 1f * mSize, ry = 1f * mSize, stroke = eyeOuter, strokeWidth = 0.8f * mSize, fill = null))
                    val pts = listOf(
                        Pair(cx, cy - 1.5f * mSize),
                        Pair(cx + 1.5f * mSize, cy),
                        Pair(cx, cy + 1.5f * mSize),
                        Pair(cx - 1.5f * mSize, cy)
                    )
                    nodes.add(PolygonNode(points = "", pointsList = pts, fill = eyeInner))
                }
                FinderStyle.PLANETS -> {
                    // EF .planets: Inner circle r=1.5, outer dashed orbit r=3.0, 4 orbiting planet circles
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    nodes.add(CircleNode(cx, cy, 3.0f * mSize, stroke = eyeOuter, strokeWidth = 0.15f * mSize, strokeDashArray = "${0.5f * mSize},${0.5f * mSize}"))
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
                    val widthVal = (2.0f + posSize) * mSize
                    val armDim = posSize * mSize
                    nodes.add(RectNode(cx - widthVal / 2f, cy - widthVal / 2f, widthVal, widthVal, fill = eyeInner))
                    nodes.add(RectNode((cx - 3f * mSize) - armDim / 2f, cy - widthVal / 2f, armDim, widthVal, fill = eyeOuter))
                    nodes.add(RectNode((cx + 3f * mSize) - armDim / 2f, cy - widthVal / 2f, armDim, widthVal, fill = eyeOuter))
                    nodes.add(RectNode(cx - widthVal / 2f, (cy - 3f * mSize) - armDim / 2f, widthVal, armDim, fill = eyeOuter))
                    nodes.add(RectNode(cx - widthVal / 2f, (cy + 3f * mSize) - armDim / 2f, widthVal, armDim, fill = eyeOuter))
                }
                else -> {
                    // EF .rectangle (CLASSIC): Exact sharp square inner 3x3 at fx+2, outer 6x6 stroke at fx+0.5
                    if (isHollowFinder) {
                        nodes.add(RectNode(fx + 0.5f * mSize, fy + 0.5f * mSize, 6f * mSize, 6f * mSize, rx = 0f, ry = 0f, stroke = eyeOuter, strokeWidth = posSize * mSize, fill = null))
                        nodes.add(RectNode(fx + 2f * mSize, fy + 2f * mSize, 3f * mSize, 3f * mSize, rx = 0f, ry = 0f, fill = eyeInner))
                    } else {
                        nodes.add(RectNode(fx, fy, 7f * mSize, 7f * mSize, rx = 0f, ry = 0f, fill = eyeOuter))
                        nodes.add(RectNode(fx + mSize, fy + mSize, 5f * mSize, 5f * mSize, rx = 0f, ry = 0f, fill = design.palette.background))
                        nodes.add(RectNode(fx + 2f * mSize, fy + 2f * mSize, 3f * mSize, 3f * mSize, rx = 0f, ry = 0f, fill = eyeInner))
                    }
                }
            }
        }

        // 5. Timing tracks (EF writeQRCode timing handling)
        val timingColor = (design.timingStyle.color ?: design.timingColor) ?: fgColor
        val timingOnlyWhite = design.timingStyle.onlyWhite
        val timingShape = design.timingStyle.shape
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                if (!matrix.isDark(col, row)) continue
                val tx = ox + col * mSize
                val ty = oy + row * mSize
                if (timingShape != ModuleShape.NONE && !timingOnlyWhite) {
                    when (timingShape) {
                        ModuleShape.CIRCLE -> {
                            nodes.add(CircleNode(tx + mSize / 2f, ty + mSize / 2f, mSize / 2f, fill = timingColor))
                        }
                        ModuleShape.ROUNDED -> {
                            nodes.add(RectNode(tx, ty, mSize, mSize, rx = mSize / 4f, ry = mSize / 4f, fill = timingColor))
                        }
                        else -> {
                            nodes.add(RectNode(tx, ty, mSize, mSize, fill = timingColor))
                        }
                    }
                } else {
                    // EF #Stb: Center 1x1 subpixel anchor at (col + 1/3, row + 1/3)
                    val anchorRect = SubpixelGeometry.computeCanvasRect(col, row, ox, oy, mSize, subX = 1, subY = 1)
                    nodes.add(RectNode(anchorRect.left, anchorRect.top, anchorRect.width, anchorRect.height, fill = timingColor))
                }
            }
        }

        // 6. Alignment patterns (EF writeQRCode alignment handling)
        val alignColor = (design.alignmentStyle.color ?: design.alignmentColor) ?: fgColor
        val alignOnlyWhite = design.alignmentStyle.onlyWhite
        val alignShape = design.alignmentStyle.shape
        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue
                if (!matrix.isDark(col, row)) continue
                val ax = ox + col * mSize
                val ay = oy + row * mSize
                if (alignShape != ModuleShape.NONE && !alignOnlyWhite) {
                    when (alignShape) {
                        ModuleShape.CIRCLE -> {
                            nodes.add(CircleNode(ax + mSize / 2f, ay + mSize / 2f, mSize / 2f, fill = alignColor))
                        }
                        ModuleShape.ROUNDED -> {
                            nodes.add(RectNode(ax, ay, mSize, mSize, rx = mSize / 4f, ry = mSize / 4f, fill = alignColor))
                        }
                        else -> {
                            nodes.add(RectNode(ax, ay, mSize, mSize, fill = alignColor))
                        }
                    }
                } else {
                    // EF #Sab: Center 1x1 subpixel anchor at (col + 1/3, row + 1/3)
                    val anchorRect = SubpixelGeometry.computeCanvasRect(col, row, ox, oy, mSize, subX = 1, subY = 1)
                    nodes.add(RectNode(anchorRect.left, anchorRect.top, anchorRect.width, anchorRect.height, fill = alignColor))
                }
            }
        }

        // 7. Center logo on QR matrix
        design.logo?.bitmap?.let { logoBmp ->
            val fraction = design.logo.scaleFraction.coerceIn(0.10f, 0.35f)
            val qrPixelSize = n * mSize
            val qrCenterX = ox + qrPixelSize / 2f
            val qrCenterY = oy + qrPixelSize / 2f
            val logoSize = qrPixelSize * fraction
            val logoX = qrCenterX - logoSize / 2f
            val logoY = qrCenterY - logoSize / 2f
            val cardPadding = 0.5f * mSize
            nodes.add(
                RectNode(
                    x = logoX - cardPadding,
                    y = logoY - cardPadding,
                    width = logoSize + 2 * cardPadding,
                    height = logoSize + 2 * cardPadding,
                    rx = 1.5f * mSize,
                    ry = 1.5f * mSize,
                    fill = design.palette.background
                )
            )
            nodes.add(
                ImageNode(
                    x = logoX,
                    y = logoY,
                    width = logoSize,
                    height = logoSize,
                    bitmap = logoBmp,
                    base64Data = IrSvgRenderer.bitmapToBase64(logoBmp),
                    preserveAspectRatio = "xMidYMid meet"
                )
            )
        }

        return QrGeometryIr(
            width = width,
            height = height,
            viewBox = "0 0 ${width.toInt()} ${height.toInt()}",
            rootNodes = nodes
        )
    }
}
