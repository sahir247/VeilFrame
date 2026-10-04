package com.veilframe.app.qr.geometry

import java.util.Locale

/**
 * Serializes a [QrGeometryIr] into standard SVG XML markup.
 */
object IrSvgRenderer {

    fun formatCoord(v: Float, isModule: Boolean = false): String {
        val d = v.toDouble()
        if (isModule && v == 1.0f) return "1.0"
        return if (d % 1.0 == 0.0) {
            d.toLong().toString()
        } else {
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    fun render(ir: QrGeometryIr): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(String.format(Locale.US, "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"%s\" width=\"100%%\" height=\"100%%\">\n", ir.viewBox))

        if (ir.defs.isNotEmpty()) {
            sb.append("  <defs>\n")
            for (def in ir.defs) {
                sb.append("    ").append(def).append("\n")
            }
            sb.append("  </defs>\n")
        }

        val hasRoundedCornersDef = ir.defs.any { it.contains("""id="rounded-corners"""") }
        val needsGroupWrap = hasRoundedCornersDef && ir.rootNodes.none { it is GroupNode && it.clipPathId == "rounded-corners" }

        if (needsGroupWrap) {
            sb.append("  <g clip-path=\"url(#rounded-corners)\">\n")
        }

        for (node in ir.rootNodes) {
            renderNode(node, sb, indent = if (needsGroupWrap) 4 else 2)
        }

        if (needsGroupWrap) {
            sb.append("  </g>\n")
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun renderNode(node: QrGeometryNode, sb: StringBuilder, indent: Int) {
        val pad = " ".repeat(indent)
        when (node) {
            is RectNode -> {
                sb.append(pad)
                val isBackdrop = node.alwaysEmitOpacity && node.x == 0f && node.y == 0f
                if (isBackdrop) {
                    sb.append(String.format(Locale.US, "<rect width=\"%s\" height=\"%s\"", formatIntOrCoord(node.width), formatIntOrCoord(node.height)))
                } else if (node.alwaysEmitOpacity && node.opacityString != null) {
                    // Finder 8x8 backing rect formatting matching EF parity contract
                    val wStr = formatIntOrCoord(node.width)
                    val hStr = formatIntOrCoord(node.height)
                    val xStr = formatIntOrCoord(node.x)
                    val yStr = formatIntOrCoord(node.y)
                    sb.append(String.format(Locale.US, "<rect opacity=\"%s\" width=\"%s\" height=\"%s\" x=\"%s\" y=\"%s\"",
                        node.opacityString, wStr, hStr, xStr, yStr))
                } else {
                    val wStr = if (node.width == 1.0f) "1.0" else if (node.width % 1f == 0f) node.width.toInt().toString() else formatIntOrCoord(node.width)
                    val hStr = if (node.height == 1.0f) "1.0" else if (node.height % 1f == 0f) node.height.toInt().toString() else formatIntOrCoord(node.height)
                    val xStr = if (node.width <= 1.0f && node.x % 1f == 0f) "${node.x.toInt()}.0" else formatIntOrCoord(node.x)
                    val yStr = if (node.height <= 1.0f && node.y % 1f == 0f) "${node.y.toInt()}.0" else formatIntOrCoord(node.y)
                    sb.append(String.format(Locale.US, "<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"",
                        xStr, yStr, wStr, hStr))
                }
                if (node.rx > 0f) sb.append(String.format(Locale.US, " rx=\"%s\"", formatCornerRadius(node.rx)))
                if (node.ry > 0f) sb.append(String.format(Locale.US, " ry=\"%s\"", formatCornerRadius(node.ry)))
                if (node.fillString != null) sb.append(" fill=\"").append(node.fillString).append("\"")
                else if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    val swStr = node.strokeWidthString ?: String.format(Locale.US, "%.4f", node.strokeWidth)
                    sb.append(String.format(Locale.US, " stroke-width=\"%s\"", swStr))
                }
                if (!node.alwaysEmitOpacity && (node.opacity < 1f || node.opacityString != null)) {
                    val opStr = node.opacityString ?: formatOpacity(node.opacity)
                    sb.append(String.format(Locale.US, " opacity=\"%s\"", opStr))
                } else if (isBackdrop && node.alwaysEmitOpacity) {
                    val opStr = node.opacityString ?: formatOpacity(node.opacity)
                    sb.append(String.format(Locale.US, " opacity=\"%s\"", opStr))
                }
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(" />\n")
            }
            is CircleNode -> {
                val rStr = if (node.radius == 0.4f) "0.4000" else formatIntOrCoord(node.radius)
                val cxStr = formatIntOrCoord(node.cx)
                val cyStr = formatIntOrCoord(node.cy)
                sb.append(pad).append(String.format(Locale.US, "<circle cx=\"%s\" cy=\"%s\" r=\"%s\"",
                    cxStr, cyStr, rStr))
                if (node.fillString != null) sb.append(" fill=\"").append(node.fillString).append("\"")
                else if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    val swStr = node.strokeWidthString ?: String.format(Locale.US, "%.4f", node.strokeWidth)
                    sb.append(String.format(Locale.US, " stroke-width=\"%s\"", swStr))
                    if (node.strokeDashArray != null) {
                        sb.append(" stroke-dasharray=\"").append(node.strokeDashArray).append("\"")
                    }
                }
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%s\"", formatOpacity(node.opacity)))
                sb.append(" />\n")
            }
            is LineNode -> {
                sb.append(pad).append(String.format(Locale.US, "<line x1=\"%.4f\" y1=\"%.4f\" x2=\"%.4f\" y2=\"%.4f\" stroke=\"%s\" stroke-width=\"%.4f\"",
                    node.x1, node.y1, node.x2, node.y2, colorToHex(node.strokeColor), node.strokeWidth))
                if (node.isRoundCap) sb.append(" stroke-linecap=\"round\"")
                if (node.strokeDashArray != null) sb.append(" stroke-dasharray=\"").append(node.strokeDashArray).append("\"")
                sb.append(" />\n")
            }
            is PolygonNode -> {
                sb.append(pad).append("<polygon points=\"").append(node.points).append("\"")
                if (node.fillString != null) sb.append(" fill=\"").append(node.fillString).append("\"")
                else if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(String.format(Locale.US, " stroke-width=\"%.4f\"", node.strokeWidth))
                }
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%s\"", formatOpacity(node.opacity)))
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(" />\n")
            }
            is PathNode -> {
                val strokeWStr = node.strokeWidthString ?: String.format(Locale.US, "%.3f", node.strokeWidth)
                sb.append(pad).append("<path")
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%s\"", formatOpacity(node.opacity)))
                sb.append(" d=\"").append(node.svgPathData).append("\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(" stroke-width=\"").append(strokeWStr).append("\"")
                }
                if (node.fillString != null) sb.append(" fill=\"").append(node.fillString).append("\"")
                else if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(" />\n")
            }
            is ImageNode -> {
                val base64 = node.base64Data ?: node.bitmap?.let { bitmapToBase64(it) } ?: ""
                val href = if (base64.startsWith("#")) base64 else "data:image/png;base64,$base64"
                val keyAttr = if (node.key != null) " key=\"${node.key}\"" else ""
                val xlinkAttr = if (node.key != null) " xlink:href=\"$href\"" else ""
                val xStr = formatIntOrCoord(node.x)
                val yStr = formatIntOrCoord(node.y)
                val wStr = formatIntOrCoord(node.width)
                val hStr = formatIntOrCoord(node.height)
                sb.append(pad).append(String.format(
                    Locale.US,
                    "<image%s%s href=\"%s\" x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"",
                    keyAttr, xlinkAttr, href, xStr, yStr, wStr, hStr
                ))
                if (node.opacity < 1f || node.key != null) {
                    sb.append(String.format(Locale.US, " opacity=\"%s\"", formatOpacity(node.opacity)))
                }
                if (node.preserveAspectRatio.isNotEmpty()) {
                    sb.append(" preserveAspectRatio=\"").append(node.preserveAspectRatio).append("\"")
                }
                if (node.style != null) {
                    sb.append(" style=\"").append(node.style).append("\"")
                }
                if (node.maskId != null) {
                    sb.append(" mask=\"url(#").append(node.maskId).append(")\"")
                }
                if (node.clipPathId != null) {
                    sb.append(" clip-path=\"url(#").append(node.clipPathId).append(")\"")
                }
                if (node.transform != null) {
                    sb.append(" transform=\"").append(node.transform).append("\"")
                }
                sb.append(" />\n")
            }
            is GroupNode -> {
                sb.append(pad).append("<g")
                if (node.maskId != null) sb.append(" mask=\"url(#").append(node.maskId).append(")\"")
                if (node.clipPathId != null) sb.append(" clip-path=\"url(#").append(node.clipPathId).append(")\"")
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%s\"", formatOpacity(node.opacity)))
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(">\n")
                for (child in node.children) {
                    renderNode(child, sb, indent + 2)
                }
                sb.append(pad).append("</g>\n")
            }
            is AnimatedImageNode -> {
                val base64List = if (node.base64Frames.isNotEmpty()) {
                    node.base64Frames
                } else {
                    node.frames.map { bitmapToBase64(it) }
                }
                if (base64List.isNotEmpty()) {
                    val framePrefix = node.framePrefix
                    val numFrames = base64List.size
                    val normalizedDelaysMs = List(numFrames) { i ->
                        maxOf(10, node.frameDelaysMs.getOrNull(i) ?: 100)
                    }
                    val totalDurationMs = maxOf(1, normalizedDelaysMs.sum())
                    val totalDurationSec = totalDurationMs / 1000.0

                    val maskAttr = if (node.maskId != null) " mask=\"url(#${node.maskId})\"" else ""
                    val clipAttr = if (node.clipPathId != null) " clip-path=\"url(#${node.clipPathId})\"" else ""
                    val transAttr = if (node.transform != null) " transform=\"${node.transform}\"" else ""

                    sb.append(pad).append("<g").append(maskAttr).append(clipAttr).append(transAttr).append(">\n")
                    sb.append(pad).append("  <defs>\n")
                    for ((idx, base64) in base64List.withIndex()) {
                        val href = if (base64.startsWith("#") || base64.startsWith("data:")) base64 else "data:image/png;base64,$base64"
                        sb.append(pad).append("    <image id=\"").append(framePrefix).append(idx).append("\" xlink:href=\"").append(href).append("\"")
                        val wStr = formatIntOrCoord(node.width)
                        val hStr = formatIntOrCoord(node.height)
                        val xStr = formatIntOrCoord(node.x)
                        val yStr = formatIntOrCoord(node.y)
                        sb.append(String.format(Locale.US, " width=\"%s\" height=\"%s\" x=\"%s\" y=\"%s\"", wStr, hStr, xStr, yStr))
                        if (node.opacity < 1f) {
                            sb.append(String.format(Locale.US, " opacity=\"%s\"", formatOpacity(node.opacity)))
                        }
                        if (node.preserveAspectRatio.isNotEmpty()) {
                            sb.append(" preserveAspectRatio=\"").append(node.preserveAspectRatio).append("\"")
                        }
                        sb.append(" />\n")
                    }
                    sb.append(pad).append("  </defs>\n")

                    var accumulatedMs = 0
                    val keyTimes = mutableListOf<String>()
                    for (delay in normalizedDelaysMs) {
                        val fraction = accumulatedMs.toDouble() / totalDurationMs
                        keyTimes.add(String.format(Locale.US, "%.3f", fraction))
                        accumulatedMs += delay
                    }
                    val valuesStr = base64List.indices.joinToString(";") { "#$framePrefix$it" }
                    val keyTimesStr = keyTimes.joinToString(";")
                    val durStr = String.format(Locale.US, "%.3f", totalDurationSec)

                    sb.append(pad).append("  <use xlink:href=\"#").append(framePrefix).append("0\">\n")
                    sb.append(pad).append("    <animate\n")
                    sb.append(pad).append("      attributeName=\"xlink:href\"\n")
                    sb.append(pad).append("      values=\"").append(valuesStr).append("\"\n")
                    sb.append(pad).append("      keyTimes=\"").append(keyTimesStr).append("\"\n")
                    sb.append(pad).append("      dur=\"").append(durStr).append("s\"\n")
                    sb.append(pad).append("      repeatCount=\"indefinite\"\n")
                    sb.append(pad).append("      calcMode=\"discrete\"\n")
                    sb.append(pad).append("    />\n")
                    sb.append(pad).append("  </use>\n")
                    sb.append(pad).append("</g>\n")
                }
            }
            is AnimatedGroupNode -> {
                if (node.frameNodes.isNotEmpty()) {
                    val framePrefix = node.framePrefix
                    val numFrames = node.frameNodes.size
                    val normalizedDelaysMs = List(numFrames) { i ->
                        maxOf(10, node.frameDelaysMs.getOrNull(i) ?: 100)
                    }
                    val totalDurationMs = maxOf(1, normalizedDelaysMs.sum())
                    val totalDurationSec = totalDurationMs / 1000.0

                    sb.append(pad).append("<g>\n")
                    sb.append(pad).append("  <defs>\n")
                    for ((idx, fNodes) in node.frameNodes.withIndex()) {
                        sb.append(pad).append("    <g id=\"").append(framePrefix).append(idx).append("\">\n")
                        for (child in fNodes) {
                            renderNode(child, sb, indent + 6)
                        }
                        sb.append(pad).append("    </g>\n")
                    }
                    sb.append(pad).append("  </defs>\n")

                    val frameIds = node.frameNodes.indices.map { "#$framePrefix$it" }
                    val valuesStr = frameIds.joinToString(";")
                    var accumulatedMs = 0
                    val keyTimes = mutableListOf<String>()
                    for (delay in normalizedDelaysMs) {
                        val fraction = accumulatedMs.toDouble() / totalDurationMs
                        keyTimes.add(String.format(Locale.US, "%.3f", fraction))
                        accumulatedMs += delay
                    }
                    val keyTimesStr = keyTimes.joinToString(";")
                    val durStr = String.format(Locale.US, "%.3f", totalDurationSec)

                    sb.append(pad).append("  <use xlink:href=\"#").append(framePrefix).append("0\">\n")
                    sb.append(pad).append("    <animate\n")
                    sb.append(pad).append("      attributeName=\"xlink:href\"\n")
                    sb.append(pad).append("      values=\"").append(valuesStr).append("\"\n")
                    sb.append(pad).append("      keyTimes=\"").append(keyTimesStr).append("\"\n")
                    sb.append(pad).append("      dur=\"").append(durStr).append("s\"\n")
                    sb.append(pad).append("      repeatCount=\"indefinite\"\n")
                    sb.append(pad).append("      calcMode=\"discrete\"\n")
                    sb.append(pad).append("    />\n")
                    sb.append(pad).append("  </use>\n")
                    sb.append(pad).append("</g>\n")
                }
            }
        }
    }

    fun bitmapToBase64(bitmap: android.graphics.Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return ""
        return try {
            val stream = java.io.ByteArrayOutputStream()
            val ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            if (ok && byteArray.isNotEmpty()) {
                try {
                    android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
                } catch (_: Throwable) {
                    java.util.Base64.getEncoder().encodeToString(byteArray)
                }
            } else {
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
            }
        } catch (_: Throwable) {
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        }
    }

    fun colorToHex(color: Int): String {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return if (a == 255) {
            String.format(Locale.US, "#%02X%02X%02X", r, g, b)
        } else {
            String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0f)
        }
    }

    fun formatOpacity(alpha: Float): String {
        val clamped = alpha.coerceIn(0f, 1f)
        if (clamped >= 1f) return "1"
        if (clamped <= 0f) return "0"
        return String.format(Locale.US, "%.4f", clamped).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    fun formatIntOrCoord(v: Float): String {
        return if (v % 1f == 0f) {
            v.toInt().toString()
        } else {
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    fun formatCornerRadius(radius: Float): String {
        val r = maxOf(0f, radius)
        return if (r % 1f == 0f) {
            r.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
        }
    }

    fun renderMaskDefinition(maskDef: QrMaskDefinition): String {
        if (maskDef.isClipPath) {
            val bounds = maskDef.bounds
            val bW = if (bounds != null) bounds.right - bounds.left else 0f
            val bH = if (bounds != null) bounds.bottom - bounds.top else 0f
            val wStr = if (bW > 0f) formatCoord(bW) else "100%"
            val hStr = if (bH > 0f) formatCoord(bH) else "100%"
            val rxStr = if (maskDef.rx > 0f) String.format(Locale.US, " rx=\"%s\"", formatCornerRadius(maskDef.rx)) else ""
            val ryStr = if (maskDef.ry > 0f) String.format(Locale.US, " ry=\"%s\"", formatCornerRadius(maskDef.ry)) else ""
            return "<clipPath id=\"${maskDef.id}\"><rect width=\"$wStr\" height=\"$hStr\"$rxStr$ryStr/></clipPath>"
        }

        val sb = StringBuilder()
        sb.append("<mask id=\"").append(maskDef.id).append("\">\n")
        if (maskDef.maskNodes.isNotEmpty()) {
            for (node in maskDef.maskNodes) {
                renderNode(node, sb, indent = 4)
            }
        } else if (maskDef.clipOutRects.isNotEmpty() && maskDef.bounds != null) {
            val b = maskDef.bounds
            renderNode(RectNode(x = b.left, y = b.top, width = b.right - b.left, height = b.bottom - b.top, fill = 0xFFFFFFFF.toInt(), fillString = "white"), sb, indent = 4)
            for (cutout in maskDef.clipOutRects) {
                renderNode(RectNode(x = cutout.left, y = cutout.top, width = cutout.right - cutout.left, height = cutout.bottom - cutout.top, fill = 0xFF000000.toInt(), fillString = "black"), sb, indent = 4)
            }
        }
        sb.append("  </mask>")
        return sb.toString()
    }
}
