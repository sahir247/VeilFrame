package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 5 — IMAGE_FILL (Visual Grammar: Image-Based Illustration/Texture)
 *
 * Implements polarity-preserving luminance mapping:
 * Source photograph pixels are sampled per-module, but their luminance is
 * non-linearly mapped into a strictly dark range:
 * - Bright source areas (sky, highlights, white hair) -> still-dark module (V <= 0.35)
 * - Dark source areas -> deeper dark module (V <= 0.10)
 *
 * Preserves the full artistic color tone and variation of the illustration
 * while preventing dark modules from washing out to white or breaking scanning.
 */
class ImageFillRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val n = matrix.size
        val scale = design.moduleStyle.scale.coerceIn(0.6f, 1.0f)
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background

        val bgImage = design.backgroundImage ?: (design.background as? BackgroundStyle.Image)?.bitmap

        if (bgImage == null) {
            BasicRenderer().render(matrix, design, canvas, geometry, context)
            return
        }

        // 1. Draw high-contrast finders
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )

        val hsv = FloatArray(3)
        val paint = context.fillPaint

        val imgW = bgImage.width
        val imgH = bgImage.height

        // 2. Sample and render each module with luminance-mapped color
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                // Exclude finders and separators (already rendered)
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val type = matrix.functionMask[col, row]
                val rect = geometry.moduleRect(col, row, scale)

                if (type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER
                ) {
                    // Timing & Alignment: preserve solid foreground
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = fgColor
                    canvas.drawRoundRect(rect, rect.width() * 0.2f, rect.width() * 0.2f, paint)
                    continue
                }

                // Sample image pixel mapped from normalized QR coordinates
                val sampleX = ((col.toFloat() / n) * imgW).toInt().coerceIn(0, imgW - 1)
                val sampleY = ((row.toFloat() / n) * imgH).toInt().coerceIn(0, imgH - 1)
                val pixel = bgImage.getPixel(sampleX, sampleY)

                Color.colorToHSV(pixel, hsv)
                val sourceBrightness = hsv[2] // 0.0 to 1.0

                // Strict luminance compression: Map 0.0..1.0 to 0.05..0.35
                // Guarantees dark module polarity even over pure white image highlights
                val mappedBrightness = 0.05f + (sourceBrightness * 0.28f)
                hsv[2] = mappedBrightness

                // Slightly boost saturation so image colors remain vivid at lower value
                hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1.0f)

                val mappedColor = Color.HSVToColor(hsv)

                paint.reset()
                paint.isAntiAlias = true
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = mappedColor

                val rx = rect.width() * 0.25f
                canvas.drawRoundRect(rect, rx, rx, paint)
            }
        }

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
