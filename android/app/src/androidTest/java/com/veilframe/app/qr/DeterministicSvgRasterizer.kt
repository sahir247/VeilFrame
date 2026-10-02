package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Deterministic SVG rasterizer for differential Canvas vs. SVG pixel testing (Issue 8).
 *
 * Parses standard SVG XML produced by [com.veilframe.app.qr.QrGenerator.generateSvg]
 * and rasterizes the vector elements onto an Android [Bitmap] using hardware/software [Canvas].
 */
object DeterministicSvgRasterizer {

    private data class LinearGradientDef(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val colors: IntArray,
        val positions: FloatArray
    )

    private data class RadialGradientDef(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val colors: IntArray,
        val positions: FloatArray
    )

    fun rasterize(svgString: String, targetWidth: Int, targetHeight: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(svgString.toByteArray(Charsets.UTF_8)))
        val svg = doc.documentElement

        val vbAttr = svg.getAttribute("viewBox")
        val (vbW, vbH) = if (vbAttr.isNotEmpty()) {
            val parts = vbAttr.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
            if (parts.size >= 4) Pair(parts[2], parts[3]) else Pair(targetWidth.toFloat(), targetHeight.toFloat())
        } else {
            Pair(targetWidth.toFloat(), targetHeight.toFloat())
        }

        val sx = targetWidth.toFloat() / vbW
        val sy = targetHeight.toFloat() / vbH
        val scaleNeeded = sx != 1f || sy != 1f
        if (scaleNeeded) {
            canvas.save()
            canvas.scale(sx, sy)
        }

        val linearGradients = mutableMapOf<String, LinearGradientDef>()
        val radialGradients = mutableMapOf<String, RadialGradientDef>()
        val clipPaths = mutableMapOf<String, Path>()

        // 1. Parse <defs>
        val defsList = svg.getElementsByTagName("defs")
        for (d in 0 until defsList.length) {
            val defsEl = defsList.item(d) as? Element ?: continue
            val children = defsEl.childNodes
            for (c in 0 until children.length) {
                val node = children.item(c) as? Element ?: continue
                when (node.tagName) {
                    "linearGradient" -> {
                        val id = node.getAttribute("id")
                        val x1 = node.getAttribute("x1").toFloatOrNull() ?: 0f
                        val y1 = node.getAttribute("y1").toFloatOrNull() ?: 0f
                        val x2 = node.getAttribute("x2").toFloatOrNull() ?: vbW
                        val y2 = node.getAttribute("y2").toFloatOrNull() ?: vbH
                        val (colors, pos) = parseStops(node)
                        linearGradients[id] = LinearGradientDef(x1, y1, x2, y2, colors, pos)
                    }
                    "radialGradient" -> {
                        val id = node.getAttribute("id")
                        val cx = node.getAttribute("cx").toFloatOrNull() ?: (vbW / 2f)
                        val cy = node.getAttribute("cy").toFloatOrNull() ?: (vbH / 2f)
                        val r = node.getAttribute("r").toFloatOrNull() ?: (maxOf(vbW, vbH) / 2f)
                        val (colors, pos) = parseStops(node)
                        radialGradients[id] = RadialGradientDef(cx, cy, r, colors, pos)
                    }
                    "clipPath" -> {
                        val id = node.getAttribute("id")
                        val rectList = node.getElementsByTagName("rect")
                        if (rectList.length > 0) {
                            val rEl = rectList.item(0) as Element
                            val x = rEl.getAttribute("x").toFloatOrNull() ?: 0f
                            val y = rEl.getAttribute("y").toFloatOrNull() ?: 0f
                            val w = rEl.getAttribute("width").toFloatOrNull() ?: vbW
                            val h = rEl.getAttribute("height").toFloatOrNull() ?: vbH
                            val rx = rEl.getAttribute("rx").toFloatOrNull() ?: 0f
                            val ry = rEl.getAttribute("ry").toFloatOrNull() ?: rx
                            val path = Path().apply {
                                addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                            }
                            clipPaths[id] = path
                        }
                    }
                }
            }
        }

