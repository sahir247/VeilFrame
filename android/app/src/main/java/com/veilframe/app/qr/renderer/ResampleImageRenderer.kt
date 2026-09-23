package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.RectF
import com.veilframe.app.qr.model.*

/**
 * Style 7 — IMAGE_RESAMPLE (Pixelated image resample into QR matrix)
 *
 * Implements authentic EFQRCode 3x3 stochastic subpixel resampling architecture:
 * - Functional patterns (finders, timing tracks, alignment patterns) maintain crisp solid contrast.
 * - Center subpixel (1, 1) of every dark data module is strictly reserved as the QR bit anchor.
 * - Surrounding 8 subpixels carry stochastic halftone photo dithering.
 * - Consumes strictly [QrDesign.imageSource], with zero fallback to background image.
 */
class ResampleImageRenderer : BaseQrRenderer() {

    override fun renderDataModules(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val sourceBitmap = design.imageSource.bitmap
        val fgPaint = context.obtainFill(design.palette.foreground)

        if (sourceBitmap != null && !sourceBitmap.isRecycled) {
            val subW = (geometry.moduleSize / 3f) * 1.02f
            val subH = (geometry.moduleSize / 3f) * 1.02f

            ResampleSubpixelEngine.traverseSubpixels(
                matrix = matrix,
                source = sourceBitmap,
                style = design.imageSource,
                seed = 42L
            ) { col, row, subX, subY, _ ->
                val baseRect = geometry.moduleRect(col, row, scale = 1.0f)
                val dx = subX % 3
                val dy = subY % 3
                val left = baseRect.left + dx * (geometry.moduleSize / 3f)
                val top = baseRect.top + dy * (geometry.moduleSize / 3f)
                canvas.drawRect(left, top, left + subW, top + subH, fgPaint)
            }
        } else {
            // Clean fallback when no source image provided: normal foreground data module fill
            val n = matrix.size
            val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (!matrix.isDark(col, row)) continue
                    if (matrix.isProtected(col, row)) continue

                    val rect = geometry.moduleRect(col, row, scale)
                    val rx = rect.width() * design.moduleStyle.cornerRadiusFraction.coerceAtLeast(0.15f)
                    canvas.drawRoundRect(rect, rx, rx, fgPaint)
                }
            }
        }
    }
}

