package com.veilframe.app.qr.geometry

import java.util.Locale

/**
 * Serializes a [QrGeometryIr] into standard SVG XML markup.
 */
object IrSvgRenderer {

    fun render(ir: QrGeometryIr): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
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
                sb.append("/>\n")
            }
            is CircleNode -> {
                sb.append(pad).append(String.format(Locale.US, "<circle cx=\"%.4f\" cy=\"%.4f\" r=\"%.4f\"", node.cx, node.cy, node.radius))
                if (node.fill != null) sb.append(" fill=\"").append(colorToHex(node.fill)).append("\"")
                else sb.append(" fill=\"none\"")
                if (node.stroke != null && node.strokeWidth > 0f) {
                    sb.append(" stroke=\"").append(colorToHex(node.stroke)).append("\"")
                    sb.append(String.format(Locale.US, " stroke-width=\"%.4f\"", node.strokeWidth))
                }
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%.3f\"", node.opacity))
                sb.append("/>\n")
            }
            is LineNode -> {
                sb.append(pad).append(String.format(Locale.US, "<line x1=\"%.4f\" y1=\"%.4f\" x2=\"%.4f\" y2=\"%.4f\" stroke=\"%s\" stroke-width=\"%.4f\"",
                    node.x1, node.y1, node.x2, node.y2, colorToHex(node.strokeColor), node.strokeWidth))
                if (node.isRoundCap) sb.append(" stroke-linecap=\"round\"")
                sb.append("/>\n")
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
                sb.append("/>\n")
            }
            is GroupNode -> {
                sb.append(pad).append("<g")
                if (node.maskId != null) sb.append(" mask=\"url(#").append(node.maskId).append(")\"")
                if (node.opacity < 1f) sb.append(String.format(Locale.US, " opacity=\"%.3f\"", node.opacity))
                if (node.transform != null) sb.append(" transform=\"").append(node.transform).append("\"")
                sb.append(">\n")
                for (child in node.children) {
                    renderNode(child, sb, indent + 2)
                }
                sb.append(pad).append("</g>\n")
            }
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
}