        // 2. Render elements in document order
        val rootChildren = svg.childNodes
        for (i in 0 until rootChildren.length) {
            val node = rootChildren.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                if (el.tagName != "defs") {
                    renderElement(el, canvas, linearGradients, radialGradients, clipPaths)
                }
            }
        }

        if (scaleNeeded) {
            canvas.restore()
        }

        return bitmap
    }

    private fun parseStops(gradEl: Element): Pair<IntArray, FloatArray> {
        val stops = gradEl.getElementsByTagName("stop")
        val colors = mutableListOf<Int>()
        val positions = mutableListOf<Float>()

        for (s in 0 until stops.length) {
            val stop = stops.item(s) as? Element ?: continue
            val offsetStr = stop.getAttribute("offset").trim()
            val offset = if (offsetStr.endsWith("%")) {
                offsetStr.removeSuffix("%").toFloatOrNull()?.div(100f) ?: 0f
            } else {
                offsetStr.toFloatOrNull() ?: 0f
            }

            val colorStr = stop.getAttribute("stop-color").trim()
            val opacityStr = stop.getAttribute("stop-opacity").trim()
            val opacity = opacityStr.toFloatOrNull() ?: 1.0f

            val baseColor = parseColor(colorStr) ?: Color.BLACK
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            val baseA = Color.alpha(baseColor)
            val finalAlpha = (baseA * opacity).toInt().coerceIn(0, 255)
            val argb = Color.argb(finalAlpha, r, g, b)

            positions.add(offset)
            colors.add(argb)
        }

        return Pair(colors.toIntArray(), positions.toFloatArray())
    }

    private fun renderElement(
        el: Element,
        canvas: Canvas,
        linearGradients: Map<String, LinearGradientDef>,
        radialGradients: Map<String, RadialGradientDef>,
        clipPaths: Map<String, Path>,
        inheritedOpacity: Float = 1.0f
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (el.tagName) {
            "g" -> {
                val count = canvas.save()
                val clipId = extractUrlId(el.getAttribute("clip-path"))
                if (clipId != null && clipPaths.containsKey(clipId)) {
                    canvas.clipPath(clipPaths[clipId]!!)
                }
                val op = el.getAttribute("opacity").toFloatOrNull() ?: 1.0f
                applyTransform(el.getAttribute("transform"), canvas)

                val children = el.childNodes
                for (c in 0 until children.length) {
                    val child = children.item(c) as? Element ?: continue
                    renderElement(child, canvas, linearGradients, radialGradients, clipPaths, inheritedOpacity * op)
                }
                canvas.restoreToCount(count)
            }
            "rect" -> {
                val x = el.getAttribute("x").toFloatOrNull() ?: 0f
                val y = el.getAttribute("y").toFloatOrNull() ?: 0f
                val w = el.getAttribute("width").toFloatOrNull() ?: 0f
                val h = el.getAttribute("height").toFloatOrNull() ?: 0f
                val rx = el.getAttribute("rx").toFloatOrNull() ?: 0f
                val ry = el.getAttribute("ry").toFloatOrNull() ?: rx
                val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity

                val fill = el.getAttribute("fill")
                if (fill.isNotEmpty() && fill != "none") {
                    configureFill(paint, fill, opacity, linearGradients, radialGradients)
                    if (rx > 0f || ry > 0f) {
                        canvas.drawRoundRect(x, y, x + w, y + h, rx, ry, paint)
                    } else {
                        canvas.drawRect(x, y, x + w, y + h, paint)
                    }
                    paint.shader = null
                }

                val stroke = el.getAttribute("stroke")
                val strokeW = el.getAttribute("stroke-width").toFloatOrNull() ?: 0f
                if (stroke.isNotEmpty() && stroke != "none" && strokeW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = parseColor(stroke) ?: Color.BLACK
                    paint.strokeWidth = strokeW
                    paint.alpha = ((Color.alpha(paint.color) / 255f) * opacity * 255).toInt().coerceIn(0, 255)
                    if (rx > 0f || ry > 0f) {
                        canvas.drawRoundRect(x, y, x + w, y + h, rx, ry, paint)
                    } else {
                        canvas.drawRect(x, y, x + w, y + h, paint)
                    }
                }
            }
            "circle" -> {
                val cx = el.getAttribute("cx").toFloatOrNull() ?: 0f
                val cy = el.getAttribute("cy").toFloatOrNull() ?: 0f
                val r = el.getAttribute("r").toFloatOrNull() ?: 0f
                val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity

                val fill = el.getAttribute("fill")
                if (fill.isNotEmpty() && fill != "none") {
                    configureFill(paint, fill, opacity, linearGradients, radialGradients)
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.shader = null
                }

                val stroke = el.getAttribute("stroke")
                val strokeW = el.getAttribute("stroke-width").toFloatOrNull() ?: 0f
                if (stroke.isNotEmpty() && stroke != "none" && strokeW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = parseColor(stroke) ?: Color.BLACK
                    paint.strokeWidth = strokeW
                    paint.alpha = ((Color.alpha(paint.color) / 255f) * opacity * 255).toInt().coerceIn(0, 255)

                    val dashAttr = el.getAttribute("stroke-dasharray")
                    if (dashAttr.isNotEmpty()) {
                        val intervals = dashAttr.split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (intervals.size >= 2) {
                            paint.pathEffect = DashPathEffect(intervals, 0f)
                        }
                    }
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.pathEffect = null
                }
            }
            "line" -> {
                val x1 = el.getAttribute("x1").toFloatOrNull() ?: 0f
                val y1 = el.getAttribute("y1").toFloatOrNull() ?: 0f
                val x2 = el.getAttribute("x2").toFloatOrNull() ?: 0f
                val y2 = el.getAttribute("y2").toFloatOrNull() ?: 0f
                val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity
                val stroke = el.getAttribute("stroke")
                val strokeW = el.getAttribute("stroke-width").toFloatOrNull() ?: 1f

                paint.style = Paint.Style.STROKE
                paint.color = parseColor(stroke) ?: Color.BLACK
                paint.strokeWidth = strokeW
                paint.alpha = ((Color.alpha(paint.color) / 255f) * opacity * 255).toInt().coerceIn(0, 255)
                if (el.getAttribute("stroke-linecap") == "round") {
                    paint.strokeCap = Paint.Cap.ROUND
                }
                val dashAttr = el.getAttribute("stroke-dasharray")
                if (dashAttr.isNotEmpty()) {
                    val intervals = dashAttr.split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                    if (intervals.size >= 2) {
                        paint.pathEffect = DashPathEffect(intervals, 0f)
                    }
                }
                canvas.drawLine(x1, y1, x2, y2, paint)
                paint.pathEffect = null
            }
            "polygon" -> {
                val ptsStr = el.getAttribute("points").trim()
                if (ptsStr.isNotEmpty()) {
                    val coords = ptsStr.split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
                    if (coords.size >= 4) {
                        val path = Path()
                        path.moveTo(coords[0], coords[1])
                        for (i in 2 until coords.size step 2) {
                            if (i + 1 < coords.size) {
                                path.lineTo(coords[i], coords[i + 1])
                            }
                        }
                        path.close()

                        val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity
                        val fill = el.getAttribute("fill")
                        if (fill.isNotEmpty() && fill != "none") {
                            configureFill(paint, fill, opacity, linearGradients, radialGradients)
                            canvas.drawPath(path, paint)
                            paint.shader = null
                        }

                        val stroke = el.getAttribute("stroke")
                        val strokeW = el.getAttribute("stroke-width").toFloatOrNull() ?: 0f
                        if (stroke.isNotEmpty() && stroke != "none" && strokeW > 0f) {
                            paint.style = Paint.Style.STROKE
                            paint.color = parseColor(stroke) ?: Color.BLACK
                            paint.strokeWidth = strokeW
                            paint.alpha = ((Color.alpha(paint.color) / 255f) * opacity * 255).toInt().coerceIn(0, 255)
                            canvas.drawPath(path, paint)
                        }
                    }
                }
            }
            "path" -> {
                val d = el.getAttribute("d").trim()
                if (d.isNotEmpty()) {
                    val path = try {
                        PathParser.createPathFromPathData(d)
                    } catch (_: Throwable) {
                        null
                    }
                    if (path != null) {
                        val count = canvas.save()
                        applyTransform(el.getAttribute("transform"), canvas)
                        val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity

                        val fill = el.getAttribute("fill")
                        if (fill.isNotEmpty() && fill != "none") {
                            configureFill(paint, fill, opacity, linearGradients, radialGradients)
                            canvas.drawPath(path, paint)
                            paint.shader = null
                        }

                        val stroke = el.getAttribute("stroke")
                        val strokeW = el.getAttribute("stroke-width").toFloatOrNull() ?: 0f
                        if (stroke.isNotEmpty() && stroke != "none" && strokeW > 0f) {
                            paint.style = Paint.Style.STROKE
                            paint.color = parseColor(stroke) ?: Color.BLACK
                            paint.strokeWidth = strokeW
                            paint.alpha = ((Color.alpha(paint.color) / 255f) * opacity * 255).toInt().coerceIn(0, 255)
                            canvas.drawPath(path, paint)
                        }
                        canvas.restoreToCount(count)
                    }
                }
            }
        }
    }

    private fun configureFill(
        paint: Paint,
        fillAttr: String,
        opacity: Float,
        linearGradients: Map<String, LinearGradientDef>,
        radialGradients: Map<String, RadialGradientDef>
    ) {
        paint.style = Paint.Style.FILL
        val gradId = extractUrlId(fillAttr)
        if (gradId != null) {
            val lin = linearGradients[gradId]
            if (lin != null) {
                paint.shader = LinearGradient(
                    lin.x1, lin.y1, lin.x2, lin.y2,
                    lin.colors, lin.positions,
                    Shader.TileMode.CLAMP
                )
                paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
                return
            }
            val rad = radialGradients[gradId]
            if (rad != null) {
                paint.shader = RadialGradient(
                    rad.cx, rad.cy, rad.r,
                    rad.colors, rad.positions,
                    Shader.TileMode.CLAMP
                )
                paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
                return
            }
        }

        val color = parseColor(fillAttr) ?: Color.BLACK
        paint.shader = null
        paint.color = color
        val baseAlpha = Color.alpha(color) / 255f
        paint.alpha = (baseAlpha * opacity * 255).toInt().coerceIn(0, 255)
    }

    private fun extractUrlId(attr: String): String? {
        val trimmed = attr.trim()
        if (trimmed.startsWith("url(#") && trimmed.endsWith(")")) {
            return trimmed.removePrefix("url(#").removeSuffix(")")
        }
        return null
    }

    private fun applyTransform(transformStr: String, canvas: Canvas) {
        if (transformStr.isEmpty()) return
        val regex = Regex("""(translate|scale|rotate)\(([^)]+)\)""")
        for (match in regex.findAll(transformStr)) {
            val op = match.groupValues[1]
            val args = match.groupValues[2].split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
            when (op) {
                "translate" -> {
                    if (args.size >= 2) canvas.translate(args[0], args[1])
                    else if (args.size == 1) canvas.translate(args[0], 0f)
                }
                "scale" -> {
                    if (args.size >= 2) canvas.scale(args[0], args[1])
                    else if (args.size == 1) canvas.scale(args[0], args[0])
                }
                "rotate" -> {
                    if (args.isNotEmpty()) canvas.rotate(args[0])
                }
            }
        }
    }

    private fun parseColor(colorStr: String?): Int? {
        if (colorStr.isNullOrEmpty() || colorStr == "none") return null
        return try {
            if (colorStr.startsWith("#")) {
                Color.parseColor(colorStr)
            } else when (colorStr.lowercase(Locale.US)) {
                "black" -> Color.BLACK
                "white" -> Color.WHITE
                "red" -> Color.RED
                "green" -> Color.GREEN
                "blue" -> Color.BLUE
                "transparent" -> Color.TRANSPARENT
                else -> Color.parseColor(colorStr)
            }
        } catch (_: Exception) {
            null
        }
    }
}
