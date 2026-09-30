package com.veilframe.app.qr.geometry

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/**
 * Renders a [QrGeometryIr] directly onto an Android [Canvas].
 */
object IrCanvasRenderer {

    fun render(ir: QrGeometryIr, canvas: Canvas, frameIndex: Int = 0) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (node in ir.rootNodes) {
            renderNode(node, canvas, paint, frameIndex, ir.masks)
        }
    }

    fun renderNode(
        node: QrGeometryNode,
        canvas: Canvas,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG),
        frameIndex: Int = 0,
        masks: Map<String, QrMaskDefinition> = emptyMap()
    ) {
        when (node) {
            is RectNode -> {
                if (node.fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.color = node.fill
                    paint.alpha = (Color.alpha(node.fill) * node.opacity).toInt().coerceIn(0, 255)
                    if (node.rx > 0f || node.ry > 0f) {
                        canvas.drawRoundRect(node.x, node.y, node.x + node.width, node.y + node.height, node.rx, node.ry, paint)
                    } else {
                        canvas.drawRect(node.x, node.y, node.x + node.width, node.y + node.height, paint)
                    }
                }
                if (node.stroke != null && node.strokeWidth > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = node.stroke
                    paint.strokeWidth = node.strokeWidth
                    paint.alpha = (Color.alpha(node.stroke) * node.opacity).toInt().coerceIn(0, 255)
                    if (node.rx > 0f || node.ry > 0f) {
                        canvas.drawRoundRect(node.x, node.y, node.x + node.width, node.y + node.height, node.rx, node.ry, paint)
                    } else {
                        canvas.drawRect(node.x, node.y, node.x + node.width, node.y + node.height, paint)
                    }
                }
            }
            is CircleNode -> {
                if (node.fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.color = node.fill
                    paint.alpha = (Color.alpha(node.fill) * node.opacity).toInt().coerceIn(0, 255)
                    canvas.drawCircle(node.cx, node.cy, node.radius, paint)
                }
                if (node.stroke != null && node.strokeWidth > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = node.stroke
                    paint.strokeWidth = node.strokeWidth
                    paint.alpha = (Color.alpha(node.stroke) * node.opacity).toInt().coerceIn(0, 255)
                    if (node.strokeDashArray != null) {
                        val intervals = node.strokeDashArray.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
                        if (intervals.size >= 2) {
                            paint.pathEffect = android.graphics.DashPathEffect(intervals, 0f)
                        }
                    } else {
                        paint.pathEffect = null
                    }
                    canvas.drawCircle(node.cx, node.cy, node.radius, paint)
                    paint.pathEffect = null
                }
            }
            is LineNode -> {
                paint.style = Paint.Style.STROKE
                paint.color = node.strokeColor
                paint.strokeWidth = node.strokeWidth
                paint.strokeCap = if (node.isRoundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
                if (node.strokeDashArray != null) {
                    val intervals = node.strokeDashArray.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
                    if (intervals.size >= 2) {
                        paint.pathEffect = android.graphics.DashPathEffect(intervals, 0f)
                    }
                } else {
                    paint.pathEffect = null
                }
                canvas.drawLine(node.x1, node.y1, node.x2, node.y2, paint)
                paint.pathEffect = null
            }
            is PolygonNode -> {
                val poly = Path()
                if (node.pointsList.isNotEmpty()) {
                    poly.moveTo(node.pointsList[0].first, node.pointsList[0].second)
                    for (i in 1 until node.pointsList.size) {
                        poly.lineTo(node.pointsList[i].first, node.pointsList[i].second)
                    }
                    poly.close()
                } else if (node.points.isNotEmpty()) {
                    val coords = node.points.trim().split(Regex("[,\\s]+")).mapNotNull { it.toFloatOrNull() }
                    if (coords.size >= 4) {
                        poly.moveTo(coords[0], coords[1])
                        for (i in 2 until coords.size step 2) {
                            if (i + 1 < coords.size) {
                                poly.lineTo(coords[i], coords[i + 1])
                            }
                        }
                        poly.close()
                    }
                }
                if (node.fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.color = node.fill
                    paint.alpha = (Color.alpha(node.fill) * node.opacity).toInt().coerceIn(0, 255)
                    canvas.drawPath(poly, paint)
                }
                if (node.stroke != null && node.strokeWidth > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = node.stroke
                    paint.strokeWidth = node.strokeWidth
                    paint.alpha = (Color.alpha(node.stroke) * node.opacity).toInt().coerceIn(0, 255)
                    canvas.drawPath(poly, paint)
                }
            }
            is PathNode -> {
                val path = node.androidPath
                if (path != null) {
                    if (node.fill != null) {
                        paint.style = Paint.Style.FILL
                        paint.color = node.fill
                        paint.alpha = (Color.alpha(node.fill) * node.opacity).toInt().coerceIn(0, 255)
                        canvas.drawPath(path, paint)
                    }
                    if (node.stroke != null && node.canvasStrokeWidth > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.color = node.stroke
                        paint.strokeWidth = node.canvasStrokeWidth
                        paint.alpha = (Color.alpha(node.stroke) * node.opacity).toInt().coerceIn(0, 255)
                        canvas.drawPath(path, paint)
                    }
                }
            }
            is ImageNode -> {
                val bmp = node.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    val count = canvas.save()
                    val effectiveClip = node.clipPath
                        ?: node.maskId?.let { masks[it]?.clipPath }
                        ?: node.clipPathId?.let { masks[it]?.clipPath }
                    if (effectiveClip != null) {
                        canvas.clipPath(effectiveClip)
                    }
                    val effectiveClipOuts = if (node.clipOutRects.isNotEmpty()) {
                        node.clipOutRects
                    } else {
                        node.maskId?.let { masks[it]?.clipOutRects }
                            ?: node.clipPathId?.let { masks[it]?.clipOutRects }
                            ?: emptyList()
                    }
                    for (clipRect in effectiveClipOuts) {
                        canvas.clipOutRect(clipRect)
                    }
                    paint.style = Paint.Style.FILL
                    paint.isFilterBitmap = true
                    paint.isDither = true
                    paint.alpha = (node.opacity.coerceIn(0f, 1f) * 255).toInt()
                    val dstBounds = RectF(node.x, node.y, node.x + node.width, node.y + node.height)
                    if (node.preserveAspectRatio.isEmpty()) {
                        // Preprocessed bitmap already matches target canvas aspect ratio and bounds.
                        // EF parity: draw directly into dstBounds without second scaling/cropping operation.
                        canvas.drawBitmap(bmp, null, dstBounds, paint)
                    } else {
                        val mode = when {
                            node.preserveAspectRatio.contains("slice", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
                            node.preserveAspectRatio.contains("meet", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT
                            node.preserveAspectRatio.equals("none", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.STRETCH
                            else -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
                        }
                        val (srcRect, resolvedDst) = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveSrcDst(bmp.width, bmp.height, dstBounds, mode)
                        canvas.drawBitmap(bmp, srcRect, resolvedDst, paint)
                    }
                    canvas.restoreToCount(count)
                }
            }
            is AnimatedImageNode -> {
                val safeIdx = if (node.frames.isNotEmpty()) {
                    if (frameIndex >= 0) frameIndex % node.frames.size else 0
                } else 0
                val bmp = node.frames.getOrNull(safeIdx)
                if (bmp != null && !bmp.isRecycled) {
                    val count = canvas.save()
                    val effectiveClip = node.clipPath
                        ?: node.maskId?.let { masks[it]?.clipPath }
                        ?: node.clipPathId?.let { masks[it]?.clipPath }
                    if (effectiveClip != null) {
                        canvas.clipPath(effectiveClip)
                    }
                    val effectiveClipOuts = if (node.clipOutRects.isNotEmpty()) {
                        node.clipOutRects
                    } else {
                        node.maskId?.let { masks[it]?.clipOutRects }
                            ?: node.clipPathId?.let { masks[it]?.clipOutRects }
                            ?: emptyList()
                    }
                    for (clipRect in effectiveClipOuts) {
                        canvas.clipOutRect(clipRect)
                    }
                    paint.style = Paint.Style.FILL
                    paint.isFilterBitmap = true
                    paint.isDither = true
                    paint.alpha = (node.opacity.coerceIn(0f, 1f) * 255).toInt()
                    val dstBounds = RectF(node.x, node.y, node.x + node.width, node.y + node.height)
                    if (node.preserveAspectRatio.isEmpty()) {
                        // Preprocessed bitmap already matches target canvas aspect ratio and bounds.
                        // EF parity: draw directly into dstBounds without second scaling/cropping operation.
                        canvas.drawBitmap(bmp, null, dstBounds, paint)
                    } else {
                        val mode = when {
                            node.preserveAspectRatio.contains("slice", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
                            node.preserveAspectRatio.contains("meet", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT
                            node.preserveAspectRatio.equals("none", ignoreCase = true) -> com.veilframe.app.qr.model.ImageScaleMode.STRETCH
                            else -> com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
                        }
                        val (srcRect, resolvedDst) = com.veilframe.app.qr.renderer.ImageScaleResolver.resolveSrcDst(bmp.width, bmp.height, dstBounds, mode)
                        canvas.drawBitmap(bmp, srcRect, resolvedDst, paint)
                    }
                    canvas.restoreToCount(count)
                }
            }
            is GroupNode -> {
                val count = canvas.save()
                val effectiveClip = node.clipPath
                    ?: node.maskId?.let { masks[it]?.clipPath }
                    ?: node.clipPathId?.let { masks[it]?.clipPath }
                if (effectiveClip != null) {
                    canvas.clipPath(effectiveClip)
                }
                val effectiveClipOuts = if (node.clipOutRects.isNotEmpty()) {
                    node.clipOutRects
                } else {
                    node.maskId?.let { masks[it]?.clipOutRects }
                        ?: node.clipPathId?.let { masks[it]?.clipOutRects }
                        ?: emptyList()
                }
                for (clipRect in effectiveClipOuts) {
                    canvas.clipOutRect(clipRect)
                }
                for (child in node.children) {
                    renderNode(child, canvas, paint, frameIndex, masks)
                }
                canvas.restoreToCount(count)
            }
            is AnimatedGroupNode -> {
                val safeIdx = if (node.frameNodes.isNotEmpty()) {
                    if (frameIndex >= 0) frameIndex % node.frameNodes.size else 0
                } else 0
                val targetFrame = node.frameNodes.getOrNull(safeIdx) ?: emptyList()
                val count = canvas.save()
                for (child in targetFrame) {
                    renderNode(child, canvas, paint, frameIndex, masks)
                }
                canvas.restoreToCount(count)
            }
        }
    }
}
