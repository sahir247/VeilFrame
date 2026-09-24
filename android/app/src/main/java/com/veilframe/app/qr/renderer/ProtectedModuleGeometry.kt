package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.ModuleShape

/**
 * Single source of truth for protected QR functional module geometry (Timing Tracks, Alignment Patterns).
 *
 * Guarantees that Canvas rendering and SVG vector generation interpret [ModuleShape]
 * (e.g. [ModuleShape.CIRCLE], [ModuleShape.SQUARE], [ModuleShape.ROUNDED]) identically.
 */
object ProtectedModuleGeometry {

    /**
     * Draws a protected functional module on an Android [Canvas].
     */
    fun drawCanvas(
        canvas: Canvas,
        rect: RectF,
        shape: ModuleShape,
        paint: Paint
    ) {
        when (shape) {
            ModuleShape.CIRCLE, ModuleShape.DOT, ModuleShape.BUBBLE -> {
                val r = (minOf(rect.width(), rect.height()) / 2f) * (if (shape == ModuleShape.DOT) 0.75f else 1.0f)
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, paint)
            }
            ModuleShape.SQUARE, ModuleShape.CONNECTED -> {
                canvas.drawRect(rect, paint)
            }
            ModuleShape.PILL -> {
                val rx = rect.width() / 2f
                val ry = rect.height() * 0.25f
                canvas.drawRoundRect(rect, rx, ry, paint)
            }
            ModuleShape.SQUIRCLE -> {
                val rx = rect.width() * 0.35f
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
            ModuleShape.DIAMOND -> {
                val path = android.graphics.Path()
                path.moveTo(rect.centerX(), rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.centerX(), rect.bottom)
                path.lineTo(rect.left, rect.centerY())
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> {
                val rx = rect.width() * 0.25f
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
        }
    }

    /**
     * Builds matching SVG element markup for a protected functional module.
     *
     * @param shape The [ModuleShape] configured for timing or alignment
     * @param x Top-left X in SVG units
     * @param y Top-left Y in SVG units
     * @param size Module width/height in SVG units
     * @param fill SVG fill color hex or CSS string
     */
    fun buildSvgElement(
        shape: ModuleShape,
        x: Double,
        y: Double,
        size: Double,
        fill: String
    ): String {
        return when (shape) {
            ModuleShape.CIRCLE, ModuleShape.DOT, ModuleShape.BUBBLE -> {
                val cx = x + size / 2.0
                val cy = y + size / 2.0
                val r = (size / 2.0) * (if (shape == ModuleShape.DOT) 0.75 else 1.0)
                """<circle cx="$cx" cy="$cy" r="$r" fill="$fill" />"""
            }
            ModuleShape.SQUARE, ModuleShape.CONNECTED -> {
                """<rect x="$x" y="$y" width="$size" height="$size" fill="$fill" />"""
            }
            ModuleShape.PILL -> {
                val rx = size * 0.5
                val ry = size * 0.25
                """<rect x="$x" y="$y" width="$size" height="$size" rx="$rx" ry="$ry" fill="$fill" />"""
            }
            ModuleShape.SQUIRCLE -> {
                val rx = size * 0.35
                """<rect x="$x" y="$y" width="$size" height="$size" rx="$rx" fill="$fill" />"""
            }
            ModuleShape.DIAMOND -> {
                val cx = x + size / 2.0
                val cy = y + size / 2.0
                val topY = y
                val rightX = x + size
                val bottomY = y + size
                val leftX = x
                """<polygon points="$cx,$topY $rightX,$cy $cx,$bottomY $leftX,$cy" fill="$fill" />"""
            }
            else -> {
                val rx = size * 0.25
                """<rect x="$x" y="$y" width="$size" height="$size" rx="$rx" fill="$fill" />"""
            }
        }
    }
}
