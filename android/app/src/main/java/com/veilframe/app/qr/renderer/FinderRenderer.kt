package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrVisualGeometry

/**
 * Unified renderer for QR code position detection patterns (finders).
 *
 * Implements canonical 7x7 outer frame, 5x5 light ring, and 3x3 central core geometry
 * while supporting visual variants (Classic, Rounded, Circle, Soft, Frame, Planets, DSJ)
 * inspired by EFQRCode specifications.
 */
object FinderRenderer {

    fun renderFinders(
        canvas: Canvas,
        geometry: QrGeometry,
        style: FinderStyle,
        outerColor: Int,
        innerColor: Int,
        backgroundColor: Int,
        context: RenderContext
    ) {
        for (i in 0..2) {
            val bounds = geometry.finderBounds(i)
            renderSingleFinder(canvas, bounds, geometry.moduleSize, style, outerColor, innerColor, backgroundColor, context)
        }
    }

    private fun renderSingleFinder(
        canvas: Canvas,
        bounds: RectF,
        cellSize: Float,
        style: FinderStyle,
        outerColor: Int,
        innerColor: Int,
        backgroundColor: Int,
        context: RenderContext
    ) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        when (style) {
            FinderStyle.CLASSIC -> {
                // Outer 7x7 dark box
                canvas.drawRect(bounds, context.obtainFill(outerColor))

                // Inner 5x5 light box
                val innerLight = RectF(bounds.left + cellSize, bounds.top + cellSize, bounds.right - cellSize, bounds.bottom - cellSize)
                canvas.drawRect(innerLight, context.obtainFill(backgroundColor))

                // Core 3x3 dark box
                val core = RectF(bounds.left + (2 * cellSize), bounds.top + (2 * cellSize), bounds.right - (2 * cellSize), bounds.bottom - (2 * cellSize))
                canvas.drawRect(core, context.obtainFill(innerColor))
            }

            FinderStyle.ROUNDED -> {
                // Outer 7x7 squircle
                val outerPath = QrVisualGeometry.createSquirclePath(bounds, context.tempPath1)
                canvas.drawPath(outerPath, context.obtainFill(outerColor))

                // Inner 5x5 light squircle
                val innerLight = RectF(bounds.left + cellSize, bounds.top + cellSize, bounds.right - cellSize, bounds.bottom - cellSize)
                val lightPath = QrVisualGeometry.createSquirclePath(innerLight, context.tempPath2)
                canvas.drawPath(lightPath, context.obtainFill(backgroundColor))

                // Core 3x3 dark squircle
                val core = RectF(bounds.left + (2 * cellSize), bounds.top + (2 * cellSize), bounds.right - (2 * cellSize), bounds.bottom - (2 * cellSize))
                val corePath = QrVisualGeometry.createSquirclePath(core, context.tempPath1)
                canvas.drawPath(corePath, context.obtainFill(innerColor))
            }

            FinderStyle.CIRCLE -> {
                // Outer ring: radius 3 modules, stroke width 1 module
                val ringPaint = context.obtainStroke(outerColor, cellSize)
                canvas.drawCircle(cx, cy, 3 * cellSize, ringPaint)

                // Core circle: radius 1.5 modules
                canvas.drawCircle(cx, cy, 1.5f * cellSize, context.obtainFill(innerColor))
            }

            FinderStyle.SOFT -> {
                // Outer rounded rect with gentle radius
                val corner = cellSize * 1.5f
                canvas.drawRoundRect(bounds, corner, corner, context.obtainFill(outerColor))

                // Inner light cutout
                val innerLight = RectF(bounds.left + cellSize, bounds.top + cellSize, bounds.right - cellSize, bounds.bottom - cellSize)
                val innerCorner = cellSize * 0.8f
                canvas.drawRoundRect(innerLight, innerCorner, innerCorner, context.obtainFill(backgroundColor))

                // Core circular dot
                canvas.drawCircle(cx, cy, 1.5f * cellSize, context.obtainFill(innerColor))
            }

            FinderStyle.FRAME -> {
                // Outer stroke frame
                val strokeW = cellSize * 0.8f
                val frameBounds = RectF(bounds.left + strokeW / 2f, bounds.top + strokeW / 2f, bounds.right - strokeW / 2f, bounds.bottom - strokeW / 2f)
                canvas.drawRoundRect(frameBounds, cellSize, cellSize, context.obtainStroke(outerColor, strokeW))

                // Core diamond
                val core = RectF(cx - 1.5f * cellSize, cy - 1.5f * cellSize, cx + 1.5f * cellSize, cy + 1.5f * cellSize)
                val diamond = QrVisualGeometry.createDiamondPath(core, context.tempPath1)
                canvas.drawPath(diamond, context.obtainFill(innerColor))
            }

            FinderStyle.PLANETS -> {
                // Core circle
                canvas.drawCircle(cx, cy, 1.5f * cellSize, context.obtainFill(innerColor))

                // Orbit ring (thin dashed/light stroke)
                val orbitPaint = context.obtainStroke(outerColor, cellSize * 0.35f)
                canvas.drawCircle(cx, cy, 3 * cellSize, orbitPaint)

                // Satellite planet dots at corners
                val dotRadius = cellSize * 0.6f
                val satOffsets = listOf(-3f, 3f)
                val fill = context.obtainFill(outerColor)
                for (ox in satOffsets) {
                    canvas.drawCircle(cx + (ox * cellSize), cy, dotRadius, fill)
                }
                for (oy in satOffsets) {
                    canvas.drawCircle(cx, cy + (oy * cellSize), dotRadius, fill)
                }
            }

            FinderStyle.DSJ -> {
                // Stepped cruciform frame (Dancing Squares)
                val fill = context.obtainFill(outerColor)
                // Center 3x3
                canvas.drawRect(cx - 1.5f * cellSize, cy - 1.5f * cellSize, cx + 1.5f * cellSize, cy + 1.5f * cellSize, fill)
                // Left step
                canvas.drawRect(cx - 3.5f * cellSize, cy - 1.5f * cellSize, cx - 2.5f * cellSize, cy + 1.5f * cellSize, fill)
                // Right step
                canvas.drawRect(cx + 2.5f * cellSize, cy - 1.5f * cellSize, cx + 3.5f * cellSize, cy + 1.5f * cellSize, fill)
                // Top step
                canvas.drawRect(cx - 1.5f * cellSize, cy - 3.5f * cellSize, cx + 1.5f * cellSize, cy - 2.5f * cellSize, fill)
                // Bottom step
                canvas.drawRect(cx - 1.5f * cellSize, cy + 2.5f * cellSize, cx + 1.5f * cellSize, cy + 3.5f * cellSize, fill)
            }
        }
    }
}
