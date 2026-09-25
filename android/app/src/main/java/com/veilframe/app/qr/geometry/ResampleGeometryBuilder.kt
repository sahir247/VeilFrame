package com.veilframe.app.qr.geometry

import android.graphics.Color
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
        geometry: QrGeometry
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

        // 2. Source Image as Continuous Backdrop
        val sourceBmp = design.imageSource.bitmap
        if (design.resampleStyle.useSourceAsBackdrop) {
            val base64 = if (sourceBmp != null && !sourceBmp.isRecycled) IrSvgRenderer.bitmapToBase64(sourceBmp) else "#sourceBackdrop"
            nodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = width,
                    height = height,
                    bitmap = sourceBmp,
                    base64Data = base64,
                    opacity = design.resampleStyle.backdropOpacity.coerceIn(0f, 1f),
                    preserveAspectRatio = "xMidYMid slice"
                )
            )
            val tint = design.resampleStyle.backdropTint
            if (tint != null) {
                nodes.add(RectNode(x = 0f, y = 0f, width = width, height = height, fill = tint))
            }
        }

        // 3. Finders
        val fgColor = design.palette.foreground
        val eyeOuter = design.eyeStyle.outerColor ?: fgColor
        val eyeInner = design.eyeStyle.innerColor ?: fgColor
        val finders = listOf(
            Pair(0, 0),
            Pair(n - 7, 0),
            Pair(0, n - 7)
        )
        for ((col, row) in finders) {
            val fx = ox + col * mSize
            val fy = oy + row * mSize
            val cx = fx + 3.5f * mSize
            val cy = fy + 3.5f * mSize
            when (design.eyeStyle.style) {
                FinderStyle.CIRCLE -> {
                    nodes.add(CircleNode(cx, cy, 3.5f * mSize, fill = eyeOuter))
                    nodes.add(CircleNode(cx, cy, 2.5f * mSize, fill = design.palette.background))
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                }
                FinderStyle.ROUNDED -> {
                    nodes.add(RectNode(fx, fy, 7f * mSize, 7f * mSize, rx = 2f * mSize, ry = 2f * mSize, fill = eyeOuter))
                    nodes.add(RectNode(fx + mSize, fy + mSize, 5f * mSize, 5f * mSize, rx = 1.5f * mSize, ry = 1.5f * mSize, fill = design.palette.background))
                    nodes.add(RectNode(fx + 2f * mSize, fy + 2f * mSize, 3f * mSize, 3f * mSize, rx = 1f * mSize, ry = 1f * mSize, fill = eyeInner))
                }
                FinderStyle.PLANETS -> {
                    nodes.add(CircleNode(cx, cy, 1.5f * mSize, fill = eyeInner))
                    nodes.add(CircleNode(cx, cy, 3.0f * mSize, stroke = eyeOuter, strokeWidth = 0.35f * mSize, strokeDashArray = "${0.5f * mSize},${0.5f * mSize}"))
                    val planetRadius = 0.5f * mSize
                    val offsets = floatArrayOf(-3f, 3f)
                    for (dx in offsets) {
                        nodes.add(CircleNode(cx + dx * mSize, cy, planetRadius, fill = eyeOuter))
                    }
                    for (dy in offsets) {
                        nodes.add(CircleNode(cx, cy + dy * mSize, planetRadius, fill = eyeOuter))
                    }
                }
                FinderStyle.DSJ -> {
                    nodes.add(RectNode(cx - 1.5f * mSize, cy - 1.5f * mSize, 3f * mSize, 3f * mSize, fill = eyeInner))
                    nodes.add(RectNode(cx - 3.5f * mSize, cy - 1.5f * mSize, 1f * mSize, 3f * mSize, fill = eyeOuter))
                    nodes.add(RectNode(cx + 2.5f * mSize, cy - 1.5f * mSize, 1f * mSize, 3f * mSize, fill = eyeOuter))
                    nodes.add(RectNode(cx - 1.5f * mSize, cy - 3.5f * mSize, 3f * mSize, 1f * mSize, fill = eyeOuter))
                    nodes.add(RectNode(cx - 1.5f * mSize, cy + 2.5f * mSize, 3f * mSize, 1f * mSize, fill = eyeOuter))
                }
                else -> {
                    nodes.add(RectNode(fx, fy, 7f * mSize, 7f * mSize, rx = 0.5f * mSize, ry = 0.5f * mSize, fill = eyeOuter))
                    nodes.add(RectNode(fx + mSize, fy + mSize, 5f * mSize, 5f * mSize, rx = 0.3f * mSize, ry = 0.3f * mSize, fill = design.palette.background))
                    nodes.add(RectNode(fx + 2f * mSize, fy + 2f * mSize, 3f * mSize, 3f * mSize, rx = 0.2f * mSize, ry = 0.2f * mSize, fill = eyeInner))
                }
            }
        }

        // 4. Timing tracks
        val timingColor = (design.timingStyle.color ?: design.timingColor) ?: fgColor
        if (design.timingStyle.shape != ModuleShape.NONE) {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue
                    if (!matrix.isDark(col, row)) continue
                    val tx = ox + col * mSize
                    val ty = oy + row * mSize
                    nodes.add(RectNode(tx, ty, mSize, mSize, fill = timingColor))
                }
            }
        }

        // 5. Alignment patterns
        val alignColor = (design.alignmentStyle.color ?: design.alignmentColor) ?: fgColor
        if (design.alignmentStyle.shape != ModuleShape.NONE) {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val role = matrix.roleAt(col, row)
                    if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue
                    if (!matrix.isDark(col, row)) continue
                    val ax = ox + col * mSize
                    val ay = oy + row * mSize
                    nodes.add(RectNode(ax, ay, mSize, mSize, fill = alignColor))
                }
            }
        }

        // 6. Subpixel dots & center anchors from ResampleSubpixelEngine
        if (sourceBmp != null && !sourceBmp.isRecycled) {
            ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                source = sourceBmp,
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

        // 7. Center logo
        design.logo?.bitmap?.let { logoBmp ->
            val fraction = design.logo.scaleFraction.coerceIn(0.10f, 0.35f)
            val logoSize = width * fraction
            val logoX = (width - logoSize) / 2f
            val logoY = (height - logoSize) / 2f
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
