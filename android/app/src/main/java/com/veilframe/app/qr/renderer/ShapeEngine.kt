package com.veilframe.app.qr.renderer

import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*
import java.util.Random

/**
 * Visual Grammar Shape Engine.
 *
 * Translates discrete [QrModule] instances and their spatial [ModuleNeighborhood]
 * into resolution-independent [Path] geometry without mutating binary QR state.
 */
object ShapeEngine {

    /**
     * Builds the geometric path for a given module into [outPath].
     */
    fun buildModulePath(
        module: QrModule,
        rect: RectF,
        design: QrDesign,
        outPath: Path = Path()
    ): Path {
        outPath.reset()
        val shape = design.moduleStyle.shape

        when {
            // DSJ composite geometry
            design.style == QrStyle.DSJ -> {
                QrVisualGeometry.createCompositeDSJ(
                    rect = rect,
                    lineThickness = design.compositeStyle.lineThickness,
                    crossScale = design.compositeStyle.crossScale,
                    path = outPath
                )
            }
            // Deterministic seeded random jitter
            design.style == QrStyle.RANDOM_RECTANGLE -> {
                val seed = design.jitterStyle.seed
                val rnd = Random(seed xor (module.col * 31L + module.row))
                val scaleFactor = 1f - (design.jitterStyle.scaleJitter * rnd.nextFloat().coerceIn(0f, 0.5f))
                val w = rect.width() * scaleFactor
                val h = rect.height() * scaleFactor
                val ox = (rnd.nextFloat() - 0.5f) * rect.width() * design.jitterStyle.offsetJitter
                val oy = (rnd.nextFloat() - 0.5f) * rect.height() * design.jitterStyle.offsetJitter
                val cx = rect.centerX() + ox
                val cy = rect.centerY() + oy
                outPath.addRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, Path.Direction.CW)
            }
            // Line style with directional capuling
            shape == ModuleShape.LINE || design.style == QrStyle.LINE -> {
                QrVisualGeometry.createLinePath(
                    rect = rect,
                    direction = design.lineStyle.direction,
                    thicknessFraction = design.lineStyle.thicknessFraction,
                    roundCaps = design.lineStyle.roundCaps,
                    path = outPath
                )
            }
            // Organic neighborhood-connected blob
            shape == ModuleShape.ORGANIC || shape == ModuleShape.CONNECTED || design.style == QrStyle.CONNECTED_ORGANIC -> {
                QrVisualGeometry.createOrganicBlobPath(rect, module.neighbors, outPath)
            }
            // Bubble styling
            shape == ModuleShape.BUBBLE -> {
                QrVisualGeometry.createBubblePath(rect, outPath)
            }
            // Star
            shape == ModuleShape.STAR -> {
                QrVisualGeometry.createStarPath(rect, outPath)
            }
            // Diamond
            shape == ModuleShape.DIAMOND -> {
                QrVisualGeometry.createDiamondPath(rect, outPath)
            }
            // Hexagon
            shape == ModuleShape.HEX -> {
                QrVisualGeometry.createHexagonPath(rect, outPath)
            }
            // Squircle
            shape == ModuleShape.SQUIRCLE -> {
                QrVisualGeometry.createSquirclePath(rect, outPath)
            }
            // Circle or Dot
            shape == ModuleShape.CIRCLE || shape == ModuleShape.DOT -> {
                val r = minOf(rect.width(), rect.height()) / 2f
                outPath.addCircle(rect.centerX(), rect.centerY(), r, Path.Direction.CW)
            }
            // Pill
            shape == ModuleShape.PILL -> {
                val rx = rect.width() / 2f
                val ry = rect.height() * 0.25f
                outPath.addRoundRect(rect, rx, ry, Path.Direction.CW)
            }
            // Rounded
            shape == ModuleShape.ROUNDED -> {
                val r = minOf(rect.width(), rect.height()) * design.moduleStyle.cornerRadiusFraction.coerceIn(0.1f, 0.5f)
                outPath.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            // Default: Square
            else -> {
                outPath.addRect(rect, Path.Direction.CW)
            }
        }

        return outPath
    }
}
