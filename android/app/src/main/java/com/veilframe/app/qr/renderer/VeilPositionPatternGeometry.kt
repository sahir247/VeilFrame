package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.veilframe.app.qr.model.FinderStyle
import java.util.Locale

/**
 * Single source of truth for VeilFrame artistic position pattern rendering.
 *
 * Implements the 5 canonical VeilFrame position styles:
 * - RECTANGLE / CLASSIC: 3x3 inner fill, 6x6 outer stroke
 * - ROUND / CIRCLE: 1.5 radius inner circle, 3.0 radius outer stroke circle
 * - ROUNDED_RECTANGLE / ROUNDED: 1.5 radius inner circle, sq25 squircle outer stroke
 * - PLANETS: 1.5 inner circle, 3.0 dashed circle (0.5, 0.5), 4 satellite circles at dist 3
 * - DSJ: Center rect of size 3 - (1 - posSize), 4 outer protruding tabs of width/height posSize
 *
 * Guarantees exact geometric and visual equivalence between Android Canvas and SVG Vector export.
 */
object VeilPositionPatternGeometry {

    const val SQ25_PATH: String = "M32.048565,-1.29480038e-15 L67.951435,1.29480038e-15 C79.0954192,-7.52316311e-16 83.1364972,1.16032014 87.2105713,3.3391588 C91.2846454,5.51799746 94.4820025,8.71535463 96.6608412,12.7894287 C98.8396799,16.8635028 100,20.9045808 100,32.048565 L100,67.951435 C100,79.0954192 98.8396799,83.1364972 96.6608412,87.2105713 C94.4820025,91.2846454 91.2846454,94.4820025 87.2105713,96.6608412 C83.1364972,98.8396799 79.0954192,100 67.951435,100 L32.048565,100 C20.9045808,100 16.8635028,98.8396799 12.7894287,96.6608412 C8.71535463,94.4820025 5.51799746,91.2846454 3.3391588,87.2105713 C1.16032014,83.1364972 5.01544207e-16,79.0954192 -8.63200256e-16,67.951435 L8.63200256e-16,32.048565 C-5.01544207e-16,20.9045808 1.16032014,16.8635028 3.3391588,12.7894287 C5.51799746,8.71535463 8.71535463,5.51799746 12.7894287,3.3391588 C16.8635028,1.16032014 20.9045808,7.52316311e-16 32.048565,-1.29480038e-15 Z"

    fun isFinderArea(x: Int, y: Int, nCount: Int): Boolean {
        return (x < 7 && y < 7) || (x >= nCount - 7 && y < 7) || (x < 7 && y >= nCount - 7)
    }

    fun isPosCenter(x: Int, y: Int, nCount: Int): Boolean {
        return (x == 3 && y == 3) || (x == nCount - 4 && y == 3) || (x == 3 && y == nCount - 4)
    }

