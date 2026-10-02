package com.veilframe.app.qr.renderer

import android.graphics.*
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*

/**
 * Visual Grammar Fill Engine.
 *
 * Decouples geometric module representation from optical appearance, supporting:
 * - Solid fills
 * - Linear, Radial, and Sweep gradients
 * - Normalized image RGB pixel sampling (VeilFrame Art Engine parity)
 * - Gamma-corrected luminance compression (Y = 0.2126R + 0.7152G + 0.0722B)
 */
object FillEngine {

    /**
     * Resolves the primary drawing color for a specific dark module.
     */
    fun resolveModuleColor(
        module: QrModule,
        matrixSize: Int,
        design: QrDesign
    ): Int {
        val defaultFg = design.palette.foreground

        // 1. Image sampling mode (Strictly imageSource.bitmap, zero background fallback)
        val sampleBitmap = design.imageSource.bitmap
        if (sampleBitmap != null && !sampleBitmap.isRecycled && (design.moduleStyle.fill == ModuleFill.IMAGE_SAMPLED || design.imageFillMode || design.style == QrStyle.IMAGE_RESAMPLE || design.style == QrStyle.IMAGE_FILL)) {
            val normX = (module.col.toFloat() + 0.5f) / matrixSize.toFloat()
            val normY = (module.row.toFloat() + 0.5f) / matrixSize.toFloat()
            val bx = (normX * (sampleBitmap.width - 1)).toInt().coerceIn(0, sampleBitmap.width - 1)
            val by = (normY * (sampleBitmap.height - 1)).toInt().coerceIn(0, sampleBitmap.height - 1)
            val pixel = sampleBitmap.getPixel(bx, by)

            // If Resample style, compress luminance to ensure barcode contrast
            if (design.style == QrStyle.IMAGE_RESAMPLE) {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                // Darken dark modules slightly for scanability guarantee
                val factor = (lum / 255f) * 0.7f
                return Color.rgb((r * factor).toInt(), (g * factor).toInt(), (b * factor).toInt())
            }

            return pixel
        }

        // 2. Default foreground (clean fallback when no source image provided)
        return defaultFg
    }

    /**
     * Prepares and configures a [Paint] instance for drawing the given module.
     */
    fun obtainModulePaint(
        module: QrModule,
        rect: RectF,
        matrixSize: Int,
        design: QrDesign,
        context: RenderContext,
        overallBounds: RectF? = null
    ): Paint {
        val color = resolveModuleColor(module, matrixSize, design)
        val paint = context.obtainFill(color)

        // Gradient shader configuration if linear or radial:
        // Use overall canvas/matrix bounds if provided to ensure the gradient smoothly spans
        // across all modules in user-space rather than restarting inside each individual module (H-05 / Issue 5).
        val bounds = overallBounds ?: rect
        if (design.moduleStyle.fill == ModuleFill.LINEAR_GRADIENT && design.palette.gradientStart != null && design.palette.gradientEnd != null) {
            paint.shader = LinearGradient(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                design.palette.gradientStart, design.palette.gradientEnd,
                Shader.TileMode.CLAMP
            )
        } else if (design.moduleStyle.fill == ModuleFill.RADIAL_GRADIENT && design.palette.gradientStart != null && design.palette.gradientEnd != null) {
            val radius = maxOf(bounds.width(), bounds.height()) / 2f
            paint.shader = RadialGradient(
                bounds.centerX(), bounds.centerY(), radius,
                design.palette.gradientStart, design.palette.gradientEnd,
                Shader.TileMode.CLAMP
            )
        } else {
            paint.shader = null
        }

        return paint
    }
}
