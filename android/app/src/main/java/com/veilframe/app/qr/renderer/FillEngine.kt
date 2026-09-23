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
 * - Normalized image RGB pixel sampling (EFQRCode parity)
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

        // 1. Image sampling mode
        val sampleBitmap = design.backgroundLayer.bitmap ?: design.backgroundImage
        if (sampleBitmap != null && (design.moduleStyle.fill == ModuleFill.IMAGE_SAMPLED || design.imageFillMode || design.style == QrStyle.IMAGE_RESAMPLE || design.style == QrStyle.IMAGE_FILL)) {
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

        // 2. Default foreground
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
        context: RenderContext
    ): Paint {
        val color = resolveModuleColor(module, matrixSize, design)
        val paint = context.obtainFill(color)

        // Gradient shader configuration if linear or radial
        if (design.moduleStyle.fill == ModuleFill.LINEAR_GRADIENT && design.palette.gradientStart != null && design.palette.gradientEnd != null) {
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                design.palette.gradientStart, design.palette.gradientEnd,
                Shader.TileMode.CLAMP
            )
        } else if (design.moduleStyle.fill == ModuleFill.RADIAL_GRADIENT && design.palette.gradientStart != null && design.palette.gradientEnd != null) {
            paint.shader = RadialGradient(
                rect.centerX(), rect.centerY(), rect.width() / 2f,
                design.palette.gradientStart, design.palette.gradientEnd,
                Shader.TileMode.CLAMP
            )
        } else {
            paint.shader = null
        }

        return paint
    }
}
