package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Style 10 — FUNCTION (Function-based custom module shapes)
 *
 * Each dark data module is rendered as a star/diamond shape whose
 * exact path is computed via a parametric function — mirroring
 * EFQRCodeStyleFunction.swift's function-drawing approach.
 *
 * The default shape is a 4-point star (rhombus with slightly curved sides),
 * with variants based on the module shape configured in the design:
 *
 *   ROUNDED   → soft petal/flower (8-point using sin/cos)
 *   DIAMOND   → diamond (rotated square)
 *   default   → 4-pointed star
 *
 * Position patterns use canonical [FinderRenderer] for consistency.
 */
class FunctionRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val scale = design.moduleStyle.scale.coerceIn(0.4f, 1.0f)
        val shape = design.moduleStyle.shape

        // 1. Draw protected finders first
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )

        val fgPaint = context.obtainFill(fgColor)

        // 2. Draw remaining modules (Timing, Alignment, Data)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue // Already handled by FinderRenderer
                }

                val type = matrix.functionMask[col, row]
                val (cx, cy) = geometry.moduleCenter(col, row)
                val cs = geometry.moduleSize

                when {
                    type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER -> {
                        // Timing & Alignment: crisp circle for contrast
                        canvas.drawCircle(cx, cy, cs * 0.40f, fgPaint)
                    }
                    else -> {
                        drawFunctionModule(canvas, cx, cy, cs, scale, shape, fgPaint, context)
                    }
                }
            }
        }

        // 3. Draw center logo
        drawLogo(canvas, design, geometry, context)
    }

    private fun drawFunctionModule(
        canvas: Canvas, cx: Float, cy: Float,
        cs: Float, scale: Float,
        shape: ModuleShape, paint: Paint,
        context: RenderContext
    ) {
        val r = cs * scale * 0.5f
        val path = context.tempPath1
        when (shape) {
            ModuleShape.ROUNDED, ModuleShape.CIRCLE -> {
                buildFlowerPath(path, cx, cy, r, petals = 8)
            }
            ModuleShape.DIAMOND -> {
                buildDiamondPath(path, cx, cy, r)
            }
            else -> {
                buildStarPath(path, cx, cy, r, points = 4)
            }
        }
        canvas.drawPath(path, paint)
    }

    /** 4 or N-pointed star. */
    private fun buildStarPath(path: Path, cx: Float, cy: Float, outerR: Float, points: Int) {
        path.reset()
        val innerR = outerR * 0.4f
        val angleStep = PI.toFloat() / points
        for (i in 0 until points * 2) {
            val angle = i * angleStep - PI.toFloat() / 2
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * cos(angle); val y = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** N-petal flower shape using sin/cos. */
    private fun buildFlowerPath(path: Path, cx: Float, cy: Float, r: Float, petals: Int) {
        path.reset()
        val steps = 360
        for (i in 0..steps) {
            val t = i.toFloat() / steps * 2 * PI.toFloat()
            val freq = petals.toFloat()
            val pR = r * (0.5f + 0.5f * cos(freq * t))
            val x = cx + pR * cos(t); val y = cy + pR * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** Diamond = rotated square. */
    private fun buildDiamondPath(path: Path, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy - r)  // top
        path.lineTo(cx + r, cy)  // right
        path.lineTo(cx, cy + r)  // bottom
        path.lineTo(cx - r, cy)  // left
        path.close()
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
