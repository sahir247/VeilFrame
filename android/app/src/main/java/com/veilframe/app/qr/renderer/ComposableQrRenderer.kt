package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*

/**
 * Unified Composable QR Renderer.
 *
 * Master orchestrator connecting:
 * - [ShapeEngine] (primitives, 8-way neighborhood organic blobs, directional lines, composite DSJ)
 * - [FillEngine] (solid, linear/radial/sweep gradients, image RGB sampling, gamma luminance)
 * - [EffectEngine] (2.5D isometric depth extrusion, mathematical function art, deterministic seeded jitter)
 * - [FinderRenderer] (strictly protected structural eyes)
 * - [BackgroundLayer] (decoupled background graphics layer)
 */
open class ComposableQrRenderer : BaseQrRenderer() {

    override fun renderBackground(
        canvas: Canvas,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val bg = design.backgroundLayer
        if (!bg.enabled) return

        // 1. Draw base color if specified
        val bgPaint = context.obtainFill(bg.color)
        canvas.drawRect(0f, 0f, geometry.outputWidth.toFloat(), geometry.outputHeight.toFloat(), bgPaint)

        // 2. Draw background image if provided
        val bmp = bg.bitmap ?: design.backgroundImage
        if (bmp != null && !bmp.isRecycled) {
            val alphaPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = ((bg.opacity.coerceIn(0f, 1f)) * 255).toInt()
            }
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = Rect(0, 0, geometry.outputWidth, geometry.outputHeight)
            canvas.drawBitmap(bmp, src, dst, alphaPaint)
        }
    }

    override fun renderDataModules(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val path = context.tempPath4
        val is25D = design.effects.is25D || design.style == QrStyle.D25

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.isProtected(col, row)) continue

                val module = matrix.moduleAt(col, row)
                val baseRect = geometry.moduleRect(col, row, design.moduleStyle.scale)

                // 1. Compute algorithmic function transform if active
                val transform = EffectEngine.computeTransform(col, row, n, design)
                val rect = if (transform.scaleMultiplier != 1.0f) {
                    val w = baseRect.width() * transform.scaleMultiplier
                    val h = baseRect.height() * transform.scaleMultiplier
                    val cx = baseRect.centerX()
                    val cy = baseRect.centerY()
                    context.tempRectF.apply { set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f) }
                } else {
                    baseRect
                }

                // 2. 2.5D Extruded Depth side faces
                if (is25D) {
                    EffectEngine.render25DSides(canvas, rect, design, context)
                }

                // 3. Build shape geometry via ShapeEngine
                ShapeEngine.buildModulePath(module, rect, design, path)

                // 4. Resolve fill color/paint via FillEngine
                val paint = FillEngine.obtainModulePaint(module, rect, n, design, context)

                // 5. Render to canvas (with rotation transform if non-zero)
                if (transform.rotationDegrees != 0f) {
                    canvas.save()
                    canvas.rotate(transform.rotationDegrees, rect.centerX(), rect.centerY())
                    canvas.drawPath(path, paint)
                    canvas.restore()
                } else {
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
