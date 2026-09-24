package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 6 — IMAGE (EFQRCodeStyleImage Parity)
 *
 * Full multi-layer EFQRCode architecture:
 * 1. Optional pre-pass: When [allowTransparent] is true, renders underlying data modules
 *    (dark with [dataColorDark], light with [dataColorLight]) before the image.
 * 2. Image layer with finder cutout: Continuous image scaled over the QR matrix, with
 *    8x8 finder boxes cut out (mask "#hole") so image never enters finder areas.
 * 3. Position Patterns: 8x8 solid backing rect with [posLightColor], followed by outer ring
 *    and inner core in [posDarkColor] (styles: rectangle, round, roundedRectangle, planets, dsj).
 * 4. Timing & Alignment Patterns: Dedicated dark and light module rendering with custom shapes/sizes.
 * 5. Data Modules: Dark and light data modules drawn on top of the image with their respective
 *    colors, shapes, and scale.
 * 6. Center Logo: Rendered centered on top if present.
 */
class ImageRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val mSize = geometry.moduleSize
        val x0 = geometry.offsetX
        val y0 = geometry.offsetY
        val dataBounds = geometry.dataRegionBounds()

        val sourceImage = design.imageSource.bitmap

        if (sourceImage == null) {
            BasicRenderer().render(matrix, design, canvas, geometry, context)
            return
        }

        val dataShape = design.moduleStyle.shape
        val dataScale = design.moduleStyle.scale.coerceIn(0.1f, 1.0f)
        val dataDarkColor = design.dataColorDark
        val dataLightColor = design.dataColorLight
        val allowTransparent = design.allowTransparent

        val posStyle = design.eyeStyle.style
        val posDarkColor = design.positionDarkColor
        val posLightColor = design.positionLightColor
        val posSize = design.positionSize.coerceIn(0.1f, 2.0f)

        val timingShape = design.timingStyle.shape
        val timingDarkColor = design.timingDarkColor
        val timingLightColor = design.timingLightColor
        val timingSize = design.timingSize.coerceIn(0.1f, 1.0f)

        val alignShape = design.alignmentStyle.shape
        val alignDarkColor = design.alignDarkColor
        val alignLightColor = design.alignLightColor
        val alignSize = design.alignSize.coerceIn(0.1f, 1.0f)

        val imageAlpha = design.imageSource.opacity.coerceIn(0f, 1f)
        val imageMode = design.imageSource.scaleMode

        // 1. Transparent Pre-Pass: Render dark and light data modules before the image
        if (allowTransparent) {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    val role = matrix.roleAt(col, row)
                    if (role != QrModuleRole.DATA) continue

                    val isDark = matrix.isDark(col, row)
                    val color = if (isDark) dataDarkColor else dataLightColor
                    val alpha = Color.alpha(color)
                    if (alpha == 0) continue

                    val rect = RectF(
                        x0 + col * mSize,
                        y0 + row * mSize,
                        x0 + (col + 1) * mSize,
                        y0 + (row + 1) * mSize
                    )
                    drawModuleShape(canvas, rect, dataShape, color, context)
                }
            }
        }

        // 2. Image Layer with 8x8 Finder Cutout Mask (#hole)
        val tlFinderRect = RectF(x0, y0, x0 + 8 * mSize, y0 + 8 * mSize)
        val trFinderRect = RectF(x0 + (n - 8) * mSize, y0, x0 + n * mSize, y0 + 8 * mSize)
        val blFinderRect = RectF(x0, y0 + (n - 8) * mSize, x0 + 8 * mSize, y0 + n * mSize)

        val saveCount = canvas.save()
        canvas.clipOutRect(tlFinderRect)
        canvas.clipOutRect(trFinderRect)
        canvas.clipOutRect(blFinderRect)
        ImageScaleResolver.drawScaledBitmap(canvas, sourceImage, dataBounds, imageMode, imageAlpha)
        canvas.restoreToCount(saveCount)

        // 3. Finder Patterns (with 8x8 posLightColor backing)
        renderFinder(canvas, x0, y0, 3.5f, 3.5f, 0, 0, mSize, posStyle, posDarkColor, posLightColor, posSize, context)
        renderFinder(canvas, x0, y0, n - 3.5f, 3.5f, n - 8, 0, mSize, posStyle, posDarkColor, posLightColor, posSize, context)
        renderFinder(canvas, x0, y0, 3.5f, n - 3.5f, 0, n - 8, mSize, posStyle, posDarkColor, posLightColor, posSize, context)

        // 4. Timing Tracks (Row 6 / Column 6)
        val timingOffset = (1.0f - timingSize) / 2.0f
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.TIMING) continue

                val isDark = matrix.isDark(col, row)
                val color = if (isDark) timingDarkColor else timingLightColor
                val alpha = Color.alpha(color)
                if (alpha == 0) continue

                val rect = RectF(
                    x0 + (col + timingOffset) * mSize,
                    y0 + (row + timingOffset) * mSize,
                    x0 + (col + timingOffset + timingSize) * mSize,
                    y0 + (row + timingOffset + timingSize) * mSize
                )
                drawModuleShape(canvas, rect, timingShape, color, context)
            }
        }

        // 5. Alignment Patterns
        val alignOffset = (1.0f - alignSize) / 2.0f
        for (col in 0 until n) {
            for (row in 0 until n) {
                val role = matrix.roleAt(col, row)
                if (role != QrModuleRole.ALIGNMENT_CENTER && role != QrModuleRole.ALIGNMENT_BORDER) continue

                val isDark = matrix.isDark(col, row)
                val color = if (isDark) alignDarkColor else alignLightColor
                val alpha = Color.alpha(color)
                if (alpha == 0) continue

                val rect = RectF(
                    x0 + (col + alignOffset) * mSize,
                    y0 + (row + alignOffset) * mSize,
                    x0 + (col + alignOffset + alignSize) * mSize,
                    y0 + (row + alignOffset + alignSize) * mSize
                )
                drawModuleShape(canvas, rect, alignShape, color, context)
            }
        }

        // 6. Data Modules (Dark & Light) on top of Image
        val dataOffset = (1.0f - dataScale) / 2.0f
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.roleAt(col, row) != QrModuleRole.DATA) continue

                val isDark = matrix.isDark(col, row)
                val color = if (isDark) dataDarkColor else dataLightColor
                val alpha = Color.alpha(color)
                if (alpha == 0) continue

                val rect = RectF(
                    x0 + (col + dataOffset) * mSize,
                    y0 + (row + dataOffset) * mSize,
                    x0 + (col + dataOffset + dataScale) * mSize,
                    y0 + (row + dataOffset + dataScale) * mSize
                )
                drawModuleShape(canvas, rect, dataShape, color, context)
            }
        }

        // 7. Center Logo
        drawLogo(canvas, design, geometry, context)
    }

    private fun renderFinder(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        cx: Float,
        cy: Float,
        bgCol: Int,
        bgRow: Int,
        mSize: Float,
        style: FinderStyle,
        darkColor: Int,
        lightColor: Int,
        sizeFactor: Float,
        context: RenderContext
    ) {
        // Draw 8x8 backing rectangle with posLightColor
        val bgPaint = context.obtainFill(lightColor)
        val bgRect = RectF(
            x0 + bgCol * mSize,
            y0 + bgRow * mSize,
            x0 + (bgCol + 8) * mSize,
            y0 + (bgRow + 8) * mSize
        )
        canvas.drawRect(bgRect, bgPaint)

        val centerPx = x0 + cx * mSize
        val centerPy = y0 + cy * mSize
        val darkPaint = context.obtainFill(darkColor)

        when (style) {
            FinderStyle.CIRCLE -> {
                // Inner solid circle (radius 1.5)
                canvas.drawCircle(centerPx, centerPy, 1.5f * mSize, darkPaint)
                // Outer ring stroke (radius 3.0, stroke-width 1.0 * posSize)
                val strokePaint = context.tempPaint.apply {
                    reset()
                    isAntiAlias = true
                    this.style = Paint.Style.STROKE
                    color = darkColor
                    strokeWidth = 1.0f * sizeFactor * mSize
                }
                canvas.drawCircle(centerPx, centerPy, 3.0f * mSize, strokePaint)
            }
            FinderStyle.ROUNDED -> {
                // Inner 3x3 rounded rect
                val innerRect = RectF(centerPx - 1.5f * mSize, centerPy - 1.5f * mSize, centerPx + 1.5f * mSize, centerPy + 1.5f * mSize)
                canvas.drawRoundRect(innerRect, 0.75f * mSize, 0.75f * mSize, darkPaint)
                // Outer 6x6 rounded rect stroke
                val strokePaint = context.tempPaint.apply {
                    reset()
                    isAntiAlias = true
                    this.style = Paint.Style.STROKE
                    color = darkColor
                    strokeWidth = 1.0f * sizeFactor * mSize
                }
                val outerRect = RectF(centerPx - 3.0f * mSize, centerPy - 3.0f * mSize, centerPx + 3.0f * mSize, centerPy + 3.0f * mSize)
                canvas.drawRoundRect(outerRect, 1.5f * mSize, 1.5f * mSize, strokePaint)
            }
            FinderStyle.PLANETS -> {
                // Inner circle
                canvas.drawCircle(centerPx, centerPy, 1.5f * mSize, darkPaint)
                // Outer dashed ring
                val strokePaint = context.tempPaint.apply {
                    reset()
                    isAntiAlias = true
                    this.style = Paint.Style.STROKE
                    color = darkColor
                    strokeWidth = 0.35f * mSize
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.5f * mSize, 0.5f * mSize), 0f)
                }
                canvas.drawCircle(centerPx, centerPy, 3.0f * mSize, strokePaint)
                // Planets satellites
                val planetRadius = 0.5f * sizeFactor * mSize
                val offsets = floatArrayOf(-3f, 3f)
                for (dx in offsets) {
                    canvas.drawCircle(centerPx + dx * mSize, centerPy, planetRadius, darkPaint)
                }
                for (dy in offsets) {
                    canvas.drawCircle(centerPx, centerPy + dy * mSize, planetRadius, darkPaint)
                }
            }
            FinderStyle.DSJ -> {
                // Center 3x3 rect
                val innerRect = RectF(centerPx - 1.5f * mSize, centerPy - 1.5f * mSize, centerPx + 1.5f * mSize, centerPy + 1.5f * mSize)
                canvas.drawRect(innerRect, darkPaint)
                // 4 protruding directional tabs
                canvas.drawRect(centerPx - 3.5f * mSize, centerPy - 1.5f * mSize, centerPx - 2.5f * mSize, centerPy + 1.5f * mSize, darkPaint)
                canvas.drawRect(centerPx + 2.5f * mSize, centerPy - 1.5f * mSize, centerPx + 3.5f * mSize, centerPy + 1.5f * mSize, darkPaint)
                canvas.drawRect(centerPx - 1.5f * mSize, centerPy - 3.5f * mSize, centerPx + 1.5f * mSize, centerPy - 2.5f * mSize, darkPaint)
                canvas.drawRect(centerPx - 1.5f * mSize, centerPy + 2.5f * mSize, centerPx + 1.5f * mSize, centerPy + 3.5f * mSize, darkPaint)
            }
            else -> {
                // CLASSIC / RECTANGLE: Inner 3x3 rect
                val innerRect = RectF(centerPx - 1.5f * mSize, centerPy - 1.5f * mSize, centerPx + 1.5f * mSize, centerPy + 1.5f * mSize)
                canvas.drawRect(innerRect, darkPaint)
                // Outer 6x6 stroke
                val strokePaint = context.tempPaint.apply {
                    reset()
                    isAntiAlias = true
                    this.style = Paint.Style.STROKE
                    color = darkColor
                    strokeWidth = 1.0f * sizeFactor * mSize
                }
                val outerRect = RectF(centerPx - 3.0f * mSize, centerPy - 3.0f * mSize, centerPx + 3.0f * mSize, centerPy + 3.0f * mSize)
                canvas.drawRect(outerRect, strokePaint)
            }
        }
    }

    private fun drawModuleShape(
        canvas: Canvas,
        rect: RectF,
        shape: ModuleShape,
        color: Int,
        context: RenderContext
    ) {
        val paint = context.obtainFill(color)
        when (shape) {
            ModuleShape.CIRCLE, ModuleShape.DOT, ModuleShape.BUBBLE -> {
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
            }
            ModuleShape.ROUNDED, ModuleShape.SQUIRCLE -> {
                val rx = rect.width() * 0.25f
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
            else -> {
                canvas.drawRect(rect, paint)
            }
        }
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
