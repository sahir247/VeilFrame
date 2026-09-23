package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 7 — IMAGE_RESAMPLE (Pixelated image resample into QR matrix)
 *
 * The background image is downsampled to the QR module grid size (n×n pixels).
 * Each dark module is drawn with the average color of its corresponding 1×1 pixel
 * in the downsampled image — producing a pixelated photo embedded in the QR.
 *
 * Light modules still render as plain background so scanning works.
 *
 * Mirrors EFQRCodeStyleResampleImage.swift's pixel-color-sampling approach.
 */
class ResampleImageRenderer : QrRenderer {

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
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val hsv = FloatArray(3)

        val bgImage = design.backgroundImage ?: (design.background as? BackgroundStyle.Image)?.bitmap

        if (bgImage == null) {
            // Fallback to basic rendering if no image provided
            BasicRenderer().render(matrix, design, canvas, geometry, context)
            return
        }

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

        // Downsample background image to n×n
        val thumb: Bitmap = Bitmap.createScaledBitmap(bgImage, n, n, true)

        val paint = context.fillPaint

        // 2. Draw remaining modules (Timing, Alignment, Data)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue // Already handled by FinderRenderer
                }

                val type = matrix.functionMask[col, row]
                val rect = geometry.moduleRect(col, row, scale)

                when {
                    type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER -> {
                        // Timing & Alignment: preserve solid foreground for contrast
                        paint.reset()
                        paint.isAntiAlias = true
                        paint.style = Paint.Style.FILL
                        paint.color = fgColor
                        canvas.drawRoundRect(rect, rect.width() * 0.2f, rect.width() * 0.2f, paint)
                    }
                    else -> {
                        // Sample color from downsampled image at (col, row)
                        val moduleColor: Int = if (col < thumb.width && row < thumb.height) {
                            val pixel = thumb.getPixel(col, row)
                            // Apply luminance compression to guarantee dark polarity
                            Color.colorToHSV(pixel, hsv)
                            hsv[2] = 0.05f + (hsv[2] * 0.50f) // Map 0..1 → 0.05..0.55
                            hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1.0f)
                            Color.HSVToColor(hsv)
                        } else {
                            fgColor
                        }

                        paint.reset()
                        paint.isAntiAlias = true
                        paint.style = Paint.Style.FILL
                        paint.color = moduleColor
                        val rx = rect.width() * 0.15f
                        canvas.drawRoundRect(rect, rx, rx, paint)
                    }
                }
            }
        }

        thumb.recycle()

        // 3. Draw center logo
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
