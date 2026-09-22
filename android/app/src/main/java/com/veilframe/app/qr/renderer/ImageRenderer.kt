package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrVisualGeometry
import com.veilframe.app.qr.QrStyleParams

/**
 * Style 6 — IMAGE (Visual Grammar: Image-Based Photo Fill)
 *
 * Fixed masking architecture:
 * 1. Composites background (quiet zone stays clean).
 * 2. Saves an offscreen layer for stylable data modules.
 * 3. Draws data module masks.
 * 4. Blends source photograph with [PorterDuff.Mode.SRC_IN] so image content
 *    is strictly confined to data modules and NEVER bleeds into quiet zone or finders.
 * 5. Overlays protected high-contrast finder patterns via [FinderRenderer].
 * 6. Composites logo on top.
 */
class ImageRenderer : QrRenderer {

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
            // Fallback to basic rendering if no image provided
            BasicRenderer().render(matrix, design, canvas, geometry, context)
            return
        }

        // Layer bounds restricted to data region so quiet zone is never painted over
        val dataBounds = geometry.dataRegionBounds()

        // 1. Offscreen layer for masked data modules
        val layerId = canvas.saveLayer(dataBounds, null)

        // 2. Draw solid data module mask shapes inside the layer
        val maskPaint = context.obtainFill(Color.BLACK)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                // Exclude protected finders and separators from image mask
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val rect = geometry.moduleRect(col, row, scale)
                val rx = rect.width() * 0.25f
                canvas.drawRoundRect(rect, rx, rx, maskPaint)
            }
        }

        // 3. Composite image using SRC_IN: image only appears where data modules were drawn
        val imgPaint = context.tempPaint
        imgPaint.reset()
        imgPaint.isAntiAlias = true
        imgPaint.isFilterBitmap = true
        imgPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val srcRect = Rect(0, 0, bgImage.width, bgImage.height)
        canvas.drawBitmap(bgImage, srcRect, dataBounds, imgPaint)

        canvas.restoreToCount(layerId)

        // 4. Render protected high-contrast finder patterns ON TOP
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )

        // 5. Render timing and alignment patterns with crisp contrast
        val timingPaint = context.obtainFill(fgColor)
        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val type = matrix.functionMask[col, row]
                if (type == FunctionPatternType.TIMING ||
                    type == FunctionPatternType.ALIGNMENT_CENTER ||
                    type == FunctionPatternType.ALIGNMENT_OTHER
                ) {
                    val rect = geometry.moduleRect(col, row, 0.85f)
                    canvas.drawRoundRect(rect, rect.width() * 0.2f, rect.width() * 0.2f, timingPaint)
                }
            }
        }

        // 6. Draw center logo
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
