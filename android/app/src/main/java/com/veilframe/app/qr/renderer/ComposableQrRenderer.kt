package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.BlendModeCompat
import androidx.core.graphics.PaintCompat
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

    override fun renderSourceBackdrop(
        canvas: Canvas,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        if (design.style == QrStyle.IMAGE_RESAMPLE && design.resampleStyle.useSourceAsBackdrop) {
            val sourceBitmap = design.imageSource.bitmap
            if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                val fullBounds = RectF(0f, 0f, geometry.outputWidth.toFloat(), geometry.outputHeight.toFloat())
                val (srcRect, dstRect) = ImageScaleResolver.resolveSrcDst(
                    sourceBitmap.width,
                    sourceBitmap.height,
                    fullBounds,
                    design.resampleStyle.backdropScaleMode
                )
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (design.resampleStyle.backdropOpacity.coerceIn(0f, 1f) * 255).toInt()
                    when (design.resampleStyle.backdropBlendMode) {
                        BackdropBlendMode.NORMAL -> {}
                        BackdropBlendMode.MULTIPLY -> PaintCompat.setBlendMode(this, BlendModeCompat.MULTIPLY)
                        BackdropBlendMode.SCREEN -> PaintCompat.setBlendMode(this, BlendModeCompat.SCREEN)
                        BackdropBlendMode.OVERLAY -> PaintCompat.setBlendMode(this, BlendModeCompat.OVERLAY)
                    }
                }
                canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)
                val tint = design.resampleStyle.backdropTint
                if (tint != null) {
                    val tintPaint = context.obtainFill(tint)
                    canvas.drawRect(dstRect, tintPaint)
                }
            }
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

        // 1. IMAGE_RESAMPLE: 3x3 Stochastic subpixel sampling (VeilFrame Art Engine parity)
        if (design.style == QrStyle.IMAGE_RESAMPLE) {
            val sourceBitmap = design.imageSource.bitmap
            if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                val fgPaint = context.obtainFill(design.palette.foreground)

                ResampleSubpixelEngine.traverseSubpixels(
                    matrix = matrix,
                    source = sourceBitmap,
                    style = design.imageSource,
                    seed = design.resampleStyle.seed,
                    policy = ArtisticResamplePolicy.from(design)
                ) { col, row, subX, subY, _ ->
                    val rect = SubpixelGeometry.computeCanvasRect(
                        col = col,
                        row = row,
                        offsetX = geometry.offsetX,
                        offsetY = geometry.offsetY,
                        moduleSize = geometry.moduleSize,
                        subX = subX,
                        subY = subY
                    )
                    canvas.drawRect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height, fgPaint)
                }
                return
            }
            // If no source image provided: falls through to normal foreground data fill
        }

        // 2. IMAGE_MASKED: Stenciled photo fill through dark module paths (Strictly imageSource.bitmap)
        if (design.moduleStyle.fill == ModuleFill.IMAGE_MASKED) {
            val sourceBitmap = design.imageSource.bitmap
            if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                val maskPath = context.tempPath3.apply { reset() }
                val scope = design.imageSource.scope

                if (design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER) {
                    val clusters = BubbleClusterEngine.computeClusters(matrix, design)
                    BubbleClusterRenderer.buildClusterPath(clusters, geometry, maskPath)
                } else {
                    for (col in 0 until n) {
                        for (row in 0 until n) {
                            if (!matrix.isDark(col, row)) continue
                            if (scope == ImageMaskScope.DATA_ONLY && matrix.isProtected(col, row)) continue

                            val module = matrix.moduleAt(col, row)
                            val baseRect = geometry.moduleRect(col, row, design.moduleStyle.scale)
                            ShapeEngine.buildModulePath(module, baseRect, design, context.tempPath1)
                            maskPath.addPath(context.tempPath1)
                        }
                    }
                }

                canvas.save()
                canvas.clipPath(maskPath)

                val dstRect = geometry.dataRegionBounds()
                val (srcRect, finalDstRect) = ImageScaleResolver.resolveSrcDst(
                    sourceBitmap.width,
                    sourceBitmap.height,
                    dstRect,
                    design.imageSource.scaleMode
                )

                if (design.imageSource.scaleMode == ImageScaleMode.ASPECT_FIT) {
                    // Solid white fill in letterbox padding
                    canvas.drawRect(dstRect, context.obtainFill(Color.WHITE))
                }

                val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (design.imageSource.opacity.coerceIn(0f, 1f) * 255).toInt()
                }

                canvas.drawBitmap(sourceBitmap, srcRect, finalDstRect, imagePaint)

                // Optional maskColor tint
                if (design.imageSource.maskAlpha > 0f) {
                    val tintPaint = context.obtainFill(design.imageSource.maskColor).apply {
                        alpha = (design.imageSource.maskAlpha.coerceIn(0f, 1f) * 255).toInt()
                    }
                    canvas.drawRect(dstRect, tintPaint)
                }

                canvas.restore()
                return
            }
            // If no source image provided: falls through to normal foreground data fill
        }

        // 2. BUBBLE_CLUSTER: Hierarchical circular clustering
        if (design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER) {
            val clusters = BubbleClusterEngine.computeClusters(matrix, design)
            BubbleClusterRenderer.render(canvas, clusters, geometry, design, context)
            return
        }

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
