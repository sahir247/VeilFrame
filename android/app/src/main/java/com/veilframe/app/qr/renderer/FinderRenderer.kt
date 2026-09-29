package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
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
 * powered by VeilFrame Art Engine specifications.
 */
object FinderRenderer {

    fun renderFinders(
        canvas: Canvas,
        geometry: QrGeometry,
        style: FinderStyle,
        outerColor: Int,
        innerColor: Int,
        backgroundColor: Int,
        scale: Float = 1.0f,
        context: RenderContext
    ) {
        for (i in 0..2) {
            val bounds = geometry.finderBounds(i)
            renderSingleFinder(canvas, bounds, geometry.moduleSize, style, outerColor, innerColor, backgroundColor, scale, context)
        }
    }

    private fun renderSingleFinder(
        canvas: Canvas,
        rawBounds: RectF,
        cellSize: Float,
        style: FinderStyle,
        outerColor: Int,
        innerColor: Int,
        backgroundColor: Int,
        scale: Float = 1.0f,
        context: RenderContext
    ) {
        val cx = rawBounds.centerX()
        val cy = rawBounds.centerY()
        val bounds = if (scale == 1.0f) rawBounds else {
            val halfW = rawBounds.width() * scale / 2f
            val halfH = rawBounds.height() * scale / 2f
            RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        val effectiveCellSize = bounds.width() / 7f
        val isHollow = Color.alpha(backgroundColor) == 0
        val cSize = effectiveCellSize

        when (style) {
            FinderStyle.CLASSIC -> {
                if (isHollow) {
                    // Hollow canonical 7x7 outer frame (stroke 1 module) and 3x3 core.
                    // Allows continuous backdrop to shine cleanly through the 1-module light ring.
                    val halfStroke = cSize * 0.5f
                    val strokeBounds = RectF(
                        bounds.left + halfStroke,
                        bounds.top + halfStroke,
                        bounds.right - halfStroke,
                        bounds.bottom - halfStroke
                    )
                    canvas.drawRect(strokeBounds, context.obtainStroke(outerColor, cSize))
                    val core = RectF(bounds.left + (2 * cSize), bounds.top + (2 * cSize), bounds.right - (2 * cSize), bounds.bottom - (2 * cSize))
                    canvas.drawRect(core, context.obtainFill(innerColor))
                } else {
                    // Outer 7x7 dark box
                    canvas.drawRect(bounds, context.obtainFill(outerColor))

                    // Inner 5x5 light box
                    val innerLight = RectF(bounds.left + cSize, bounds.top + cSize, bounds.right - cSize, bounds.bottom - cSize)
                    canvas.drawRect(innerLight, context.obtainFill(backgroundColor))

                    // Core 3x3 dark box
                    val core = RectF(bounds.left + (2 * cSize), bounds.top + (2 * cSize), bounds.right - (2 * cSize), bounds.bottom - (2 * cSize))
                    canvas.drawRect(core, context.obtainFill(innerColor))
                }
            }

            FinderStyle.ROUNDED -> {
                if (isHollow) {
                    val halfStroke = cSize * 0.5f
                    val strokeBounds = RectF(
                        bounds.left + halfStroke,
                        bounds.top + halfStroke,
                        bounds.right - halfStroke,
                        bounds.bottom - halfStroke
                    )
                    val outerPath = QrVisualGeometry.createSquirclePath(strokeBounds, context.tempPath1)
                    canvas.drawPath(outerPath, context.obtainStroke(outerColor, cSize))

                    val core = RectF(bounds.left + (2 * cSize), bounds.top + (2 * cSize), bounds.right - (2 * cSize), bounds.bottom - (2 * cSize))
                    val corePath = QrVisualGeometry.createSquirclePath(core, context.tempPath2)
                    canvas.drawPath(corePath, context.obtainFill(innerColor))
                } else {
                    // Outer 7x7 squircle
                    val outerPath = QrVisualGeometry.createSquirclePath(bounds, context.tempPath1)
                    canvas.drawPath(outerPath, context.obtainFill(outerColor))

                    // Inner 5x5 light squircle
                    val innerLight = RectF(bounds.left + cSize, bounds.top + cSize, bounds.right - cSize, bounds.bottom - cSize)
                    val lightPath = QrVisualGeometry.createSquirclePath(innerLight, context.tempPath2)
                    canvas.drawPath(lightPath, context.obtainFill(backgroundColor))

                    // Core 3x3 dark squircle
                    val core = RectF(bounds.left + (2 * cSize), bounds.top + (2 * cSize), bounds.right - (2 * cSize), bounds.bottom - (2 * cSize))
                    val corePath = QrVisualGeometry.createSquirclePath(core, context.tempPath1)
                    canvas.drawPath(corePath, context.obtainFill(innerColor))
                }
            }

            FinderStyle.CIRCLE -> {
                // Outer ring: radius 3 modules, stroke width 1 module
                val ringPaint = context.obtainStroke(outerColor, cSize)
                canvas.drawCircle(cx, cy, 3 * cSize, ringPaint)

                // Core circle: radius 1.5 modules
                canvas.drawCircle(cx, cy, 1.5f * cSize, context.obtainFill(innerColor))
            }

            FinderStyle.SOFT -> {
                if (isHollow) {
                    val halfStroke = cSize * 0.5f
                    val strokeBounds = RectF(
                        bounds.left + halfStroke,
                        bounds.top + halfStroke,
                        bounds.right - halfStroke,
                        bounds.bottom - halfStroke
                    )
                    val corner = cSize * 1.5f
                    canvas.drawRoundRect(strokeBounds, corner, corner, context.obtainStroke(outerColor, cSize))
                    canvas.drawCircle(cx, cy, 1.5f * cSize, context.obtainFill(innerColor))
                } else {
                    // Outer rounded rect with gentle radius
                    val corner = cSize * 1.5f
                    canvas.drawRoundRect(bounds, corner, corner, context.obtainFill(outerColor))

                    // Inner light cutout
                    val innerLight = RectF(bounds.left + cSize, bounds.top + cSize, bounds.right - cSize, bounds.bottom - cSize)
                    val innerCorner = cSize * 0.8f
                    canvas.drawRoundRect(innerLight, innerCorner, innerCorner, context.obtainFill(backgroundColor))

                    // Core circular dot
                    canvas.drawCircle(cx, cy, 1.5f * cSize, context.obtainFill(innerColor))
                }
            }

            FinderStyle.FRAME -> {
                // Outer stroke frame
                val strokeW = cSize * 0.8f
                val frameBounds = RectF(bounds.left + strokeW / 2f, bounds.top + strokeW / 2f, bounds.right - strokeW / 2f, bounds.bottom - strokeW / 2f)
                canvas.drawRoundRect(frameBounds, cSize, cSize, context.obtainStroke(outerColor, strokeW))

                // Core diamond
                val core = RectF(cx - 1.5f * cSize, cy - 1.5f * cSize, cx + 1.5f * cSize, cy + 1.5f * cSize)
                val diamond = QrVisualGeometry.createDiamondPath(core, context.tempPath1)
                canvas.drawPath(diamond, context.obtainFill(innerColor))
            }

            FinderStyle.PLANETS -> {
                // Core circle
                canvas.drawCircle(cx, cy, 1.5f * cSize, context.obtainFill(innerColor))

                // Orbit ring (thin dashed/light stroke)
                val orbitPaint = context.obtainStroke(outerColor, cSize * 0.35f)
                canvas.drawCircle(cx, cy, 3 * cSize, orbitPaint)

                // Satellite planet dots at corners
                val dotRadius = cSize * 0.6f
                val satOffsets = listOf(-3f, 3f)
                val fill = context.obtainFill(outerColor)
                for (ox in satOffsets) {
                    canvas.drawCircle(cx + (ox * cSize), cy, dotRadius, fill)
                }
                for (oy in satOffsets) {
                    canvas.drawCircle(cx, cy + (oy * cSize), dotRadius, fill)
                }
            }

            FinderStyle.DSJ -> {
                // Stepped cruciform frame (Dancing Squares)
                val fill = context.obtainFill(outerColor)
                // Center 3x3
                canvas.drawRect(cx - 1.5f * cSize, cy - 1.5f * cSize, cx + 1.5f * cSize, cy + 1.5f * cSize, fill)
                // Left step
                canvas.drawRect(cx - 3.5f * cSize, cy - 1.5f * cSize, cx - 2.5f * cSize, cy + 1.5f * cSize, fill)
                // Right step
                canvas.drawRect(cx + 2.5f * cSize, cy - 1.5f * cSize, cx + 3.5f * cSize, cy + 1.5f * cSize, fill)
                // Top step
                canvas.drawRect(cx - 1.5f * cSize, cy - 3.5f * cSize, cx + 1.5f * cSize, cy - 2.5f * cSize, fill)
                // Bottom step
                canvas.drawRect(cx - 1.5f * cSize, cy + 2.5f * cSize, cx + 1.5f * cSize, cy + 3.5f * cSize, fill)
            }
        }
    }
}
