package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.veilframe.app.qr.model.*
import kotlin.random.Random

/**
 * Style 1 — BASIC (Visual Grammar: Geometric)
 *
 * Implements clean geometric module rendering:
 * - Square, Rounded, Circle, Dot, Squircle, Diamond, Hex, Star, Bubble
 * - High-contrast canonical finder patterns via [FinderRenderer]
 * - Protected structural lifecycle via [BaseQrRenderer]
 * - Module fill options (Solid, Linear Gradient, Radial Gradient)
 * - Allocation-free execution via [RenderContext]
 */
class BasicRenderer : BaseQrRenderer() {

    override fun renderDataModules(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val fgColor = design.palette.foreground
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val shape = design.moduleStyle.shape
        val fill = design.moduleStyle.fill

        val hasGradient = fill == ModuleFill.LINEAR_GRADIENT || fill == ModuleFill.RADIAL_GRADIENT ||
            (design.palette.gradientType != GradientType.NONE &&
                design.palette.gradientStart != null && design.palette.gradientEnd != null)

        val fgPaint = context.obtainFill(fgColor)

        if (hasGradient) {
            val startColor = design.palette.gradientStart ?: fgColor
            val endColor = design.palette.gradientEnd ?: fgColor
            val isRadial = fill == ModuleFill.RADIAL_GRADIENT || design.palette.gradientType == GradientType.RADIAL

            fgPaint.shader = if (isRadial) {
                RadialGradient(
                    geometry.offsetX + geometry.contentWidth / 2f,
                    geometry.offsetY + geometry.contentHeight / 2f,
                    maxOf(geometry.contentWidth, geometry.contentHeight) / 2f,
                    startColor,
                    endColor,
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    geometry.offsetX, geometry.offsetY,
                    geometry.offsetX + geometry.contentWidth, geometry.offsetY + geometry.contentHeight,
                    startColor,
                    endColor,
                    Shader.TileMode.CLAMP
                )
            }
        }

        val rng = Random(design.effects.seed)

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                // Skip protected patterns (handled by BaseQrRenderer)
                if (matrix.isProtected(col, row)) continue

                val rect = geometry.moduleRect(col, row, scale)
                drawModuleShape(canvas, rect, shape, design.moduleStyle.cornerRadiusFraction, fgPaint, context, rng)
            }
        }
    }

    private fun drawModuleShape(
        canvas: Canvas,
        rect: RectF,
        shape: ModuleShape,
        cornerFraction: Float,
        paint: Paint,
        context: RenderContext,
        rng: Random? = null
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width()

        when (shape) {
            ModuleShape.NONE -> return
            ModuleShape.SQUARE -> {
                canvas.drawRect(rect, paint)
            }
            ModuleShape.ROUNDED -> {
                val rx = w * cornerFraction
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
            ModuleShape.CIRCLE -> {
                canvas.drawCircle(cx, cy, w / 2f, paint)
            }
            ModuleShape.DOT -> {
                val r = (w / 2f) * 0.75f
                canvas.drawCircle(cx, cy, r, paint)
            }
            ModuleShape.SQUIRCLE -> {
                val path = QrVisualGeometry.createSquirclePath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.DIAMOND -> {
                val path = QrVisualGeometry.createDiamondPath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.HEX -> {
                val path = QrVisualGeometry.createHexagonPath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.STAR -> {
                val path = QrVisualGeometry.createStarPath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.BUBBLE -> {
                val path = QrVisualGeometry.createBubblePath(rect, context.tempPath1)
                canvas.drawPath(path, paint)
            }
            ModuleShape.ORGANIC -> {
                val factor = rng?.let { it.nextDouble(0.6, 1.0).toFloat() } ?: 0.85f
                canvas.drawCircle(cx, cy, (w / 2f) * factor, paint)
            }
            ModuleShape.PILL -> {
                val rx = w / 2f
                val ry = w * 0.35f
                val pillRect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
                canvas.drawRoundRect(pillRect, ry, ry, paint)
            }
            ModuleShape.CONNECTED, ModuleShape.LINE, ModuleShape.BUBBLE_CLUSTER, ModuleShape.CUSTOM -> {
                val rx = w * 0.15f
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
        }
    }
}
