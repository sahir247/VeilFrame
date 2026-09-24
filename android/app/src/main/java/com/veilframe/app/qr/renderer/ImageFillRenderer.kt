package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 5 — IMAGE_FILL (EFQRCodeStyleImageFill Parity)
 *
 * Implements EFQRCode continuous image masking:
 * `<mask id="hole">...<rect width="1.02" height="1.02" fill="white"/>...</mask>`
 * `<g mask="url(#hole)"><rect fill="backgroundColor"/><image .../><rect fill="maskColor"/></g>`
 *
 * The source image is continuous across the QR code area and revealed through the dark-module
 * stencil mask, combined with a base [backgroundColor] and an overlay [maskColor] tint.
 * Modules are NOT individually sampled; the full visual gradient of the continuous image shines through.
 */
class ImageFillRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val sourceImage = design.imageSource.bitmap

        if (sourceImage == null) {
            BasicRenderer().render(matrix, design, canvas, geometry, context)
            return
        }

        val n = matrix.size
        val mSize = geometry.moduleSize
        val x0 = geometry.offsetX
        val y0 = geometry.offsetY
        val dataBounds = geometry.dataRegionBounds()

        val bgColor = design.imageFillBackgroundColor
        val maskColor = design.imageFillMaskColor
        val imageAlpha = design.imageSource.opacity.coerceIn(0f, 1f)
        val imageMode = design.imageSource.scaleMode

        // 1. Offscreen layer for masked QR stencil
        val layerId = canvas.saveLayer(dataBounds, null)

        // 2. Draw solid stencil mask of all dark modules with anti-gap 1.02 expansion (matching EF's 1.02 size)
        val maskPaint = context.obtainFill(Color.WHITE)
        val antiGap = 0.01f * mSize

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (matrix.isDark(col, row)) {
                    val left = x0 + col * mSize - antiGap
                    val top = y0 + row * mSize - antiGap
                    val right = x0 + (col + 1) * mSize + antiGap
                    val bottom = y0 + (row + 1) * mSize + antiGap
                    canvas.drawRect(left, top, right, bottom, maskPaint)
                }
            }
        }

        // 3. Composite continuous fill content using SRC_IN
        val contentPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        val contentLayer = canvas.saveLayer(dataBounds, contentPaint)

        // 3a. Solid backgroundColor across QR area
        val bgPaint = context.obtainFill(bgColor)
        canvas.drawRect(dataBounds, bgPaint)

        // 3b. Continuous scaled image across QR area
        ImageScaleResolver.drawScaledBitmap(canvas, sourceImage, dataBounds, imageMode, imageAlpha)

        // 3c. Solid maskColor tint overlay across QR area
        val tintPaint = context.obtainFill(maskColor)
        canvas.drawRect(dataBounds, tintPaint)

        canvas.restoreToCount(contentLayer)
        canvas.restoreToCount(layerId)

        // 4. Center Logo if present
        drawLogo(canvas, design, geometry, context)
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
