package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import com.veilframe.app.qr.geometry.ImageGeometryBuilder
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 6 — IMAGE (VeilFrameStyleImage Parity)
 *
 * Full multi-layer VeilFrame Art Engine architecture implemented via unified [QrGeometryIr]
 * built by [ImageGeometryBuilder]:
 * 1. Background layer: Solid backdrop in [QrDesign.palette.background].
 * 2. Optional pre-pass: When [allowTransparent] is true and an image is present, renders underlying data modules
 *    (dark with [dataColorDark], light with [dataColorLight]) before the image.
 * 3. Image layer with finder cutout: Continuous image scaled over the QR matrix, with
 *    8x8 finder boxes cut out (mask "#hole") so image never enters finder areas.
 * 4. Position Patterns: 8x8 solid backing rect with [posLightColor], followed by outer ring
 *    and inner core in [posDarkColor] (styles: rectangle, round, roundedRectangle, planets, dsj).
 * 5. Timing Tracks: Dedicated dark and light module rendering with custom shapes/sizes.
 * 6. Alignment Patterns: Dedicated dark and light module rendering with custom shapes/sizes.
 * 7. Data Modules: Dark and light data modules drawn on top of the image with their respective
 *    colors, shapes, and scale.
 * 8. Center Logo: Rendered centered on top if present.
 */
class ImageRenderer : IrBackedQrRenderer {

    override val ownsBackdrop: Boolean
        get() = true

    override fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val crPx = if (design.backdropStyle.cornerRadius > 0f) design.backdropStyle.cornerRadius * geometry.moduleSize else 0f
        val count = if (crPx > 0f) {
            val saveCount = canvas.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(0f, 0f, geometry.outputWidthFloat, geometry.outputHeightFloat, crPx, crPx, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            saveCount
        } else null
        try {
            val ir = generateGeometry(matrix, design, geometry)
            IrCanvasRenderer.render(ir, canvas, frameIndex = context.frameIndex)
        } finally {
            if (count != null) canvas.restoreToCount(count)
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
