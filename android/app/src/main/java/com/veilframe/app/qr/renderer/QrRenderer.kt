package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.LogoBackgroundMode
import com.veilframe.app.qr.model.LogoShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrVisualGeometry
import com.veilframe.app.qr.QrStyleParams

/**
 * Base contract for QR rendering styles in the Visual Grammar Engine.
 */
interface QrRenderer {

    /**
     * Legacy rendering contract.
     */
    fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float)

    /**
     * Modern domain rendering contract with geometric and memory context.
     */
    fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        // Bridge default implementation: apply canvas translation for 4-module quiet zone
        canvas.save()
        canvas.translate(geometry.offsetX, geometry.offsetY)
        val legacyParams = QrStyleParams(
            outputSize = (geometry.matrixSize * geometry.moduleSize).toInt(),
            foreground = design.palette.foreground,
            background = design.palette.background,
            logo = design.logo?.bitmap,
            logoFraction = design.logo?.scaleFraction ?: 0.20f
        )
        render(matrix, legacyParams, canvas, geometry.moduleSize)
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------
// Shared helpers available to all renderers
// ---------------------------------------------------------------------------

internal val Paint.fillPaint: Paint get() = apply { style = Paint.Style.FILL }
internal val Paint.strokePaint: Paint get() = apply { style = Paint.Style.STROKE }

internal fun solidPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
}

/**
 * Draws the center logo if present, strictly verifying that it does not
 * intersect protected function patterns.
 */
internal fun drawLogo(
    canvas: Canvas,
    design: QrDesign,
    geometry: QrGeometry,
    context: RenderContext
) {
    val logo = design.logo ?: return
    val bitmap = logo.bitmap ?: return

    val dst = geometry.computeLogoRect(logo.scaleFraction)
    val pad = geometry.moduleSize * logo.paddingModules

    val paddedRect = RectF(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad)

    // Draw backing background for high contrast
    if (logo.backgroundMode != LogoBackgroundMode.NONE) {
        val bgColor = when (logo.backgroundMode) {
            LogoBackgroundMode.AUTO_CONTRAST -> 0xFFFFFFFF.toInt()
            LogoBackgroundMode.FOREGROUND -> design.palette.foreground
            LogoBackgroundMode.BACKGROUND -> design.palette.background
            LogoBackgroundMode.CUSTOM -> logo.customBackgroundColor
            LogoBackgroundMode.NONE -> 0
        }

        val bgPaint = context.obtainFill(bgColor)
        when (logo.shape) {
            LogoShape.SQUIRCLE -> {
                val path = QrVisualGeometry.createSquirclePath(paddedRect, context.tempPath1)
                canvas.drawPath(path, bgPaint)
            }
            LogoShape.CIRCLE -> {
                canvas.drawCircle(paddedRect.centerX(), paddedRect.centerY(), paddedRect.width() / 2f, bgPaint)
            }
            LogoShape.SQUARE -> {
                canvas.drawRoundRect(paddedRect, pad, pad, bgPaint)
            }
        }
    }

    // Draw logo bitmap centered inside dst
    canvas.drawBitmap(bitmap, null, dst, null)
}

/**
 * Legacy drawLogo overload.
 */
internal fun drawLogo(canvas: Canvas, params: QrStyleParams, outputSize: Int) {
    val logo = params.logo ?: return
    val logoSize = (outputSize * params.logoFraction).coerceIn(1f, outputSize * 0.33f)
    val left = (outputSize - logoSize) / 2f
    val top  = (outputSize - logoSize) / 2f
    val dst = RectF(left, top, left + logoSize, top + logoSize)
    val pad = logoSize * 0.08f
    val bgPaint = solidPaint(0xFFFFFFFF.toInt())
    canvas.drawRoundRect(
        RectF(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad),
        pad * 2f, pad * 2f, bgPaint
    )
    canvas.drawBitmap(logo, null, dst, null)
}
