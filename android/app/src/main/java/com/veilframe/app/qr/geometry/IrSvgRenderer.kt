package com.veilframe.app.qr.geometry

import java.util.Locale

/**
 * Serializes a [QrGeometryIr] into standard SVG XML markup.
 */
object IrSvgRenderer {

    fun render(ir: QrGeometryIr): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
        sb.append(String.format(Locale.US, "width=\"%.2f\" height=\"%.2f\" viewBox=\"%s\">\n", ir.width, ir.height, ir.viewBox))

        if (ir.defs.isNotEmpty()) {
            sb.append("  <defs>\n")
            for (def in ir.defs) {
                sb.append("    ").append(def).append("\n")
            }
            sb.append("  </defs>\n")
        }

        for (node in ir.rootNodes) {
            renderNode(node, sb, indent = 2)
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun renderNode(node: QrGeometryNode, sb: StringBuilder, indent: Int) {
        val pad = " ".repeat(indent)
        when (node) {
            is RectNode -> {
                sb.append(pad).append(String.format(Locale.US, "<rect x=\"%.4f\" y=\"%.4f\" width=\"%.4f\" height=\"%.4f\"", node.x, node.y, node.width, node.height))
                if (node.rx > 0f) sb.append(String.format(Locale.US, " rx=\"%.4f\"", node.rx))
                if (node.ry > 0f) sb.append(String.format(Locale.US, " ry=\"%.4f\"", node.ry))
                if (node.fillString != null) sb.append(" fill=\"").append(node.fillString).append("\"")
                else if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(String.format(Locale.US, " stroke-width=\"%.4f\"", node.strokeWidth))
                }
                if (node.alwaysEmitOpacity || node.opacity < 1f) {
                    sb.append(String.format(Locale.US, " opacity=\"%.2f\"", node.opacity))
                }
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(" />\n")
            }
            is CircleNode -> {
                sb.append(pad).append(String.format(Locale.US, "<circle cx=\"%.4f\" cy=\"%.4f\" r=\"%.4f\"", node.cx, node.cy, node.radius))
                if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(String.format(Locale.US, " stroke-width=\"%.4f\"", node.strokeWidth))
                    if (node.strokeDashArray != null) {
                        sb.append(" stroke-dasharray=\"").append(node.strokeDashArray).append("\"")
                    }
                }
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%.3f\"", node.opacity))
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
                if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(String.format(Locale.US, " stroke-width=\"%.4f\"", node.strokeWidth))
                }
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%.2f\"", node.opacity))
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(" />\n")
            }
            is PathNode -> {
                sb.append(pad).append("<path d=\"").append(node.svgPathData).append("\"")
                if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(String.format(Locale.US, " stroke-width=\"%.4f\"", node.strokeWidth))
                }
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%.3f\"", node.opacity))
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(" />\n")
            }
            is ImageNode -> {
                val base64 = node.base64Data ?: node.bitmap?.let { bitmapToBase64(it) } ?: ""
                val href = if (base64.startsWith("#")) base64 else "data:image/png;base64,$base64"
                sb.append(pad).append(String.format(
                    Locale.US,
                    "<image href=\"%s\" x=\"%.4f\" y=\"%.4f\" width=\"%.4f\" height=\"%.4f\"",
                    href, node.x, node.y, node.width, node.height
                ))
                if (node.opacity < 1f) {
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
                    val delaysMs = if (node.frameDelaysMs.isNotEmpty()) node.frameDelaysMs else List(base64List.size) { 100 }
                    val totalDurationMs = maxOf(1, delaysMs.sum())
                    val totalDurationSec = totalDurationMs / 1000.0

                    val maskAttr = if (node.maskId != null) " mask=\"url(#${node.maskId})\"" else ""
                    val clipAttr = if (node.clipPathId != null) " clip-path=\"url(#${node.clipPathId})\"" else ""
                    val transAttr = if (node.transform != null) " transform=\"${node.transform}\"" else ""

                    sb.append(pad).append("<g").append(maskAttr).append(clipAttr).append(transAttr).append(">\n")
                    sb.append(pad).append("  <defs>\n")
                    for ((idx, base64) in base64List.withIndex()) {
                        val href = if (base64.startsWith("#") || base64.startsWith("data:")) base64 else "data:image/png;base64,$base64"
                        sb.append(pad).append("    <image id=\"").append(framePrefix).append(idx).append("\" xlink:href=\"").append(href).append("\"")
                        sb.append(String.format(Locale.US, " width=\"%.4f\" height=\"%.4f\" x=\"%.4f\" y=\"%.4f\"", node.width, node.height, node.x, node.y))
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
                    for (delay in delaysMs) {
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
                    val totalDurationMs = maxOf(1, node.frameDelaysMs.sum())
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
                    for ((idx, delay) in node.frameDelaysMs.withIndex()) {
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
}