    fun toIrNodes(
        x: Int,
        y: Int,
        moduleSize: Float,
        offsetX: Float,
        offsetY: Float,
        style: FinderStyle,
        size: Float,
        color: Int
    ): List<com.veilframe.app.qr.geometry.QrGeometryNode> {
        val cs = moduleSize
        val ox = offsetX + x * cs
        val oy = offsetY + y * cs
        val cx = ox + cs * 0.5f
        val cy = oy + cs * 0.5f

        val nodes = mutableListOf<com.veilframe.app.qr.geometry.QrGeometryNode>()
        when (style) {
            FinderStyle.CLASSIC -> {
                val left = ox - cs
                val top = oy - cs
                nodes.add(com.veilframe.app.qr.geometry.RectNode(left, top, 3 * cs, 3 * cs, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.RectNode(ox - 2.5f * cs, oy - 2.5f * cs, 6 * cs, 6 * cs, stroke = color, strokeWidth = 1f * size * cs))
            }
            FinderStyle.CIRCLE -> {
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, 1.5f * cs, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, 3.0f * cs, stroke = color, strokeWidth = 1f * size * cs))
            }
            FinderStyle.ROUNDED -> {
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, 1.5f * cs, fill = color))
                val rx = 6f * cs * 0.25f
                nodes.add(com.veilframe.app.qr.geometry.RectNode(ox - 2.5f * cs, oy - 2.5f * cs, 6 * cs, 6 * cs, rx = rx, ry = rx, stroke = color, strokeWidth = 1f * size * cs))
            }
            FinderStyle.PLANETS -> {
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, 1.5f * cs, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy, 3.0f * cs, stroke = color, strokeWidth = 0.15f * cs))
                val satR = 0.5f * size * cs
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx + 3f * cs, cy, satR, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx - 3f * cs, cy, satR, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy + 3f * cs, satR, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.CircleNode(cx, cy - 3f * cs, satR, fill = color))
            }
            FinderStyle.DSJ -> {
                val widthValue = (3.0f - (1.0f - size)) * cs
                val xTempValue = ox + (1.0f - size) / 2.0f * cs
                val yTempValue = oy + (1.0f - size) / 2.0f * cs

                nodes.add(com.veilframe.app.qr.geometry.RectNode(xTempValue - cs, yTempValue - cs, widthValue, widthValue, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.RectNode(xTempValue - 3f * cs, yTempValue - cs, size * cs, widthValue, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.RectNode(xTempValue + 3f * cs, yTempValue - cs, size * cs, widthValue, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.RectNode(xTempValue - cs, yTempValue - 3f * cs, widthValue, size * cs, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.RectNode(xTempValue - cs, yTempValue + 3f * cs, widthValue, size * cs, fill = color))
            }
            else -> {
                val left = ox - cs
                val top = oy - cs
                nodes.add(com.veilframe.app.qr.geometry.RectNode(left, top, 3 * cs, 3 * cs, fill = color))
                nodes.add(com.veilframe.app.qr.geometry.RectNode(ox - 2.5f * cs, oy - 2.5f * cs, 6 * cs, 6 * cs, stroke = color, strokeWidth = 1f * size * cs))
            }
        }
        return nodes
    }

    fun drawCanvas(
        canvas: Canvas,
        x: Int,
        y: Int,
        moduleSize: Float,
        offsetX: Float,
        offsetY: Float,
        style: FinderStyle,
        size: Float,
        color: Int
    ) {
        val cs = moduleSize
        val ox = offsetX + x * cs
        val oy = offsetY + y * cs
        val cx = ox + cs * 0.5f
        val cy = oy + cs * 0.5f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = Paint.Style.STROKE
            this.strokeWidth = 1f * size * cs
        }

        when (style) {
            FinderStyle.CLASSIC -> { // rectangle
                val left = ox - cs
                val top = oy - cs
                canvas.drawRect(left, top, left + 3 * cs, top + 3 * cs, fillPaint)
                canvas.drawRect(ox - 2.5f * cs, oy - 2.5f * cs, ox + 3.5f * cs, oy + 3.5f * cs, strokePaint)
            }
            FinderStyle.CIRCLE -> { // round
                canvas.drawCircle(cx, cy, 1.5f * cs, fillPaint)
                canvas.drawCircle(cx, cy, 3.0f * cs, strokePaint)
            }
            FinderStyle.ROUNDED -> { // roundedRectangle
                canvas.drawCircle(cx, cy, 1.5f * cs, fillPaint)
                val path = Path()
                val rx = 6f * cs * 0.25f
                val rectF = android.graphics.RectF(ox - 2.5f * cs, oy - 2.5f * cs, ox + 3.5f * cs, oy + 3.5f * cs)
                path.addRoundRect(rectF, rx, rx, Path.Direction.CW)
                canvas.drawPath(path, strokePaint)
            }
            FinderStyle.PLANETS -> { // planets
                canvas.drawCircle(cx, cy, 1.5f * cs, fillPaint)
                val dashedPaint = Paint(strokePaint).apply {
                    strokeWidth = 0.15f * cs
                    pathEffect = DashPathEffect(floatArrayOf(0.5f * cs, 0.5f * cs), 0f)
                }
                canvas.drawCircle(cx, cy, 3.0f * cs, dashedPaint)
                val satR = 0.5f * size * cs
                canvas.drawCircle(cx + 3f * cs, cy, satR, fillPaint)
                canvas.drawCircle(cx - 3f * cs, cy, satR, fillPaint)
                canvas.drawCircle(cx, cy + 3f * cs, satR, fillPaint)
                canvas.drawCircle(cx, cy - 3f * cs, satR, fillPaint)
            }
            FinderStyle.DSJ -> { // dsj
                val widthValue = (3.0f - (1.0f - size)) * cs
                val xTempValue = ox + (1.0f - size) / 2.0f * cs
                val yTempValue = oy + (1.0f - size) / 2.0f * cs

                // Center
                canvas.drawRect(xTempValue - cs, yTempValue - cs, xTempValue - cs + widthValue, yTempValue - cs + widthValue, fillPaint)
                // Left
                canvas.drawRect(xTempValue - 3f * cs, yTempValue - cs, xTempValue - 3f * cs + size * cs, yTempValue - cs + widthValue, fillPaint)
                // Right
                canvas.drawRect(xTempValue + 3f * cs, yTempValue - cs, xTempValue + 3f * cs + size * cs, yTempValue - cs + widthValue, fillPaint)
                // Top
                canvas.drawRect(xTempValue - cs, yTempValue - 3f * cs, xTempValue - cs + widthValue, yTempValue - 3f * cs + size * cs, fillPaint)
                // Bottom
                canvas.drawRect(xTempValue - cs, yTempValue + 3f * cs, xTempValue - cs + widthValue, yTempValue + 3f * cs + size * cs, fillPaint)
            }
            else -> {
                val left = ox - cs
                val top = oy - cs
                canvas.drawRect(left, top, left + 3 * cs, top + 3 * cs, fillPaint)
                canvas.drawRect(ox - 2.5f * cs, oy - 2.5f * cs, ox + 3.5f * cs, oy + 3.5f * cs, strokePaint)
            }
        }
    }

    fun buildSvgElements(
        x: Int,
        y: Int,
        qz: Int,
        style: FinderStyle,
        size: Float,
        colorHex: String,
        alpha: Float,
        idStart: Int
    ): Pair<String, Int> {
        val sb = StringBuilder()
        var id = idStart
        val ax = x + qz
        val ay = y + qz
        val alphaStr = String.format(Locale.US, "%.2f", alpha)
        val posSizeStr = String.format(Locale.US, "%.3f", size)

        when (style) {
            FinderStyle.CLASSIC -> { // rectangle
                sb.append("""  <rect key="$id" opacity="$alphaStr" width="3" height="3" fill="$colorHex" x="${ax - 1}" y="${ay - 1}"/>""").append("\n")
                id++
                sb.append("""  <rect key="$id" opacity="$alphaStr" fill="none" stroke-width="$posSizeStr" stroke="$colorHex" x="${ax - 2.5}" y="${ay - 2.5}" width="6" height="6"/>""").append("\n")
                id++
            }
            FinderStyle.CIRCLE -> { // round
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax + 0.5}" cy="${ay + 0.5}" r="1.5"/>""").append("\n")
                id++
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="none" stroke-width="$posSizeStr" stroke="$colorHex" cx="${ax + 0.5}" cy="${ay + 0.5}" r="3"/>""").append("\n")
                id++
            }
            FinderStyle.ROUNDED -> { // roundedRectangle
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax + 0.5}" cy="${ay + 0.5}" r="1.5"/>""").append("\n")
                id++
                val sw = String.format(Locale.US, "%.3f", 100.0 / 6.0 * size)
                sb.append("""  <path key="$id" opacity="$alphaStr" d="$SQ25_PATH" stroke="$colorHex" stroke-width="$sw" fill="none" transform="translate(${ax - 2.5},${ay - 2.5}) scale(0.06,0.06)"/>""").append("\n")
                id++
            }
            FinderStyle.PLANETS -> { // planets
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax + 0.5}" cy="${ay + 0.5}" r="1.5"/>""").append("\n")
                id++
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="none" stroke-width="0.15" stroke-dasharray="0.5,0.5" stroke="$colorHex" cx="${ax + 0.5}" cy="${ay + 0.5}" r="3"/>""").append("\n")
                id++
                val satR = String.format(Locale.US, "%.3f", 0.5 * size)
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax + 3 + 0.5}" cy="${ay + 0.5}" r="$satR"/>""").append("\n")
                id++
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax - 3 + 0.5}" cy="${ay + 0.5}" r="$satR"/>""").append("\n")
                id++
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax + 0.5}" cy="${ay + 3 + 0.5}" r="$satR"/>""").append("\n")
                id++
                sb.append("""  <circle key="$id" opacity="$alphaStr" fill="$colorHex" cx="${ax + 0.5}" cy="${ay - 3 + 0.5}" r="$satR"/>""").append("\n")
                id++
            }
            FinderStyle.DSJ -> { // dsj
                val widthVal = 3.0f - (1.0f - size)
                val widthValStr = String.format(Locale.US, "%.3f", widthVal)
                val xTemp = ax + (1.0f - size) / 2.0f
                val yTemp = ay + (1.0f - size) / 2.0f
                val xtStr = String.format(Locale.US, "%.3f", xTemp - 1)
                val ytStr = String.format(Locale.US, "%.3f", yTemp - 1)

                sb.append("""  <rect key="$id" opacity="$alphaStr" width="$widthValStr" height="$widthValStr" fill="$colorHex" x="$xtStr" y="$ytStr"/>""").append("\n")
                id++
                val xLStr = String.format(Locale.US, "%.3f", xTemp - 3)
                sb.append("""  <rect key="$id" opacity="$alphaStr" width="$posSizeStr" height="$widthValStr" fill="$colorHex" x="$xLStr" y="$ytStr"/>""").append("\n")
                id++
                val xRStr = String.format(Locale.US, "%.3f", xTemp + 3)
                sb.append("""  <rect key="$id" opacity="$alphaStr" width="$posSizeStr" height="$widthValStr" fill="$colorHex" x="$xRStr" y="$ytStr"/>""").append("\n")
                id++
                val yTStr = String.format(Locale.US, "%.3f", yTemp - 3)
                sb.append("""  <rect key="$id" opacity="$alphaStr" width="$widthValStr" height="$posSizeStr" fill="$colorHex" x="$xtStr" y="$yTStr"/>""").append("\n")
                id++
                val yBStr = String.format(Locale.US, "%.3f", yTemp + 3)
                sb.append("""  <rect key="$id" opacity="$alphaStr" width="$widthValStr" height="$posSizeStr" fill="$colorHex" x="$xtStr" y="$yBStr"/>""").append("\n")
                id++
            }
            else -> {
                sb.append("""  <rect key="$id" opacity="$alphaStr" width="3" height="3" fill="$colorHex" x="${ax - 1}" y="${ay - 1}"/>""").append("\n")
                id++
                sb.append("""  <rect key="$id" opacity="$alphaStr" fill="none" stroke-width="$posSizeStr" stroke="$colorHex" x="${ax - 2.5}" y="${ay - 2.5}" width="6" height="6"/>""").append("\n")
                id++
            }
        }
        return Pair(sb.toString(), id)
    }
}

@Deprecated("Renamed to VeilPositionPatternGeometry", ReplaceWith("VeilPositionPatternGeometry"))
typealias EfPositionPatternGeometry = VeilPositionPatternGeometry
