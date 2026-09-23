package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 4 — DSJ (DJ-cross)
 *
 * Each dark position detection pattern is rendered as a cross/DSJ shape
 * via [FinderRenderer] with [FinderStyle.DSJ].
 *
 * Data modules use the same DSJ cross motif scaled to a single cell:
 * the center square and four tiny protruding tabs.
 *
 * Mirrors EFQRCodeStyleDSJ.swift position-pattern rendering and extends it
 * to all data modules for a consistent aesthetic.
 * Allocation-free implementation using [RenderContext].
 */
class DsjRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val cs = geometry.moduleSize
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)

        // 1. Draw DSJ finders
        val eyeOuter = design.eyeStyle.outerColor ?: fgColor
        val eyeInner = design.eyeStyle.innerColor ?: fgColor
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = FinderStyle.DSJ,
            outerColor = eyeOuter,
            innerColor = eyeInner,
            backgroundColor = bgColor,
            context = context
        )

        val fgPaint = context.obtainFill(fgColor)

        // 2. Data and functional modules
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val x = geometry.offsetX + col * cs
                val y = geometry.offsetY + row * cs
                val cx = x + cs * 0.5f
                val cy = y + cs * 0.5f

                drawDsjData(canvas, cx, cy, cs, scale, fgPaint)
            }
        }

        drawLogo(canvas, design, geometry, context)
    }

    /** DSJ data: mini cross in each dark cell. */
    private fun drawDsjData(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        cs: Float,
        scale: Float,
        paint: Paint
    ) {
        val half = cs * scale * 0.5f
        val armW = half * 0.35f

        // Main center square
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
        // Four protruding tabs
        val tab = half * 0.3f
        canvas.drawRect(cx - armW, cy - half - tab, cx + armW, cy - half, paint)  // top
        canvas.drawRect(cx - armW, cy + half, cx + armW, cy + half + tab, paint)  // bottom
        canvas.drawRect(cx - half - tab, cy - armW, cx - half, cy + armW, paint)  // left
        canvas.drawRect(cx + half, cy - armW, cx + half + tab, cy + armW, paint)  // right
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        render(matrix, design, canvas, geometry, RenderContext())
    }
}
