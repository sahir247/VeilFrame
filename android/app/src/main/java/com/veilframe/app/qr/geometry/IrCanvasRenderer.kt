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

    fun render(ir: QrGeometryIr, canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (node in ir.rootNodes) {
            renderNode(node, canvas, paint)
        }
    }

    private fun renderNode(node: QrGeometryNode, canvas: Canvas, paint: Paint) {
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
                    if (node.stroke != null && node.strokeWidth > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.color = node.stroke
                        paint.strokeWidth = node.strokeWidth
                        paint.alpha = (Color.alpha(node.stroke) * node.opacity).toInt().coerceIn(0, 255)
                        canvas.drawPath(path, paint)
                    }
                }
            }
            is ImageNode -> {
                val bmp = node.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    val count = canvas.save()
                    for (clipRect in node.clipOutRects) {
                        canvas.clipOutRect(clipRect)
                    }
                    paint.style = Paint.Style.FILL
                    paint.isFilterBitmap = true
                    paint.isDither = true
                    paint.alpha = (node.opacity.coerceIn(0f, 1f) * 255).toInt()
                    val dst = RectF(node.x, node.y, node.x + node.width, node.y + node.height)
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, dst, paint)
                    canvas.restoreToCount(count)
                }
            }
            is GroupNode -> {
                val count = canvas.save()
                for (child in node.children) {
                    renderNode(child, canvas, paint)
                }
                canvas.restoreToCount(count)
            }
        }
    }
}
