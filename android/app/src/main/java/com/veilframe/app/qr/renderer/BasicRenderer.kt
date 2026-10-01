package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.geometry.BasicGeometryBuilder
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix

/**
 * Style 1 — BASIC (Visual Grammar: Geometric)
 *
 * Implements clean geometric module rendering backed by unified [QrGeometryIr]:
 * - Square, Rounded, Circle, Dot, Squircle, Diamond, Hex, Star, Bubble, Pill, Organic
 * - High-contrast canonical finder patterns via [VeilPositionPatternGeometry]
 * - Protected structural lifecycle (Format and Version patterns forced square)
 * - Single source of truth across Android Canvas rasterization and SVG vector emission (H-05)
 */
class BasicRenderer : IrBackedQrRenderer {

    override val ownsBackdrop: Boolean
        get() = true

    override fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        return BasicGeometryBuilder.generateGeometry(
            matrix = matrix,
            design = design,
            geometry = geometry
        )
    }

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val ir = generateGeometry(matrix, design, geometry)
        IrCanvasRenderer.render(
            ir = ir,
            canvas = canvas,
            frameIndex = context.frameIndex
        )
    }

    override fun render(
        matrix: QrMatrix,
        params: QrStyleParams,
        canvas: Canvas,
        cellSize: Float
    ) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        render(
            matrix = matrix,
            design = design,
            canvas = canvas,
            geometry = geometry,
            context = RenderContext()
        )
    }
}
