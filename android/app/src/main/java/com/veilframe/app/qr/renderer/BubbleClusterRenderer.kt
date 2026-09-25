package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Path
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry

/**
 * Android Canvas renderer for [ClusterPrimitive] instances produced by [BubbleClusterEngine].
 *
 * Uses cached paints from [RenderContext] for zero-allocation rendering across frame updates.
 */
object BubbleClusterRenderer {

    fun render(
        canvas: Canvas,
        clusters: List<ClusterPrimitive>,
        geometry: QrGeometry,
        design: QrDesign,
        context: RenderContext
    ) {
        val modulePx = geometry.moduleSize
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background

        for (cluster in clusters) {
            val cx = geometry.offsetX + cluster.cx * modulePx
            val cy = geometry.offsetY + cluster.cy * modulePx
            val r = cluster.radius * modulePx

            if (cluster.isSolid) {
                // Solid singleton dot
                val fillPaint = context.obtainFill(fgColor)
                canvas.drawCircle(cx, cy, r, fillPaint)
            } else {
                // Stroked bubble with center fill
                val fillPaint = context.obtainFill(bgColor)
                canvas.drawCircle(cx, cy, r, fillPaint)

                val strokeW = if (cluster.isAmbient) {
                    cluster.strokeWidthRatio * modulePx
                } else {
                    (cluster.strokeWidthRatio * modulePx).coerceAtLeast(1.0f)
                }
                val strokePaint = context.obtainStroke(fgColor, strokeW)
                canvas.drawCircle(cx, cy, r, strokePaint)

                // Optional inner core dot (for 3x3 cross center)
                if (cluster.hasInnerDot && cluster.innerRadius > 0f) {
                    val innerR = cluster.innerRadius * modulePx
                    val innerPaint = context.obtainFill(fgColor)
                    canvas.drawCircle(cx, cy, innerR, innerPaint)
                }
            }
        }
    }

    /**
     * Builds a geometric [Path] aggregating all dark elements of the bubble clusters.
     * Useful when [ModuleFill.IMAGE_MASKED] is combined with [ModuleShape.BUBBLE_CLUSTER].
     */
    fun buildClusterPath(
        clusters: List<ClusterPrimitive>,
        geometry: QrGeometry,
        outPath: Path
    ) {
        val modulePx = geometry.moduleSize
        for (cluster in clusters) {
            if (cluster.isAmbient) continue // Do not include ambient bubbles in dark data mask

            val cx = geometry.offsetX + cluster.cx * modulePx
            val cy = geometry.offsetY + cluster.cy * modulePx
            val r = cluster.radius * modulePx

            if (cluster.isSolid) {
                outPath.addCircle(cx, cy, r, Path.Direction.CW)
            } else {
                outPath.addCircle(cx, cy, r, Path.Direction.CW)
                if (cluster.hasInnerDot && cluster.innerRadius > 0f) {
                    val innerR = cluster.innerRadius * modulePx
                    outPath.addCircle(cx, cy, innerR, Path.Direction.CW)
                }
            }
        }
    }
}
