package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*
import java.util.Random
import kotlin.math.*

/**
 * Visual Grammar Effect Engine.
 *
 * Applies depth, mathematical transforms, and algorithmic modifications:
 * - 2.5D extruded isometric polygons (DepthStyle)
 * - Controlled mathematical function transforms (Wave, Radial, Ripple, Noise, Spiral, Organic)
 * - Deterministic seeded random jitter (reproducible previews & exports)
 */
object EffectEngine {

    data class ModuleTransform(
        val scaleMultiplier: Float = 1.0f,
        val rotationDegrees: Float = 0.0f,
        val alphaMultiplier: Float = 1.0f,
        val colorOffset: Int = 0
    )

    /**
     * Computes the algorithmic visual transform for a module at (col, row).
     */
    fun computeTransform(
        col: Int,
        row: Int,
        matrixSize: Int,
        design: QrDesign
    ): ModuleTransform {
        if (design.style != QrStyle.FUNCTION && design.style != QrStyle.STYLE_FUNCTION) {
            return ModuleTransform()
        }

        val fStyle = design.functionStyle
        val cx = matrixSize / 2.0
        val cy = matrixSize / 2.0
        val dx = col - cx
        val dy = row - cy
        val dist = sqrt(dx * dx + dy * dy)

        val rawVal: Double = when (fStyle.type) {
            FunctionType.WAVE -> {
                sin(fStyle.frequency * col + fStyle.phase.toDouble())
            }
            FunctionType.RADIAL -> {
                (dist / (matrixSize / 2.0)).coerceIn(0.0, 1.0)
            }
            FunctionType.RIPPLE -> {
                sin(fStyle.frequency * dist + fStyle.phase.toDouble())
            }
            FunctionType.NOISE -> {
                val rnd = Random(fStyle.seed xor (col * 31L + row))
                (rnd.nextDouble() * 2.0) - 1.0
            }
            FunctionType.SPIRAL -> {
                val angle = atan2(dy, dx)
                sin(angle * 2.0 + dist * fStyle.frequency)
            }
            FunctionType.CHECKER -> {
                if ((col + row) % 2 == 0) 1.0 else -1.0
            }
            FunctionType.ORGANIC -> {
                sin(col * 0.5) * cos(row * 0.5)
            }
            FunctionType.RANDOM -> {
                val rnd = Random(fStyle.seed xor (col * 17L + row * 7L))
                (rnd.nextDouble() * 2.0) - 1.0
            }
        }

        // Scale modulation: safe range [0.55 .. 1.0] for scanability
        val scale = (1.0 - (fStyle.amplitude * (0.5 * (rawVal + 1.0)))).toFloat().coerceIn(0.55f, 1.0f)
        val rot = (rawVal * fStyle.rotationDegrees).toFloat()

        return ModuleTransform(
            scaleMultiplier = scale,
            rotationDegrees = rot,
            alphaMultiplier = 1.0f
        )
    }

    /**
     * Renders 2.5D extruded side faces behind a module top face.
     */
    fun render25DSides(
        canvas: Canvas,
        rect: RectF,
        design: QrDesign,
        context: RenderContext
    ) {
        val depth = design.depthStyle.depth
        val faces = QrVisualGeometry.createGridAligned25DFaces(
            rect = rect,
            depth = depth,
            angleDegrees = design.depthStyle.angleDegrees,
            topPath = context.tempPath1,
            leftPath = context.tempPath2,
            rightPath = context.tempPath3
        )

        val leftPaint = context.obtainFill(design.depthStyle.leftColor)
        canvas.drawPath(faces.left, leftPaint)
        val rightPaint = context.obtainFill(design.depthStyle.rightColor)
        canvas.drawPath(faces.right, rightPaint)
    }
}
