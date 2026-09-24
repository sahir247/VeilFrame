package com.veilframe.app.qr.geometry

import android.graphics.Canvas
import android.graphics.Paint

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
                    paint.alpha = (paint.alpha * node.opacity).toInt().coerceIn(0, 255)
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
                    paint.alpha = (paint.alpha * node.opacity).toInt().coerceIn(0, 255)
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
                    paint.alpha = (paint.alpha * node.opacity).toInt().coerceIn(0, 255)
                    canvas.drawCircle(node.cx, node.cy, node.radius, paint)
                }
                if (node.stroke != null && node.strokeWidth > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = node.stroke
                    paint.strokeWidth = node.strokeWidth
                    paint.alpha = (paint.alpha * node.opacity).toInt().coerceIn(0, 255)
                    canvas.drawCircle(node.cx, node.cy, node.radius, paint)
                }
            }
            is LineNode -> {
                paint.style = Paint.Style.STROKE
                paint.color = node.strokeColor
                paint.strokeWidth = node.strokeWidth
                paint.strokeCap = if (node.isRoundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
                canvas.drawLine(node.x1, node.y1, node.x2, node.y2, paint)
            }
            is PathNode -> {
                // If path can be rendered directly or parsed
                // Native android Path rendering
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
