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
            ModuleShape.CIRCLE -> {
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
            }
            ModuleShape.SQUARE -> {
                canvas.drawRect(rect, paint)
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
            ModuleShape.CIRCLE -> {
                val cx = x + size / 2.0
                val cy = y + size / 2.0
                val r = size / 2.0
                """<circle cx="$cx" cy="$cy" r="$r" fill="$fill" />"""
            }
            ModuleShape.SQUARE -> {
                """<rect x="$x" y="$y" width="$size" height="$size" fill="$fill" />"""
            }
            else -> {
                val rx = size * 0.25
                """<rect x="$x" y="$y" width="$size" height="$size" rx="$rx" fill="$fill" />"""
            }
        }
    }
}
