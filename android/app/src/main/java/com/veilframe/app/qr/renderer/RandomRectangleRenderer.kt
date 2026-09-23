package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams
import kotlin.random.Random

/**
 * Style 9 — RANDOM_RECTANGLE
 *
 * Each dark data module is drawn as a rectangle with randomly jittered
 * size (within ±15% of cell size) and a slight random position offset.
 * The jitter seed is deterministic via [design.effects.seed]
 * so re-renders are reproducible.
 *
 * Position patterns are drawn via canonical [FinderRenderer] for reliable scanning.
 * Allocation-free implementation using [RenderContext].
 */
class RandomRectangleRenderer : QrRenderer {

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
        val baseScale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)

        // 1. Draw finders first with protected canonical geometry
        val eyeOuter = design.eyeStyle.outerColor ?: fgColor
        val eyeInner = design.eyeStyle.innerColor ?: fgColor
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = eyeOuter,
            innerColor = eyeInner,
            backgroundColor = bgColor,
            context = context
        )

        val fgPaint = context.obtainFill(fgColor)
        val rng = Random(design.effects.seed)

        // 2. Data and functional modules
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val x = geometry.offsetX + col * cs
                val y = geometry.offsetY + row * cs

                val jitter = rng.nextFloat() * 0.3f - 0.15f // [-0.15, +0.15]
                val scale = (baseScale + jitter).coerceIn(0.25f, 1.0f)
                val dx = (rng.nextFloat() - 0.5f) * cs * 0.1f
                val dy = (rng.nextFloat() - 0.5f) * cs * 0.1f
                val half = cs * scale / 2f
                val cx = x + cs * 0.5f + dx
                val cy = y + cs * 0.5f + dy
                val r = half * rng.nextFloat().coerceIn(0.1f, 0.4f)

                context.tempRectF.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(context.tempRectF, r, r, fgPaint)
            }
        }

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
        render(matrix, design, canvas, geometry, RenderContext())
    }
}
