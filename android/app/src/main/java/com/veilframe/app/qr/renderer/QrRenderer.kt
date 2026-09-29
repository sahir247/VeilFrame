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
 * Draws the center logo using the authoritative VeilIconPipeline.
 */
internal fun drawLogo(
    canvas: Canvas,
    design: QrDesign,
    geometry: QrGeometry,
    context: RenderContext,
    frameIndex: Int = context.frameIndex
) {
    com.veilframe.app.qr.geometry.VeilIconPipeline.drawLogo(
        canvas = canvas,
        design = design,
        ox = geometry.offsetX,
        oy = geometry.offsetY,
        qrPixelSize = geometry.matrixSize * geometry.moduleSize,
        context = context,
        frameIndex = frameIndex
    )
}

/**
 * Legacy drawLogo overload delegating to VeilIconPipeline.
 */
internal fun drawLogo(
    canvas: Canvas,
    params: QrStyleParams,
    outputSize: Int,
    frameIndex: Int = 0
) {
    val design = QrDesign.fromQrStyleParams(params)
    com.veilframe.app.qr.geometry.VeilIconPipeline.drawLogo(
        canvas = canvas,
        design = design,
        ox = 0f,
        oy = 0f,
        qrPixelSize = outputSize.toFloat(),
        frameIndex = frameIndex
    )
}
