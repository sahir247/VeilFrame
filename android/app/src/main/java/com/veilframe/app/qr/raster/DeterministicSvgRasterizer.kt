package com.veilframe.app.qr.raster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Base64
import androidx.core.graphics.PathParser
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Deterministic SVG rasterizer for differential Canvas vs. SVG verification (AUDIT C-04 / P1.4).
 *
 * Parses standard SVG XML produced by [com.veilframe.app.qr.QrGenerator.generateSvg]
 * and rasterizes the vector elements onto an Android [Bitmap] using hardware/software [Canvas]
 * for independent closed-loop scanability validation prior to export.
 *
 * Fully supports:
 * - Geometric elements: <rect>, <circle>, <line>, <polygon>, <path>
 * - Containers & composition: <g>, <defs>, transforms (translate, scale, rotate)
 * - Shading: <linearGradient>, <radialGradient> with stop-opacity and userSpaceOnUse
 * - Clipping: <clipPath> containing <rect>, <circle>, <path>
 * - Masking: <mask id="..."> with ITU-R BT.709 luminance-to-alpha conversion & DST_IN offscreen blending
 * - Embedded Images: <image> decoding Base64 PNG/JPEG payloads with preserveAspectRatio (slice, meet, none)
 * - References: <use xlink:href="#..."> resolving definitions with coordinate transforms and fill cascades
 * - Animation tags: <animate> safely parsed for deterministic static frame-0 rendering
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
        val masks = mutableMapOf<String, Element>()
        val elementsById = mutableMapOf<String, Element>()

        // 1. Recursive indexing of all elements with an ID and definitions
        indexDocumentElements(svg, elementsById, masks)

        // 2. Parse gradients and clipPaths from <defs>
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
                        val path = parseClipPath(node, vbW, vbH)
                        if (id.isNotEmpty()) {
                            clipPaths[id] = path
                        }
                    }
                }
            }
        }

        // 3. Render root body elements in document order (skipping <defs>)
        val rootChildren = svg.childNodes
        for (i in 0 until rootChildren.length) {
            val node = rootChildren.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                if (el.tagName != "defs") {
                    renderElement(
                        el = el,
                        canvas = canvas,
                        linearGradients = linearGradients,
                        radialGradients = radialGradients,
                        clipPaths = clipPaths,
                        masks = masks,
                        elementsById = elementsById,
                        inheritedOpacity = 1.0f,
                        overrideFill = null,
                        vbW = vbW,
                        vbH = vbH,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                }
            }
        }

        if (scaleNeeded) {
            canvas.restore()
        }

        return bitmap
    }

    private fun indexDocumentElements(
        node: Node,
        elementsById: MutableMap<String, Element>,
        masks: MutableMap<String, Element>
    ) {
        if (node.nodeType == Node.ELEMENT_NODE) {
            val el = node as Element
            val id = el.getAttribute("id")
            if (id.isNotEmpty()) {
                elementsById[id] = el
                if (el.tagName == "mask") {
                    masks[id] = el
                }
            }
            val children = el.childNodes
            for (i in 0 until children.length) {
                indexDocumentElements(children.item(i), elementsById, masks)
            }
        }
    }

    private fun parseClipPath(clipEl: Element, vbW: Float, vbH: Float): Path {
        val path = Path()
        val children = clipEl.childNodes
        for (k in 0 until children.length) {
            val child = children.item(k) as? Element ?: continue
            when (child.tagName) {
                "rect" -> {
                    val x = child.getAttribute("x").toFloatOrNull() ?: 0f
                    val y = child.getAttribute("y").toFloatOrNull() ?: 0f
                    val w = child.getAttribute("width").toFloatOrNull() ?: vbW
                    val h = child.getAttribute("height").toFloatOrNull() ?: vbH
                    val rx = child.getAttribute("rx").toFloatOrNull() ?: 0f
                    val ry = child.getAttribute("ry").toFloatOrNull() ?: rx
                    if (rx > 0f || ry > 0f) {
                        path.addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                    } else {
                        path.addRect(RectF(x, y, x + w, y + h), Path.Direction.CW)
                    }
                }
                "circle" -> {
                    val cx = child.getAttribute("cx").toFloatOrNull() ?: 0f
                    val cy = child.getAttribute("cy").toFloatOrNull() ?: 0f
                    val r = child.getAttribute("r").toFloatOrNull() ?: 0f
                    path.addCircle(cx, cy, r, Path.Direction.CW)
                }
                "path" -> {
                    val d = child.getAttribute("d").trim()
                    if (d.isNotEmpty()) {
                        try {
                            val p = PathParser.createPathFromPathData(d)
                            if (p != null) {
                                if (child.hasAttribute("transform")) {
                                    val tMatrix = parseTransformMatrix(child.getAttribute("transform"))
                                    p.transform(tMatrix)
                                }
                                path.addPath(p)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        return path
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
        masks: Map<String, Element>,
        elementsById: Map<String, Element>,
        inheritedOpacity: Float = 1.0f,
        overrideFill: String? = null,
        vbW: Float,
        vbH: Float,
        targetWidth: Int,
        targetHeight: Int
    ) {
        // Handle element-level mask attribute
        val maskId = extractUrlId(el.getAttribute("mask"))
        val maskEl = if (maskId != null) masks[maskId] else null

        if (maskEl != null) {
            // Allocate offscreen layer for isolated compositing
            val layerCount = canvas.saveLayer(null, null)

            // Render element content without re-processing mask
            renderElementContent(
                el = el,
                canvas = canvas,
                linearGradients = linearGradients,
                radialGradients = radialGradients,
                clipPaths = clipPaths,
                masks = masks,
                elementsById = elementsById,
                inheritedOpacity = inheritedOpacity,
                overrideFill = overrideFill,
                vbW = vbW,
                vbH = vbH,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )

            // Render and apply mask with luminance-to-alpha DST_IN transfer
            val maskBmp = renderMaskBitmap(
                maskEl = maskEl,
                vbW = vbW,
                vbH = vbH,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                linearGradients = linearGradients,
                radialGradients = radialGradients,
                clipPaths = clipPaths,
                masks = masks,
                elementsById = elementsById
            )
            if (maskBmp != null) {
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                val invMatrix = android.graphics.Matrix()
                if (canvas.matrix.invert(invMatrix)) {
                    val countM = canvas.save()
                    canvas.concat(invMatrix)
                    canvas.drawBitmap(maskBmp, 0f, 0f, maskPaint)
                    canvas.restoreToCount(countM)
                } else {
                    canvas.drawBitmap(maskBmp, null, RectF(0f, 0f, vbW, vbH), maskPaint)
                }
                maskBmp.recycle()
            }

            canvas.restoreToCount(layerCount)
            return
        }

        renderElementContent(
            el = el,
            canvas = canvas,
            linearGradients = linearGradients,
            radialGradients = radialGradients,
            clipPaths = clipPaths,
            masks = masks,
            elementsById = elementsById,
            inheritedOpacity = inheritedOpacity,
            overrideFill = overrideFill,
            vbW = vbW,
            vbH = vbH,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    private fun renderElementContent(
        el: Element,
        canvas: Canvas,
        linearGradients: Map<String, LinearGradientDef>,
        radialGradients: Map<String, RadialGradientDef>,
        clipPaths: Map<String, Path>,
        masks: Map<String, Element>,
        elementsById: Map<String, Element>,
        inheritedOpacity: Float = 1.0f,
        overrideFill: String? = null,
        vbW: Float,
        vbH: Float,
        targetWidth: Int,
        targetHeight: Int
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
                    renderElement(
                        el = child,
                        canvas = canvas,
                        linearGradients = linearGradients,
                        radialGradients = radialGradients,
                        clipPaths = clipPaths,
                        masks = masks,
                        elementsById = elementsById,
                        inheritedOpacity = inheritedOpacity * op,
                        overrideFill = overrideFill,
                        vbW = vbW,
                        vbH = vbH,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
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

                val rawFill = el.getAttribute("fill")
                val fill = if (rawFill.isNotEmpty()) rawFill else (overrideFill ?: "")
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

                val rawFill = el.getAttribute("fill")
                val fill = if (rawFill.isNotEmpty()) rawFill else (overrideFill ?: "")
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
                        val rawFill = el.getAttribute("fill")
                        val fill = if (rawFill.isNotEmpty()) rawFill else (overrideFill ?: "")
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
                        val clipId = extractUrlId(el.getAttribute("clip-path"))
                        if (clipId != null && clipPaths.containsKey(clipId)) {
                            canvas.clipPath(clipPaths[clipId]!!)
                        }
                        val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity

                        val rawFill = el.getAttribute("fill")
                        val fill = if (rawFill.isNotEmpty()) rawFill else (overrideFill ?: "")
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
            "image" -> {
                val href = el.getAttribute("href").ifEmpty { el.getAttribute("xlink:href") }
                val bmp = if (href.startsWith("data:image/") || href.contains("base64,")) {
                    decodeBase64ToBitmap(href)
                } else if (href.startsWith("#")) {
                    val refId = href.removePrefix("#")
                    val refEl = elementsById[refId]
                    if (refEl != null && refEl.tagName == "image") {
                        val refHref = refEl.getAttribute("href").ifEmpty { refEl.getAttribute("xlink:href") }
                        decodeBase64ToBitmap(refHref)
                    } else null
                } else null

                if (bmp != null) {
                    val x = el.getAttribute("x").toFloatOrNull() ?: 0f
                    val y = el.getAttribute("y").toFloatOrNull() ?: 0f
                    val w = el.getAttribute("width").toFloatOrNull() ?: vbW
                    val h = el.getAttribute("height").toFloatOrNull() ?: vbH
                    val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1.0f) * inheritedOpacity

                    val count = canvas.save()
                    applyTransform(el.getAttribute("transform"), canvas)
                    val clipId = extractUrlId(el.getAttribute("clip-path"))
                    if (clipId != null && clipPaths.containsKey(clipId)) {
                        canvas.clipPath(clipPaths[clipId]!!)
                    }

                    val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
                    }
                    val par = el.getAttribute("preserveAspectRatio").trim().lowercase(Locale.US)
                    val imgW = bmp.width.toFloat()
                    val imgH = bmp.height.toFloat()

                    if (par == "none") {
                        canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), imgPaint)
                    } else if (par.contains("slice")) {
                        val s = maxOf(w / imgW, h / imgH)
                        val dw = imgW * s
                        val dh = imgH * s
                        val dx = x + (w - dw) / 2f
                        val dy = y + (h - dh) / 2f
                        canvas.save()
                        canvas.clipRect(x, y, x + w, y + h)
                        canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), imgPaint)
                        canvas.restore()
                    } else { // default "meet" or xMidYMid meet
                        val s = minOf(w / imgW, h / imgH)
                        val dw = imgW * s
                        val dh = imgH * s
                        val dx = x + (w - dw) / 2f
                        val dy = y + (h - dh) / 2f
                        canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), imgPaint)
                    }
                    canvas.restoreToCount(count)
                }
            }
            "use" -> {
                val href = el.getAttribute("href").ifEmpty { el.getAttribute("xlink:href") }
                val targetId = href.removePrefix("#")
                val targetEl = elementsById[targetId]
                if (targetEl != null) {
                    val count = canvas.save()
                    val ux = el.getAttribute("x").toFloatOrNull() ?: 0f
                    val uy = el.getAttribute("y").toFloatOrNull() ?: 0f
                    if (ux != 0f || uy != 0f) {
                        canvas.translate(ux, uy)
                    }
                    applyTransform(el.getAttribute("transform"), canvas)
                    val clipId = extractUrlId(el.getAttribute("clip-path"))
                    if (clipId != null && clipPaths.containsKey(clipId)) {
                        canvas.clipPath(clipPaths[clipId]!!)
                    }
                    val op = el.getAttribute("opacity").toFloatOrNull() ?: 1.0f
                    val useFill = el.getAttribute("fill")

                    renderElement(
                        el = targetEl,
                        canvas = canvas,
                        linearGradients = linearGradients,
                        radialGradients = radialGradients,
                        clipPaths = clipPaths,
                        masks = masks,
                        elementsById = elementsById,
                        inheritedOpacity = inheritedOpacity * op,
                        overrideFill = useFill.ifEmpty { overrideFill },
                        vbW = vbW,
                        vbH = vbH,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                    canvas.restoreToCount(count)
                }
            }
            "animate" -> {
                // Static rasterizer parses frame 0 directly; animation transitions are no-ops
            }
        }
    }

    private fun renderMaskBitmap(
        maskEl: Element,
        vbW: Float,
        vbH: Float,
        targetWidth: Int,
        targetHeight: Int,
        linearGradients: Map<String, LinearGradientDef>,
        radialGradients: Map<String, RadialGradientDef>,
        clipPaths: Map<String, Path>,
        masks: Map<String, Element>,
        elementsById: Map<String, Element>
    ): Bitmap? {
        val maskBmp = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        val maskCanvas = Canvas(maskBmp)
        val sx = targetWidth.toFloat() / vbW
        val sy = targetHeight.toFloat() / vbH
        maskCanvas.scale(sx, sy)

        val children = maskEl.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            renderElement(
                el = child,
                canvas = maskCanvas,
                linearGradients = linearGradients,
                radialGradients = radialGradients,
                clipPaths = clipPaths,
                masks = masks,
                elementsById = elementsById,
                inheritedOpacity = 1.0f,
                overrideFill = null,
                vbW = vbW,
                vbH = vbH,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
        }

        // ITU-R BT.709 luminance-to-alpha mask conversion
        val pixels = IntArray(targetWidth * targetHeight)
        maskBmp.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val a = (p ushr 24) and 0xFF
            if (a == 0) {
                pixels[idx] = 0
            } else {
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                val finalAlpha = ((lum * a) / 255).coerceIn(0, 255)
                pixels[idx] = finalAlpha shl 24
            }
        }
        maskBmp.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return maskBmp
    }

    private fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        val clean = base64Str.substringAfter("base64,").trim()
        val bytes = try {
            Base64.decode(clean, Base64.DEFAULT)
        } catch (_: Throwable) {
            try {
                java.util.Base64.getDecoder().decode(clean)
            } catch (_: Throwable) {
                null
            }
        } ?: return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
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

    private fun parseTransformMatrix(transformStr: String): android.graphics.Matrix {
        val matrix = android.graphics.Matrix()
        if (transformStr.isEmpty()) return matrix
        val regex = Regex("""(translate|scale|rotate)\(([^)]+)\)""")
        for (match in regex.findAll(transformStr)) {
            val op = match.groupValues[1]
            val args = match.groupValues[2].split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
            when (op) {
                "translate" -> {
                    if (args.size >= 2) matrix.preTranslate(args[0], args[1])
                    else if (args.size == 1) matrix.preTranslate(args[0], 0f)
                }
                "scale" -> {
                    if (args.size >= 2) matrix.preScale(args[0], args[1])
                    else if (args.size == 1) matrix.preScale(args[0], args[0])
                }
                "rotate" -> {
                    if (args.size >= 3) matrix.preRotate(args[0], args[1], args[2])
                    else if (args.isNotEmpty()) matrix.preRotate(args[0])
                }
            }
        }
        return matrix
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
                    if (args.size >= 3) canvas.rotate(args[0], args[1], args[2])
                    else if (args.isNotEmpty()) canvas.rotate(args[0])
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
