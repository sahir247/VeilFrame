package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.model.EfFunctionDataStyle
import com.veilframe.app.qr.model.EfFunctionType
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Style 10 — FUNCTION (Mathematical Function Custom Module Shapes)
 *
 * Implements EFQRCodeStyleFunction with exact mathematical parity:
 * - 5 position finder styles (.rectangle, .round, .roundedRectangle, .planets, .dsj) via [EfPositionPatternGeometry].
 * - Two canonical mathematical functions:
 *   1. FADE: Cosine radial gradient function `(1 - cos(PI * dist)) / 6 + 1/5`.
 *   2. CIRCLE: Concentric circular band ($5/20 < \text{dist} < 8/20$) with dual-colored
 *      in-band styling across dark and light modules, and optional background ring.
 * - Supports both ROUND and RECTANGLE module styles.
 */
class FunctionRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val nCount = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        val posColor = design.eyeStyle.outerColor ?: design.palette.foreground
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize

        val funcType = design.efFunctionStyle.functionType
        val dataStyle = design.efFunctionStyle.dataStyle
        val dataColor = design.efFunctionStyle.dataColor
        val circleColor = design.efFunctionStyle.circleColor

        // 1. Draw background ring if CIRCLE function + ROUND style
        if (funcType == EfFunctionType.CIRCLE && dataStyle == EfFunctionDataStyle.ROUND) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = circleColor
                style = Paint.Style.STROKE
                strokeWidth = (nCount.toFloat() / 15.0f) * cs
            }
            val ringCx = ox + (nCount.toFloat() / 2.0f) * cs
            val ringCy = oy + (nCount.toFloat() / 2.0f) * cs
            val ringR = (nCount.toFloat() / 2.0f * sqrt(2.0f) * 13.0f / 40.0f) * cs
            canvas.drawCircle(ringCx, ringCy, ringR, ringPaint)
        }

        // 2. Draw finders via canonical EF position geometry
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(nCount - 4, 3),
            Pair(3, nCount - 4)
        )
        for ((fx, fy) in finderCenters) {
            EfPositionPatternGeometry.drawCanvas(
                canvas = canvas,
                x = fx,
                y = fy,
                moduleSize = cs,
                offsetX = ox,
                offsetY = oy,
                style = posStyle,
                size = posSize,
                color = posColor
            )
        }

        val dataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dataColor
            style = Paint.Style.FILL
        }
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = circleColor
            style = Paint.Style.FILL
        }
        val whiteFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.1f * cs
        }

        val centerCoord = (nCount - 1).toFloat() / 2.0f
        val maxDist = (nCount.toFloat() / 2.0f) * sqrt(2.0f)

        // 3. Draw function modules
        for (x in 0 until nCount) {
            for (y in 0 until nCount) {
                if (EfPositionPatternGeometry.isFinderArea(x, y, nCount)) continue

                val isDark = matrix.isDark(x, y)
                val dist = sqrt((centerCoord - x).pow(2) + (centerCoord - y).pow(2)) / maxDist

                when (funcType) {
                    EfFunctionType.FADE -> {
                        val sizeF = (1.0f - cos(PI.toFloat() * dist)) / 6.0f + 1.0f / 5.0f
                        if (isDark) {
                            when (dataStyle) {
                                EfFunctionDataStyle.RECTANGLE -> {
                                    val rectSize = sizeF + 0.2f
                                    val rx = x + (1.0f - rectSize) / 2.0f
                                    val ry = y + (1.0f - rectSize) / 2.0f
                                    canvas.drawRect(
                                        ox + rx * cs,
                                        oy + ry * cs,
                                        ox + (rx + rectSize) * cs,
                                        oy + (ry + rectSize) * cs,
                                        dataPaint
                                    )
                                }
                                EfFunctionDataStyle.ROUND -> {
                                    val cx = ox + (x + 0.5f) * cs
                                    val cy = oy + (y + 0.5f) * cs
                                    canvas.drawCircle(cx, cy, sizeF * cs, dataPaint)
                                }
                            }
                        }
                    }
                    EfFunctionType.CIRCLE -> {
                        var sizeF: Float
                        var activeColor = dataColor
                        var pointVisible = isDark

                        if (dist > 5.0f / 20.0f && dist < 8.0f / 20.0f) {
                            sizeF = 0.5f
                            activeColor = circleColor
                            pointVisible = true
                        } else {
                            sizeF = if (dataStyle == EfFunctionDataStyle.RECTANGLE) 0.15f else 0.25f
                        }

                        if (pointVisible) {
                            val activePaint = if (activeColor == circleColor) circlePaint else dataPaint
                            when (dataStyle) {
                                EfFunctionDataStyle.RECTANGLE -> {
                                    val baseSize = 2.0f * sizeF + 0.1f
                                    if (isDark) {
                                        val rx = x + (1.0f - baseSize) / 2.0f
                                        val ry = y + (1.0f - baseSize) / 2.0f
                                        canvas.drawRect(
                                            ox + rx * cs,
                                            oy + ry * cs,
                                            ox + (rx + baseSize) * cs,
                                            oy + (ry + baseSize) * cs,
                                            activePaint
                                        )
                                    } else {
                                        val rectSize = baseSize - 0.1f
                                        val rx = x + (1.0f - rectSize) / 2.0f
                                        val ry = y + (1.0f - rectSize) / 2.0f
                                        val left = ox + rx * cs
                                        val top = oy + ry * cs
                                        val right = left + rectSize * cs
                                        val bottom = top + rectSize * cs
                                        canvas.drawRect(left, top, right, bottom, whiteFillPaint)
                                        strokePaint.color = activeColor
                                        canvas.drawRect(left, top, right, bottom, strokePaint)
                                    }
                                }
                                EfFunctionDataStyle.ROUND -> {
                                    val cx = ox + (x + 0.5f) * cs
                                    val cy = oy + (y + 0.5f) * cs
                                    if (isDark) {
                                        canvas.drawCircle(cx, cy, sizeF * cs, activePaint)
                                    } else {
                                        canvas.drawCircle(cx, cy, sizeF * cs, whiteFillPaint)
                                        strokePaint.color = activeColor
                                        canvas.drawCircle(cx, cy, sizeF * cs, strokePaint)
                                    }
                                }
                            }
                        }
                    }
                }
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
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
