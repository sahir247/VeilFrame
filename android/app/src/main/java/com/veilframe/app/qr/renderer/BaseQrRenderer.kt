package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.model.*

/**
 * Authoritative template method base class for QR code renderers.
 *
 * Enforces protected module lifecycle per ISO/IEC 18004:
 * 1. Background & Quiet Zone
 * 2. Protected Finders (strictly preserved geometry via [FinderRenderer])
 * 3. Timing Tracks (Row 6 / Column 6)
 * 4. Alignment Patterns (Version >= 2)
 * 5. Data Modules (Creative / artistic shaping and fills)
 * 6. Center Logo (Boundary margin & contrast backdrop)
 *
 * Subclasses implement [renderDataModules] for creative effects, guaranteeing that
 * structural patterns are never corrupted by artistic styling.
 */
abstract class BaseQrRenderer : QrRenderer {

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        // 1. Background & Quiet Zone
        renderBackground(canvas, design, geometry, context)

        // 2. Source Image as Continuous Backdrop (e.g. for IMAGE_RESAMPLE screenshot parity)
        renderSourceBackdrop(canvas, design, geometry, context)

        // 3. Protected Finders
        renderFinders(canvas, matrix, design, geometry, context)

        // 4. Timing Tracks
        renderTiming(canvas, matrix, design, geometry, context)

        // 5. Alignment Patterns
        renderAlignment(canvas, matrix, design, geometry, context)

        // 6. Explicit Format and Version Information (Protected functional modules)
        renderFormatAndVersion(canvas, matrix, design, geometry, context)

        // 7. Data Modules (Artistic / creative area)
        renderDataModules(canvas, matrix, design, geometry, context)

        // 8. Center Logo
        renderLogo(canvas, design, geometry, context)
    }

    open fun renderFormatAndVersion(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        if (design.style == QrStyle.IMAGE_RESAMPLE) {
            // In IMAGE_RESAMPLE, format and version modules are rendered with subpixel precision
            // (center anchors + stochastic dots) rather than solid module blocks.
            return
        }
        val formatColor = design.palette.foreground
        val paint = context.obtainFill(formatColor)
        val n = matrix.size

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val role = matrix.roleAt(col, row)
                if (role == QrModuleRole.FORMAT || role == QrModuleRole.VERSION) {
                    val rect = geometry.moduleRect(col, row, 1.0f)
                    ProtectedModuleGeometry.drawCanvas(canvas, rect, ModuleShape.SQUARE, paint)
                }
            }
        }
    }

    open fun renderBackground(
        canvas: Canvas,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        // Default does nothing because QrGenerator draws canvas background;
        // renderers with custom backdrops can override.
    }

    open fun renderSourceBackdrop(
        canvas: Canvas,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        // Default does nothing; overridden by renderers supporting continuous source backdrops.
    }

    open fun renderFinders(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val fgColor = design.palette.foreground
        val bgColor = if (design.style == QrStyle.IMAGE_RESAMPLE && design.resampleStyle.useSourceAsBackdrop) {
            android.graphics.Color.TRANSPARENT
        } else {
            design.palette.background
        }
        FinderRenderer.renderFinders(
            canvas = canvas,
            geometry = geometry,
            style = design.eyeStyle.style,
            outerColor = design.eyeStyle.outerColor ?: fgColor,
            innerColor = design.eyeStyle.innerColor ?: fgColor,
            backgroundColor = bgColor,
            context = context
        )
    }

    open fun renderTiming(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        if (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite) {
            return
        }
        val timingColor = design.timingStyle.color ?: design.timingColor ?: design.palette.foreground
        val paint = context.obtainFill(timingColor)
        val n = matrix.size

        for (i in 8 until n - 8) {
            // Horizontal timing: row 6
            if (matrix.isDark(i, 6)) {
                val rect = geometry.moduleRect(i, 6, design.timingStyle.scale)
                drawTimingModule(canvas, rect, design.timingStyle.shape, paint, context)
            }
            // Vertical timing: col 6
            if (matrix.isDark(6, i)) {
                val rect = geometry.moduleRect(6, i, design.timingStyle.scale)
                drawTimingModule(canvas, rect, design.timingStyle.shape, paint, context)
            }
        }
    }

    open fun drawTimingModule(
        canvas: Canvas,
        rect: RectF,
        shape: ModuleShape,
        paint: Paint,
        context: RenderContext
    ) {
        ProtectedModuleGeometry.drawCanvas(canvas, rect, shape, paint)
    }

    open fun renderAlignment(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        if (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite) {
            return
        }
        val alignColor = design.alignmentStyle.color ?: design.alignmentColor ?: design.palette.foreground
        val paint = context.obtainFill(alignColor)
        val n = matrix.size

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (!matrix.isDark(col, row)) continue
                val role = matrix.roleAt(col, row)
                if (role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER) {
                    val rect = geometry.moduleRect(col, row, design.alignmentStyle.scale)
                    drawAlignmentModule(canvas, rect, design.alignmentStyle.shape, paint, context)
                }
            }
        }
    }

    open fun drawAlignmentModule(
        canvas: Canvas,
        rect: RectF,
        shape: ModuleShape,
        paint: Paint,
        context: RenderContext
    ) {
        ProtectedModuleGeometry.drawCanvas(canvas, rect, shape, paint)
    }

    abstract fun renderDataModules(
        canvas: Canvas,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    )

    open fun renderLogo(
        canvas: Canvas,
        design: QrDesign,
        geometry: QrGeometry,
        context: RenderContext
    ) {
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
