package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import com.veilframe.app.qr.geometry.D25Geometry
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 3 — 2.5D ISOMETRIC
 *
 * Implements axonometric isometric projection:
 * Matrix: `matrix(sqrt(3)/2, 0.5, -sqrt(3)/2, 0.5, 0, 0)`
 * ViewBox: `x = -nCount, y = -nCount/2, width = nCount*2, height = nCount*2`
 *
 * Each dark module is extruded into a 3D isometric block with:
 * - Top Face: Rhombus mapped with [topColor]
 * - Left Face: Skewed parallelogram extending down by [height] with [leftColor]
 * - Right Face: Skewed parallelogram extending down by [height] with [rightColor]
 *
 * Modules are drawn in column-major order (for col in 0 until n, for row in 0 until n)
 * matching EFQRCode painter order.
 */
class Renderer25D : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        D25Geometry.renderCanvas(matrix, design, canvas, geometry)
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
