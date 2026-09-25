package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.ResampleGeometryBuilder
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix

/**
 * Style 7 — IMAGE_RESAMPLE (Pixelated image resample into QR matrix)
 *
 * Implements authentic VeilFrame Art Engine-inspired 3x3 stochastic subpixel resampling architecture:
 * - Structural patterns (finders, timing tracks, alignment patterns) maintain crisp solid contrast per ISO/IEC 18004.
 * - Center subpixel (1, 1) of every dark data module is strictly reserved as the QR bit anchor.
 * - Surrounding 8 subpixels carry stochastic halftone photo dithering.
 * - Consumes strictly [com.veilframe.app.qr.model.QrDesign.imageSource], with zero fallback to background image.
 * - Consumes unified [com.veilframe.app.qr.geometry.QrGeometryIr] intermediate representation matching SVG export.
 */
class ResampleImageRenderer : ComposableQrRenderer() {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry)
        IrCanvasRenderer.render(ir, canvas)
    }
}
